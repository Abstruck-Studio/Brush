package org.abstruck.brush.host.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.transform.EarlyBoot;
import org.abstruck.brush.transform.InjectionLedger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * S2 Fabric 宿主适配入口（{@code fabric.mod.json} 的 {@code entrypoints.client}）。
 *
 * <p><b>运行位置</b>：本类由 Fabric Loader（Knot）加载 —— 宿主持有自己的类加载器与 mixin 服务，
 * 我们不得抢 MC 类定义权（CLAUDE.md §11.7）。因此本类<b>只做三件事</b>：
 * <ol>
 *   <li><b>模式守卫 + 宿主事实桥接</b>（{@link BrushFabricPreLaunch#bridgeHostFacts()}，
 *       与 preLaunch 入口共用）：{@code brush.standalone} 出现即熔断（§9.5）；
 *       写 {@code brush.host*} 属性（§9.3：宿主代码可调核心，核心不引用宿主类型）；</li>
 *   <li><b>幂等兜底引导</b>：{@link EarlyBoot#ensure} —— 正常路径已由 preLaunch 入口
 *       （{@code BrushFabricPreLaunch}，Knot 引导内、游戏首个类变换之前）完成，
 *       此处退化为 no-op 兜底；mixin 织入已由 Fabric 依 {@code fabric.mod.json} 的 mixins 项完成，
 *       <b>不</b>调用 {@code TransformBootstrap#install()}（那只属于 S1）；</li>
 *   <li><b>自检武装 + 失败即中止</b>：反射调用 {@code org.abstruck.brush.selftest.Selftest#arm()}
 *       （C.1：自检装置已移出 main 源集 —— 生产分发不含它，T2 夹具 jar 提供；须晚于客户端就绪，
 *       故必须留在本入口而非 preLaunch）；任一步失败抛异常让宿主启动失败 —— 不静默、不降级（§3.5）。</li>
 * </ol>
 *
 * <p><b>时机</b>：引导的驱动者是 preLaunch 入口 —— mod 的 mixin 配置<b>必须</b>在任何游戏类
 * 经过 mixin 变换之前注册（选择门机理见 {@link BrushFabricPreLaunch} 类 javadoc），
 * 该入口早于本入口；vanilla 最早锚点（{@code Bootstrap.bootStrap} HEAD）仍兜底引导并派发
 * {@code bootstrap.early}；三处入口皆幂等，先到者胜出。时序正确性由 T2 自检报告的 hook
 * 命中计数（各 ≥1）与 mod mixin 实弹断言行自证，不靠假定。
 */
public final class BrushFabricClient implements ClientModInitializer {

    /** 游戏侧 log4j（MC 上下文，落 run/fabric/logs/latest.log）。 */
    private static final Logger LOGGER = LogManager.getLogger(BrushFabricClient.class);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[brush] --- S2 host adaptation: Brush inside Fabric (P6) ---");

        String mcVersion = BrushFabricPreLaunch.bridgeHostFacts();

        // 统一引导（幂等）：核心 hook 监听器 + brush-mods/ 加载（§11.8 D2 与宿主 mods/ 隔离）。
        // 通常已由 preLaunch 入口先行完成 —— 此处为兜底，不再是驱动者。
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("brush-mods");
        try {
            EarlyBoot.ensure(modsDir, mcVersion);
        } catch (IOException e) {
            // fail-fast，不静默（§3.5）；逐条明细已由引导侧打进日志
            throw new IllegalStateException("[brush] early boot under Fabric host failed: " + e.getMessage(), e);
        }
        LOGGER.info("[brush] " + InjectionLedger.summary());

        boolean selftest = "true".equals(System.getProperty(BrushProperties.SELFTEST));
        if (selftest) {
            armSelftest();
        }
        // 实机探针（2026-10-14 新增）：与 selftest 同一机理 —— 开关经系统属性抵达，类在夹具 jar 里。
        // 用途：把"双向网络恰好一次"这类**需要真进世界**的验证从 S1 扩到 S2（此前只有 S1 有证据）。
        boolean reality = "true".equals(System.getProperty(BrushProperties.REALITY));
        if (reality) {
            armRealityProbe();
        }
        LOGGER.info("[brush] S2 ready: hooks installed, brush-mods loaded, selftest "
                + (selftest ? "armed (report + exit at first client.tick)" : "off")
                + ", reality " + (reality ? "armed (join singleplayer world; exit code = verdict)" : "off"));
    }

    /**
     * 反射武装自检（C.1）：{@code selftest.Selftest} 已移出 main 源集 —— 生产形态（loader jar
     * 装进宿主）不含该类，T2 测试实例经夹具 jar（{@code brush_selftest}，见 build.gradle
     * {@code selftestFixtureJar}）提供，与 brush 主 jar 同层宿主加载器 → 类身份与 Dispatcher 共享。
     *
     * <p>失败语义（§3.5 不静默降级）：自检属性已置位而类不可达 = 硬错误中止启动 ——
     * "想让自检验证却根本没跑" 比崩溃更坏（E1 静默形态）。
     */
    private static void armSelftest() {
        Class<?> selftest;
        try {
            selftest = Class.forName("org.abstruck.brush.selftest.Selftest");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[brush] selftest requested (brush.selftest=true) but "
                    + "org.abstruck.brush.selftest.Selftest is not on the host classpath —— the selftest "
                    + "payload ships separately from the loader artifact (T2 fixture jar); "
                    + "run via runFabricSelftest rather than a production instance", e);
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
     * <p>失败语义同 selftest（§3.5 不静默降级）：属性已置位而类不可达 = 硬错误中止启动 ——
     * "想让实机验证跑却根本没跑"比崩溃更坏。</p>
     */
    private static void armRealityProbe() {
        Class<?> probe;
        try {
            probe = Class.forName("org.abstruck.brush.selftest.RealityProbe");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[brush] reality probe requested (brush.reality=true) but "
                    + "org.abstruck.brush.selftest.RealityProbe is not on the host classpath —— the probe "
                    + "payload ships separately from the loader artifact (T2 fixture jar); "
                    + "run via runFabricRealityProbe rather than a production instance", e);
        }
        try {
            probe.getMethod("arm").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[brush] reality probe arming failed: " + e, e);
        }
    }
}
