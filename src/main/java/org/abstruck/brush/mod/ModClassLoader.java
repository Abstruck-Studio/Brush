package org.abstruck.brush.mod;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Brush mod 的类加载器。
 *
 * <p><b>定位</b>：
 * <ul>
 *   <li>parent = 游戏侧 {@code BrushClassLoader} —— mod 代码可引用 MC 与库类，以及 Brush 的
 *       L2 API；</li>
 *   <li>每个 mod 一个实例 —— 用于<b>实例化入口类与解析该 mod 自己的类</b>；</li>
 *   <li>URLClassLoader 自带 parallel capable 注册（JDK 行为），mod 自建线程并发定义类安全；</li>
 *   <li>委托策略为标准 parent-first：mod 若打包了与父加载器同名的类（如 gson），以父加载器为准。</li>
 * </ul>
 *
 * <p><b>类隔离的实况（2026-10-14 订正）</b>：本加载器的 URL 列表只含<b>该 mod 自己</b>的 jar
 * （与嵌套 jar），但它的父级是<b>游戏侧共享加载器</b>（S1 = {@code BrushClassLoader}、
 * S2 = Knot、S3 = 宿主类加载桥），而装配期 {@code RuntimeBridge.exposeModJar} 会把<b>每个</b>
 * mod jar 暴露进那个共享空间 —— 叠加 parent-first 委托，结论是：<b>mod 之间其实可以互相看见
 * 对方的类</b>。此前本 javadoc 写"mod 之间类空间隔离（不支持 mod 互引类）"，与实际不符，已订正
 * （证据链与影响见 docs/26；Phase C 的独立 {@code Brush API} mod 正依赖这一性质，且该性质已由
 * selftest 探针 {@code SelftestSharedClassSpaceProbe} 在三个运行时断言）。</p>
 *
 * <p><b>方向性约束</b>：核心（launch/transform/mod 引导）不得依赖 mod 的任何类型 ——
 * 本加载器只被 {@link ModBootstrap} 用于实例化入口类，实例以 {@code Object} 形式持有。
 *
 * <p><b>嵌套 jar</b>：{@code brush.mod.json} 的 {@code jars[]} 声明的条目由
 * {@link NestedJarExtractor} 解出后作为<b>额外类路径</b>加进本加载器 —— 于是"打进 mod jar 的
 * 第三方依赖"对该 mod 的代码可见（docs/25 §6 缺口 #1）。多个 URL 由 {@link URLClassLoader}
 * 按声明顺序查找，语义与单个 jar 时一致。</p>
 *
 * <p><b>L1 隔离说明（如实登记）</b>：mod 在技术上能经父加载器触达 {@code org.abstruck.brush.transform}
 * （L1 实现层）。这是类加载模型决定的、无法低成本强制的事；「禁止 mod 直接依赖 L1」
 * 属<b>契约约束</b>（L2 API 是 mod 唯一合法依赖面），此处登记而非假装已强制。
 */
final class ModClassLoader extends URLClassLoader {

    private final String modId;

    ModClassLoader(String modId, Path jar, List<Path> nestedJars, ClassLoader parent)
            throws MalformedURLException {
        super("brush-mod:" + modId, urls(jar, nestedJars), parent);
        this.modId = modId;
    }

    /** URL 顺序 = 查找顺序：mod jar 本体在前，嵌套 jar 按声明顺序在后。 */
    private static URL[] urls(Path jar, List<Path> nestedJars) throws MalformedURLException {
        List<URL> urls = new ArrayList<>(1 + nestedJars.size());
        urls.add(jar.toUri().toURL());
        for (Path nested : nestedJars) {
            urls.add(nested.toUri().toURL());
        }
        return urls.toArray(new URL[0]);
    }

    @Override
    public String toString() {
        return "ModClassLoader[" + modId + "]";
    }
}
