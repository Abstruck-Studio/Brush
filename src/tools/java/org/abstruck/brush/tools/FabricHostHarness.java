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
 * S2/T2 测试实例启动器：装配 Fabric 宿主的 Minecraft 客户端并以<b>子 JVM</b>启动
 * {@code net.fabricmc.loader.impl.launch.knot.KnotClient}（宿主入口，docs/11 §1）。
 *
 * <p><b>为什么是子 JVM 而不是复用 S1 的进程内启动</b>：S2 下宿主（Knot）持有自己的类加载器与 mixin
 * 服务，我们不得抢 MC 类定义权（§11.7）；且 spawn 形态让 JVM 参数完整可控（§15.3）—— version json 的
 * {@code arguments.jvm} 全部原样传递（含 {@code -cp} 与纯 JVM 旗标），这是进程内 S1 做不到的。
 *
 * <p><b>复用</b>：MC 库装配（{@link DependencyAssembler}）、参数装配（{@link LaunchParams}）、
 * client jar 校验与 log4j 配置修补全部与 S1 同源（共享实现）。
 *
 * <p><b>实例布局</b>（{@code --instance=<dir>}，T2 用 {@code run/fabric/}，.gitignore 已排除）：
 * <pre>
 *   mods/        宿主 mod：brush-&lt;version&gt;.jar（本产物）+ FabricHostProfile.MODS（每次运行先清空重放）
 *   brush-mods/  我们的 mod（hello-mod.jar，由 gradle 任务 exampleModFabric 产出）
 *   logs/ games/ etc.  游戏自身产物（加入 cwd = instance）
 * </pre>
 *
 * <p>本类只属于测试装置（tools 源集，不进产物 jar）。
 */
public final class FabricHostHarness {

    private static final Logger LOGGER = LogManager.getLogger(FabricHostHarness.class);

    private static final String USER_AGENT = "Brush-Loader (P6 fabric harness)";

    /** 宿主入口类（docs/11 §1）。 */
    private static final String KNOT_MAIN = "net.fabricmc.loader.impl.launch.knot.KnotClient";

    /** profile 显式传入的转发属性：值含首尾空格，逐字节照抄 profile（Knot 借此转交真实入口 Main）。 */
    private static final String FABRIC_MC_EMU = "-DFabricMcEmu= net.minecraft.client.main.Main ";

    private FabricHostHarness() { }

    public static void main(String[] args) throws Exception {
        // 第一条语句：错误路径（参数解析失败）也要能打日志（同 Brush.main 约定）
        LauncherLogging.install();
        String versionId = BuildProperties.mcVersion();
        // P1-10 收尾：默认路径统一由 BuildProperties 提供（单一来源）
        Path cacheRoot = BuildProperties.cacheRoot();
        Path clientJar = BuildProperties.clientJar();
        Path instance = BuildProperties.fabricInstanceDir();
        Path brushJar = null;
        Path selftestJar = null;
        boolean selftest = false;
        boolean reality = false;
        // 2026-10-15（用户报"长时间卡在 MC libraries 之后"）：本地注入栈 jar 清单。
        // 由 Gradle 任务从**已解析的编译/运行类路径**给出（sponge-mixin / mixinextras / asm* / fabric-loader），
        // 这些正是 FabricHostProfile.LIBS 要摆进宿主实例的那几个 —— 本机明明有，就不该再联网下载。
        List<Path> localInjectLibs = List.of();
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
                // C.1：selftest 夹具 jar（Selftest/RealityProbe 类载体）—— 仅自检任务传入；
                // 生产实例不传。装载进宿主 mods/，与 brush 主 jar 同层加载器（类身份共享）。
                selftestJar = Path.of(arg.substring("--selftest-jar=".length()));
            } else if (arg.equals("--selftest")) {
                selftest = true;
            } else if (arg.equals("--reality")) {
                // 实机探针（同 selftest：夹具 jar 提供 Selftest/RealityProbe 类，本开关经
                // -Dbrush.reality=true 抵达宿主内的 BrushFabricClient，由其反射武装）
                reality = true;
            } else if (arg.startsWith("--inject-libs=")) {
                // pathSeparator 分隔的本地 jar 清单（与 S1 的 registerBrushRun 同名同义）
                String value = arg.substring("--inject-libs=".length());
                List<Path> parsed = new ArrayList<>();
                for (String part : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (!part.isBlank()) {
                        parsed.add(Path.of(part));
                    }
                }
                localInjectLibs = List.copyOf(parsed);
            } else {
                LOGGER.error("[brush] unknown argument: " + arg);
                System.exit(2);
            }
        }
        if (brushJar == null) {
            LOGGER.error("[brush] --brush-jar=<path to build/libs/brush-<version>.jar> is required");
            System.exit(2);
        }
        if (selftest && selftestJar == null) {
            // 自检而夹具缺席 = 武装必失败（BrushFabricClient fail-fast）——装配侧提前给出可读归因
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
                    localInjectLibs);
        } catch (Exception e) {
            LOGGER.error("[brush] FATAL: " + e.getMessage(), e);
            code = 1;
        }
        System.exit(code);
    }

    private static int run(String versionId, Path cacheRoot, Path clientJar, Path instance, Path brushJar,
            Path selftestJar, boolean selftest, boolean reality, List<Path> localInjectLibs) throws Exception {
        LOGGER.info("[brush] === P6 Fabric host harness (S2/T2) ===");
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

        // 2) MC 本体：本地 vanilla client jar（只读），与 S1 同一条来源、同一校验
        Path clientJarAbs = clientJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(clientJarAbs)) {
            throw new IOException("client jar not found: " + clientJarAbs);
        }
        LaunchParams.verifyClientJar(info, clientJarAbs);

        // 3) MC 库装配（复用 P1.3；命中缓存）
        // verbose=true：下载开始前打印 URL 与心跳（2026-10-15）—— 否则网络挂住时日志一片空白，像死锁。
        Downloader downloader = new Downloader(USER_AGENT, true);
        DependencyAssembler.Result deps = DependencyAssembler.assemble(info, cacheRoot, downloader, false);
        LOGGER.info("[brush] MC libraries: " + deps.classpath.size() + " jars, natives dir: " + deps.nativesDirectory);

        // 4) 宿主构件（Fabric Loader 栈 7 库 + T2 真实 mod；sha1 全校验，清单见 FabricHostProfile）
        //    2026-10-15：**本地优先** —— 我们自己的注入栈（sponge-mixin / asm* / fabric-loader）本机
        //    Gradle 缓存里就有（Gradle 任务经 --inject-libs= 传进来），先复制再退化为下载。
        //    实测动机：maven.fabricmc.net 不通时，旧实现会在这里静默等满 3×2 分钟。
        Map<String, Path> localByName = new LinkedHashMap<>();
        for (Path candidate : localInjectLibs) {
            if (Files.isRegularFile(candidate)) {
                localByName.put(candidate.getFileName().toString(), candidate.toAbsolutePath().normalize());
            }
        }
        Path libCache = cacheRoot.resolve("fabric").resolve("libs");
        List<Path> fabricLibs = new ArrayList<>();
        for (FabricHostProfile.Lib lib : FabricHostProfile.LIBS) {
            Path target = libCache.resolve(lib.fileName());
            Path local = localByName.get(lib.fileName());
            if (local != null && !local.equals(target)) {
                Files.createDirectories(libCache);
                Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[brush] staged locally (no download): " + lib.fileName() + " <- " + local);
            } else {
                downloader.download(URI.create(lib.url()), target, lib.sha1(), lib.size(), false);
            }
            fabricLibs.add(target);
        }
        Path modCache = cacheRoot.resolve("fabric").resolve("mods");
        List<Path> hostModJars = new ArrayList<>();
        for (FabricHostProfile.Mod mod : FabricHostProfile.MODS) {
            Path target = modCache.resolve(mod.fileName());
            downloader.download(URI.create(mod.url()), target, mod.sha1(), mod.size(), false);
            hostModJars.add(target);
        }

        // 5) 实例装配：mods/ 先清空（确定性 —— 恰为我们声明的集合，避免旧版本残留导致双份 mod id）
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
                    + " (selftest needs a Brush-side mod; run `gradlew.bat exampleModFabric` first)");
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

        // 6) log4j 配置（复用 S1 同款下载 + 控制台修补；路径经子 JVM 参数传入）
        Path log4jConfig = LaunchParams.downloadLoggingConfig(info, cacheRoot, downloader);

        // 7) classpath = MC 库 + MC 本体 + Fabric 库（profile 次序；顺序与官方 launcher 的同构）
        List<Path> classpath = new ArrayList<>(deps.classpath);
        classpath.add(clientJarAbs);
        classpath.addAll(fabricLibs);
        String classpathString = join(classpath);

        // 8) JVM 参数：version json arguments.jvm 原样传递（rules 过滤 + 占位符替换，含 -cp 与纯旗标）
        Map<String, String> values = new LinkedHashMap<>();
        values.put("natives_directory", deps.nativesDirectory.toString());
        values.put("launcher_name", LaunchParams.LAUNCHER_NAME);
        values.put("launcher_version", LaunchParams.LAUNCHER_VERSION);
        values.put("classpath", classpathString);
        List<String> jvmArgs = new ArrayList<>();
        if (info.arguments != null && info.arguments.jvm != null) {
            for (VersionInfo.Argument argument : info.arguments.jvm) {
                if (!RuleEvaluator.applies(argument.rules)) {
                    continue;
                }
                for (String value : argument.values) {
                    jvmArgs.add(LaunchParams.substitute(value, values, "arguments.jvm"));
                }
            }
        }
        jvmArgs.add("-Xmx2G");
        jvmArgs.add(FABRIC_MC_EMU);
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
            // 自检开关经系统属性抵达宿主内的 BrushFabricClient（BrushProperties.SELFTEST 的字面值；
            // 这里是 tools 侧，不依赖游戏侧类；值以 org.abstruck.brush.BrushProperties 为唯一来源）
            jvmArgs.add("-Dbrush.selftest=true");
        }
        if (reality) {
            // 实机探针开关（同上机理；BrushFabricClient 读该属性后反射武装 RealityProbe）
            jvmArgs.add("-Dbrush.reality=true");
        }

        // 9) game args（复用 S1 同款装配；game_directory = instance）
        List<String> gameArgs = LaunchParams.buildGameArguments(info, cacheRoot, instanceAbs);

        // 10) 组装并打印子 JVM 命令行
        Path javaExe = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.addAll(jvmArgs);
        command.add(KNOT_MAIN);
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
        LOGGER.info("[brush]   classpath (" + classpath.size() + "):");
        for (Path entry : classpath) {
            LOGGER.info("[brush]     " + entry);
        }
        LOGGER.info("[brush]   main: " + KNOT_MAIN + " (Fabric Loader " + FabricHostProfile.LOADER_VERSION + ")");
        for (String arg : gameArgs) {
            LOGGER.info("[brush]   game: " + arg);
        }

        // 11) spawn（cwd = instance；IO 继承 —— 子 JVM 日志直达本控制台，文件日志落 instance/logs/latest.log）
        LOGGER.info("[brush] spawning child JVM -- the game window will open");
        Process process = new ProcessBuilder(command).directory(instanceAbs.toFile()).inheritIO().start();
        int exit = process.waitFor();
        LOGGER.info("[brush] child JVM exited with code " + exit
                + (selftest ? " (selftest verdict; report above in latest.log)" : ""));
        return exit;
    }

    // ------------------------------------------------------------------
    // 实例目录辅助
    // ------------------------------------------------------------------

    /** 清空 mods/ 下的 jar（确定性重放；只作用于我们自己的 run/fabric 实例目录）。 */
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
