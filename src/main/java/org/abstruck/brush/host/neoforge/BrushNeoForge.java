package org.abstruck.brush.host.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.VersionInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.host.HostSeams;
import org.abstruck.brush.transform.CoreHooks;
import org.abstruck.brush.transform.EarlyBoot;
import org.abstruck.brush.transform.InjectionLedger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * S3 NeoForge 宿主适配入口（{@code META-INF/neoforge.mods.toml} 声明的 javafml mod，
 * 实例化方式为<b>构造器注入</b>：{@code (IEventBus, ModContainer)}，依据见 docs/13 §2）。
 *
 * <p><b>运行位置</b>：本类由 FML 在 mod 构造阶段加载 —— 宿主持有自己的
 * TransformingClassLoader 与 mixin 子系统（{@code mixin.service} 钉死为
 * {@code FMLMixinService}），我们不得抢 MC 类定义权（CLAUDE.md §11.7）。
 * 因此本类<b>只做三件事</b>：
 * <ol>
 *   <li><b>模式守卫</b>：{@code brush.standalone} 出现即熔断（§9.5）—— 那意味着 S1 的 P1 管线在
 *       宿主内被误启动，会出现双重 mixin 变换器；</li>
 *   <li><b>宿主事实经系统属性桥接后调核心</b>（§9.3：宿主代码可调核心，核心不引用宿主类型）：
 *       写 {@code brush.host*} 属性 → {@link EarlyBoot#ensure}（幂等统一引导：
 *       {@link CoreHooks#install()} + {@code brush-mods/} 加载，与宿主 {@code mods/} 隔离，
 *       §11.8 D2）—— 通常已由 vanilla 最早锚点 {@code bootstrap.early} 在 patched
 *       {@code Minecraft} 构造路径中<b>先行完成</b>，此处退化为兜底；mixin 织入已由 FML 依
 *       {@code neoforge.mods.toml} 的 {@code [[mixins]]} 完成，
 *       <b>不</b>调用 {@code TransformBootstrap#install()}）→ 按需反射调用
 *       {@code org.abstruck.brush.selftest.Selftest#arm()}（C.1：自检装置已移出 main 源集，
 *       T3 夹具 jar 提供）；</li>
 *   <li><b>失败即中止</b>：任一步失败抛异常让宿主启动失败 —— 不静默、不降级（§3.5）。</li>
 * </ol>
 *
 * <p><b>时机</b>：本构造器不是引导的驱动者：引导由 vanilla 最早锚点
 * （{@code Bootstrap.bootStrap} HEAD，patched {@code Minecraft} 构造路径）先行完成，
 * 本构造器只做宿主事实桥接 + 幂等兜底 + 自检武装。构造器运行于 FML 的
 * mod 构造阶段，早于首个 {@code run()} / {@code tick()}；此时宿主 mod 清单 {@code ModList}
 * 可能尚未就绪（{@code ModList.get()} 未就绪时返回 null）—— 未就绪则挂
 * {@link FMLClientSetupEvent} 兜底填充，自检报告在首个 {@code client.tick} 才读取该属性，
 * 时序必然成立。时序正确性最终由 T3 自检报告的 hook 命中计数（各 ≥1）自证，不靠假定。
 */
@Mod(value = "brush", dist = Dist.CLIENT)
public final class BrushNeoForge {

    /** 游戏侧 log4j（MC 上下文，落 run/neoforge/logs/latest.log）。 */
    private static final Logger LOGGER = LogManager.getLogger(BrushNeoForge.class);

    public BrushNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[brush] --- S3 host adaptation: Brush inside NeoForge (P7) ---");

        // 熔断（§9.5）：standalone 标记出现 = P1 管线在宿主内被误启动（双重 mixin 变换器）。
        // BrushProperties.STANDALONE 是编译期常量，此处引用不触发任何类加载（同 Brush 注释）。
        if ("true".equals(System.getProperty(BrushProperties.STANDALONE))) {
            throw new IllegalStateException("[brush] " + BrushProperties.STANDALONE
                    + "=true inside a NeoForge host: the standalone P1 pipeline must not run in host mode"
                    + " (CLAUDE.md sections 9.5/11.5)");
        }

        // 宿主事实（宿主代码可引用宿主类型；经属性桥接给核心，§9.3）。
        // 版本来源 = FMLLoader.getCurrent().getVersionInfo()（record：mcVersion/neoForgeVersion/neoFormVersion），
        // 值即 --fml.* 启动参数（docs/13 §2）。
        VersionInfo fmlVersion = FMLLoader.getCurrent().getVersionInfo();
        String mcVersion = fmlVersion.mcVersion();
        String neoForgeVersion = fmlVersion.neoForgeVersion();
        System.setProperty(BrushProperties.HOST, "neoforge");
        System.setProperty(BrushProperties.HOST_VERSION, neoForgeVersion);
        System.setProperty(BrushProperties.GAME_VERSION, mcVersion);
        String hostMods = hostModsOrNull();
        if (hostMods != null) {
            System.setProperty(BrushProperties.HOST_MODS, hostMods);
            LOGGER.info("[brush] host mods: " + hostMods);
        } else {
            // 构造期 ModList 尚未就绪 —— 挂客户端初始化事件兜底。兜底仍读不到即抛错中止（§3.5）。
            modEventBus.addListener(FMLClientSetupEvent.class, event -> {
                String mods = hostModsOrNull();
                if (mods == null) {
                    throw new IllegalStateException("[brush] ModList still not loaded at FMLClientSetupEvent");
                }
                System.setProperty(BrushProperties.HOST_MODS, mods);
                LOGGER.info("[brush] host mods (filled at FMLClientSetupEvent): " + mods);
            });
            LOGGER.info("[brush] host mods: deferred (ModList not loaded at construction;"
                    + " will be filled at FMLClientSetupEvent)");
        }
        LOGGER.info("[brush] host: neoforge " + neoForgeVersion + ", minecraft " + mcVersion
                + " (brush mod container version " + modContainer.getModInfo().getVersion() + ")");

        // 统一引导（幂等）：核心 hook 监听器 + brush-mods/ 加载（§11.8 D2 与宿主 mods/ 隔离）。
        // 通常已由最早锚点（Bootstrap.bootStrap HEAD）先行完成 —— 此处为兜底，不再是驱动者。
        // 不再走 TransformBootstrap —— 那只属于 S1（standalone mixin 引导）；
        // mixin 织入由 FML 依 toml 的 [[mixins]] 完成（§11.4）。
        Path modsDir = FMLPaths.GAMEDIR.get().resolve("brush-mods");
        try {
            EarlyBoot.ensure(modsDir, mcVersion);
        } catch (IOException e) {
            // fail-fast，不静默（§3.5）；逐条明细已由引导侧打进日志
            throw new IllegalStateException("[brush] early boot under NeoForge host failed: " + e.getMessage(), e);
        }
        LOGGER.info("[brush] " + InjectionLedger.summary());

        // 载荷转注册桥（2026-10-15）：NeoForge 要求自定义载荷先经其 NetworkRegistry 登记，未登记者
        // checkPacket 直接拒绝 ⇒ 我们的 BrushNetwork.send 在 S3 下会崩（实机证据 docs/29 §6.5）。
        // 桥与网络引擎**已按 docs/26 §7.6 整体搬进库模组 brush-api**，故核心这里只把事件总线
        // **当 Object 递出**（接缝，见 host.HostSeams）—— 核心零 mod 类型引用，方向合法。
        // 必须在 mod bus 的 RegisterPayloadHandlersEvent 派发**之前**触发 —— 构造期正是其时。
        HostSeams.fireModEventBus(modEventBus);

        boolean selftest = "true".equals(System.getProperty(BrushProperties.SELFTEST));
        if (selftest) {
            armSelftest();
        }
        // 实机探针（2026-10-14 新增）：与 selftest 同一机理 —— 开关经系统属性抵达，类在夹具 jar 里。
        // 用途：把"双向网络恰好一次"这类**需要真进世界**的验证从 S1 扩到 S3。
        boolean reality = "true".equals(System.getProperty(BrushProperties.REALITY));
        if (reality) {
            armRealityProbe();
        }
        LOGGER.info("[brush] S3 ready: hooks installed, brush-mods loaded, selftest "
                + (selftest ? "armed (report + exit at first client.tick)" : "off")
                + ", reality " + (reality ? "armed (join singleplayer world; exit code = verdict)" : "off"));
    }

    /**
     * 反射武装自检（C.1）：{@code selftest.Selftest} 已移出 main 源集 —— 生产形态（loader jar
     * 装进宿主）不含该类，T3 测试实例经夹具 jar（{@code brush_selftest}，见 build.gradle
     * {@code selftestFixtureJar}）提供，与 brush 主 jar 同层宿主加载器 → 类身份与 Dispatcher 共享。
     *
     * <p>失败语义（§3.5 不静默降级）：自检属性已置位而类不可达 = 硬错误中止启动。
     */
    private static void armSelftest() {
        Class<?> selftest;
        try {
            selftest = Class.forName("org.abstruck.brush.selftest.Selftest");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[brush] selftest requested (brush.selftest=true) but "
                    + "org.abstruck.brush.selftest.Selftest is not on the host classpath —— the selftest "
                    + "payload ships separately from the loader artifact (T3 fixture jar); "
                    + "run via runNeoForgeSelftest rather than a production instance", e);
        }
        try {
            selftest.getMethod("arm").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[brush] selftest arming failed: " + e, e);
        }
    }

    /**
     * 反射武装实机探针（{@code selftest.RealityProbe}）：与 {@link #armSelftest()} 同机理 ——
     * 该类在夹具 jar（{@code brush_selftest}）里，生产形态的 loader jar 不含它。
     *
     * <p>失败语义同 selftest（§3.5 不静默降级）：属性已置位而类不可达 = 硬错误中止启动。</p>
     */
    private static void armRealityProbe() {
        Class<?> probe;
        try {
            probe = Class.forName("org.abstruck.brush.selftest.RealityProbe");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[brush] reality probe requested (brush.reality=true) but "
                    + "org.abstruck.brush.selftest.RealityProbe is not on the host classpath —— the probe "
                    + "payload ships separately from the loader artifact (T3 fixture jar); "
                    + "run via runNeoForgeRealityProbe rather than a production instance", e);
        }
        try {
            probe.getMethod("arm").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[brush] reality probe arming failed: " + e, e);
        }
    }

    /**
     * 宿主已加载 mod 清单（含宿主自身与第三方 mod；排序保证可复现）。
     *
     * @return 逗号连接的 {@code id@version} 列表；{@code ModList} 尚未就绪（null 或空）时返回 null
     */
    private static String hostModsOrNull() {
        ModList modList = ModList.get();
        if (modList == null || modList.getMods().isEmpty()) {
            return null;
        }
        List<String> entries = new ArrayList<>();
        for (IModInfo mod : modList.getMods()) {
            entries.add(mod.getModId() + "@" + mod.getVersion());
        }
        entries.sort(Comparator.naturalOrder());
        return String.join(",", entries);
    }
}
