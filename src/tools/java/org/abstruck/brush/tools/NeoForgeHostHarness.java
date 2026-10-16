package org.abstruck.brush.tools;

import org.abstruck.brush.launch.BuildProperties;
import org.abstruck.brush.launch.LaunchParams;
import org.abstruck.brush.launch.deps.DependencyAssembler;
import org.abstruck.brush.launch.meta.RuleEvaluator;
import org.abstruck.brush.launch.meta.VersionInfo;
import org.abstruck.brush.launch.util.Downloader;
import org.abstruck.brush.launch.util.LauncherLogging;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * S3/T3 测试实例启动器：装配 NeoForge 宿主的 Minecraft 客户端并以<b>子 JVM</b>启动
 * {@code net.neoforged.fml.startup.Client}（version json 的 mainClass，docs/13 §2）。
 *
 * <p><b>为什么是子 JVM</b>：与 S2 同因（§11.7 不抢 MC 类定义权；spawn 让 JVM 参数完整可控，§15.3）——
 * 且 FML 自身就是官方启动器的 spawn 形态，参数集照抄 launcher profile。
 *
 * <p><b>与 S2 装置的三处结构差异</b>（其余逐段镜像 {@link FabricHostHarness}）：
 * <ol>
 *   <li><b>宿主安装</b>：NeoForge 生产安装 = 官方安装器跑处理器（{@code PROCESS_MINECRAFT_JAR
 *       --apply-patches}），不是"下载 jar 拼 classpath"。本器以子进程<b>幂等</b>调用官方安装器
 *       （sha1 核验的制品），产出 patched client jar + universal jar + 库（maven 布局，docs/13 §2）；</li>
 *   <li><b>classpath 不含任何 MC 本体 jar</b>：FML 的生产路径 = GameLocator 在 classpath 上
 *       <b>找不到</b> MC 类时，改由
 *       {@code -DlibraryDirectory} 定位 patched client jar 与 universal jar —— 照抄官方形态；</li>
 *   <li><b>参数三源合并</b>：vanilla json 的 arguments.{jvm,game}（父）+ neo json 的（子，
 *       含 {@code --fml.*} 与 {@code ${library_directory}}）按 rules 过滤 + 占位符替换后拼接。</li>
 * </ol>
 *
 * <p><b>实例布局</b>（{@code --instance=<dir>}，T3 用 {@code run/neoforge/}，.gitignore 已排除）：
 * 同 S2（mods/ 宿主 mod 每次清空重放；brush-mods/ 我们的 mod）。安装缓存独立于实例：
 * {@code cache/neoforge/}（安装器 + 安装目录，均不进仓库）。
 *
 * <p>本类只属于测试装置（tools 源集，不进产物 jar）。
 */
public final class NeoForgeHostHarness {

    private static final Logger LOGGER = LogManager.getLogger(NeoForgeHostHarness.class);

    private static final String USER_AGENT = "Brush-Loader (P7 neoforge harness)";

    private NeoForgeHostHarness() { }

    public static void main(String[] args) throws Exception {
        // 第一条语句：错误路径（参数解析失败）也要能打日志（同 Brush.main 约定）
        LauncherLogging.install();
        String versionId = BuildProperties.mcVersion();
        // P1-10 收尾：默认路径统一由 BuildProperties 提供（单一来源）
        Path cacheRoot = BuildProperties.cacheRoot();
        Path clientJar = BuildProperties.clientJar();
        Path instance = BuildProperties.neoforgeInstanceDir();
        Path brushJar = null;
        Path selftestJar = null;
        boolean selftest = false;
        boolean reality = false;
        boolean installOnly = false;
        // 离线首装逃生舱：本机已有的官方安装器副本（--installer-jar=），sha1 仍须与 profile 一致。
        Path localInstallerJar = null;
        for (String arg : args) {
            if (arg.startsWith("--version=")) {
                versionId = arg.substring("--version=".length());
            } else if (arg.startsWith("--cache=")) {
                cacheRoot = Path.of(arg.substring("--cache=".length()));
            } else if (arg.startsWith("--client-jar=")) {
                clientJar = Path.of(arg.substring("--client-jar=".length()));
            } else if (arg.startsWith("--instance=")) {
                instance = Path.of(arg.substring("--instance=".length()));
            } else if (arg.startsWith("--brush-jar=")) {
                brushJar = Path.of(arg.substring("--brush-jar=".length()));
            } else if (arg.startsWith("--selftest-jar=")) {
                // C.1：selftest 夹具 jar（Selftest/RealityProbe 类载体）—— 仅自检任务传入
                selftestJar = Path.of(arg.substring("--selftest-jar=".length()));
            } else if (arg.equals("--selftest")) {
                selftest = true;
            } else if (arg.equals("--reality")) {
                // 实机探针（同 selftest：夹具 jar 提供 Selftest/RealityProbe 类，本开关经
                // -Dbrush.reality=true 抵达宿主内的 BrushNeoForge，由其反射武装）
                reality = true;
            } else if (arg.equals("--install-only")) {
                // 2026-10-15：只装宿主产物、不启动游戏（服务"新设备自举"；见 run() 步骤 4b）
                installOnly = true;
            } else if (arg.startsWith("--installer-jar=")) {
                // 2026-10-15：用本机安装器副本替代下载（maven.neoforged.net 不通时的离线首装路径）
                localInstallerJar = Path.of(arg.substring("--installer-jar=".length()));
            } else {
                LOGGER.error("[brush] unknown argument: " + arg);
                System.exit(2);
            }
        }
        if (brushJar == null && !installOnly) {
            LOGGER.error("[brush] --brush-jar=<path to build/libs/brush-<version>.jar> is required"
                    + " (not needed with --install-only)");
            System.exit(2);
        }
        if (selftest && selftestJar == null) {
            // 自检而夹具缺席 = 武装必失败（BrushNeoForge fail-fast）——装配侧提前给出可读归因
            LOGGER.error("[brush] --selftest requires --selftest-jar=<brush-selftest.jar> "
                    + "(the selftest payload ships separately from the loader artifact, C.1)");
            System.exit(2);
        }
        if (reality && selftestJar == null) {
            // 同 selftest：RealityProbe 也在夹具 jar 里；缺席则武装必失败，提前给可读归因
            LOGGER.error("[brush] --reality requires --selftest-jar=<brush-selftest.jar> "
                    + "(the RealityProbe payload ships separately from the loader artifact, C.1)");
            System.exit(2);
        }
        int code;
        try {
            code = run(versionId, cacheRoot, clientJar, instance, brushJar, selftestJar, selftest, reality,
                    installOnly, localInstallerJar);
        } catch (Exception e) {
            LOGGER.error("[brush] FATAL: " + e.getMessage(), e);
            code = 1;
        }
        System.exit(code);
    }

    private static int run(String versionId, Path cacheRoot, Path clientJar, Path instance, Path brushJar,
            Path selftestJar, boolean selftest, boolean reality, boolean installOnly, Path localInstallerJar)
            throws Exception {
        LOGGER.info("[brush] === P7 NeoForge host harness (S3/T3) ===");
        LOGGER.info("[brush] version=" + versionId + ", cache=" + cacheRoot.toAbsolutePath().normalize()
                + ", instance=" + instance.toAbsolutePath().normalize()
                + ", selftest=" + selftest
                + ", reality=" + reality
                + (selftestJar == null ? "" : ", selftestJar=" + selftestJar.getFileName()));

        // 1) version json（复用 P1.2 元数据缓存；经 gradle metadata 任务生成）
        Path versionFile = cacheRoot.resolve("meta").resolve("versions").resolve(versionId + ".json");
        if (!Files.isRegularFile(versionFile)) {
            throw new IOException("version json not found: " + versionFile.toAbsolutePath()
                    + " (run `gradlew.bat metadata` first)");
        }
        VersionInfo info = VersionInfo.load(versionFile);
        int requiredJava = info.javaVersion == null ? 25 : info.javaVersion.majorVersion;
        if (Runtime.version().feature() < requiredJava) {
            throw new IOException("Java " + requiredJava + "+ required by version json, running on "
                    + Runtime.version().feature());
        }

        // 2) MC 本体：本地 vanilla client jar（只读），与 S1/S2 同一条来源、同一校验
        Path clientJarAbs = clientJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(clientJarAbs)) {
            throw new IOException("client jar not found: " + clientJarAbs);
        }
        LaunchParams.verifyClientJar(info, clientJarAbs);

        // 3) MC 库装配（复用 P1.3；命中缓存）
        // verbose=true：下载开始前打印 URL 与心跳（2026-10-15）—— 网络挂住时日志不再一片空白。
        Downloader downloader = new Downloader(USER_AGENT, true);
        DependencyAssembler.Result deps = DependencyAssembler.assemble(info, cacheRoot, downloader, false);
        LOGGER.info("[brush] MC libraries: " + deps.classpath.size() + " jars, natives dir: " + deps.nativesDirectory);

        // 4) 官方安装器（sha1 核验）→ 幂等安装（patched client jar + universal jar + 库，maven 布局）
        //    2026-10-15（用户报 NeoForge 安装器下载超时）：**先判完整性，已装好就完全不碰网络**。
        //    旧实现无条件先下载安装器，于是"已装好的机器"也会在 maven.neoforged.net 不通时白等 3×2 分钟
        //    并以 exit 1 收场 —— 而它其实一步都不需要下载。
        Path neoCache = cacheRoot.resolve("neoforge");
        Path installDir = neoCache.resolve("install");
        if (installationComplete(installDir)) {
            LOGGER.info("[brush] NeoForge installation already present -- installer download skipped (offline OK): "
                    + installDir.toAbsolutePath().normalize());
        } else {
            NeoForgeHostProfile.Installer installer = NeoForgeHostProfile.INSTALLER;
            Path installerJar = neoCache.resolve(installer.fileName());
            if (localInstallerJar != null) {
                // 离线首装的逃生舱：用本机已有的安装器副本（sha1 仍须与该 profile 一致）
                String actual = Downloader.sha1(localInstallerJar);
                if (!actual.equalsIgnoreCase(installer.sha1())) {
                    throw new IOException("local NeoForge installer sha1 mismatch: " + localInstallerJar
                            + " (expected " + installer.sha1() + ", actual " + actual + ")");
                }
                Files.createDirectories(neoCache);
                Files.copy(localInstallerJar, installerJar, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[brush] using local NeoForge installer (sha1 verified, no download): " + localInstallerJar);
            } else {
                try {
                    downloader.download(URI.create(installer.url()), installerJar, installer.sha1(),
                            installer.size(), false);
                } catch (IOException e) {
                    // 2026-10-15（用户报"NeoForge Timeout"）：装不上时给**可操作出路**，不要只留一句下载失败。
                    throw new IOException("cannot obtain the NeoForge installer (" + installer.url() + "): "
                            + e.getMessage()
                            + " -- remedies: (a) pass -Pbrush.cache=<dir> pointing at a cache that already"
                            + " contains neoforge/install; (b) copy a prepared neoforge/install directory into "
                            + installDir.toAbsolutePath().normalize()
                            + "; (c) pass --installer-jar=<local neoforge-"
                            + NeoForgeHostProfile.NEOFORGE_VERSION + "-installer.jar>"
                            + " (Gradle: -Pbrush.neoforgeInstaller=<path>).", e);
                }
            }
            ensureInstallation(versionId, installerJar, installDir, clientJarAbs, versionFile);
        }

        // 4b) 仅安装模式（2026-10-15，服务"新设备自举"）：宿主产物装好即停，**不启动游戏**。
        //     用途：全新设备上先备好 NeoForge 安装产物 —— 编译 Brush API（其 S3 载荷桥要 universal jar）
        //     与跑 T2/T3 都需要它。缺产物时**可读失败**，不静默。
        if (installOnly) {
            Path universal = installDir.resolve("libraries/net/neoforged/neoforge/"
                    + NeoForgeHostProfile.NEOFORGE_VERSION + "/neoforge-"
                    + NeoForgeHostProfile.NEOFORGE_VERSION + "-universal.jar");
            LOGGER.info("[brush] install-only: NeoForge " + NeoForgeHostProfile.NEOFORGE_VERSION
                    + " installed at " + installDir.toAbsolutePath().normalize());
            LOGGER.info("[brush] install-only: universal jar present=" + Files.isRegularFile(universal)
                    + " -> " + universal.toAbsolutePath().normalize());
            if (!Files.isRegularFile(universal)) {
                throw new IOException("NeoForge installation incomplete: universal jar missing at " + universal);
            }
            return 0;
        }

        // 5) 安装后的 version json（NeoForge profile；ID 与 universal toml 版本一致）
        Path neoJson = installDir.resolve("versions")
                .resolve("neoforge-" + NeoForgeHostProfile.NEOFORGE_VERSION)
                .resolve("neoforge-" + NeoForgeHostProfile.NEOFORGE_VERSION + ".json");
        if (!Files.isRegularFile(neoJson)) {
            throw new IOException("installed NeoForge version json not found: " + neoJson);
        }
        VersionInfo neoInfo = VersionInfo.load(neoJson);
        if (neoInfo.mainClass == null) {
            throw new IOException("NeoForge version json has no mainClass: " + neoJson);
        }
        LOGGER.info("[brush] NeoForge " + NeoForgeHostProfile.NEOFORGE_VERSION
                + " (mc " + versionId + "), mainClass: " + neoInfo.mainClass);

        // 6) NeoForge 库（26 条，按安装目录 maven 布局解析；缺失才下载，sha1 校验）
        List<Path> neoLibs = resolveNeoLibraries(neoInfo, installDir, downloader);
        LOGGER.info("[brush] NeoForge libraries: " + neoLibs.size() + " jars (resolved from "
                + installDir.resolve("libraries").toAbsolutePath().normalize() + ")");

        // 7) 宿主 mod 下载（T3 真实 Neo mod；sha1 全校验，见 NeoForgeHostProfile）
        Path modCache = neoCache.resolve("mods");
        List<Path> hostModJars = new ArrayList<>();
        for (NeoForgeHostProfile.Mod mod : NeoForgeHostProfile.MODS) {
            Path target = modCache.resolve(mod.fileName());
            downloader.download(URI.create(mod.url()), target, mod.sha1(), mod.size(), false);
            hostModJars.add(target);
        }

        // 8) 实例装配：mods/ 先清空（确定性 —— 恰为我们声明的集合，避免旧版本残留导致双份 mod id）
        Path instanceAbs = instance.toAbsolutePath().normalize();
        Path modsDir = instanceAbs.resolve("mods");
        Path brushModsDir = instanceAbs.resolve("brush-mods");
        Files.createDirectories(modsDir);
        Files.createDirectories(brushModsDir);
        resetModsDir(modsDir);
        // brush-mods 为空是合法场景：交互式纯客户端启动（同 S1 的 runClient）不装载我们的 mod；
        // 仅 selftest 需要至少一个我们的 mod 作为断言对象，缺失时 fail-fast。
        if (selftest && !containsJar(brushModsDir)) {
            throw new IOException("no mod jars in " + brushModsDir
                    + " (selftest needs a Brush-side mod; run `gradlew.bat exampleModNeoForge` first)");
        }
        copyInto(brushJar, modsDir);
        if (selftestJar != null) {
            copyInto(selftestJar, modsDir);   // C.1：自检夹具（brush_selftest）与主 jar 同层装载
        }
        for (Path modJar : hostModJars) {
            copyInto(modJar, modsDir);
        }
        LOGGER.info("[brush] instance mods (" + listJars(modsDir).size() + "): " + listJars(modsDir));
        LOGGER.info("[brush] instance brush-mods: " + listJars(brushModsDir));

        // 9) log4j 配置（复用 S1 同款下载 + 控制台修补；路径经子 JVM 参数传入）
        Path log4jConfig = LaunchParams.downloadLoggingConfig(info, cacheRoot, downloader);

        // 10) classpath = MC 库 + NeoForge 库 —— 【不含任何 MC 本体 jar】：
        //     FML 生产路径 = classpath 无 MC 类时经 -DlibraryDirectory 定位 patched client jar
        //     与 universal jar（docs/13 §2）。
        List<Path> classpath = new ArrayList<>(deps.classpath);
        classpath.addAll(neoLibs);
        String classpathString = join(classpath);
        Path librariesDir = installDir.resolve("libraries").toAbsolutePath().normalize();

        // 11) JVM 参数：vanilla arguments.jvm（父）+ neo arguments.jvm（子，含 ${library_directory}），
        //     均按 rules 过滤 + 占位符替换；末尾追加我们自己的旗标。
        Map<String, String> values = new LinkedHashMap<>();
        values.put("natives_directory", deps.nativesDirectory.toString());
        values.put("launcher_name", LaunchParams.LAUNCHER_NAME);
        values.put("launcher_version", LaunchParams.LAUNCHER_VERSION);
        values.put("classpath", classpathString);
        values.put("library_directory", librariesDir.toString());
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.addAll(flattenArguments(info.arguments == null ? null : info.arguments.jvm,
                values, "vanilla arguments.jvm"));
        jvmArgs.addAll(flattenArguments(neoInfo.arguments == null ? null : neoInfo.arguments.jvm,
                values, "neoforge arguments.jvm"));
        jvmArgs.add("-Xmx2G");
        if (log4jConfig != null) {
            jvmArgs.add("-Dlog4j.configurationFile=" + log4jConfig);
        }
        // Brush 最早锚点（Bootstrap.bootStrap HEAD）在宿主自己的 bootstrap 引导中即引导
        // brush-mods —— 早于宿主入口点，故上下文（游戏目录 / 游戏版本）必须经启动参数提前可见
        // （属性名以 org.abstruck.brush.BrushProperties 为唯一来源，这里是 tools 侧，
        // 只写字面值不引用游戏侧类）。
        jvmArgs.add("-Dbrush.game.dir=" + instanceAbs);
        jvmArgs.add("-Dbrush.game.version=" + versionId);
        if (selftest) {
            // 自检开关经系统属性抵达宿主内的 BrushNeoForge（BrushProperties.SELFTEST 的字面值；
            // 这里是 tools 侧，不依赖游戏侧类；值以 org.abstruck.brush.BrushProperties 为唯一来源）
            jvmArgs.add("-Dbrush.selftest=true");
        }
        if (reality) {
            // 实机探针开关（同上机理；BrushNeoForge 读该属性后反射武装 RealityProbe）
            jvmArgs.add("-Dbrush.reality=true");
        }

        // 12) game args：vanilla json（复用 S1 同款装配；game_directory = instance）+ neo json 的
        //     arguments.game（--fml.neoForgeVersion / --fml.mcVersion / --fml.neoFormVersion 三者）
        List<String> gameArgs = new ArrayList<>(LaunchParams.buildGameArguments(info, cacheRoot, instanceAbs));
        gameArgs.addAll(flattenArguments(neoInfo.arguments == null ? null : neoInfo.arguments.game,
                values, "neoforge arguments.game"));

        // 13) 组装并打印子 JVM 命令行
        Path javaExe = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.addAll(jvmArgs);
        command.add(neoInfo.mainClass);
        command.addAll(gameArgs);

        LOGGER.info("[brush] --- child JVM command ---");
        LOGGER.info("[brush]   java: " + javaExe);
        for (String arg : jvmArgs) {
            if (arg.equals(classpathString)) {
                LOGGER.info("[brush]   jvm:  <${classpath} -> " + classpath.size() + " entries>");
            } else {
                LOGGER.info("[brush]   jvm:  " + arg);
            }
        }
        LOGGER.info("[brush]   classpath (" + classpath.size() + ", no Minecraft game jar - production layout):");
        for (Path entry : classpath) {
            LOGGER.info("[brush]     " + entry);
        }
        LOGGER.info("[brush]   main: " + neoInfo.mainClass + " (NeoForge "
                + NeoForgeHostProfile.NEOFORGE_VERSION + ", libraryDirectory=" + librariesDir + ")");
        for (String arg : gameArgs) {
            LOGGER.info("[brush]   game: " + arg);
        }

        // 14) spawn（cwd = instance；IO 继承 —— 子 JVM 日志直达本控制台，文件日志落 instance/logs/latest.log）
        LOGGER.info("[brush] spawning child JVM -- the game window will open");
        Process process = new ProcessBuilder(command).directory(instanceAbs.toFile()).inheritIO().start();
        int exit = process.waitFor();
        LOGGER.info("[brush] child JVM exited with code " + exit
                + (selftest ? " (selftest verdict; report above in latest.log)" : ""));
        return exit;
    }

    // ------------------------------------------------------------------
    // NeoForge 安装（官方安装器，幂等）
    // ------------------------------------------------------------------

    /**
     * 安装是否已完整（与 {@link #ensureInstallation} 的判据一致）：安装后的 version json +
     * patched client jar + universal jar 三者齐备。2026-10-15 提取为独立方法，供"已装则跳过
     * 安装器下载"的快路径使用（旧实现无条件下载安装器 ⇒ 离线/网络不通时白等超时）。
     */
    private static boolean installationComplete(Path installDir) {
        String neoVersion = NeoForgeHostProfile.NEOFORGE_VERSION;
        Path installedJson = installDir.resolve("versions").resolve("neoforge-" + neoVersion)
                .resolve("neoforge-" + neoVersion + ".json");
        Path patchedJar = installDir.resolve("libraries/net/neoforged/minecraft-client-patched")
                .resolve(neoVersion).resolve("minecraft-client-patched-" + neoVersion + ".jar");
        Path universalJar = installDir.resolve("libraries/net/neoforged/neoforge")
                .resolve(neoVersion).resolve("neoforge-" + neoVersion + "-universal.jar");
        return Files.isRegularFile(installedJson) && Files.isRegularFile(patchedJar)
                && Files.isRegularFile(universalJar);
    }

    /**
     * 确保 NeoForge 已安装（幂等）：缺则调用官方安装器子进程。
     *
     * <p>安装器前置：目标目录需存在 {@code launcher_profiles.json}（最小合法内容即可）
     * 与 {@code versions/<mc>/{<mc>.jar,<mc>.json}}（PROCESS_MINECRAFT_JAR 的输入）。两处均为
     * <b>cache 内的副本</b> —— 本地 vanilla 资产只读（§1.3）。
     */
    private static void ensureInstallation(String versionId, Path installerJar, Path installDir,
            Path clientJar, Path vanillaJson) throws IOException, InterruptedException {
        String neoVersion = NeoForgeHostProfile.NEOFORGE_VERSION;
        if (installationComplete(installDir)) {
            LOGGER.info("[brush] NeoForge installation already present: " + installDir.toAbsolutePath().normalize());
            return;
        }

        Files.createDirectories(installDir);
        Path profiles = installDir.resolve("launcher_profiles.json");
        if (!Files.isRegularFile(profiles)) {
            Files.writeString(profiles, "{\"profiles\":{},\"settings\":{\"enableSnapshots\":false},\"version\":3}");
            LOGGER.info("[brush]   seeded launcher_profiles.json (installer precondition, empirical)");
        }
        Path versionDir = installDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);
        Path seededJar = versionDir.resolve(versionId + ".jar");
        if (!Files.isRegularFile(seededJar)) {
            Files.copy(clientJar, seededJar);
            LOGGER.info("[brush]   seeded " + versionId + ".jar (copy of local vanilla client, read-only source)");
        }
        Path seededJson = versionDir.resolve(versionId + ".json");
        if (!Files.isRegularFile(seededJson)) {
            Files.copy(vanillaJson, seededJson);
            LOGGER.info("[brush]   seeded " + versionId + ".json");
        }

        Path javaExe = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = List.of(javaExe.toString(), "-Djava.awt.headless=true",
                "-jar", installerJar.toAbsolutePath().normalize().toString(),
                "--installClient", installDir.toAbsolutePath().normalize().toString());
        LOGGER.info("[brush] running official NeoForge installer (sha1-verified artifact): "
                + String.join(" ", command));
        Process process = new ProcessBuilder(command).directory(installDir.toFile()).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("NeoForge installer exited with code " + exit);
        }
        if (!installationComplete(installDir)) {
            throw new IOException("NeoForge installation incomplete after installer run: "
                    + installDir.toAbsolutePath().normalize());
        }
        LOGGER.info("[brush] NeoForge installed: " + installDir.toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------
    // 参数与库辅助
    // ------------------------------------------------------------------

    /**
     * NeoForge version json 的 libraries → 安装目录内的 maven 布局路径；缺失才下载（sha1 校验）。
     * 只接受带 {@code downloads.artifact} 的条目 —— 形态不符即报错，不猜（§3 规则 1）。
     */
    private static List<Path> resolveNeoLibraries(VersionInfo neoInfo, Path installDir, Downloader downloader)
            throws IOException {
        Path librariesDir = installDir.resolve("libraries");
        List<Path> result = new ArrayList<>();
        if (neoInfo.libraries == null) {
            return result;
        }
        for (VersionInfo.Library library : neoInfo.libraries) {
            if (!RuleEvaluator.applies(library.rules)) {
                continue;
            }
            VersionInfo.Artifact artifact = library.downloads == null ? null : library.downloads.artifact;
            if (artifact == null || artifact.path == null) {
                throw new IOException("NeoForge library entry without downloads.artifact: " + library.name);
            }
            Path target = librariesDir.resolve(artifact.path);
            if (!Files.isRegularFile(target)) {
                if (artifact.url == null) {
                    throw new IOException("NeoForge library missing locally and has no url: " + artifact.path);
                }
                LOGGER.info("[brush]   NeoForge library missing, downloading: " + artifact.path);
                downloader.download(URI.create(artifact.url), target, artifact.sha1, artifact.size, false);
            }
            result.add(target);
        }
        return result;
    }

    /** arguments.{jvm,game}：rules 过滤 + 占位符替换 → 扁平参数列表。 */
    private static List<String> flattenArguments(List<VersionInfo.Argument> arguments,
            Map<String, String> values, String where) throws IOException {
        List<String> result = new ArrayList<>();
        if (arguments == null) {
            return result;
        }
        for (VersionInfo.Argument argument : arguments) {
            if (!RuleEvaluator.applies(argument.rules)) {
                continue;
            }
            for (String value : argument.values) {
                result.add(LaunchParams.substitute(value, values, where));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 实例目录辅助
    // ------------------------------------------------------------------

    /** 清空 mods/ 下的 jar（确定性重放；只作用于我们自己的 run/neoforge 实例目录）。 */
    private static void resetModsDir(Path modsDir) throws IOException {
        for (Path entry : listJars(modsDir)) {
            Files.delete(entry);
            LOGGER.info("[brush]   removed stale mod jar: " + entry.getFileName());
        }
    }

    private static List<Path> listJars(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    private static boolean containsJar(Path dir) throws IOException {
        return !listJars(dir).isEmpty();
    }

    private static void copyInto(Path source, Path targetDir) throws IOException {
        Path target = targetDir.resolve(source.getFileName());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[brush]   installed host mod: " + source.getFileName()
                + " (" + Files.size(source) + " bytes)");
    }

    private static String join(List<Path> paths) {
        StringBuilder builder = new StringBuilder();
        for (Path path : paths) {
            if (builder.length() > 0) {
                builder.append(File.pathSeparatorChar);
            }
            builder.append(path.toAbsolutePath().normalize());
        }
        return builder.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
