package org.abstruck.brush.launch;

import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.launch.deps.DependencyAssembler;
import org.abstruck.brush.launch.meta.RuleEvaluator;
import org.abstruck.brush.launch.meta.VersionInfo;
import org.abstruck.brush.launch.util.Downloader;
import org.abstruck.brush.launch.util.LauncherLogging;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * P1.5 入口：以 {@link BrushClassLoader} 在<b>本进程内</b>启动 MC 客户端
 * （本地 vanilla jar —— 26.1 起未混淆，官方名即运行时名）。
 *
 * <p>为什么是进程内（而不是 spawn 子 JVM）：P2 注入层要求"在类首次加载前完成注入"
 * （CLAUDE.md §14.4），这只有由我们的 {@link BrushClassLoader} 亲自加载 MC 类才能做到；
 * 子 JVM 里的 MC 与我们的加载器无关，注入无从谈起。
 *
 * <p>由此产生的 JVM 参数处理差异：
 * <ul>
 *   <li>{@code arguments.jvm} 中不适用规则（osx/x86）的条目按 rules 排除；</li>
 *   <li>{@code -D*} 属性经 {@code System.setProperty} 在类加载前设置（含 ${natives_directory} 等占位符替换）；</li>
 *   <li>{@code -cp ${classpath}} 不设置 —— classpath 由 {@link BrushClassLoader} 直接持有；</li>
 *   <li>纯 JVM 旗标（windows 规则下的 {@code -XX:HeapDumpPath}、以及 26.1.2 新增的
 *       {@code --sun-misc-unsafe-memory-access=allow} / {@code --enable-native-access=ALL-UNNAMED}）
 *       无法在进程内追加，打印为 skipped —— 后两个对游戏侧有意义，已由 build.gradle 的
 *       runClient jvmArgs 在拉起本 JVM 时直接传入（§15.3 进程内局限，逐旗标分别处置）；</li>
 *   <li>log4j 配置：下载 version json {@code logging.client.file} 并按官方给出的
 *       {@code -Dlog4j.configurationFile=${path}} 设置系统属性；控制台 appender 的
 *       {@code LegacyXMLLayout} 替换为 PatternLayout（派生物，原件保持只读）。</li>
 * </ul>
 *
 * <p>离线启动参数（无账号体系）：用户名 {@code Dev}（{@link LaunchParams#PLAYER_NAME}）、固定 UUID、accessToken "0"、
 * userType "msa"（官方 launcher 的现代取值）。与所有开发环境一样，服务端鉴权会失败并停留在离线模式。
 *
 * <p>P8 自检模式（{@code --brush.selftest}，CLAUDE.md §14.1）：在第 5.7 步武装自检
 * （{@code org.abstruck.brush.selftest.Selftest}，注册进游戏侧派发表），首个
 * {@code client.tick} 时打印结构化报告并 {@code System.exit(退出码)}，不等用户操作；
 * 退出码语义见 {@code Selftest} 类 javadoc（该类属自检装置，不随产物分发）。
 * 经 {@code runSelftest} Gradle 任务驱动（§19）。
 *
 * <p>产物消费（均只读）：version json（{@code cache/meta/versions/}，P1.2 校验）=&gt; 参数、入口类、
 * client jar 的 sha1/size；{@code --client-jar} 指向的本地 vanilla client jar =&gt; MC 本体
 * （启动前按 {@code downloads.client} 复核 sha1/size，失败即拒绝启动）；
 * library classpath 经 P1.3 装配；assets 经 P1.5.0 装配（{@code --assetsDir}/{@code --assetIndex}）。
 */
public final class Brush {

    private static final Logger LOGGER = LogManager.getLogger(Brush.class);

    /**
     * 上游 sponge-mixin 的类转储开关名（非 {@code brush.*} 协议属性，不入
     * {@link BrushProperties} —— 该类的审计口径只收 {@code "brush."} 字面量）。
     * 名称出处（0.17.4 源码实测）：{@code MixinEnvironment$Option.DEBUG_EXPORT(DEBUG_ALL, "export")}，
     * 属性名 = 前缀 {@code mixin.debug} + {@code export}；读取为纯
     * {@code System.getProperty}（无宿主门控）。
     */
    private static final String MIXIN_DEBUG_EXPORT_PROPERTY = "mixin.debug.export";

    /** 上游类转储目录名（{@code org.spongepowered.asm.util.Constants.DEBUG_OUTPUT_PATH}，0.17.4 源码实测）。 */
    private static final String MIXIN_EXPORT_DIR = ".mixin.out";

    /** 注入台账落盘文件名（{@code -Dbrush.mixin.dump} 薄别名的第二个产物，见 {@link #armMixinDumpHook}）。 */
    private static final String MIXIN_AUDIT_TRAIL_FILE = "brush-audit-trail.log";

    private Brush() { }

    public static void main(String[] args) {
        // 第一条语句：错误路径（参数解析失败）也要能打日志，见 LauncherLogging javadoc
        LauncherLogging.install();
        // P1-6 薄别名（docs/20 §6.3）：-Dbrush.mixin.dump=true → 打开上游 mixin.debug.export。
        // 时序：必须早于任何 mixin 类被加载 —— sponge-mixin 0.17.4 在首个 MixinEnvironment 构造时
        // 快照全部 Option（MixinEnvironment 构造器逐项 getBooleanValue() → System.getProperty），
        // 而首个环境诞生于 TransformBootstrap.install() 内的 MixinBootstrap.init()（第 5.5 步），
        // 此处（main 首语句段）远早于它，别名对整个启动期生效；无需在 TransformBootstrap 再设。
        if ("true".equals(System.getProperty(BrushProperties.MIXIN_DUMP))) {
            System.setProperty(MIXIN_DEBUG_EXPORT_PROPERTY, "true");
            LOGGER.info("[brush] -D" + BrushProperties.MIXIN_DUMP + "=true -> -D"
                    + MIXIN_DEBUG_EXPORT_PROPERTY + "=true (upstream sponge-mixin class export;"
                    + " audit trail will be dumped to " + MIXIN_EXPORT_DIR + "/"
                    + MIXIN_AUDIT_TRAIL_FILE + " at JVM exit)");
        }
        String versionId = BuildProperties.mcVersion();
        // P1-10 收尾：默认路径统一由 BuildProperties 提供（单一来源），不再各自写死
        Path cacheRoot = BuildProperties.cacheRoot();
        Path gameDir = BuildProperties.gameDir();
        Path clientJar = BuildProperties.clientJar();
        List<Path> injectLibs = new ArrayList<>();
        boolean selftest = false;
        boolean reality = false;
        String modTest = null;   // null = 未开；"true" = 全部；具体 modId = 只该 mod（提案 §3）
        for (String arg : args) {
            if (arg.startsWith("--version=")) {
                versionId = arg.substring("--version=".length());
            } else if (arg.startsWith("--cache=")) {
                cacheRoot = Path.of(arg.substring("--cache=".length()));
            } else if (arg.startsWith("--game-dir=")) {
                gameDir = Path.of(arg.substring("--game-dir=".length()));
            } else if (arg.startsWith("--client-jar=")) {
                clientJar = Path.of(arg.substring("--client-jar=".length()));
            } else if (arg.equals("--brush.selftest")) {
                // P8（CLAUDE.md §14.1）：启动到首个 tick 后打印自检报告并退出，退出码见 Selftest
                selftest = true;
            } else if (arg.equals("--brush.reality")) {
                // 实机探针：真实进入单机世界，验证网络端到端 / HUD 实绘 / 真实输入
                reality = true;
            } else if (arg.equals("--brush.mod-test")) {
                // 提案 §3 方案 A：mod 实机测试 —— 运行全部已声明测试装置（entrypoints.test）的 mod
                modTest = "true";
            } else if (arg.startsWith("--brush.mod-test=")) {
                // 只运行指定 mod（提案 §3：--brush.mod-test=<modId>）
                modTest = arg.substring("--brush.mod-test=".length());
                if (modTest.isBlank()) {
                    LOGGER.error("[brush] --brush.mod-test= requires a non-blank mod id");
                    System.exit(2);
                }
            } else if (arg.startsWith("--inject-libs=")) {
                // P2.1：注入栈（mixin/mixinextras/asm/本工程类与资源）—— 由 build.gradle 传入
                for (String entry : arg.substring("--inject-libs=".length()).split(Pattern.quote(File.pathSeparator))) {
                    if (!entry.isEmpty()) {
                        injectLibs.add(Path.of(entry));
                    }
                }
            } else {
                LOGGER.error("[brush] unknown argument: " + arg);
                System.exit(2);
            }
        }
        int code;
        try {
            code = run(versionId, cacheRoot, gameDir, clientJar, injectLibs, selftest, reality, modTest);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOGGER.error("[brush] FATAL: entry class threw", cause);
            code = 1;
        } catch (Exception e) {
            LOGGER.error("[brush] FATAL: " + e.getMessage(), e);
            code = 1;
        }
        // 游戏主循环已结束（或失败）；显式退出，避免残留线程拖住 JVM
        System.exit(code);
    }

    private static int run(String versionId, Path cacheRoot, Path gameDir, Path clientJar, List<Path> injectLibs,
            boolean selftest, boolean reality, String modTest) throws Exception {
        LOGGER.info("[brush] === Brush launch (P1.5 + P2.2 injection) ===");
        LOGGER.info("[brush] version=" + versionId + ", cache=" + cacheRoot.toAbsolutePath().normalize()
                + ", game-dir=" + gameDir.toAbsolutePath().normalize());

        Path versionFile = cacheRoot.resolve("meta").resolve("versions").resolve(versionId + ".json");
        if (!Files.isRegularFile(versionFile)) {
            throw new IOException("version json not found: " + versionFile.toAbsolutePath()
                    + " (run `gradlew.bat metadata` first)");
        }
        VersionInfo info = VersionInfo.load(versionFile);

        // 版本桥接属性：**无条件**设置（不止自检路径）—— 游戏侧 ModsScreen 副标题与 Selftest 报告
        // 都读 brush.game.version；S1 的启动参数不含 -D 注入。与 selftest / loadMods 同款桥接：
        // 只经系统属性通信；属性名字符串的唯一来源在 BrushProperties 内（编译期常量）。
        System.setProperty(BrushProperties.GAME_VERSION, info.id);

        // CLAUDE.md §2：MC 26.1+ 强制 Java 25，低于即停止（json 无 javaVersion 字段时同样按 25 要求）
        int requiredJava = info.javaVersion == null ? 25 : info.javaVersion.majorVersion;
        if (Runtime.version().feature() < requiredJava) {
            throw new IOException("Java " + requiredJava + "+ required by version json, running on "
                    + Runtime.version().feature());
        }

        // MC 本体：本地 vanilla client jar（只读），启动前按 downloads.client 复核
        Path clientJarAbs = clientJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(clientJarAbs)) {
            throw new IOException("client jar not found: " + clientJarAbs
                    + " (pass --client-jar=<path>; default is " + BuildProperties.mcVersion()
                    + "/" + BuildProperties.mcVersion() + ".jar)");
        }
        LaunchParams.verifyClientJar(info, clientJarAbs);

        Path gameDirAbs = gameDir.toAbsolutePath().normalize();
        Files.createDirectories(gameDirAbs);

        // 1) 库装配（命中缓存；quiet 下载器避免 54 行 cached 噪声）
        Downloader downloader = new Downloader(LaunchParams.USER_AGENT, false);
        DependencyAssembler.Result deps = DependencyAssembler.assemble(info, cacheRoot, downloader, false);
        LOGGER.info("[brush] libraries: " + deps.classpath.size() + " jars, natives dir: " + deps.nativesDirectory);

        // 2) log4j 配置（logging.client.file；属性名取自其 argument 模板）
        LaunchParams.downloadLoggingConfig(info, cacheRoot, downloader);

        // 3) arguments.jvm → 进程内可应用的 -D 属性
        LOGGER.info("[brush] --- JVM arguments (in-process launch) ---");
        Map<String, String> jvmValues = new LinkedHashMap<>();
        jvmValues.put("natives_directory", deps.nativesDirectory.toString());
        jvmValues.put("launcher_name", LaunchParams.LAUNCHER_NAME);
        jvmValues.put("launcher_version", LaunchParams.LAUNCHER_VERSION);
        applyJvmArguments(info, jvmValues);

        // 3.5) arguments.default-user-jvm（26.1.2 新增）：全为 JVM 旗标（-Xms/-Xmx/GC 选项），
        //      进程内无法应用。仍按 rules 评估并逐条打印适用性（含 versionRange 规则），仅作审计。
        if (info.arguments != null && info.arguments.defaultUserJvm != null) {
            reportDefaultUserJvm(info.arguments.defaultUserJvm);
        }

        // 4) arguments.game → 实际传给入口类的参数（rules 过滤 + 占位符替换）
        List<String> gameArgs = LaunchParams.buildGameArguments(info, cacheRoot, gameDirAbs);

        // 5) BrushClassLoader：库在前、MC 本体在后（与官方 launcher 的 -cp 次序一致），
        //    注入栈（P2.1：mixin / mixinextras / asm / 本工程类与资源）追加在末尾
        List<Path> classpath = new ArrayList<>(deps.classpath);
        classpath.add(clientJarAbs);
        for (Path injectLib : injectLibs) {
            if (!Files.exists(injectLib)) {
                throw new IOException("--inject-libs entry not found: " + injectLib.toAbsolutePath());
            }
            classpath.add(injectLib);
        }
        BrushClassLoader loader = new BrushClassLoader(classpath, ClassLoader.getPlatformClassLoader(), info.mainClass);
        Thread.currentThread().setContextClassLoader(loader);
        LOGGER.info("[brush] BrushClassLoader active: parent=" + loader.getParent().getName()
                + ", classpath entries=" + classpath.size() + " (game libs + MC + " + injectLibs.size()
                + " injection-stack entries)");

        // 5.5) P2.1：游戏侧引导 Mixin 栈并注册变换管线。
        //      必须在任何 MC/net.minecraft 类被加载之前完成（CLAUDE.md §14.4：load-time 注入）。
        BiFunction<String, byte[], byte[]> pipeline = installInjection(loader);
        loader.addTransformer((name, bytes) -> (byte[]) pipeline.apply(name, bytes));
        LOGGER.info("[brush] injection pipeline registered as BrushClassLoader transformer #1");
        // 注：注入点/被注入类数由游戏侧在配置注册后打印（InjectionLedger，§16.5），此处不重复
        // 5.5.1) -Dbrush.mixin.dump 薄别名的台账 dump 钩子（仅别名开启时注册；docs/20 §6.3）
        armMixinDumpHook(loader);

        // 5.6) P3 + 最早锚点统一引导（游戏侧 EarlyBoot，经 JDK 类型桥反射调用）。
        //      内容 = 核心监听器安装 + 模组加载（S2/S3 下同一引导由 vanilla 最早锚点
        //      Bootstrap.bootStrap HEAD 先行完成，此处为显式上下文路径；幂等）。
        //      时机：注入管线注册之后、入口类加载之前 —— 任何解析/依赖/实例化失败都在游戏启动前
        //      fail-fast 中止（CLAUDE.md §4.5：可读报告 + 不静默跳过）。摘要由 EarlyBoot 打印。
        loadMods(loader, gameDirAbs, info.id);

        // 5.7) P8：自检武装（仅 --brush.selftest）。注册时间点尽可能晚 —— 注入管线与模组加载都已
        //      就绪，报告读取的状态即最终状态；未开启时零监听器、零开销。
        if (selftest) {
            armSelftest(loader);
        }
        if (reality) {
            armReality(loader);
        }
        // 5.8) 提案 §3 方案 A：mod 实机测试武装（仅 --brush.mod-test[=<modId>]）。
        //      在 CLIENT_READY 之后驱动已声明 entrypoints.test 的 mod 执行 ModClientTest；
        //      与 selftest / reality 三模式互斥（同一进程只开一种退出点 —— 与 §4 "先跑 selftest
        //      再跑 mod-test" 兼容：两次独立进程各开其一，同进程不叠加退出点，避免双 System.exit）。
        if (modTest != null) {
            if (selftest || reality) {
                LOGGER.error("[brush] --brush.mod-test is mutually exclusive with --brush.selftest /"
                        + " --brush.reality (each drives its own exit point; run them in separate"
                        + " processes)");
                System.exit(2);
            }
            armModTest(loader, modTest);
        }

        String entryResource = info.mainClass.replace('.', '/') + ".class";
        if (loader.findResource(entryResource) == null) {
            throw new IOException("entry class not on Brush classpath: " + entryResource);
        }
        LOGGER.info("[brush] entry class present: " + entryResource);

        // 6) 反射启动（入口类全名取自 version json）
        Class<?> entry = Class.forName(info.mainClass, true, loader);
        Method main = entry.getMethod("main", String[].class);
        LOGGER.info("[brush] invoking " + info.mainClass + ".main -- the game window will open");
        main.invoke(null, (Object) gameArgs.toArray(new String[0]));
        LOGGER.info("[brush] entry returned; game exited");
        if (selftest || modTest != null) {
            // 自检 / mod-test 模式下游戏"正常返回"= 未到达退出点（首个 client.tick）就结束了 ——
            // 这是失败而非通过（防假通过：派发链断裂时窗口会一直开着，用户关窗走这条路径；
            // §14.1 / 提案 §3 不降低校验严格度）。mod-test 的探针在 client.tick 即 System.exit，
            // 正常情况下不会走到这里的"正常返回"分支。
            LOGGER.error("[brush] " + (modTest != null ? "mod-test" : "selftest")
                    + " was armed but the game returned before its exit point (first client.tick);"
                    + " reporting failure");
            return 1;
        }
        return 0;
    }

    /**
     * {@code -Dbrush.mixin.dump=true} 薄别名的第二半（P1-6，docs/20 §6.3）：注册 JVM 退出钩子，
     * 把游戏侧注入台账（{@code transform.BrushAuditTrail.INSTANCE.applied()}，mixin 核心
     * {@code onApply} 的真实施加记录）落盘为运行目录 {@code .mixin.out/brush-audit-trail.log}。
     *
     * <p>与上游 {@code mixin.debug.export}（类转储，同一目录）互补：转储看字节码，本文件看
     * "哪些 mixin 实际施加到了哪个目标类"。桥接约定同 {@link #installInjection}：只经 JDK 类型
     * 与反射通信，不共享自定义类型身份；{@code BrushAuditTrail} 本身零改动（app 侧只读其公开面）。
     *
     * <p><b>边界（如实）</b>：钩子在 {@code System.exit} 触发的正常退出路径上执行
     * （游戏主循环结束 / 自检退出点都走它）；进程被强杀 / VM abort 时钩子不执行，该路径无产物 ——
     * 不伪装成"必然落盘"。S2/S3 下本类不是入口，钩子不存在（那些运行时直接用上游开关，
     * 宿主 mixin 子系统的 {@code IMixinAuditTrail} 归宿主）。
     *
     * <p>调用时机：注入管线注册成功之后（第 5.5.1 步）—— 之前失败（bootstrap 抛异常）则启动中止，
     * 台账无从谈起，钩子也不注册。
     */
    private static void armMixinDumpHook(BrushClassLoader loader) {
        if (!"true".equals(System.getProperty(BrushProperties.MIXIN_DUMP))) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> dumpMixinAuditTrail(loader),
                "brush-mixin-dump"));
        LOGGER.info("[brush] mixin dump: audit trail hook armed (writes " + MIXIN_EXPORT_DIR + "/"
                + MIXIN_AUDIT_TRAIL_FILE + " at JVM exit)");
    }

    /**
     * 退出钩子本体：反射读取 {@code BrushAuditTrail} 公开面并写文件。失败必须响亮
     * （包为带 {@code [brush]} 前缀的 {@code RuntimeException} 上抛，JVM 会把它连栈打印到
     * {@code stderr}）—— 不吞、不静默（CLAUDE.md §3.5）。日志走 {@code System.out}：
     * 退出期游戏侧 log4j 可能已关（§3.1 同款理由）。
     */
    private static void dumpMixinAuditTrail(BrushClassLoader loader) {
        try {
            Class<?> trail = Class.forName("org.abstruck.brush.transform.BrushAuditTrail", true, loader);
            Object instance = trail.getField("INSTANCE").get(null);
            List<?> applied = (List<?>) trail.getMethod("applied").invoke(instance);
            int generated = (Integer) trail.getMethod("generatedCount").invoke(instance);
            int postProcessed = (Integer) trail.getMethod("postProcessedCount").invoke(instance);

            Path outDir = Path.of(MIXIN_EXPORT_DIR);
            Files.createDirectories(outDir);
            Path out = outDir.resolve(MIXIN_AUDIT_TRAIL_FILE);
            // 2026-10-15：补上 mod 归属 —— trail 串里的 "from mod <modid>" 在 mixin 侧拿不到
            // （此前恒为 (unknown)，而本方法的注释按"它会给"写），这里按**配置名**查表补写。
            Map<String, String> owners = mixinConfigOwners(loader);
            StringBuilder lines = new StringBuilder()
                    .append("# Brush mixin audit trail (alias -D").append(BrushProperties.MIXIN_DUMP)
                    .append("=true; record format: <target> <- <mixin>; mixin id format:")
                    .append(" <config>:<mixin FQN> from mod <modid>)\n")
                    .append("# applied=").append(applied.size())
                    .append(", generated=").append(generated)
                    .append(", postProcessed=").append(postProcessed)
                    .append(", owners=").append(owners.size()).append('\n');
            for (Object record : applied) {
                lines.append(recordComponent(record, "target")).append(" <- ")
                        .append(withOwner(recordComponent(record, "mixin"), owners)).append('\n');
            }
            Files.writeString(out, lines.toString(), StandardCharsets.UTF_8);
            System.out.println("[brush] mixin dump: audit trail written to "
                    + out.toAbsolutePath().normalize() + " (applied=" + applied.size() + ")");
        } catch (Exception e) {
            throw new RuntimeException("[brush] mixin dump: audit trail dump failed", e);
        }
    }

    /** record 分量反射读取（{@code Applied.target()} / {@code Applied.mixin()}）。 */
    private static String recordComponent(Object record, String component) {
        try {
            return String.valueOf(record.getClass().getMethod(component).invoke(record));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("[brush] mixin dump: cannot read record component '"
                    + component + "' of " + record.getClass().getName(), e);
        }
    }

    /**
     * 配置名 → modid 映射（2026-10-15 新增；修"落盘里 mod 归属恒为 {@code (unknown)}"）。
     *
     * <p>数据源两处：① {@code ModBootstrap.modMixinRegistrations()} 的 {@code configs()}
     * （每 mod 注册的 mixin 配置资源名，正是 trail 串里 {@code <config>:} 那一段）；
     * ② 核心自己的配置 {@code TransformBootstrap.configResource()} → modid {@code brush}。</p>
     *
     * <p>两者都经反射访问（与 {@link #dumpMixinAuditTrail} 同款：本类属启动器侧，与游戏侧
     * 类不共享类型身份）。解析失败**不阻断落盘**，但会打一行说明 —— 不静默降级。</p>
     *
     * @param loader 游戏侧 {@link BrushClassLoader}
     * @return 配置资源名 → modid（可能为空表）
     */
    private static Map<String, String> mixinConfigOwners(BrushClassLoader loader) {
        Map<String, String> owners = new LinkedHashMap<>();
        try {
            Class<?> bootstrap = Class.forName("org.abstruck.brush.mod.ModBootstrap", true, loader);
            Object registrations = bootstrap.getMethod("modMixinRegistrations").invoke(null);
            for (Object registration : (List<?>) registrations) {
                String modId = String.valueOf(registration.getClass().getMethod("modId").invoke(registration));
                for (Object config : (List<?>) registration.getClass().getMethod("configs").invoke(registration)) {
                    owners.putIfAbsent(String.valueOf(config), modId);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[brush] mixin dump: mod attribution unavailable for mod configs (" + e + ")");
        }
        try {
            Class<?> transform = Class.forName("org.abstruck.brush.transform.TransformBootstrap", true, loader);
            owners.putIfAbsent(String.valueOf(transform.getMethod("configResource").invoke(null)), "brush");
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[brush] mixin dump: core mixin config name unavailable (" + e + ")");
        }
        return owners;
    }

    /**
     * 把 trail 串里的 {@code from mod <x>} 改写成真实 modid（查不到则该串原样保留）。
     *
     * @param mixinId trail 串，形如 {@code <config>:<mixin FQN> from mod (unknown)}
     * @param owners  {@link #mixinConfigOwners} 的结果
     * @return 补好归属的串
     */
    private static String withOwner(String mixinId, Map<String, String> owners) {
        int colon = mixinId.indexOf(':');
        if (colon <= 0) {
            return mixinId;
        }
        String owner = owners.get(mixinId.substring(0, colon));
        if (owner == null) {
            return mixinId;
        }
        int fromMod = mixinId.indexOf(" from mod ");
        return fromMod < 0
                ? mixinId + " from mod " + owner
                : mixinId.substring(0, fromMod) + " from mod " + owner;
    }

    /**
     * P2.1：游戏侧引导 Mixin 栈（{@code org.abstruck.brush.transform.TransformBootstrap}）。
     *
     * <p>app 侧与游戏侧只经 JDK 类型通信（{@link BiFunction}&lt;String, byte[], byte[]&gt;），
     * 不共享任何自定义类型身份。引导本身不加载任何 MC 类。
     */
    private static BiFunction<String, byte[], byte[]> installInjection(BrushClassLoader loader) throws Exception {
        Class<?> bootstrap = Class.forName("org.abstruck.brush.transform.TransformBootstrap", true, loader);
        Object result;
        try {
            result = bootstrap.getMethod("install").invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("mixin bootstrap failed: " + cause, cause);
        }
        if (!(result instanceof BiFunction<?, ?, ?>)) {
            throw new IOException("TransformBootstrap.install() returned unexpected type: "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        @SuppressWarnings("unchecked")
        BiFunction<String, byte[], byte[]> pipeline = (BiFunction<String, byte[], byte[]>) result;
        return pipeline;
    }

    /**
     * 最早锚点统一引导（{@code org.abstruck.brush.transform.EarlyBoot}）——
     * 核心监听器安装 + 模组加载的唯一入口（S2/S3 下同一引导由 vanilla 最早锚点先行完成，
     * 此处为显式上下文路径；见 EarlyBoot 类 javadoc）。
     *
     * <p>与 {@link #installInjection} 同款桥接：只经 JDK 类型（{@link Path} / {@link String}）通信，
     * 不共享自定义类型身份。mods 目录为 {@code <game-dir>/brush-mods}（CLAUDE.md §11.8：
     * 与宿主加载器的 {@code mods/} 隔离，避免双向误扫）。
     *
     * <p>失败语义：引导侧已把逐条可读报告打印进游戏侧日志（latest.log），此处把
     * {@link IOException} 原样上抛中止启动 —— 不是裸异常、不静默跳过（CLAUDE.md §3.5 / §4.5）。
     */
    private static void loadMods(BrushClassLoader loader, Path gameDirAbs, String gameVersion) throws IOException {
        Class<?> earlyBoot;
        try {
            earlyBoot = Class.forName("org.abstruck.brush.transform.EarlyBoot", true, loader);
        } catch (ClassNotFoundException e) {
            throw new IOException("early boot class not on Brush classpath: org.abstruck.brush.transform.EarlyBoot", e);
        }
        Path modsDir = gameDirAbs.resolve("brush-mods");
        try {
            earlyBoot.getMethod("ensure", Path.class, String.class).invoke(null, modsDir, gameVersion);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) {
                throw io; // 消息即摘要；逐条明细已在游戏侧日志打印
            }
            throw new IOException("early boot failed: " + cause, cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("early boot invocation failed: " + e, e);
        }
    }

    /**
     * P8：武装自检（{@code org.abstruck.brush.selftest.Selftest}，CLAUDE.md §14.1）。
     *
     * <p>与 {@link #installInjection} / {@link #loadMods} 同款桥接：只经系统属性与 JDK 类型通信，
     * 不共享自定义类型身份。这里引用的常量是<b>编译期常量</b>（引用在编译期内联进本类，
     * 不触发任何游戏侧类的运行时加载），字符串的唯一来源在 {@code BrushProperties} 内。
     * 游戏版本属性（{@code brush.game.version}）已由 {@link #run} 对全部启动路径无条件设置，
     * 此处不重复。
     */
    private static void armSelftest(BrushClassLoader loader) throws IOException {
        System.setProperty(BrushProperties.SELFTEST, "true");
        Class<?> selftest;
        try {
            selftest = Class.forName("org.abstruck.brush.selftest.Selftest", true, loader);
        } catch (ClassNotFoundException e) {
            throw new IOException("selftest class not on Brush classpath: org.abstruck.brush.selftest.Selftest", e);
        }
        try {
            selftest.getMethod("arm").invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("selftest arming failed: " + cause, cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("selftest arming failed: " + e, e);
        }
        LOGGER.info("[brush] selftest mode: the game will exit automatically at first client.tick "
                + "(exit code semantics in selftest.Selftest)");
    }

    /**
     * mod 实机测试武装（{@code --brush.mod-test}，提案 {@code brush-inworld-mod-test-hook-proposal}
     * §3 方案 A）—— 桥接约定同 {@link #armSelftest}：属性开关（{@link BrushProperties#MOD_TEST}
     * 值 = {@code "true"} 或具体 modId）+ 经游戏侧类加载器载入 {@code selftest.ModTestProbe} 并
     * 调用 {@code arm()}（引用常量 = 编译期内联，不触发游戏侧类加载）。探针在首个
     * {@code client.tick}（客户端已就绪）驱动全部目标 mod 的 {@code entrypoints.test} 并退出，
     * 退出码语义见 {@code ModTestProbe}（mod 自定位 bit 16+，见其 javadoc）。
     *
     * @param loader  游戏侧类加载器（反射载体，不共享自定义类型身份）
     * @param modTest 目标：{@code "true"} = 全部已声明测试装置的 mod；具体 modId = 只该 mod
     */
    private static void armModTest(BrushClassLoader loader, String modTest) throws IOException {
        System.setProperty(BrushProperties.MOD_TEST, modTest);
        Class<?> modTestProbe;
        try {
            modTestProbe = Class.forName("org.abstruck.brush.selftest.ModTestProbe", true, loader);
        } catch (ClassNotFoundException e) {
            throw new IOException("mod-test probe class not on Brush classpath:"
                    + " org.abstruck.brush.selftest.ModTestProbe", e);
        }
        try {
            modTestProbe.getMethod("arm").invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("mod-test arming failed: " + cause, cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("mod-test arming failed: " + e, e);
        }
        LOGGER.info("[brush] mod-test mode: will run declared entrypoints.test at first client.tick"
                + " (target=" + modTest + "; exit code semantics in selftest.ModTestProbe)");
    }

    /**
     * 实机探针武装（{@code --brush.reality}）—— 桥接约定同 {@link #armSelftest}：
     * 属性开关 + 经游戏侧类加载器载入 {@code selftest.RealityProbe} 并调用 {@code arm()}
     * （引用常量 = 编译期内联，不触发游戏侧类加载）。
     */
    private static void armReality(BrushClassLoader loader) throws IOException {
        System.setProperty(BrushProperties.REALITY, "true");
        Class<?> reality;
        try {
            reality = Class.forName("org.abstruck.brush.selftest.RealityProbe", true, loader);
        } catch (ClassNotFoundException e) {
            throw new IOException("reality probe class not on Brush classpath:"
                    + " org.abstruck.brush.selftest.RealityProbe", e);
        }
        try {
            reality.getMethod("arm").invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("reality probe arming failed: " + cause, cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("reality probe arming failed: " + e, e);
        }
        LOGGER.info("[brush] reality mode: the game will join a singleplayer world and verify"
                + " network end-to-end / HUD pixels / OS-level input, then exit with a code");
    }

    /** arguments.jvm：rules 过滤后应用 -D 属性；-cp 与纯 JVM 旗标跳过并说明。 */
    private static void applyJvmArguments(VersionInfo info, Map<String, String> values) throws IOException {
        if (info.arguments == null || info.arguments.jvm == null) {
            return;
        }
        boolean skipClasspathNext = false;
        for (VersionInfo.Argument argument : info.arguments.jvm) {
            if (!RuleEvaluator.applies(argument.rules)) {
                continue;
            }
            for (String value : argument.values) {
                if (skipClasspathNext) {
                    skipClasspathNext = false;
                    LOGGER.info("[brush]   skipped: " + value + " (classpath is held by BrushClassLoader)");
                    continue;
                }
                if (value.equals("-cp")) {
                    skipClasspathNext = true;
                    LOGGER.info("[brush]   skipped: -cp (classpath is held by BrushClassLoader)");
                    continue;
                }
                if (value.startsWith("-D")) {
                    String body = value.substring(2);
                    int eq = body.indexOf('=');
                    String key = eq >= 0 ? body.substring(0, eq) : body;
                    String val = eq >= 0 ? LaunchParams.substitute(body.substring(eq + 1), values, "arguments.jvm") : "";
                    System.setProperty(key, val);
                    LOGGER.info("[brush]   set: " + key + "=" + val);
                } else {
                    LOGGER.info("[brush]   skipped: " + value + " (JVM flag; set via runClient jvmArgs where applicable)");
                }
            }
        }
    }

    /** default-user-jvm 审计打印（不改变启动参数），见调用点注释。 */
    private static void reportDefaultUserJvm(List<VersionInfo.Argument> arguments) {
        LOGGER.info("[brush] --- default-user-jvm (audit only; JVM flags not applicable in-process) ---");
        int applies = 0;
        for (VersionInfo.Argument argument : arguments) {
            boolean match = RuleEvaluator.applies(argument.rules);
            if (match) {
                applies++;
            }
            LOGGER.info("[brush]   " + (match ? "applies " : "skipped ") + String.join(" ", argument.values));
        }
        LOGGER.info("[brush] default-user-jvm: " + applies + "/" + arguments.size()
                + " group(s) apply (os.versionRange evaluated against " + RuleEvaluator.rangeVersion() + ")");
    }

}
