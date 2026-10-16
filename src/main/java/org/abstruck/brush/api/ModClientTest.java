package org.abstruck.brush.api;

import java.util.Map;

/**
 * 可选 mod 实机测试钩子（提案 {@code brush-inworld-mod-test-hook-proposal} §3 方案 A）：
 * 让 mod 在 {@code --brush.mod-test} 运行中，对"进世界之后"的核心行为（建世界 / 数据加载 /
 * 配方生效 / 方块实体 / 实体渲染与生成 / 截屏留证）做端到端断言，结论进退出码。
 *
 * <p>与 {@link ModSelfTest} 的差异：{@link ModSelfTest} 覆盖<b>装载期</b>（首个 {@code client.tick}
 * 之前，注册计数 / mixins / 资源可读性）；本接口覆盖<b>进世界之后</b>的一切 —— 加载器在
 * {@code CLIENT_READY} 之后调用本接口（提案 §3 时机约定），并提供 {@link ClientTestContext}
 * （建世界 / 等服务端 / 截屏的样板收编，见其 javadoc）。
 *
 * <p><b>调用契约</b>：
 * <ul>
 *   <li>仅在 {@code --brush.mod-test} 运行中被调用：{@code CLIENT_READY} 之后由加载器
 *       （{@code selftest.ModTestProbe}）对每个声明了测试入口的 mod 入口实例<b>恰一次</b>；
 *       常规运行 / 自检 / 实机探针零调用、零成本。</li>
 *   <li>实现方<b>不得抛出</b>：抛出的 {@code RuntimeException} 会被捕获并记为该 mod 的全部检查项
 *       FAIL（异常栈如实上报，不吞 —— CLAUDE.md §3.5）；返回 {@code null}、键为 {@code null}
 *       或值为 {@code null} 同样记为格式非法 FAIL。</li>
 *   <li>返回值 = {@code Map.entry(检查名, 是否通过)} 的<b>任意集合</b>（提案 §3 原文签名
 *       {@code Map<Map.Entry<String, Boolean>, ?>}）；每个条目打印报告行
 *       {@code [PASS]/[FAIL] <modId>: <检查名>}；任一 FAIL 置退出码（mod 自定位见
 *       {@link ClientTestContext} 的退出码协议节）。</li>
 * </ul>
 *
 * <p><b>声明方式（提案 §3）</b>：实现类经 {@code brush.mod.json} 的 {@code test} 附加节声明
 * （与 {@link ModSelfTest} 的"经 {@code brush.mod.json} 的附加节声明"同款约定，避免反射约定）；
 * 缺省（不声明）时该 mod 在本模式下不运行任何测试 —— 向后兼容。
 *
 * <p><b>零 MC 引用（纯 JDK + Minecraft 单类）</b>：本接口签名含 {@link java.util.Map} 与
 * {@link ClientTestContext}（其含 {@code Minecraft}）—— 自检 / 探针源集（{@code org.abstruck.brush.selftest}）
 * 可直接引用本接口而不破坏其"零 MC 引用"类加载不变量（与 {@code ModSelfTest} 同口径；
 * 探针类里的 MC 类型集中在 selftest 源集）。
 */
public interface ModClientTest {

    /**
     * 执行本 mod 的实机测试。在 {@code CLIENT_READY} 后、游戏实例可获得时调用；实现可自行
     * 建世界 / 进世界，或使用加载器提供的 {@link ClientTestContext}（建世界 / 等服务端 /
     * 截屏的样板收编）。返回 {@code Map.entry(检查名, 是否通过)} 的集合（单条目或多条目）。
     *
     * <p>契约与失败语义见接口 javadoc。实现不得抛出（抛出 = 该 mod 全部检查项 FAIL，异常栈
     * 如实上报，不吞）。
     *
     * @param context 加载器提供的测试上下文（建世界 / 等服务端 / 截屏）
     * @return 检查项结果集（键 = 检查名，值 = 是否通过）；{@code null} / 格式非法 = FAIL
     */
    Map<Map.Entry<String, Boolean>, ?> runClientTest(ClientTestContext context);
}
