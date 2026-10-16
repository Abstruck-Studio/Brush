package org.abstruck.brush.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 注册缓冲（E2/E3）—— <b>零 MC 引用</b>（可单测；语义见
 * {@code src/test/java/org/abstruck/brush/registry/ClientRegistrationBufferTest.java}）。
 *
 * <p><b>为什么需要缓冲</b>：两类注册在 vanilla 里都没有"调用即可用"的可达窗口，
 * 但共同点是 <b>内容冻结之后的统一落地点对两者都安全</b>（S1/S2 = bootStrap TAIL；
 * S3 = 首个 {@code packs.registered}，见下"S3 延迟落地"节）：
 * <ul>
 *   <li><b>客户端注册</b>（实体 / 方块实体渲染器、菜单屏幕）：渲染器映射在
 *       {@code Minecraft} 构造期构建（26.1.2：{@code createEntityRenderers} 于
 *       {@code Minecraft.<init>} 内调用，字节码偏移已核实），此后写入静默失效；</li>
 *   <li><b>延迟内容注册</b>（E2 属性，{@code registerAttributes} 的
 *       {@code Supplier<AttributeSupplier.Builder>}）：vanilla 属性 Holder 在
 *       {@code BuiltInRegistries.bootStrap()} 的 {@code freeze()} 阶段才绑定
 *       （唯一 {@code bindValue} 调用点 = {@code MappedRegistry.lambda$freeze$0}），
 *       在 CONTENT_REGISTRATION 窗口（{@code bootStrap} HEAD，pre-freeze）现场求值
 *       {@code AttributeSupplier.Builder} 会触发
 *       {@code Holder$Reference.value() -> IllegalStateException: unbound value} ——
 *       故求值（{@code builder.get()}）必须推迟到 TAIL（post-freeze），与 vanilla
 *       自身经 {@code Bootstrap.validate()} 于 freeze 后构建 {@code DefaultAttributes}
 *       完全同理。</li>
 * </ul>
 * Brush 的接入点因此是同一个 {@code BuiltInRegistries#bootStrap()V} 的 TAIL
 * （E3，注入台账 16 号点）—— 三运行时下都必然早于渲染器映射的构建
 * （S1 早于整个 {@code Minecraft} 构造；S2/S3 构造已在途但映射构建点远晚于本窗口）。
 * mod 的注册调用<b>任意时机</b>到达时先入缓冲，在运行时对应的落地点统一
 * {@link #flush()} → mod 无需知道窗口位置。</p>
 *
 * <p><b>语义（三条，与单测逐条对应）</b>：
 * <ol>
 *   <li><b>flush 前</b>：任务入队，不执行（顺序保持 = 调用序）；</li>
 *   <li><b>flush</b>：按序执行全部已入队任务，然后永久置位；幂等（重复调用 no-op）。
 *       执行中途某任务抛异常 → <b>快速失败</b>：异常原样上抛、其后任务不执行
 *       （启动期硬错误的既定风格，不静默跳过）；</li>
 *   <li><b>flush 后到场</b>：任务<b>立即执行</b>。合法性由调用方守卫承担：
 *       {@code BrushRegistry} 的客户端注册路径拒绝"Minecraft 实例已存在<b>且</b>本窗口
 *       已关闭"的过晚调用（判定读 {@link #windowActive()}）—— 经本缓冲到达执行时，
 *       该守卫已保证 vanilla 映射尚未构建。</li>
 * </ol>
 *
 * <p><b>窗口标记（E2/S2 实测修正）</b>：{@link #enterWindow()} / {@link #exitWindow()}
 * 由 {@code BuiltInRegistries.bootStrap} 的 HEAD / TAIL handler 首末设置。26.1.2 三运行时下
 * bootStrap 的调用点不同：S1 在 {@code Minecraft} 构造前（Main.main → Bootstrap），
 * S2 在 {@code Minecraft.<init>} 中途（fabric-registry-sync-v0 的 afterModInit；T2 首跑
 * 实测栈 {@code Minecraft.<init>(Minecraft.java:477) → BuiltInRegistries.bootStrap()}）。
 * 因此<b>窗口内 {@code Minecraft.getInstance()} 可能已非 null</b>，而渲染器 / 屏幕映射
 * 尚未构建 —— 过晚守卫必须按"实例存在 且 窗口已关闭"判定，不能只看实例
 * （T2 首跑即因此误拒失败，修正见 {@code BrushRegistry.registerDeferred} javadoc）。</p>
 *
 * <p><b>S3 延迟落地（T3 实测修正，2026-10-12）</b>：NeoForge 下 bootStrap TAIL <b>不是</b>
 * 安全的落地点 —— 宿主自身的内容注册阶段（{@code ClientModLoader.begin()} →
 * "Registry initialization"）晚于本 TAIL（javap：patched {@code Main.main} 偏移 680
 * {@code Bootstrap.validate()} / 683 {@code begin()}，而 bootStrap + TAIL 由
 * {@code BackgroundWaiter} 先行完成），neo 内容（如 {@code NeoForgeMod.SWIM_SPEED}）的
 * DeferredHolder 在 begin 内才绑定。TAIL 求值属性 Supplier 因此读到未绑定 holder
 * （T3 实测 {@code NPE: Trying to access unbound value: ...neoforge:swim_speed}，
 * 崩溃栈归档 {@code run/neoforge/crash-reports/}）。S3 的 flush 因此推迟到
 * <b>首个 {@code packs.registered}</b> —— {@code Minecraft.<init>} 内 PackRepository
 * 构造点，晚于 begin()（neo 注册已完成）、早于实体 / 方块实体渲染调度器构建
 * （javap 偏移核实：PackRepository @279/307 vs EntityRenderDispatcher @1950、
 * BlockEntityRenderDispatcher @2014）；由 {@code CoreHooks} 的 sweeper 监听器经
 * {@link #deferredFlushDue()} 判定后执行。S1/S2 与"仓库先于 TAIL"序下 sweeper 均自然
 * no-op。{@code CLIENT_REGISTRATION} 事件本身三运行时仍在 TAIL 派发（事件时序不随
 * 运行时漂移）—— S3 下事件内提交的注册同样入缓冲，在 packs.registered 落地。</p>
 *
 * <p><b>S3 已知告警（T3 实测定性，2026-10-12）</b>：S3 下 begin() 的注册表 bake 阶段
 * 对每个非 MISC 实体类型做"有无属性"校验（javap：宿主
 * {@code NeoForgeRegistryCallbacks$AttributeCallbacks.onBake} 调 vanilla
 * {@code DefaultAttributes.validate()}），该时刻 brush 实体已注册（内容窗口）、属性写
 * （经本缓冲）尚未落地 → 对每个此时刻尚无属性的 brush 实体打一行
 * {@code ERROR: Entity <id> has no attributes}。<b>无功能后果</b>：{@code validate()}
 * 只读（仅经 {@code Util.logAndPauseIfInIde} 记录 —— javap 实证 = {@code LOGGER.error}
 * + 仅在 JDWP 调试下暂停，不抛）；落地后各类读取均正确（{@code DefaultAttributes.getSupplier}
 * 的"宿主 view → 原版 SUPPLIERS"两级回退，javap 实证；自检的 {@code hasSupplier} 断言
 * 实测 PASS）。更早落地不可行：neo 属性 holder 在 begin() 内才绑定（见上节），而 begin()
 * 与首个仓库构建之间没有属于我们的锚点（不得注入宿主类，§9.3 宿主解耦）。宿主侧任何在
 * 本落地点<b>之前</b>查询 supplier 的路径（已实测实例 = 上述 bake 校验）会观察到"未注册"
 * 态 —— 属该设计的已知边界，如实登记。</p>
 *
 * <p><b>线程模型</b>：启动期串行（mod 注册 → 统一 flush → 映射构建点 同线程序）——
 * S1/S2 的 flush 在 bootStrap TAIL；S3 的 flush 在 {@code Minecraft} 构造期内
 * （首仓库构建线程 = 主线程，bootStrap 由 BackgroundWaiter 先行完成，无并发窗口）。
 * lock 仅为防御，不承诺跨线程顺序。flush 是 <b>每进程一次</b>的动作，
 * 不支持复位（自检 / 测试同进程不重复引导 vanilla）。</p>
 */
public final class ClientRegistrationBuffer {

    private static final Object LOCK = new Object();
    private static final List<Runnable> PENDING = new ArrayList<>();
    private static volatile boolean flushed;
    private static volatile boolean windowActive;
    private static volatile boolean tailReached;

    private ClientRegistrationBuffer() {
    }

    /**
     * 提交一个注册任务：flush 前入队，flush 后立即执行（见类 javadoc 三条语义）。
     * 任务异常一律穿透（入队路径 = 调用现场抛出；flush 路径 = flush 调用者抛出）。
     */
    public static void enqueue(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (LOCK) {
            if (!flushed) {
                PENDING.add(task);
                return;
            }
        }
        task.run();
    }

    /** 窗口到达：按序执行全部缓冲任务并永久置位；幂等（重复调用 no-op）。 */
    public static void flush() {
        List<Runnable> batch;
        synchronized (LOCK) {
            if (flushed) {
                return;
            }
            flushed = true;
            batch = List.copyOf(PENDING);
            PENDING.clear();
        }
        for (Runnable task : batch) {
            task.run();
        }
    }

    /** 窗口是否已度过（flush 是否已发生）。供调用方守卫（如"过晚注册"判定）读取。 */
    public static boolean flushed() {
        return flushed;
    }

    /**
     * 标记 bootStrap 窗口进入（{@code BuiltInRegistries.bootStrap} HEAD handler 首行）。
     * S2/S3 宿主可能把 bootStrap 推迟到 {@code Minecraft} 构造中途执行 —— 窗口内实例
     * 已存在但映射未构建，过晚守卫据此放行（见类 javadoc "窗口标记"节）。
     */
    public static void enterWindow() {
        windowActive = true;
    }

    /**
     * 标记 bootStrap 窗口退出（TAIL handler 末行，flush + 事件派发<b>之后</b> ——
     * 事件内提交的注册仍属窗口内合法调用）。
     */
    public static void exitWindow() {
        windowActive = false;
    }

    /** 当前是否处于 bootStrap 窗口内（HEAD 至 TAIL 事件派发完成）。 */
    public static boolean windowActive() {
        return windowActive;
    }

    /**
     * bootStrap TAIL 到达标记（TAIL handler 首行，先于 flush / 事件派发）。
     * 延迟 flush（S3）的资格判定之一：S2 下首个 {@code packs.registered} 可能<b>先于</b>
     * 本 TAIL 到达（PackRepository 构造在 {@code Minecraft.<init>} 浅处、bootStrap 在深处）
     * —— sweeper 据此避免在 TAIL 之前误 flush（那时 vanilla 内容尚未 freeze，求值属性必崩）。
     */
    public static void markTailReached() {
        tailReached = true;
    }

    /**
     * 延迟 flush 是否到期（S3 sweeper 的判定，见类 javadoc"S3 延迟落地"节）：
     * TAIL 已过（{@link #markTailReached}）且尚未 {@link #flush()}。S1/S2 下 TAIL 已当场
     * flush → 恒 false（自然 no-op）；S2 的 TAIL 前仓库 → {@code tailReached}=false → false。
     */
    public static boolean deferredFlushDue() {
        return tailReached && !flushed;
    }
}
