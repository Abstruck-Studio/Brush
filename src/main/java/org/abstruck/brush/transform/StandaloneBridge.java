package org.abstruck.brush.transform;

import org.abstruck.brush.mod.RuntimeBridge;
import org.abstruck.brush.mod.RuntimeKind;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * S1（standalone）运行时接面（docs/19 §4）。
 *
 * <p><b>类空间与桥接约定</b>：本类由 S1 启动管线里的 {@code BrushClassLoader} 加载（游戏侧类空间），
 * 而 {@code BrushClassLoader} 类本身位于应用侧类空间 —— 两侧不共享类身份（docs/06 §5 同款约定）。
 * 因此 {@link #exposeModJar} <b>不</b>引用 {@code BrushClassLoader} 类型，而是对"加载本类的加载器"
 * 实例做 JDK 反射调用 {@code addModJar(java.nio.file.Path)}（只经 JDK 类型通信）；方法不存在时
 * 硬失败并报出实际加载器类名（不静默降级）。
 *
 * <p><b>expose 的语义</b>：{@code BrushClassLoader.addModJar} 把 mod jar 追加到类路径<b>末尾</b> ——
 * vanilla / 游戏库已在前，不会被遮蔽（findResource 顺序）；追加后：
 * (a) mod 类可被加载（findClass → 经注入管线变换 → 定义，mod 的自 mixin 在 S1 下因此可用）；
 * (b) {@code BrushMixinService} 的资源读取经同一加载器实例，mod 的 mixin 配置与 mixin 类字节
 * 自动可读（无需额外通路）。
 *
 * <p><b>注册语义</b>：与 S2/S3 相同 —— sponge-mixin 的 {@code Mixins} 注册表 +
 * {@link MixinConfigRegistry} 复核（配置资源经上面的 (b) 通路读取）。
 */
public final class StandaloneBridge implements RuntimeBridge {

    /** addModJar 方法缓存（每进程恰解析一次；见类 javadoc 的桥接约定）。 */
    private static volatile Method addModJar;

    @Override
    public RuntimeKind kind() {
        return RuntimeKind.BRUSH;
    }

    @Override
    public void exposeModJar(Path jar) throws IOException {
        ClassLoader loader = StandaloneBridge.class.getClassLoader();
        Method method = addModJar;
        if (method == null) {
            try {
                method = loader.getClass().getMethod("addModJar", Path.class);
            } catch (NoSuchMethodException e) {
                throw new IOException("standalone bridge: class loader " + loader.getClass().getName()
                        + " does not expose addModJar(Path); expected the S1 BrushClassLoader"
                        + " (was the mod loaded outside the standalone pipeline?)", e);
            }
            addModJar = method;
        }
        try {
            method.invoke(loader, jar);
        } catch (IllegalAccessException e) {
            throw new IOException("standalone bridge: cannot access addModJar on "
                    + loader.getClass().getName() + ": " + e, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("standalone bridge: addModJar(" + jar + ") failed: " + cause, cause);
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
}
