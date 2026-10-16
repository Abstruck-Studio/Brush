package org.abstruck.brush.api;

/**
 * 专用服务端入口（L2 契约）—— {@code brush.mod.json} 的 {@code entrypoints.server} 所列类实现的接口。
 *
 * <p><b>语义对齐 Fabric</b>：server 入口只属于<b>专用服务端（dedicated server）</b>运行时 ——
 * Fabric 的 {@code Hooks.startClient} 只派发 main + client，{@code startServer} 只派发
 * main + server；单人集成的逻辑服务端<b>不</b>触发 server 入口（那是生命周期事件的范围，
 * 不是入口点）。
 *
 * <p><b>当前派发状态</b>：Brush 现有三种运行时（S1 纯 Brush / S2 +Fabric / S3 +NeoForge）
 * <b>均为客户端</b> —— 没有任何 dedicated 运行时，因此本接口与 {@code entrypoints.server} 键
 * 当前<b>只被校验、不被派发</b>：
 * <ul>
 *   <li>元数据侧：{@code entrypoints.server} 的类名在加载期做结构校验（合法类全名），
 *       <b>类本身不会被加载</b> —— dedicated-only 类可能引用客户端不存在的类型，
 *       提前加载会在客户端抛 {@code LinkageError}；</li>
 *   <li>派发侧：无调用点。自检报告以 {@code entrypoints.server: declared=N} 如实列出声明数；</li>
 *   <li>存在意义：元数据与接口契约<b>完整</b>（一个 jar 三种运行时 —— main / client / server
 *       三键就位，服务端侧 mod 代码与客户端侧同 jar），并让未来 dedicated 运行时
 *       或第三方服务端宿主接入时语义直接就位，无需改 mod 侧。</li>
 * </ul>
 *
 * <p><b>契约</b>（若未来被派发）：public 具体类 + public 无参构造器 + 实现本接口；构造与
 * {@link #onInitializeServer()} 调用在同一 mod 上下文内执行；不实现接口 = 可读失败、不静默跳过。
 */
public interface BrushDedicatedServerModInitializer {

    /**
     * 专用服务端初始化。
     *
     * <p><b>当前不会被调用</b>（Brush 无 dedicated 运行时；见类 javadoc）——
     * 本方法的存在是契约完整性，不是可用的回调。实现它不会让代码在任何现有运行时执行。
     */
    void onInitializeServer();
}
