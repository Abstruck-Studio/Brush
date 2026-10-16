package org.abstruck.brush.transform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.abstruck.brush.BrushModule;
import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.mod.ModBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * 最早锚点统一引导（"起点化"）—— Brush 的引导起点，先于宿主入口点与游戏内容注册。
 *
 * <p><b>定位</b>：把"核心监听器安装（{@link CoreHooks#install()}）+ UI 模块安装
 * （{@link BrushModule} SPI，经 {@link #installModules()}）+ 我们的 mod 加载
 * （{@link ModBootstrap#install}）"收拢为 <b>唯一引导入口</b>，按"最早者胜出"被驱动：
 * <ul>
 *   <li><b>vanilla 最早锚点</b>：{@code net.minecraft.server.Bootstrap#bootStrap()V} 的 HEAD
 *       （注入点 {@code BootstrapMixin} → L1 事件 {@link HookEvents#BOOTSTRAP_EARLY}）——
 *       S2/S3 下 Brush 在宿主的 bootstrap 路径上先行引导，先于宿主的 mod 生命周期入口点，
 *       不再等待它；</li>
 *   <li><b>显式调用</b>：S1 启动管线与 S2/S3 宿主入口
 *       （{@code host.fabric.BrushFabricClient} / {@code host.neoforge.BrushNeoForge}）——
 *       锚点已先行时退化为幂等兜底。</li>
 * </ul></p>
 *
 * <p><b>顺序保证（三运行时无条件成立）</b>：锚点 HEAD 先于同一方法内的
 * {@code BuiltInRegistries.bootStrap()}（内容注册窗口）—— 因此"内容注册窗口派发前 Brush 已
 * 引导"是结构性保证，不依赖任何运行时调度巧合；窗口 handler 内另设兜底 ensure（零新增织入
 * 指令），见 {@code BuiltInRegistriesMixin}。</p>
 *
 * <p><b>幂等语义</b>：首个调用者胜出（{@link #boot} 的 LOCK + {@link #summary} 缓存）——
 * 重复调用返回既有摘要并记一行 "already complete (no-op)"；<b>失败不缓存</b>：异常原样/
 * 包装上抛，进程随即中止，不存在"之后重试"的路径（fail-fast，不静默降级）。</p>
 *
 * <p><b>上下文解析链</b>（锚点处没有宿主类型可用 —— 宿主 API 只在 {@code host.*} 包内使用，
 * §9.3；逐级回退，全部失败抛可读 {@link IOException}，禁止静默编造 "unknown"）：
 * <ol>
 *   <li>调用方显式传参（S1 管线 / S2·S3 宿主入口）；</li>
 *   <li>系统属性 {@link BrushProperties#GAME_DIR}（游戏目录）与
 *       {@link BrushProperties#GAME_VERSION}（游戏版本）—— 启动装置在子 JVM 参数中设置
 *       （锚点早于宿主入口点，必须能独立取到上下文）；</li>
 *   <li>回退：{@code user.dir}（= 游戏目录）+ classpath 资源 {@code /version.json}
 *       （vanilla jar 与 patched jar 根目录均含的游戏自述版本文件）。</li>
 * </ol></p>
 *
 * <p><b>事件派发</b>：{@link #onBootstrap()} 在<b>首次</b>锚点调用时、引导完成之后派发一次
 * {@link HookEvents#BOOTSTRAP_EARLY}。once 标志在本类内（不依赖 vanilla 的 bootstrapped
 * 守卫 —— 我们的 handler 在守卫<b>之前</b>执行，重复调用 {@code bootStrap()} 不得重复派发）。</p>
 *
 * <p><b>footprint</b>：锚点 mixin 织入 1 条 {@code EarlyBoot.onBootstrap()} 静态调用
 * （内部：once 守卫 → 引导 → 首次 fire；§10.1"每方法 1 注入点 / 1 条指令"形态），
 * 清单见 {@link InjectionLedger} 与 docs/footprint.md。</p>
 *
 * <p><b>分层</b>：本类不引用任何 MC / 宿主类型（与 {@code api} 层同一纪律）；依赖仅
 * JDK + log4j + gson（三者游戏 classpath 均自带）。</p>
 */
public final class EarlyBoot {

    /** 游戏侧 log4j（与其余 transform 类同一 logger 体系）。 */
    private static final Logger LOGGER = LogManager.getLogger(EarlyBoot.class);

    /** 引导互斥 + 状态可见性（摘要与 once 标志都在其保护下读写）。 */
    private static final Object LOCK = new Object();

    /** 引导摘要；非 null = 已引导（幂等返回它）。 */
    private static volatile String summary;

    /** {@link HookEvents#BOOTSTRAP_EARLY} 是否已派发（首次锚点调用派发一次）。 */
    private static boolean eventFired;

    private EarlyBoot() {
    }

    /**
     * vanilla 最早锚点入口（{@code BootstrapMixin} 的 handler 调用）——
     * 引导（若尚未）+ 首次派发 {@link HookEvents#BOOTSTRAP_EARLY}。
     *
     * <p>失败语义：上下文解析 / mod 加载失败 → {@link IllegalStateException} 上抛
     * （经 vanilla {@code Bootstrap.bootStrap()} 冒泡，游戏启动中止；详细报告已在游戏侧日志打印）。
     */
    public static void onBootstrap() {
        boolean fire;
        synchronized (LOCK) {
            fire = !eventFired;
            eventFired = true;
        }
        try {
            ensureAuto();
        } catch (IOException e) {
            throw new IllegalStateException("[brush] early boot failed at the vanilla bootstrap anchor ("
                    + HookEvents.BOOTSTRAP_EARLY + "): " + e.getMessage(), e);
        }
        if (fire) {
            Dispatcher.fire(HookEvents.BOOTSTRAP_EARLY);
        }
    }

    /**
     * 显式上下文引导（S1 管线 / S2·S3 宿主入口）：幂等，重复调用返回既有摘要。
     *
     * @param modsDir     我们的 mod 目录（{@code brush-mods/}）
     * @param gameVersion 游戏版本（version json 的 id，如 {@code "26.1.2"}）
     * @return mod 加载摘要（与 {@link ModBootstrap#install} 同值，供调用方日志）
     * @throws IOException 引导失败（消息为可读摘要；不缓存失败）
     */
    public static String ensure(Path modsDir, String gameVersion) throws IOException {
        return boot(modsDir, gameVersion);
    }

    /**
     * 无上下文引导（锚点 / 内容注册窗口兜底）：上下文经系统属性与游戏自述资源解析
     * （链见类 javadoc），其余语义同 {@link #ensure}。
     */
    public static String ensureAuto() throws IOException {
        return boot(null, null);
    }

    /** 幂等引导主体：首个调用者胜出；上下文解析（显式参数优先）→ install → 摘要缓存。 */
    private static String boot(Path modsDir, String gameVersion) throws IOException {
        synchronized (LOCK) {
            if (summary != null) {
                LOGGER.info("[brush] early boot: already complete (no-op)");
                return summary;
            }
            Path dir = modsDir != null ? modsDir : resolveModsDir();
            String version = gameVersion != null ? gameVersion : resolveGameVersion();
            LOGGER.info("[brush] early boot: starting (mods=" + dir.toAbsolutePath().normalize()
                    + ", game=" + version + ")");
            CoreHooks.install();
            installModules();
            String result;
            try {
                result = ModBootstrap.install(dir, version);
            } catch (IOException e) {
                LOGGER.error("[brush] early boot: FAILED: " + e.getMessage());
                throw e;
            }
            summary = result;
            LOGGER.info("[brush] early boot: complete (" + result + ")");
            return result;
        }
    }

    /**
     * UI 模块安装（{@link BrushModule} SPI，Phase D；评审 P1-5）：把 transform 层对 ui 包的
     * 编译期硬编码依赖改为 {@link ServiceLoader} 发现 —— 核心只依赖根包的 SPI 接口。
     *
     * <p><b>类加载器纪律（S1 类身份陷阱，勿改）</b>：必须用 {@code EarlyBoot} 自身的类加载器，
     * <b>不得</b>用 TCCL —— S1 下本类由 {@code BrushClassLoader} 加载、TCCL 是 app 加载器：
     * 经 TCCL 装载会得到 app 侧的第二份 {@code BrushModule}/{@code ModsUi} 类，接口类身份不同，
     * for-each 处当场 {@code ClassCastException}。
     *
     * <p><b>headless 降级（评审 §5 阶段 3，docs/24）</b>：provider 数量 = 0 时<b>不再硬失败</b>，
     * 而是进入显式 headless 模式继续启动（置位 {@code uiModulesAbsent}，启动日志明确打印
     * "UI module absent → headless mode"，绝不静默；评审目标："Brush 的 jar 在没有 UI 模块时
     * 仍能启动（顺便得到 headless 能力）"）。provider 在场（count &gt; 0）时行为完全不变
     * （逐模块安装 + 日志；模块自身 {@code install()} 幂等，多入口重复引导安全）。
     *
     * <p><b>自检集成</b>：{@link #uiModulesAbsent()} 暴露 headless 状态，
     * {@code src/selftest/Selftest} 的 {@code ui.mods} 信息行可据此注明
     * "headless (UI module absent)"（当前 Selftest 由另一子代理负责，集成待后续 ——
     * 见 docs/24【待确认】项）。
     */
    private static volatile boolean uiModulesAbsent = false;

    private static void installModules() {
        ClassLoader loader = EarlyBoot.class.getClassLoader();
        if (loader == null) {
            // 理论上不可达（本类必由某一侧加载器加载）；兜底回退系统加载器，不静默跳过
            loader = ClassLoader.getSystemClassLoader();
        }
        int count = 0;
        for (BrushModule module : ServiceLoader.load(BrushModule.class, loader)) {
            module.install();
            count++;
            LOGGER.info("[brush] ui module installed: " + module.name()
                    + " (" + module.getClass().getName() + ")");
        }
        if (count == 0) {
            // headless 降级（评审 §5 阶段 3；docs/24）：UI 模块缺席 → 显式继续启动，不静默。
            // 启动日志明确打印（"禁止静默降级"原则：降级必须显式可见）；
            // Mods 按钮 / 创造标签栏将缺席，但核心（mod 加载 / 注入 / API）不受影响。
            uiModulesAbsent = true;
            LOGGER.warn("[brush] UI module absent → headless mode: no BrushModule providers found"
                    + " via " + loader.getClass().getName()
                    + " (META-INF/services/org.abstruck.brush.BrushModule not on the classpath;"
                    + " Mods button / creative-tab sidebar will be unavailable; core continues);"
                    + " this is explicit, not silent (docs/24)");
        }
    }

    /**
     * UI 模块是否缺席（headless 模式）：true = {@link #installModules()} 发现 0 个提供者，
     * 启动进入 headless（Mods 按钮 / 创造标签栏缺席，核心不受影响）。
     * 供自检报告读取（{@code src/selftest/Selftest} 的 {@code ui.mods} 信息行注明 headless；
     * 当前 Selftest 尚未集成，见 docs/24【待确认】项）。
     */
    public static boolean uiModulesAbsent() {
        return uiModulesAbsent;
    }

    /** 游戏目录解析：{@link BrushProperties#GAME_DIR} → {@code user.dir} → 可读失败。 */
    private static Path resolveModsDir() throws IOException {
        String dir = System.getProperty(BrushProperties.GAME_DIR);
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir).resolve("brush-mods");
        }
        String userDir = System.getProperty("user.dir");
        if (userDir == null || userDir.isBlank()) {
            throw new IOException("cannot determine the game directory: system property "
                    + BrushProperties.GAME_DIR + " is not set and user.dir is unavailable"
                    + " (set -D" + BrushProperties.GAME_DIR + "=<game directory> on the JVM command line)");
        }
        return Path.of(userDir).resolve("brush-mods");
    }

    /**
     * 游戏版本解析：{@link BrushProperties#GAME_VERSION} → classpath 资源 {@code /version.json}
     * 的 {@code id} → 可读失败。
     */
    private static String resolveGameVersion() throws IOException {
        String version = System.getProperty(BrushProperties.GAME_VERSION);
        if (version != null && !version.isBlank()) {
            return version;
        }
        try (InputStream in = EarlyBoot.class.getResourceAsStream("/version.json")) {
            if (in != null) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                if (json.has("id")) {
                    return json.get("id").getAsString();
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[brush] early boot: /version.json unreadable: " + e);
        }
        throw new IOException("cannot determine the game version: system property "
                + BrushProperties.GAME_VERSION + " is not set and /version.json is unavailable"
                + " on the classpath (set -D" + BrushProperties.GAME_VERSION
                + "=<version id> on the JVM command line)");
    }
}
