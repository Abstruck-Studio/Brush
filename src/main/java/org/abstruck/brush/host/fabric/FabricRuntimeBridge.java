package org.abstruck.brush.host.fabric;

import org.abstruck.brush.mod.RuntimeBridge;
import org.abstruck.brush.mod.RuntimeKind;
import org.abstruck.brush.transform.MixinConfigRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;

/**
 * S2（Fabric 宿主）运行时接面。全程 JDK 反射（{@code KnotClassLoader} 是
 * 包私有类，公开方法经反射仍需 {@code setAccessible}），本类与宿主只共享 JDK 类型（§9.3）。
 *
 * <p><b>暴露机制（单点）</b>：{@code KnotClassLoader.addUrlFwd(URL)}
 * 把 mod jar 追加进 Knot 的 {@code DynamicURLClassLoader}（{@code urlLoader}）。
 * {@code KnotClassLoader.getResource} 为 <b>urlLoader 优先</b>、之后才回退
 * {@code originalLoader}；而 Knot 的类字节读取与 {@code tryLoadClass}
 * 都走这条通路 —— 因此追加后：mod 的 mixin 配置资源、mixin 类字节对宿主 mixin 子系统可读，
 * 且 mod 类由 Knot 的注入管线定义（mixin 得以施加）。
 *
 * <p><b>不触碰 {@code validParentCodeSources}</b>：该白名单门控的是<b>系统 classpath 上</b>
 * 的类能否被系统父加载器送达游戏。我们的 jar 只进 urlLoader、从不位于系统 classpath，
 * 扩展该集合是语义 no-op；少碰一个内部成员即少一处版本脆弱面。
 *
 * <p><b>版本纪律</b>：成员名按 Fabric Loader <b>0.19.5</b> 写死；缺失即硬失败并
 * 报实际类名（不静默降级，CLAUDE.md §3.5；宿主升级后重跑矩阵，§17.3 E3）。
 *
 * <p><b>注册语义</b>：配置资源经 urlLoader 通路可读，直接
 * {@link MixinConfigRegistry} 注册 + 复核 —— 与 S1/S3 共用同一逻辑。
 */
public final class FabricRuntimeBridge implements RuntimeBridge {

    private static final Logger LOGGER = LogManager.getLogger(FabricRuntimeBridge.class);

    // 懒初始化字段（P1-8）：volatile + init() 整体同步 —— 未同步时另一线程可能
    // 看到 initialized=true 但 knotLoader / addUrlFwd 仍为 null（部分初始化状态可见）。
    private volatile Object knotLoader;
    private volatile Method addUrlFwd;
    private volatile boolean initialized;

    @Override
    public RuntimeKind kind() {
        return RuntimeKind.FABRIC;
    }

    @Override
    public void exposeModJar(Path jar) throws IOException {
        init();
        URL url;
        try {
            url = jar.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IOException("fabric bridge: bad jar path " + jar + ": " + e, e);
        }
        try {
            addUrlFwd.invoke(knotLoader, url);
            LOGGER.info("[brush]   fabric bridge: exposed " + jar.getFileName() + " (addUrlFwd)");
        } catch (IllegalAccessException e) {
            throw new IOException("fabric bridge: reflective access failed: " + e, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("fabric bridge: exposing " + jar + " failed: " + cause, cause);
        }
    }

    @Override
    public void registerModMixinConfig(String configResource) throws IOException {
        try {
            MixinConfigRegistry.registerAndVerify(configResource);
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 反射解析 Knot 接面（见类 javadoc）。{@code addUrlFwd} 是包私有类
     * {@code KnotClassLoader} 上的 public 方法 —— 反射调用需 {@code setAccessible}，
     * 故走 {@code getDeclaredMethod}；方法直接声明在 KnotClassLoader 上（0.19.5）。
     * 缺失 = 可读硬失败（版本漂移必须显式暴露，不静默降级）。
     */
    private synchronized void init() throws IOException {
        if (initialized) {
            return;
        }
        ClassLoader loader = FabricRuntimeBridge.class.getClassLoader();
        Class<?> loaderClass = loader.getClass();
        try {
            addUrlFwd = loaderClass.getDeclaredMethod("addUrlFwd", URL.class);
            addUrlFwd.setAccessible(true);
        } catch (NoSuchMethodException | RuntimeException e) {
            throw new IOException("fabric bridge: class loader " + loaderClass.getName()
                    + " does not expose the Knot internals this bridge is built on"
                    + " (addUrlFwd(URL); taken from Fabric Loader 0.19.5 bytecode): " + e, e);
        }
        knotLoader = loader;
        initialized = true;
        LOGGER.info("[brush] fabric bridge initialized: loader=" + loaderClass.getName());
    }
}
