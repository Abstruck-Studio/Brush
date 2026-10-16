package org.abstruck.brush.api;

import java.util.Map;

/**
 * 可选 mod 自检钩子（E4）：让 mod 在自检退出点（首个 {@code client.tick}）对"自己的注册是否
 * 真实生效"做端到端断言，结论进自检报告与退出码（{@code --brush.selftest} 运行专用）。
 *
 * <p><b>调用契约</b>：
 * <ul>
 *   <li>仅在自检运行中被调用：退出点（首个 {@code client.tick}，渲染线程）对每个入口实例
 *       <b>恰一次</b>；常规运行零监听、零调用、零成本。</li>
 *   <li>调用时刻的既成事实：内容注册窗口与客户端注册 flush（{@code BuiltInRegistries.bootStrap}
 *       的 HEAD / TAIL 两窗口）均已过去、Minecraft 实例已构造 —— 注册表、{@code DefaultAttributes}、
 *       {@code SpawnPlacements}、渲染器 / 屏幕映射等都可直接读取断言，无需等待任何后续时机。</li>
 *   <li>实现方<b>不得抛出</b>：抛出的 {@code RuntimeException} 会被捕获并记为 FAIL（异常栈如实
 *       上报，不吞）；返回 {@code null}、键为 {@code null} 或值为 {@code null} 同样记为格式非法 FAIL。</li>
 *   <li>返回值 = {@code Map.entry(检查名, 是否通过)} 的单条目。检查名进报告行
 *       {@code [PASS]/[FAIL] <modId>: <检查名>}；任一 FAIL 置退出码位 256
 *       （{@code Selftest} 的 {@code EXIT_MOD_SELFTEST}）。无任何 mod 实现本接口时报告为
 *       信息行、<b>不是失败</b>（"必须有 mod 自检"是具体夹具的事实，不进核心断言）。</li>
 * </ul>
 *
 * <p><b>实现位置</b>：自检对 {@code ModBootstrap.loadedMods()} 的<b>入口实例</b>做
 * {@code instanceof} 探测 —— 主入口（{@link BrushModInitializer}）或客户端入口
 * （{@link BrushClientModInitializer}）实例实现本接口均可。
 *
 * <p><b>零 MC 引用</b>（纯 JDK 类型）：自检源集（{@code org.abstruck.brush.selftest}）可直接
 * 引用本接口而不破坏其"零 MC 引用"类加载不变量（与 {@code ContentLedger} 同口径）。</p>
 */
@FunctionalInterface
public interface ModSelfTest {

    /**
     * 执行本 mod 的自检。返回 {@code Map.entry(检查名, 是否通过)}（单条目）；
     * 契约与失败语义见接口 javadoc。实现不得抛出。
     */
    Map.Entry<String, Boolean> selfTest();
}
