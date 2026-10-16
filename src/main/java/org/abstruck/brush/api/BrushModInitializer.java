package org.abstruck.brush.api;

/**
 * 模组主入口（L2 契约）—— {@code brush.mod.json} 的 {@code entrypoints.main} 所列类实现的接口。
 *
 * <p><b>契约</b>：{@code entrypoints.main} 的每个类须为 public 具体类 + public 无参构造器，
 * 且<b>实现本接口</b>；Brush 在模组加载期（游戏代码运行之前）构造其实例、随即调用
 * {@link #onInitialize()}（构造与调用都在该 mod 的上下文中执行，见 {@code content/ModContext}）。
 * 不实现本接口的主入口类 = 加载失败（可读报告 + 中止启动，不静默跳过）。
 *
 * <p><b>调用时机</b>：{@code bootstrap.early} 锚点（早于 {@code Main.main}、内容注册窗口与一切游戏生命
 * 周期事件）—— S1/S2/S3 三运行时一致。此时期<b>不得假定任何游戏状态已就绪</b>：这是"注册期"，
 * 适合注册 L2 事件监听器（{@link BrushEvents}）；游戏交互走监听器（生命周期事件）。
 * 内容注册（物品 / 方块…）经 {@link BrushEvents#CONTENT_REGISTRATION} 的监听器执行 ——
 * 见 {@link BrushRegistry} 的调用位置约束。
 *
 * <p><b>三运行时同一语义</b>：本接口零 MC / 宿主类型依赖，同一份字节码在
 * S1 / S2 / S3 行为一致；主入口在宿主环境同样于 Brush 引导时执行（不依赖宿主入口点）。
 *
 * <p>命名与语义对齐 Fabric 的 {@code ModInitializer#onInitialize()}；
 * 与 Fabric 的差异 = 调用时机为"Brush 引导期"（更早，见上），
 * 且 Brush 尚无服务端运行时 —— server 契约见
 * {@link BrushDedicatedServerModInitializer}。
 */
public interface BrushModInitializer {

    /**
     * 模组主初始化（每进程恰一次；游戏未就绪，见类 javadoc）。
     * 抛出异常 = 该 mod 加载失败（可读报告 + 中止启动，不吞异常）。
     */
    void onInitialize();
}
