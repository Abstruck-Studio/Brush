package org.abstruck.brush.content;

import org.abstruck.brush.transform.Dispatcher;

import java.util.function.Supplier;

/**
 * 当前 mod 的注册上下文：把"这段代码正在为哪个 mod 执行"从入口类构造期
 * 带到内容注册窗口期的监听器执行现场，供归账（{@link ContentLedger}）标注归属
 * （注册来源由 {@code ModBootstrap} 在入口构造期设置的内部 ThreadLocal 上下文标注）。
 *
 * <p><b>为什么需要它</b>：注册发生在 {@code api.BrushEvents.CONTENT_REGISTRATION} 的监听器内
 * （窗口到达时），而那一刻栈上只剩我们的派发代码 —— mod id 只有在入口类<b>构造期</b>才天然可见。
 * 本类在构造期把 mod id 放进 ThreadLocal，监听器经 {@link #withCurrent}（由
 * {@code api.Event.register} 在注册时捕获并施加）把上下文带回来。
 *
 * <p><b>零 MC 引用</b>（同 {@link ContentLedger}）：本类被 {@code api.Event} 引用，
 * 而 {@code api} 包受"零 MC"约束（无 MC 类路径即可加载并派发）。
 *
 * <p><b>线程模型</b>：mod 入口构造与内容注册窗口均发生在同一线程（S1 主线程；宿主下以各宿主
 * 为准），ThreadLocal 仅作为"沿调用链传递"的载体；{@link #callWith} / {@link #withCurrent}
 * 均为 save-restore，嵌套时内层覆盖外层、退出时原样恢复，无跨调用残留。
 *
 * <p><b>作用域纪律</b>：仅 {@code mod.ModBootstrap.instantiate}（设置）与
 * {@code api.Event.register}（捕获 + 施加）两个调用点 —— 不做全局安装、不做默认值推断
 * （无上下文 = 调用方用错，见 {@code BrushRegistry} 的显式拒绝）。
 */
public final class ModContext {

    /** 当前线程隐含的 mod id；未设置 = null。 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ModContext() {
    }

    /** 当前线程的 mod id；未在 mod 上下文内时返回 {@code null}。 */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * 在 {@code modId} 上下文中执行 {@code action} 并返回其结果（save-restore）。
     * 调用点：{@code ModBootstrap.instantiate} 包裹入口类构造 —— 构造期注册的事件监听器
     * 经 {@link #withCurrent} 捕获到的即该 mod id。
     *
     * <p>异常语义：{@code action} 抛出的受检 / 非受检异常一律<b>原样穿透</b>（本方法只做
     * save-restore，不包装、不吞；调用方既有 catch 语义不变）。
     */
    public static <T> T callWith(String modId, Supplier<T> action) {
        String previous = CURRENT.get();
        CURRENT.set(modId);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * 把 {@code supplier} 包装为"执行时恢复捕获时上下文"的等价函数
     * （{@code api.Event.register} 在注册时调用 —— 捕获刻即 mod 入口构造期）。
     * 捕获为 null（非 mod 上下文的注册，如宿主适配 / 测试）时返回原函数，零额外分配。
     */
    public static <T> Supplier<T> withCurrent(Supplier<T> supplier) {
        String captured = current();
        if (captured == null) {
            return supplier;
        }
        return () -> {
            String previous = CURRENT.get();
            CURRENT.set(captured);
            try {
                return supplier.get();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 把 {@code listener}（直接挂在 L1 {@link Dispatcher} 上的那种）包装为
     * "派发时恢复捕获时上下文"的等价函数 —— 形状与 {@link #withCurrent} 相同，
     * 仅为 {@code Dispatcher.Listener} 的函数式签名单列。捕获为 null 时返回原监听器。
     */
    public static Dispatcher.Listener wrap(Dispatcher.Listener listener) {
        String captured = current();
        if (captured == null) {
            return listener;
        }
        return args -> {
            String previous = CURRENT.get();
            CURRENT.set(captured);
            try {
                listener.onEvent(args);
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(String previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
