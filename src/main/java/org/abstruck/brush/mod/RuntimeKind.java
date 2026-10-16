package org.abstruck.brush.mod;

import org.abstruck.brush.BrushProperties;

import java.util.Locale;

/**
 * 当前进程所处的运行时（S1/S2/S3；探测见 {@link #detect()}）。
 *
 * <p>识别依据（探针顺序即优先级，全部为可观察事实）：
 * <ol>
 *   <li>{@code brush.standalone} 系统属性为 true → {@link #BRUSH}。该属性只有我们的
 *       {@code TransformBootstrap.install()}（S1 启动管线）会设置 —— 与
 *       {@code BrushMixinService.isValid()} 同款守卫，S2/S3 下宿主不会设置它；</li>
 *   <li>类空间可见 {@code net.fabricmc.loader.impl.launch.knot.Knot} 的资源 → {@link #FABRIC}
 *       （Fabric Loader 在启动 classpath 上，模组类空间可见）；</li>
 *   <li>类空间可见 {@code net.neoforged.fml.loading.FMLLoader} 的资源 → {@link #NEOFORGE}；</li>
 *   <li>都不匹配 → 抛可读 {@link IllegalStateException}（不静默假定 vanilla —— 假定错会
 *       让 mod mixin 注册到错误的子系统上）。</li>
 * </ol>
 */
public enum RuntimeKind {

    /** S1：vanilla + Brush（standalone 启动管线）。 */
    BRUSH("Brush"),

    /** S2：Fabric 宿主 + Brush（Fabric Loader 在启动 classpath 上）。 */
    FABRIC("Fabric"),

    /** S3：NeoForge 宿主 + Brush（FML 提供类加载与 mixin 子系统）。 */
    NEOFORGE("NeoForge");

    private final String implSuffix;

    RuntimeKind(String implSuffix) {
        this.implSuffix = implSuffix;
    }

    /**
     * 平台实现类的命名后缀（{@code api.BrushExpectPlatform} 契约）：
     * 桩类 {@code com.example.Platform} 的平台实现类名 =
     * {@code com.example.PlatformImpl} + 本后缀（如 {@code PlatformImplFabric}）。
     */
    public String implSuffix() {
        return implSuffix;
    }

    /**
     * {@code brush.mod.json.mixins} 的分组键（= implSuffix 小写：{@code brush} / {@code fabric} /
     * {@code neoforge}；当前运行时只注册自己键下的配置列表）。
     */
    public String jsonKey() {
        return implSuffix.toLowerCase(Locale.ROOT);
    }

    /** 由分组键解析运行时；未知键抛可读 {@link IllegalArgumentException}（typo 防护，调用方转加载失败）。 */
    public static RuntimeKind byJsonKey(String key) {
        for (RuntimeKind kind : values()) {
            if (kind.jsonKey().equals(key)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown mixins group key '" + key
                + "' (expected brush / fabric / neoforge)");
    }

    /** 探测当前运行时（见类 javadoc；无法识别时抛可读 {@link IllegalStateException}）。 */
    public static RuntimeKind detect() {
        if (Boolean.parseBoolean(System.getProperty(BrushProperties.STANDALONE))) {
            return BRUSH;
        }
        ClassLoader loader = RuntimeKind.class.getClassLoader();
        if (loader.getResource("net/fabricmc/loader/impl/launch/knot/Knot.class") != null) {
            return FABRIC;
        }
        if (loader.getResource("net/neoforged/fml/loading/FMLLoader.class") != null) {
            return NEOFORGE;
        }
        throw new IllegalStateException("cannot detect the host runtime: neither the standalone marker ("
                + BrushProperties.STANDALONE + ") nor a Fabric Loader / NeoForge FML class is visible"
                + " from " + loader + " -- refusing to guess (a wrong guess would register mod mixins into the"
                + " wrong subsystem; CLAUDE.md 7.1 silent-failure avoidance)");
    }
}
