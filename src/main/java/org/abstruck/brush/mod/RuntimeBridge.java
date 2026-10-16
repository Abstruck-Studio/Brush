package org.abstruck.brush.mod;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 运行时接面 SPI（项目内部接口；mod 不可见）。
 *
 * <p>每个运行时一个实现（S1 自研加载器 / S2 Fabric Knot / S3 NeoForge FML），
 * 由 {@link RuntimeBridges} 在 {@code mod} 核心之外按类名载入 —— <b>核心不引用任何宿主类型</b>：
 * 接口只出现 JDK 类型（{@link Path} / {@link String}），宿主相关代码全部在各自的
 * {@code .host} 子包或 S1 的 {@code transform} 包内（包纪律）。
 *
 * <p>两个能力：
 * <ul>
 *   <li>{@link #exposeModJar} —— 把 mod jar 暴露给当前运行时的<b>类加载与字节读取</b>通路，
 *       使 (a) mod 类可被加载、(b) mixin 子系统能读到 mod 的<b>配置资源与 mixin 类字节</b>。
 *       三种运行时的暴露点不同（S1：{@code BrushClassLoader.addModJar}；
 *       S2：Knot 的 {@code addUrlFwd} + 父类路径白名单扩展；S3：游戏 {@code ModuleClassLoader}
 *       的 fallback 类加载器包装）；</li>
 *   <li>{@link #registerModMixinConfig} —— 把 mod 声明的 mixin 配置注册进<b>当前活动的</b>
 *       mixin 子系统（三运行时共用同一份 sponge-mixin 0.17.4 的 {@code Mixins} 注册表，
 *       注册后<b>复核</b>确实入列 —— 解析失败只会记 ERROR 就丢弃，不复核 = 静默失效）。</li>
 * </ul>
 *
 * <p>失败语义：全部转可读 {@link IOException}（消息自解释），由 {@link ModBootstrap} 汇总进
 * 加载失败报告 —— 不静默跳过。
 */
public interface RuntimeBridge {

    /** 本桥接对应的运行时。 */
    RuntimeKind kind();

    /**
     * 把 mod jar（原始或预处理后的副本）暴露给当前运行时的类加载 / 字节读取通路。
     * 对同一个 jar 幂等。
     */
    void exposeModJar(Path jar) throws IOException;

    /**
     * 注册一个 mod mixin 配置（{@code brush.mod.json.mixins} 条目）并复核注册结果。
     * 配置解析失败（JSON 非法 / 资源读不到）在此硬失败 —— hook 不允许静默消失。
     */
    void registerModMixinConfig(String configResource) throws IOException;
}
