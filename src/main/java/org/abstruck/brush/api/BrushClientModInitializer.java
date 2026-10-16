package org.abstruck.brush.api;

/**
 * 客户端入口（L2 契约）—— {@code brush.mod.json} 的 {@code entrypoints.client} 所列类实现的接口。
 *
 * <p><b>契约</b>：{@code entrypoints.client} 的每个类须为 public 具体类 + public 无参构造器，
 * 且<b>实现本接口</b>；Brush 在<b>客户端初始化时机</b>构造其实例、随即调用
 * {@link #onInitializeClient()}（都在该 mod 的上下文中执行）。不实现本接口的 client 入口类 =
 * 客户端初始化失败（可读报告 + 中止启动，不静默跳过）。client 入口类只在客户端时机被加载
 * —— 可安全引用客户端类型。
 *
 * <p><b>调用时机</b>：{@code client.run} 锚点（{@code Minecraft#run()V} 的 HEAD）——
 * 客户端实例已构造完成（{@code Minecraft.getInstance()} 可用）、渲染侧 bootstrap 已完成、
 * <b>首个 tick 与 {@code BrushEvents.CLIENT_READY} 派发之前</b>。派发顺序是结构性保证
 * （{@code transform.ClientBoot}：先全部 client 初始化，再派发 {@code client.run}）——
 * 因此在 {@link #onInitializeClient()} 内注册的 {@code CLIENT_READY} 监听器<b>同批送达</b>。
 * 客户端初始化在 {@code entrypoints.main}（{@link BrushModInitializer}）之后执行。
 *
 * <p><b>与 Fabric 的时机差异</b>：Fabric 的 {@code ClientModInitializer} 在 {@code Minecraft}
 * 构造器内触发，早于渲染侧 bootstrap；Brush 复用在三运行时均命中的 {@code client.run} 锚点
 * —— 更早的 {@code ClientBootstrap#bootstrap} 在 {@code Minecraft} 实例构造前调用（mod 拿不到
 * 客户端实例），且新增注入点违背最小改动原则。对当前 API 面两者无行为差异。
 *
 * <p><b>三运行时同一语义</b>：三运行时均为客户端，本入口在三者都触发；同一份字节码行为一致。
 */
public interface BrushClientModInitializer {

    /**
     * 客户端初始化（每进程恰一次；客户端实例已就绪、首个 tick 之前，见类 javadoc）。
     * 抛出异常 = 客户端初始化失败（可读报告 + 中止启动，不吞异常）。
     */
    void onInitializeClient();
}
