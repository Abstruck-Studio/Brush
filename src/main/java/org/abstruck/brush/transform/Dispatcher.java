package org.abstruck.brush.transform;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 运行时派发器（CLAUDE.md §8.3：薄注入 + 派发表）。
 *
 * <p>注入进 vanilla 字节码的唯一条指令就是对本类 {@code fire(...)} 的静态调用；
 * 判定逻辑（谁关心、怎么处理、是否启用）全部在这张派发表里，不在字节码里 ——
 * 因此<b>加 hook 不需要重新变换任何类</b>，footprint 也不随 hook 数量增长。</p>
 *
 * <p><b>强类型事件 key（评审 P2-1 整改）</b>：注册 / 派发 / 自省入口一律收
 * {@link HookEvent}（事件 id 只能以 {@code HookEvents.X} 常量或测试专属实例出现），
 * 裸 String 字面量不再能进入本类 —— 拼错在编译期即失败。派发表内部仍以 id 字符串为键
 * （{@link HookEvent#id()}），键值与类型化之前逐字节一致（台账 / 日志 / 自检报告按之核对）。</p>
 *
 * <p><b>无 hook 时的零开销短路</b>：{@link #fire(HookEvent)} 的第一动作是
 * {@code TABLE.get(event.id())} —— 事件无人注册时为"一次哈希查找 + 空判断"即返回，
 * 零分配、不进循环。这是热路径（每帧 tick）可安全注入的量化依据。</p>
 *
 * <p><b>可链式（§7.2）</b>：同一事件允许多个监听器并存，按 {@code (order, 注册序号)}
 * 升序调用；开关（{@link Registration#setEnabled}）只跳过自身，不阻断他人；
 * 不提供任何独占语义（对照 {@code @Redirect} 的不可链式）。</p>
 *
 * <p><b>异常策略</b>：监听器异常不吞（§3.5），包一层带事件 id 与注册序号的
 * {@link IllegalStateException} 立即上抛 —— 快速失败，绝不让 hook 错误静默消失。</p>
 *
 * <p><b>线程模型</b>：注册/注销/开关可在任意线程（CHM + COWAL，写时复制保证派发无锁）；
 * 派发在触发线程执行（对 vanilla hook 即游戏主线程）。</p>
 */
public final class Dispatcher {

    /** L1 监听器（§6：L1 原始钩子层；L2 语义层在其上包装）。 */
    @FunctionalInterface
    public interface Listener {
        /**
         * @param args 触发点携带的参数快照；无参事件共享常量空数组（{@link #NO_ARGS}），禁止修改
         */
        void onEvent(Object[] args);
    }

    /** 无参事件的共享空数组（禁止修改，见 {@link Listener#onEvent}）。 */
    private static final Object[] NO_ARGS = new Object[0];

    /** 事件 id（{@link HookEvent#id()} 的字符串值）→ 有序注册表。表空时对应键被移除，让 {@code fire} 回到纯短路路径。 */
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Registration>> TABLE =
            new ConcurrentHashMap<>();

    private static final AtomicLong REGISTRATION_SEQUENCE = new AtomicLong();

    private Dispatcher() {
    }

    // ---------------------------------------------------------------------
    // 注册 / 注销 / 开关
    // ---------------------------------------------------------------------

    /** 注册监听器（order = 0）。 */
    public static Registration register(HookEvent event, Listener listener) {
        return register(event, 0, listener);
    }

    /**
     * 注册监听器。
     *
     * @param order 调用次序（升序；同 order 按注册先后）
     * @return 注册句柄：可开关、可注销（{@link Registration}）
     */
    public static Registration register(HookEvent event, int order, Listener listener) {
        if (event == null) {
            throw new IllegalArgumentException("event id must not be empty");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        Registration registration =
                new Registration(event.id(), order, REGISTRATION_SEQUENCE.getAndIncrement(), listener);
        TABLE.compute(event.id(), (key, existing) -> {
            CopyOnWriteArrayList<Registration> list = existing == null ? new CopyOnWriteArrayList<>() : existing;
            list.add(insertionIndex(list, registration), registration);
            return list;
        });
        return registration;
    }

    /** 保持 {@code (order, 序号)} 升序的插入位置。 */
    private static int insertionIndex(List<Registration> list, Registration registration) {
        int index = 0;
        for (Registration other : list) {
            if (other.order > registration.order
                    || (other.order == registration.order && other.sequence > registration.sequence)) {
                break;
            }
            index++;
        }
        return index;
    }

    // ---------------------------------------------------------------------
    // 派发
    // ---------------------------------------------------------------------

    /** 无参派发（热路径可用：无监听器时零分配）。 */
    public static void fire(HookEvent event) {
        CopyOnWriteArrayList<Registration> registrations = TABLE.get(event.id());
        if (registrations == null) {
            return;
        }
        dispatch(event.id(), registrations, NO_ARGS);
    }

    /** 单参派发（参数数组仅在确实存在监听器时才分配）。 */
    public static void fire(HookEvent event, Object arg) {
        CopyOnWriteArrayList<Registration> registrations = TABLE.get(event.id());
        if (registrations == null) {
            return;
        }
        dispatch(event.id(), registrations, new Object[] { arg });
    }

    /**
     * 双参派发（如 {@code input.key.press} 载荷 = [KeyEvent, action]；
     * 参数数组同样仅在存在监听器时才分配）。
     */
    public static void fire(HookEvent event, Object arg1, Object arg2) {
        CopyOnWriteArrayList<Registration> registrations = TABLE.get(event.id());
        if (registrations == null) {
            return;
        }
        dispatch(event.id(), registrations, new Object[] { arg1, arg2 });
    }

    /**
     * 四参派发（如 {@code creative.tab.output} 载荷：标签页键 / 显示参数 / 内容集合 / 搜索集合；
     * 参数数组同样仅在存在监听器时才分配）。
     */
    /**
     * 三参派发（如 {@code server.payload} 载荷 = [Reply, Identifier channel, byte[] data]）。
     *
     * <p><b>2026-10-15 新增</b>：此前只有 0/1/2/4 参重载，恰好缺 3 参 —— 而
     * {@link org.abstruck.brush.transform.HookEvents#SERVER_PAYLOAD} 改成
     * {@code (Reply, id, data)} 后恰为 3 参（docs/26 §7.6 第①条后续：核心 L2 桥不再持有具体载荷类型）。
     * 参数数组同样仅在确实存在监听器时才分配。</p>
     */
    public static void fire(HookEvent event, Object arg1, Object arg2, Object arg3) {
        CopyOnWriteArrayList<Registration> registrations = TABLE.get(event.id());
        if (registrations == null) {
            return;
        }
        dispatch(event.id(), registrations, new Object[] { arg1, arg2, arg3 });
    }

    public static void fire(HookEvent event, Object arg1, Object arg2, Object arg3, Object arg4) {
        CopyOnWriteArrayList<Registration> registrations = TABLE.get(event.id());
        if (registrations == null) {
            return;
        }
        dispatch(event.id(), registrations, new Object[] { arg1, arg2, arg3, arg4 });
    }

    private static void dispatch(String event, List<Registration> registrations, Object[] args) {
        for (Registration registration : registrations) {
            if (!registration.enabled) {
                continue;
            }
            try {
                registration.listener.onEvent(args);
            } catch (Throwable t) {
                throw new IllegalStateException("[brush] hook listener (order=" + registration.order
                        + ", seq=" + registration.sequence + ") for event '" + event + "' failed", t);
            }
        }
    }

    // ---------------------------------------------------------------------
    // 自省（启动日志与自检报告用，§10.5 / §14.1）
    // ---------------------------------------------------------------------

    /** 事件当前监听器数（含被开关关闭但仍注册的）。 */
    public static int listenerCount(HookEvent event) {
        return listenerCount(event.id());
    }

    /**
     * 事件 id 字符串形态的监听器数查询 —— <b>自检源集走廊</b>：{@code org.abstruck.brush.selftest.Selftest}
     * 以 {@code CoreHooks.hitCounts()} 快照的 String 键遍历回查监听数（键 = id 字符串），
     * 该源集不在 P2-1 整改的可改范围，本重载为其保留；<b>新代码一律用
     * {@link #listenerCount(HookEvent)}</b>。键值语义与类型化之前一致
     * （String 键即派发表的 id 键）。
     */
    public static int listenerCount(String event) {
        CopyOnWriteArrayList<Registration> registrations = TABLE.get(event);
        return registrations == null ? 0 : registrations.size();
    }

    /** 全部事件 → 监听器数快照（键 = id 字符串，与 {@code CoreHooks.hitCounts()} 同键形态）。 */
    public static Map<String, Integer> listenerCounts() {
        return TABLE.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().size()));
    }

    // ---------------------------------------------------------------------
    // 注册句柄
    // ---------------------------------------------------------------------

    /** 单个监听器的注册句柄：开关与注销。注销幂等；注销后 {@link #isEnabled()} 恒 false。 */
    public static final class Registration implements AutoCloseable {

        private final String event;
        private final int order;
        private final long sequence;
        private final Listener listener;

        private volatile boolean enabled = true;
        private volatile boolean unregistered;

        private Registration(String event, int order, long sequence, Listener listener) {
            this.event = event;
            this.order = order;
            this.sequence = sequence;
            this.listener = listener;
        }

        /** 开关：关闭后派发跳过该监听器，但保留注册（可重新启用）。 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return this.enabled && !this.unregistered;
        }

        /** 注销并从派发表移除；若该事件已无监听器则移除事件键（恢复零开销短路路径）。 */
        public void unregister() {
            if (this.unregistered) {
                return;
            }
            this.unregistered = true;
            TABLE.computeIfPresent(this.event, (key, list) -> {
                list.remove(this);
                return list.isEmpty() ? null : list;
            });
        }

        @Override
        public void close() {
            unregister();
        }
    }
}
