package org.abstruck.brush.host.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.transform.EarlyBoot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * S2 Fabric 宿主 {@code preLaunch} 入口 —— <b>mod mixin 配置注册的时机载体</b>。
 *
 * <p><b>为什么必须存在</b>：sponge-mixin 的选择门在游戏类开始被 mixin 变换之后即永久关闭，
 * 之后注册的配置永不被选中 —— 表现为"启动正常、无任何报错、mod mixin 全部不存在"
 * （CLAUDE.md §7.1 形态 B 的近亲）。因此 mod 的 mixin 配置必须在任何游戏类经过 mixin
 * 变换之前注册。
 *
 * <p><b>为什么是 preLaunch</b>：Fabric Loader 在 {@code Knot.init} 内完成 mixin 引导后
 * <b>立即</b>调用 preLaunch entrypoints —— 此刻游戏 {@code Main.main} 尚未开始、
 * 任何游戏类尚未加载，因此本入口注册的配置必然先于首个类变换尝试，
 * 选择门结构性放行（不靠时序巧合）。
 *
 * <p><b>与其他入口的分工（三处皆幂等，先到者胜出）</b>：
 * <ol>
 *   <li>本入口（preLaunch）：宿主事实桥接 + {@link EarlyBoot#ensure} —— 扫描 brush-mods /
 *       prepared jar / 暴露（addUrlFwd）/ 注册 mod mixin 配置 / 实例化 main 入口；</li>
 *   <li>{@link BrushFabricClient}（client entrypoint，Minecraft 构造期）：幂等兜底
 *       （引导已完成时为 no-op）+ 自检武装（自检报告 + 退出必须晚于客户端就绪）；</li>
 *   <li>vanilla 最早锚点（{@code BootstrapMixin} → {@link EarlyBoot#onBootstrap}）：
 *       兜底引导 + {@code bootstrap.early} 事件派发，语义不变。</li>
 * </ol>
 *
 * <p><b>失败即中止</b>：任一步失败抛异常让宿主启动失败（§3.5 不静默、不降级）。
 */
public final class BrushFabricPreLaunch implements PreLaunchEntrypoint {

    /** 游戏侧 log4j（MC 上下文，落 run/fabric/logs/latest.log）。 */
    private static final Logger LOGGER = LogManager.getLogger(BrushFabricPreLaunch.class);

    @Override
    public void onPreLaunch() {
        LOGGER.info("[brush] --- S2 preLaunch: bootstrap before the game's first mixin transform (P6) ---");
        String mcVersion = bridgeHostFacts();
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("brush-mods");
        try {
            EarlyBoot.ensure(modsDir, mcVersion);
        } catch (IOException e) {
            // fail-fast，不静默（§3.5）；逐条明细已由引导侧打进日志
            throw new IllegalStateException("[brush] early boot at Fabric preLaunch failed: "
                    + e.getMessage(), e);
        }
        LOGGER.info("[brush] S2 preLaunch complete: brush-mods surfaced, mixin configs registered"
                + " before any game class went through the mixin transformer");
    }

    /**
     * 熔断守卫 + 宿主事实经系统属性桥接（§9.3：宿主代码可引用宿主类型，核心只读属性）。
     * 两个宿主入口（preLaunch / client）共用；返回值 = minecraft 版本（{@link EarlyBoot#ensure} 的入参）。
     */
    static String bridgeHostFacts() {
        // 熔断（§9.5）：standalone 标记出现 = P1 管线在宿主内被误启动（双重 mixin 变换器）。
        // BrushProperties.STANDALONE 是编译期常量，此处引用不触发任何类加载（同 Brush 注释）。
        if ("true".equals(System.getProperty(BrushProperties.STANDALONE))) {
            throw new IllegalStateException("[brush] " + BrushProperties.STANDALONE
                    + "=true inside a Fabric host: the standalone P1 pipeline must not run in host mode"
                    + " (CLAUDE.md sections 9.5/11.5)");
        }
        FabricLoader loader = FabricLoader.getInstance();
        String loaderVersion = modVersion(loader, "fabricloader");
        String mcVersion = modVersion(loader, "minecraft");
        String hostMods = hostMods(loader);
        System.setProperty(BrushProperties.HOST, "fabric");
        System.setProperty(BrushProperties.HOST_VERSION, loaderVersion);
        System.setProperty(BrushProperties.GAME_VERSION, mcVersion);
        System.setProperty(BrushProperties.HOST_MODS, hostMods);
        LOGGER.info("[brush] host: fabric loader " + loaderVersion + ", minecraft " + mcVersion);
        LOGGER.info("[brush] host mods: " + hostMods);
        return mcVersion;
    }

    /** 读宿主 mod 版本（如 fabricloader / minecraft 容器）；缺失时如实返回 "unknown"。 */
    static String modVersion(FabricLoader loader, String modId) {
        Optional<ModContainer> container = loader.getModContainer(modId);
        return container.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** 宿主已加载 mod 清单（含宿主自身与第三方 mod；嵌套 jar 也在列，排序保证可复现）。 */
    static String hostMods(FabricLoader loader) {
        List<String> entries = new ArrayList<>();
        for (ModContainer container : loader.getAllMods()) {
            entries.add(container.getMetadata().getId() + "@"
                    + container.getMetadata().getVersion().getFriendlyString());
        }
        entries.sort(Comparator.naturalOrder());
        return String.join(",", entries);
    }
}
