package org.abstruck.brush.mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 运行时桥接的<b>选择器</b>（项目内部；核心按<b>类名字符串</b>载入宿主实现，
 * 不出现任何宿主类型的编译期引用 —— {@code mod} 包因此永远不知道 Fabric / NeoForge 的存在）。
 *
 * <p>选择流程：{@link RuntimeKind#detect()}（属性 + 资源探针）→ 类名表 → {@code Class.forName}
 * （经由本类的加载器，即游戏侧类空间，与 {@link ModBootstrap} / 桥接实现同一空间）。
 * 三个实现的落点：
 * <ul>
 *   <li>{@code BRUSH} → {@code org.abstruck.brush.transform.StandaloneBridge}（S1，核心内）；</li>
 *   <li>{@code FABRIC} → {@code org.abstruck.brush.host.fabric.FabricRuntimeBridge}（宿主子包）；</li>
 *   <li>{@code NEOFORGE} → {@code org.abstruck.brush.host.neoforge.NeoForgeRuntimeBridge}（同上）。</li>
 * </ul>
 *
 * <p>结果缓存（每进程恰解析一次）；失败抛可读 {@link IllegalStateException}，由
 * {@link ModBootstrap#install} 汇总进加载失败报告。
 */
public final class RuntimeBridges {

    private static final Logger LOGGER = LogManager.getLogger(RuntimeBridges.class);

    private static volatile RuntimeBridge bridge;

    private RuntimeBridges() {
    }

    /** 当前运行时的桥接实例（首次调用探测 + 载入 + 缓存）。 */
    public static RuntimeBridge current() {
        RuntimeBridge cached = bridge;
        if (cached != null) {
            return cached;
        }
        synchronized (RuntimeBridges.class) {
            if (bridge == null) {
                bridge = create();
            }
            return bridge;
        }
    }

    private static RuntimeBridge create() {
        RuntimeKind kind = RuntimeKind.detect();
        String className = switch (kind) {
            case BRUSH -> "org.abstruck.brush.transform.StandaloneBridge";
            case FABRIC -> "org.abstruck.brush.host.fabric.FabricRuntimeBridge";
            case NEOFORGE -> "org.abstruck.brush.host.neoforge.NeoForgeRuntimeBridge";
        };
        ClassLoader loader = RuntimeBridges.class.getClassLoader();
        LOGGER.info("[brush] runtime kind: " + kind + " (bridge " + className + ", loader "
                + loader.getClass().getName() + ")");
        Class<?> bridgeClass;
        try {
            bridgeClass = Class.forName(className, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("runtime bridge class not on the Brush classpath: "
                    + className + " (runtime " + kind + ")", e);
        } catch (LinkageError e) {
            throw new IllegalStateException("runtime bridge " + className + " failed to link: " + e.getMessage(), e);
        }
        if (!RuntimeBridge.class.isAssignableFrom(bridgeClass)) {
            throw new IllegalStateException("runtime bridge " + className + " does not implement "
                    + RuntimeBridge.class.getName());
        }
        try {
            return (RuntimeBridge) bridgeClass.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate runtime bridge " + className + ": " + e, e);
        }
    }
}
