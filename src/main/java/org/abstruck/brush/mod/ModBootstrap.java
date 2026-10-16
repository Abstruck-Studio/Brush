package org.abstruck.brush.mod;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.api.BrushClientModInitializer;
import org.abstruck.brush.api.BrushModInitializer;
import org.abstruck.brush.content.ModContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组加载（游戏侧引导）：扫描 {@code brush-mods/} → 解析 {@code brush.mod.json} → 依赖解析与拓扑排序
 * → 实例化入口类 → 打印加载报告。
 *
 * <p><b>运行位置</b>：本类由 {@code BrushClassLoader} 加载（游戏侧类空间），经
 * {@code Brush.loadMods} 反射调用，仅以 JDK 类型（{@code java.nio.file.Path} / {@code String}）通信 ——
 * 与 {@code TransformBootstrap} 同款桥接约定。调用时机：注入管线注册完成之后、{@code Main.main} 之前
 * （启动管线第 5.6 步）。
 *
 * <p><b>失败语义</b>：一切失败（jar 缺 brush.mod.json / 元数据非法 / 依赖缺失 / 版本不符 /
 * 循环依赖 / 入口类实例化失败）都在此汇总成<b>可读报告</b>打印（落入 latest.log），再抛
 * {@link IOException}（消息为单行摘要）中止启动 —— 不抛裸异常、不静默跳过任何一个 mod。
 *
 * <p><b>目录约定</b>：{@code <game-dir>/brush-mods/*.jar}，与宿主加载器的
 * {@code mods/} 分离。只识别 jar（目录与非 jar 文件打印跳过说明）；{@code brush-mods/} 不存在时
 * 自动创建并报告 0 个 mod（合法状态）。
 *
 * <p><b>入口契约（三键 + 严格接口，与 Fabric 对齐）</b>：
 * {@code entrypoints.main} 列出的类须为 public 具体类 + public 无参构造器 +
 * 实现 {@code org.abstruck.brush.api.BrushModInitializer} —— 加载期（早于 {@code Main.main}）
 * 逐个实例化、随即调用 {@code onInitialize()}；实例登记在 {@link #loadedMods()}。
 * {@code entrypoints.client}（须实现 {@code BrushClientModInitializer}）<b>不在加载期实例化</b> ——
 * 迟绑定到客户端初始化时机（{@link #runClientEntrypoints}，经 {@code client.run} 锚点由
 * {@code transform.ClientBoot} 驱动），经与 main 入口同一 {@code ModClassLoader} 加载。
 * {@code entrypoints.server} 只做类名结构校验，当前不加载、不派发（无 dedicated 运行时；
 * 语义与理由见 {@code api.BrushDedicatedServerModInitializer}）。
 * 主入口初始化期（构造 + {@code onInitialize()}）<b>不应假定游戏状态已就绪</b>
 * （本调用早于 {@code Main.main}）。
 */
public final class ModBootstrap {

    private static final Logger LOGGER = LogManager.getLogger(ModBootstrap.class);

    /** 元数据文件名（jar 根）。 */
    public static final String METADATA_ENTRY = "brush.mod.json";

    /** mod id 规范：小写字母开头，允许小写字母 / 数字 / {@code _ . -}。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]*");

    /** Java 类全名（含包），入口类名校验用。 */
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** 已实例化的入口（生命周期事件的投递对象）。 */
    private static final List<LoadedMod> LOADED = new ArrayList<>();

    /**
     * 每 mod 的运行时登记（拓扑序；{@link #instantiate} 时写入）—— 客户端入口迟绑定的载体：
     * client 入口类在客户端初始化时机经 <b>同一 {@code ModClassLoader} 实例</b>加载
     * （与 main 入口同一类空间；见 {@link ModRuntime}）。
     */
    private static final List<ModRuntime> RUNTIMES = new ArrayList<>();

    /**
     * 本次加载中<b>实际注册</b>的 mod mixin 配置台账（步骤 3 登记点写入，拓扑序）。
     * 供自检报告的 {@code mod.mixins} 小节（F）使用 —— 与 {@link #RUNTIMES} 同生命周期
     * （每进程恰一次加载；{@link #install} 首部复位）。
     */
    private static final List<MixinRegistration> MIXIN_REGISTRATIONS = new ArrayList<>();

    /** {@link #runClientEntrypoints()} 是否已完成（幂等守卫；每进程恰派发一次）。 */
    private static boolean clientEntriesDone;

    /** 客户端初始化器实际调用成功数（自检报告用；见 {@link #clientEntrypointsInvoked()}）。 */
    private static volatile int clientEntriesInvoked;

    private ModBootstrap() { }

    /**
     * 游戏版本（{@link #install} 收到的 version json id；供 {@code api.BrushMods.gameVersion()} 与
     * 自检报告复用）。install 之前为 null（{@link #gameVersion()} 返回 {@code "unknown"}）。
     */
    private static volatile String GAME_VERSION;

    /** 游戏版本串（见 {@link #GAME_VERSION}；未初始化时 {@code "unknown"}）。 */
    public static String gameVersion() {
        String version = GAME_VERSION;
        return version == null ? "unknown" : version;
    }

    /**
     * 已加载 mod 的入口实例登记项。
     *
     * @param id         mod id
     * @param version    mod 版本
     * @param entryClass 入口类全名
     * @param instance   实例（以 {@code Object} 持有：核心不依赖 mod 类型）
     * @param jar        mod jar 路径
     * @param metadata   解析后的元数据（UI 展示名 / 描述 / 作者 / icon 等的数据源；
     *                   一个 mod 的多个入口类共享同一实例，只读消费）
     */
    public record LoadedMod(String id, String version, String entryClass, Object instance, Path jar,
            ModMetadata metadata) { }

    /** 已加载的入口实例（只读快照；生命周期事件在其上投递）。 */
    public static synchronized List<LoadedMod> loadedMods() {
        return List.copyOf(LOADED);
    }

    /**
     * 已加载 mod 的元数据视图（每 mod 一行，依赖拓扑序；{@code api.BrushMods} 的数据源）。
     *
     * <p>与 {@link #loadedMods()} 的差异：后者按<b>入口类</b>登记（一个 mod 多个入口 = 多行），
     * 本方法按 <b>mod</b> 登记（{@link #RUNTIMES}，实例化任何入口之前即全量）—— 因此在任意 mod 的
     * {@code onInitialize()} 里都可见本次加载的全部 mod（含自身，见 {@code api.BrushMods} 的时机语义）。
     *
     * @param id          mod id
     * @param name        展示名（原样，可能为 null —— 消费方自行回退 id）
     * @param version     版本串（原样）
     * @param description 描述（原样，可能为 null）
     * @param authors     作者列表（恒非 null；元数据缺省时为空列表）
     * @param icon        图标声明（jar 内条目路径，原样，可能为 null —— 缺省回退默认图标）
     * @param depends     依赖 id 列表（恒非 null；元数据缺省时为空列表；保持元数据声明序）
     * @param jar         mod jar 路径（原始 jar，非预处理副本）
     * @param entryCount  声明的主入口类数（{@code entrypoints.main} 的条目数；UI 用以标识多入口 mod）
     */
    public record ModEntry(String id, String name, String version, String description,
            List<String> authors, String icon, List<String> depends, Path jar, int entryCount) { }

    /** 见 {@link ModEntry}。 */
    public static synchronized List<ModEntry> modEntries() {
        List<ModEntry> out = new ArrayList<>(RUNTIMES.size());
        for (ModRuntime runtime : RUNTIMES) {
            ModMetadata metadata = runtime.metadata();
            out.add(new ModEntry(metadata.id, metadata.name, metadata.version, metadata.description,
                    metadata.authors == null ? List.of() : List.copyOf(metadata.authors),
                    metadata.icon,
                    metadata.depends == null ? List.of() : List.copyOf(metadata.depends.keySet()),
                    runtime.jar(),
                    metadata.entrypoints == null || metadata.entrypoints.main == null
                            ? 0 : metadata.entrypoints.main.size()));
        }
        return List.copyOf(out);
    }

    /**
     * 一次 mod mixin 注册的台账条目（F；步骤 3 每 mod ≤1 条，仅记<b>有生效配置</b>的 mod）。
     *
     * @param modId       mod id
     * @param groupKey    生效的运行时组键（{@link RuntimeKind#jsonKey()}：{@code brush} /
     *                    {@code fabric} / {@code neoforge}）
     * @param inherited   该组是否继承自 {@code brush} 组（{@code neoforge} 组缺省而回退，见
     *                    {@link #mixinsFor}）
     * @param configs     已注册的 mixin 配置资源名（保持声明序）
     * @param preparedJar 注册时暴露给运行时的<b>预处理副本</b>路径（{@code exposeModJar} 的实参；
     *                    自检报告的 declared 计数即从此 jar 读配置 JSON —— 与注册读的是同一份字节）
     */
    public record MixinRegistration(String modId, String groupKey, boolean inherited,
            List<String> configs, Path preparedJar) { }

    /** 见 {@link MixinRegistration}（只读快照，拓扑序）。 */
    public static synchronized List<MixinRegistration> modMixinRegistrations() {
        return List.copyOf(MIXIN_REGISTRATIONS);
    }

    /**
     * 每 mod 的运行时登记项（{@link #instantiate} 时建立）。
     *
     * @param metadata 解析后的元数据（client / server 入口列表与主入口共享同一只读数据源）
     * @param jar      mod jar 路径
     * @param loader   该 mod 的类加载器 —— 与 main 入口同一实例；client 入口类经它加载，
     *                 因此 client 入口类可见本 mod 主入口类（mod 之间仍隔离）
     */
    private record ModRuntime(ModMetadata metadata, Path jar, ModClassLoader loader) { }

    /**
     * 执行模组加载（入口，供 {@code Brush.loadMods} 反射调用）。
     *
     * @param modsDir     {@code brush-mods/} 目录（不存在则创建）
     * @param gameVersion 游戏版本（version json 的 id，如 {@code "26.1.2"}）
     * @return 单行摘要（app 侧日志用），如 {@code "mods: 1 loaded (example 1.0.0)"}
     * @throws IOException 任一环节失败（消息为可读摘要；详细报告已在此之前打印）
     */
    public static String install(Path modsDir, String gameVersion) throws IOException {
        LOGGER.info("[brush] --- mod loading (P3) ---");
        GAME_VERSION = gameVersion;
        synchronized (ModBootstrap.class) {
            RUNTIMES.clear();   // 防御：install 每进程恰一次（EarlyBoot once-guard），重复调用不留残余登记
            MIXIN_REGISTRATIONS.clear();
        }
        Files.createDirectories(modsDir);
        Path modsDirAbs = modsDir.toAbsolutePath().normalize();

        List<Path> jars = scanJars(modsDirAbs);
        LOGGER.info("[brush] mod dir: " + modsDirAbs + " (" + jars.size() + " jar(s))");

        // 1) 逐 jar 解析 brush.mod.json（解析类问题与依赖类问题汇总后一并报告）
        List<String> problems = new ArrayList<>();
        List<DiscoveredMod> discovered = new ArrayList<>();
        Gson gson = new Gson();
        for (Path jar : jars) {
            try {
                discovered.add(new DiscoveredMod(jar, readMetadata(jar, gson)));
            } catch (IOException e) {
                problems.add(e.getMessage());
            }
        }

        // 2) 依赖解析：id 去重 / 存在性 / 版本区间 / 拓扑排序
        String loaderVersion = loaderVersion();
        ModResolver.Result result = ModResolver.resolve(discovered, loaderVersion, gameVersion);
        problems.addAll(result.problems());
        // 提示性声明（`conflicts` 命中 / `recommends` 未满足）：WARN，不阻断加载。
        // 与 problems 分流的理由：Fabric 语义里这两项就是"警告"而非"崩溃"（见 ModMetadata javadoc）。
        for (String warning : result.warnings()) {
            LOGGER.warn("[brush] mod declaration: " + warning);
        }
        if (!problems.isEmpty()) {
            reportProblems(modsDirAbs, jars.size(), problems);
            throw new IOException("mod loading failed: " + problems.size()
                    + " problem(s); no mods were instantiated (details in log)");
        }

        // 3) 运行时接面（拓扑序，先于任何 mod 代码执行）：
        //    prepared jar（平台桩预处理）→ 暴露给运行时类空间 → 注册 mod mixin 配置；
        //    同时为本 mod 建立 ModClassLoader 并登记 RUNTIMES（BrushMods 查询即此时可见全量）。
        RuntimeBridge bridge = null;
        try {
            bridge = RuntimeBridges.current();
        } catch (RuntimeException e) {
            problems.add(e.getMessage());
        }
        Map<String, ModClassLoader> loaders = new HashMap<>();
        if (bridge != null) {
            Path cacheDir = modsDirAbs.resolveSibling("brush-cache").resolve("prepared");
            for (DiscoveredMod mod : result.ordered()) {
                String modId = mod.metadata().id;
                try {
                    Path runtimeJar = ModJarPreparer.prepare(mod, bridge.kind(), cacheDir);
                    // 嵌套 jar（JiJ）：解出后作为该类加载器的额外路径。三运行时走同一条装配路径，
                    // 因为宿主不认识 brush.mod.json（docs/25 §6 缺口 #1）。
                    List<Path> nestedJars = NestedJarExtractor.extract(mod, cacheDir);
                    ModClassLoader loader = new ModClassLoader(modId, runtimeJar, nestedJars,
                            ModBootstrap.class.getClassLoader());
                    bridge.exposeModJar(runtimeJar);
                    // 嵌套 jar 一并暴露给运行时：该 mod 的 mixin 可能引用嵌套 jar 里的类，
                    // 而 mixin 子系统是按"jar 对运行时可见"来读类的（只进 mod 类加载器不够）。
                    for (Path nested : nestedJars) {
                        bridge.exposeModJar(nested);
                    }
                    String groupKey = bridge.kind().jsonKey();
                    Map<String, List<String>> declared = mod.metadata().mixins;
                    List<String> ownMixins = mixinsFor(bridge.kind(), mod.metadata());
                    boolean inherited = declared != null && declared.get(groupKey) == null
                            && !ownMixins.isEmpty();
                    if (!ownMixins.isEmpty()) {
                        for (String configResource : ownMixins) {
                            bridge.registerModMixinConfig(configResource);
                        }
                        LOGGER.info("[brush]   mixins: " + modId + " [" + groupKey
                                + (inherited ? " (inherited from brush)" : "") + "] -> "
                                + String.join(", ", ownMixins));
                        // 台账（F）：注册全部成功后登记（registerModMixinConfig 失败即抛，到此即全成）
                        synchronized (ModBootstrap.class) {
                            MIXIN_REGISTRATIONS.add(new MixinRegistration(modId, groupKey, inherited,
                                    List.copyOf(ownMixins), runtimeJar));
                        }
                    }
                    loaders.put(modId, loader);
                    synchronized (ModBootstrap.class) {
                        RUNTIMES.add(new ModRuntime(mod.metadata(), mod.jar(), loader));
                    }
                } catch (IOException e) {
                    problems.add(mod.fileName() + ": mod '" + modId + "': " + e.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            reportProblems(modsDirAbs, jars.size(), problems);
            throw new IOException("mod loading failed: " + problems.size()
                    + " problem(s); no mods were instantiated (details in log)");
        }

        // 4) 按拓扑序实例化入口类
        List<String> loadedIds = new ArrayList<>();
        for (DiscoveredMod mod : result.ordered()) {
            instantiate(mod, loaders.get(mod.metadata().id));
            loadedIds.add(mod.metadata().id + " " + mod.metadata().version);
        }

        // 5) 报告
        // 桥接属性（§9.3）：把加载器版本与已装 mod 清单写进系统属性，供 transform 层的
        // 崩溃报告段（BrushCrashReport）读取 —— transform 不得直接引用 mod 层类型。
        System.setProperty(BrushProperties.LOADER_VERSION, loaderVersion);
        System.setProperty(BrushProperties.LOADED_MODS, result.ordered().stream()
                .map(mod -> mod.metadata().id + "@" + mod.metadata().version)
                .collect(java.util.stream.Collectors.joining(",")));
        LOGGER.info("[brush]   order: " + String.join(" -> ",
                result.ordered().stream().map(mod -> mod.metadata().id).toList()));
        for (LoadedMod mod : loadedMods()) {
            LOGGER.info("[brush]   loaded: " + mod.id() + " " + mod.version()
                    + " entry=" + mod.entryClass());
        }
        // 注：曾有"库模组"标记（`library` 字段 + 本处打印豁免行），随 CLAUDE.md §6 作废一并删除
        // （2026-10-14 用户指令：「把 library 删掉，默认可用」）—— 现在**任何** mod 都可以使用
        // Brush 的任何类，加载器不再区分"库模组"与非库模组。
        String summary = loadedIds.isEmpty()
                ? "mods: none found in " + modsDirAbs
                : "mods: " + loadedIds.size() + " loaded (" + String.join(", ", loadedIds) + ")";
        LOGGER.info("[brush] mod loading complete -- " + summary);
        LOGGER.info("[brush] (loader version " + loaderVersion + ", game version " + gameVersion + ")");
        return summary;
    }

    // ------------------------------------------------------------------
    // 扫描与元数据解析
    // ------------------------------------------------------------------

    /** 扫描 {@code brush-mods/} 下的 {@code *.jar}（字典序，可复现）；非 jar 项打印跳过说明。 */
    private static List<Path> scanJars(Path modsDirAbs) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDirAbs)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry)
                        && name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    jars.add(entry.toAbsolutePath().normalize());
                } else {
                    LOGGER.info("[brush]   skipping non-jar entry: " + name);
                }
            }
        }
        jars.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return jars;
    }

    /**
     * 读取并校验一个 jar 的 {@code brush.mod.json}。
     * 失败消息以 jar 文件名开头（报告自解释），抛 {@link IOException} 由调用方汇总。
     *
     * <p>解析 / 校验在 {@link ZipFile} 作用域内进行，顺带完成 icon 条目存在性检查
     * （展示性字段，仅警告不阻断；UI 侧还有第二层回退，见 ui.ModIconLoader）。
     */
    private static ModMetadata readMetadata(Path jar, Gson gson) throws IOException {
        String fileName = jar.getFileName().toString();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(METADATA_ENTRY);
            if (entry == null) {
                throw new IOException(fileName + ": missing " + METADATA_ENTRY
                        + " at jar root (only Brush mods belong in brush-mods/; "
                        + "Fabric/NeoForge mods are loaded by their own loader)");
            }
            String json;
            try (InputStream in = zip.getInputStream(entry)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonElement root;
            try {
                root = JsonParser.parseString(json);
            } catch (JsonParseException e) {
                throw new IOException(fileName + ": " + METADATA_ENTRY + " is not valid JSON: " + e.getMessage(), e);
            }
            if (root.isJsonObject() && root.getAsJsonObject().get("mixins") != null
                    && root.getAsJsonObject().get("mixins").isJsonArray()) {
                throw new IOException(fileName + ": 'mixins' must be an object of runtime groups"
                        + " {brush / fabric / neoforge: [...]} (the flat list form was retired"
                        + " 2026-10-11, docs/19 section 6)");
            }
            if (root.isJsonObject()) {
                // 缺口 #4（docs/25 §3.3）：声明表的"字符串 or 字符串数组"两形态归一化
                // （数组 = OR；在 fromJson 之前把数组折成 " || " 串，模型与单字符串形态都不动）。
                DeclarationSpecs.normalizeDeclarationArrays(root.getAsJsonObject(), fileName);
            }
            ModMetadata metadata;
            try {
                metadata = gson.fromJson(root, ModMetadata.class);
            } catch (JsonParseException e) {
                throw new IOException(fileName + ": " + METADATA_ENTRY + " is not valid JSON: " + e.getMessage(), e);
            }
            if (metadata == null) {
                throw new IOException(fileName + ": " + METADATA_ENTRY + " is empty");
            }
            validate(metadata, fileName);
            checkIconEntry(zip, metadata, fileName);
            checkMixinEntries(zip, metadata, fileName);
            return metadata;
        }
    }

    /**
     * icon 条目存在性检查（不阻断）：声明了 icon 但 jar 内无对应条目（或条目是目录）时打警告，
     * 加载照常进行 —— icon 是展示性字段，UI 会回退到默认图标。
     */
    private static void checkIconEntry(ZipFile zip, ModMetadata metadata, String fileName) {
        String iconPath = normalizeIconPath(metadata.icon);
        if (iconPath.isEmpty()) {
            return;
        }
        ZipEntry iconEntry = zip.getEntry(iconPath);
        if (iconEntry == null || iconEntry.isDirectory()) {
            LOGGER.warn("[brush]   " + fileName + ": icon '" + metadata.icon
                    + "' not found in jar (UI falls back to the default icon)");
        }
    }

    /**
     * jar 条目路径归一化：去首尾空白与首部 {@code /}（jar 条目名不带前导斜杠）；null → 空串。
     * icon 读取（UI 侧共用）与 mixin 配置条目共用此规则。
     */
    public static String normalizeJarEntryPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /** icon 路径归一化（{@link #normalizeJarEntryPath} 的 icon 语义别名，UI 侧沿用旧名）。 */
    public static String normalizeIconPath(String icon) {
        return normalizeJarEntryPath(icon);
    }

    /**
     * 当前运行时生效的 mixin 组（{@code brush.mod.json.mixins} 的 {@code brush / fabric / neoforge} 键）：
     * {@code fabric} / {@code neoforge} 键缺省时<b>继承 {@code brush} 键的列表</b>（brush = 默认组；
     * 显式声明空列表 {@code []} = 该运行时无 mod mixin，不继承）。键与默认键都不存在 = 无 mod mixin。
     * 其余组只做存在性检查（{@link #checkMixinEntries}）、不注册。
     */
    private static List<String> mixinsFor(RuntimeKind kind, ModMetadata metadata) {
        if (metadata.mixins == null) {
            return List.of();
        }
        List<String> entries = metadata.mixins.get(kind.jsonKey());
        if (entries == null && kind != RuntimeKind.BRUSH) {
            entries = metadata.mixins.get(RuntimeKind.BRUSH.jsonKey());
        }
        return entries == null ? List.of() : entries;
    }

    /**
     * mixins 条目存在性检查（{@code brush.mod.json.mixins} 全部声明组；<b>硬错误</b>，与展示性的
     * icon 相反）：配置条目缺失意味着该 mod 的 hook 会静默不存在，必须在加载期失败得可读。
     * 非当前运行时的组同样检查 —— 声明的文件必须在 jar 里（只不注册）。
     * 条目路径已在 {@link #validate} 里归一化。
     */
    private static void checkMixinEntries(ZipFile zip, ModMetadata metadata, String fileName)
            throws IOException {
        if (metadata.mixins == null) {
            return;
        }
        for (List<String> configs : metadata.mixins.values()) {
            for (String configResource : configs) {
                ZipEntry entry = zip.getEntry(configResource);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException(fileName + ": mod '" + metadata.id + "': mixin config '"
                            + configResource + "' not found in the jar");
                }
            }
        }
    }

    /**
     * 结构校验：id / version / entrypoints.main / 依赖键。失败消息可读。
     *
     * <p>包级可见（非 private）供同包单测直测（{@code ModBootstrapTest}）—— 唯一生产调用点为
     * {@link #readMetadata}；行为与失败语义不受可见性影响。
     */
    static void validate(ModMetadata metadata, String fileName) throws IOException {
        if (metadata.id == null || !ID_PATTERN.matcher(metadata.id).matches()) {
            throw new IOException(fileName + ": invalid id " + quote(metadata.id)
                    + " (must match [a-z][a-z0-9_.-]*)");
        }
        if (metadata.version == null) {
            throw new IOException(fileName + ": missing version (mod '" + metadata.id + "')");
        }
        try {
            VersionRange.parseVersion(metadata.version);
        } catch (IllegalArgumentException e) {
            throw new IOException(fileName + ": mod '" + metadata.id + "': " + e.getMessage());
        }
        if (metadata.entrypoints == null || metadata.entrypoints.main == null
                || metadata.entrypoints.main.isEmpty()) {
            throw new IOException(fileName + ": mod '" + metadata.id
                    + "': entrypoints.main must list at least one class");
        }
        validateEntryList(metadata, fileName, "main", metadata.entrypoints.main);
        validateEntryList(metadata, fileName, "client", metadata.entrypoints.client);
        validateEntryList(metadata, fileName, "server", metadata.entrypoints.server);
        // 测试入口（提案 §3；可选键，缺省 = 该 mod 在 --brush.mod-test 模式下无测试，合法状态）：
        // 类名结构校验与 main / client / server 同款（null 键在此直接返回，不报错）；接口实现
        // 检查（instanceof ModClientTest）在探针实例化时执行（见 selftest.ModTestProbe）。
        validateEntryList(metadata, fileName, "test", metadata.entrypoints.test);
        if (metadata.mixins != null) {
            Map<String, List<String>> normalized = new HashMap<>(metadata.mixins.size());
            for (Map.Entry<String, List<String>> group : metadata.mixins.entrySet()) {
                String key = group.getKey();
                if (key == null) {
                    throw new IOException(fileName + ": mod '" + metadata.id
                            + "': mixins contains a null group key");
                }
                try {
                    RuntimeKind.byJsonKey(key);
                } catch (IllegalArgumentException e) {
                    throw new IOException(fileName + ": mod '" + metadata.id + "': " + e.getMessage(), e);
                }
                if (group.getValue() == null) {
                    throw new IOException(fileName + ": mod '" + metadata.id
                            + "': mixins." + key + " must be a list of config resource names");
                }
                List<String> list = new ArrayList<>(group.getValue().size());
                for (String configResource : group.getValue()) {
                    String path = normalizeJarEntryPath(configResource);
                    if (path.isEmpty()) {
                        throw new IOException(fileName + ": mod '" + metadata.id
                                + "': mixins." + key + " contains a blank entry");
                    }
                    if (list.contains(path)) {
                        throw new IOException(fileName + ": mod '" + metadata.id
                                + "': duplicate mixin config entry '" + path + "'");
                    }
                    list.add(path);
                }
                normalized.put(key, List.copyOf(list));
            }
            metadata.mixins = Map.copyOf(normalized);
        }
        if (metadata.depends != null) {
            for (String dependencyId : metadata.depends.keySet()) {
                if (dependencyId == null || dependencyId.isBlank()) {
                    throw new IOException(fileName + ": mod '" + metadata.id
                            + "': depends contains a blank id");
                }
            }
        }
        // 嵌套 jar（JiJ，docs/25 §6 缺口 #1；形态沿用 Fabric 的 jars[].file）：
        // 结构校验 + 路径归一化。**jar 内条目是否存在**在装配期由 NestedJarExtractor 检查
        // （此处只碰元数据，不读 jar —— 同 mixins 的分工）。
        if (metadata.jars != null) {
            List<String> normalizedNested = new ArrayList<>(metadata.jars.size());
            for (int i = 0; i < metadata.jars.size(); i++) {
                ModMetadata.NestedJar nested = metadata.jars.get(i);
                if (nested == null || nested.file == null) {
                    throw new IOException(fileName + ": mod '" + metadata.id + "': jars[" + i
                            + "] must be an object with a 'file' key (Fabric-shaped declaration:"
                            + " \"jars\": [{\"file\": \"nested/vendor/dep.jar\"}])");
                }
                String path = normalizeJarEntryPath(nested.file);
                if (path.isEmpty()) {
                    throw new IOException(fileName + ": mod '" + metadata.id + "': jars[" + i
                            + "] has a blank 'file' value");
                }
                if (normalizedNested.contains(path)) {
                    throw new IOException(fileName + ": mod '" + metadata.id
                            + "': duplicate nested jar entry '" + path + "'");
                }
                normalizedNested.add(path);
                nested.file = path;
            }
        }
        // 别名 id（provides，docs/25 §3.3）：格式与自身 id 同款；不得等于自身 id、不得自重复。
        // **跨 mod 的别名冲突**（撞别的 mod 的 id / 两个 mod 提供同一别名）在 ModResolver 判定
        // —— 那需要同时看到全部 mod。
        if (metadata.provides != null) {
            List<String> normalizedAliases = new ArrayList<>(metadata.provides.size());
            for (String alias : metadata.provides) {
                if (alias == null || !ID_PATTERN.matcher(alias).matches()) {
                    throw new IOException(fileName + ": mod '" + metadata.id + "': provides entry '"
                            + alias + "' is not a valid mod id (expected " + ID_PATTERN.pattern() + ")");
                }
                if (alias.equals(metadata.id)) {
                    throw new IOException(fileName + ": mod '" + metadata.id
                            + "': provides must not list the mod's own id");
                }
                if (normalizedAliases.contains(alias)) {
                    throw new IOException(fileName + ": mod '" + metadata.id
                            + "': duplicate provides entry '" + alias + "'");
                }
                normalizedAliases.add(alias);
            }
            metadata.provides = List.copyOf(normalizedAliases);
        }
        // 测试 jar（提案 §3；便利字段，缺省合法）：显式声明但为空白 = typo 防护（可读失败，
        // 与 icon 的"不阻断"相反 —— 空白 testJar 会让 mod-test 模式静默无 jar 可追加，比
        // "未声明"更危险）。testJar 是<b>jar 外的独立文件路径</b>（如 "build/libs/<mod>-tests.jar"，
        // 相对游戏目录 / 项目根），由 app 侧（Brush / Gradle task）在 mod-test 模式下解析并追加
        // 进注入栈（与 --inject-libs 同款机制，提案 §3 路 ②）—— 文件存在性检查在该层做
        // （ModBootstrap 只解析元数据，不触碰文件系统外的产物；同 mixins 在 ModBootstrap 只查
        // jar 内条目、不在运行时文件系统校验的口径）。
        if (metadata.testJar != null && metadata.testJar.isBlank()) {
            throw new IOException(fileName + ": mod '" + metadata.id
                    + "': testJar must be a non-blank path (or omit the key entirely)");
        }
    }

    // ------------------------------------------------------------------
    // 实例化
    // ------------------------------------------------------------------

    /**
     * 实例化一个 mod 的 <b>main</b> 入口类（拓扑序调用），登记进 {@link #loadedMods()}。
     * 类加载器与 {@link ModRuntime} 登记由 {@link #install} 的阶段 3（运行时接面）建立 ——
     * 此处只做入口实例化；client 键<b>不</b>在此时实例化（迟绑定，见 {@link #runClientEntrypoints}）。
     * 失败即汇总报告并中止（不静默跳过）。
     */
    private static void instantiate(DiscoveredMod mod, ModClassLoader loader) throws IOException {
        ModMetadata metadata = mod.metadata();

        for (String entryClassName : metadata.entrypoints.main) {
            Object instance = instantiateEntry(metadata.id, mod.fileName(), loader, entryClassName,
                    BrushModInitializer.class, "entry class", "onInitialize()",
                    BrushModInitializer::onInitialize);
            synchronized (ModBootstrap.class) {
                LOADED.add(new LoadedMod(metadata.id, metadata.version, entryClassName, instance, mod.jar(),
                        metadata));
            }
        }
    }

    /**
     * 入口类通用实例化（main / client 入口共用）：加载 → 具体类与构造器检查 → 构造 → 接口检查 →
     * 接口方法调用。构造与调用都在该 mod 的 {@link ModContext} 上下文内执行 —— 构造期 /
     * 初始化期注册的事件监听器据此捕获归属。
     *
     * <p><b>接口契约（严格，与 Fabric 对齐）</b>：入口类必须实现 {@code initializerType}
     * （不实现 = 可读失败，不提供"仅构造"的兼容路径）。接口方法返回 void 且不声明受检异常，
     * 因此以 {@link Consumer} 传入、方法引用直接可用。
     *
     * <p>失败消息以 mod id 开头（报告自解释），全部转为 {@link IOException} 由调用方汇总：
     * 受检的构造异常用 {@link ReflectiveCarrier} 包过 {@code Supplier} 边界后原样拆出；
     * 接口方法抛出的任意 {@code Throwable} 用 {@link EntryFailure} 带出（保留为 cause，不吞）。
     *
     * @param label  入口类型描述（"entry class" / "client entry class"，进失败消息）
     * @param method 接口方法名（含括号，进失败消息与方法引用配对）
     * @param invoke 接口方法引用（如 {@code BrushModInitializer::onInitialize}）
     * @return 入口类实例（构造成功且接口方法未抛出时返回）
     */
    private static <T> Object instantiateEntry(String modId, String fileName, ModClassLoader loader,
            String entryClassName, Class<T> initializerType, String label, String method,
            Consumer<T> invoke) throws IOException {
        // P1-9：loader 为 null（加载器登记缺失）时在入口处给出可读失败，
        // 不让 NPE 从 Class.forName 深处冒出、丢失 mod 归属信息。
        Objects.requireNonNull(loader, "mod class loader must not be null (mod '" + modId + "')");
        Class<?> entryClass;
        try {
            entryClass = Class.forName(entryClassName, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IOException("mod '" + modId + "' (" + fileName + "): " + label
                    + " not found: " + entryClassName, e);
        } catch (LinkageError | SecurityException e) {
            throw new IOException("mod '" + modId + "': " + label + " " + entryClassName
                    + " failed to link: " + e, e);
        }

        if (entryClass.isInterface() || Modifier.isAbstract(entryClass.getModifiers())) {
            throw new IOException("mod '" + modId + "': " + label + " " + entryClassName
                    + " must be a concrete class");
        }
        Constructor<?> constructor;
        try {
            constructor = entryClass.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IOException("mod '" + modId + "': " + label + " " + entryClassName
                    + " must declare a public no-arg constructor", e);
        }

        Object instance;
        try {
            instance = ModContext.callWith(modId, () -> {
                Object created;
                try {
                    created = constructor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new ReflectiveCarrier(e);
                }
                if (!initializerType.isInstance(created)) {
                    throw new EntryFailure("mod '" + modId + "': " + label + " " + entryClassName
                            + " must implement " + initializerType.getName()
                            + " (entrypoint interface contract, see docs/09 section 7)");
                }
                try {
                    invoke.accept(initializerType.cast(created));
                } catch (Throwable t) {
                    throw new EntryFailure("mod '" + modId + "': " + label + " " + entryClassName
                            + " threw during " + method + ": " + t, t);
                }
                return created;
            });
        } catch (ReflectiveCarrier e) {
            if (e.getCause() instanceof InvocationTargetException invoked) {
                Throwable cause = invoked.getCause() == null ? invoked : invoked.getCause();
                throw new IOException("mod '" + modId + "': " + label + " " + entryClassName
                        + " threw during construction: " + cause, cause);
            }
            throw new IOException("mod '" + modId + "': " + label + " " + entryClassName
                    + " could not be instantiated: " + e.getCause(), e.getCause());
        } catch (EntryFailure e) {
            throw new IOException(e.getMessage(), e.getCause());
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // 客户端入口（client 键；客户端初始化时机派发）
    // ------------------------------------------------------------------

    /**
     * 派发全部客户端入口：按拓扑序遍历已加载 mod 的 {@code entrypoints.client}，
     * 实例化入口类并调用 {@code onInitializeClient()} —— 实例化、接口检查、调用都在该 mod 的
     * {@link ModContext} 上下文内，经与 main 入口<b>同一 {@code ModClassLoader} 实例</b>加载
     * （client 入口类可见本 mod 主入口类；mod 之间仍隔离）。
     *
     * <p><b>调用方与时机</b>：由 {@code transform.ClientBoot} 在 {@code client.run} 锚点调用，
     * 先于 {@code client.run} 事件派发（见该类 javadoc）—— 每进程恰一次，本方法自带幂等守卫。
     *
     * <p><b>失败语义</b>（同 {@link #install}）：问题一次列全 → 打印报告块 → 抛可读
     * {@link IOException}（调用方包装后经锚点冒泡，中止启动）；不静默跳过任何一个入口。
     */
    public static void runClientEntrypoints() throws IOException {
        synchronized (ModBootstrap.class) {
            if (clientEntriesDone) {
                return;
            }
            clientEntriesDone = true;
        }

        List<ModRuntime> runtimes;
        synchronized (ModBootstrap.class) {
            runtimes = List.copyOf(RUNTIMES);
        }

        List<String> problems = new ArrayList<>();
        int invoked = 0;
        for (ModRuntime runtime : runtimes) {
            List<String> entryClasses = runtime.metadata().entrypoints.client;
            if (entryClasses == null || entryClasses.isEmpty()) {
                continue;
            }
            String fileName = runtime.jar().getFileName().toString();
            for (String entryClassName : entryClasses) {
                try {
                    instantiateEntry(runtime.metadata().id, fileName, runtime.loader(), entryClassName,
                            BrushClientModInitializer.class, "client entry class", "onInitializeClient()",
                            BrushClientModInitializer::onInitializeClient);
                } catch (IOException e) {
                    problems.add(e.getMessage());
                    continue;
                }
                invoked++;
                LOGGER.info("[brush] client init: " + runtime.metadata().id + " -> " + entryClassName
                        + " (onInitializeClient() done)");
            }
        }
        clientEntriesInvoked = invoked;

        if (!problems.isEmpty()) {
            LOGGER.error("[brush] CLIENT INITIALIZATION FAILED -- " + problems.size()
                    + " problem(s) at the client entry anchor:");
            for (String problem : problems) {
                LOGGER.error("[brush]   - " + problem);
            }
            LOGGER.error("[brush] aborting launch (no silent skip; CLAUDE.md 3.5)");
            throw new IOException("client initialization failed: " + problems.size()
                    + " problem(s); aborting launch (details in log)");
        }
        LOGGER.info("[brush] client init complete -- " + invoked + " client initializer(s) invoked"
                + (invoked == 0 ? " (no mod declares entrypoints.client)" : ""));
    }

    /** 声明的 client 入口总数（已加载 mod 的 {@code entrypoints.client} 条目合计；自检审计用）。 */
    public static int clientEntrypointsDeclared() {
        synchronized (ModBootstrap.class) {
            int declared = 0;
            for (ModRuntime runtime : RUNTIMES) {
                List<String> entryClasses = runtime.metadata().entrypoints.client;
                if (entryClasses != null) {
                    declared += entryClasses.size();
                }
            }
            return declared;
        }
    }

    /** 实际调用成功的 client 初始化器数（{@link #runClientEntrypoints()} 之后；自检审计用）。 */
    public static int clientEntrypointsInvoked() {
        return clientEntriesInvoked;
    }

    /**
     * 声明的 server 入口总数（自检报告用）。<b>只统计、不派发</b> —— dedicated 运行时不在范围，
     * server 入口类不会被加载（见 {@code api.BrushDedicatedServerModInitializer}）。
     */
    public static int serverEntrypointsDeclared() {
        synchronized (ModBootstrap.class) {
            int declared = 0;
            for (ModRuntime runtime : RUNTIMES) {
                List<String> entryClasses = runtime.metadata().entrypoints.server;
                if (entryClasses != null) {
                    declared += entryClasses.size();
                }
            }
            return declared;
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 入口类名列表的结构校验（main / client / server 三键共用）：null = 该键缺席（合法；
     * main 的非空要求由 {@link #validate} 先行断言）；每个类名须匹配 {@link #CLASS_NAME_PATTERN}。
     *
     * <p>server 列表只做本项结构校验 —— 类在客户端运行时不加载（dedicated-only 类可能引用
     * 客户端不存在的类型），语义见 {@code api.BrushDedicatedServerModInitializer}。
     */
    private static void validateEntryList(ModMetadata metadata, String fileName, String key,
            List<String> entryClasses) throws IOException {
        if (entryClasses == null) {
            return;
        }
        for (String entryClass : entryClasses) {
            if (entryClass == null || !CLASS_NAME_PATTERN.matcher(entryClass).matches()) {
                throw new IOException(fileName + ": mod '" + metadata.id + "': invalid " + key
                        + " entry class name " + quote(entryClass));
            }
        }
    }

    /**
     * 加载器自身元数据：读类路径根 {@code /brush.mod.json}（与普通 mod 同格式同位置；
     * 构建期由 build.gradle 展开 {@code ${version}}）。
     *
     * <p><b>与 jar 内 mod 的解析差异</b>（如实登记）：<b>不</b>跑 {@link #validate} / 依赖检查 ——
     * 加载器本体不是 {@code brush-mods/} 加载项，不进 {@link #loadedMods()}（核心与自检口径不变）。
     * 消费者：{@link #loaderVersion()}（依赖解析中 {@code brush} id 的比对对象）与模组列表界面的
     * 自身条目（{@code ui.ModsScreen.buildLoaderSelf}）、默认图标路径（{@code ui.ModIconLoader}）——
     * 均为同一游戏侧类空间。
     *
     * <p>失败语义：缺失 / 解析失败 / id 或 version 缺失 → WARN + 回退（version = {@code "unknown"}，
     * 依赖检查仍失败得可读）。结果缓存；返回恒非 null。
     */
    public static ModMetadata loaderMetadata() {
        ModMetadata cached = LOADER_METADATA;
        if (cached == null) {
            cached = loadLoaderMetadata();
            LOADER_METADATA = cached;
        }
        return cached;
    }

    /** 加载器元数据缓存（见 {@link #loaderMetadata()}；幂等，重复解析无副作用）。 */
    private static volatile ModMetadata LOADER_METADATA;

    private static ModMetadata loadLoaderMetadata() {
        try (InputStream in = ModBootstrap.class.getResourceAsStream("/" + METADATA_ENTRY)) {
            if (in == null) {
                LOGGER.warn("[brush] /" + METADATA_ENTRY + " not found on the classpath; loader metadata"
                        + " unavailable ('brush' dependency checks will fail readable)");
                return fallbackLoaderMetadata();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ModMetadata metadata = new Gson().fromJson(json, ModMetadata.class);
            if (metadata == null || metadata.id == null || metadata.version == null) {
                LOGGER.warn("[brush] /" + METADATA_ENTRY + " is malformed (id/version missing);"
                        + " loader metadata unavailable");
                return fallbackLoaderMetadata();
            }
            return metadata;
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("[brush] cannot read /" + METADATA_ENTRY + ": " + e);
            return fallbackLoaderMetadata();
        }
    }

    /** 元数据不可用时的最小回退（id 保留、version = unknown；见 {@link #loaderMetadata()} 失败语义）。 */
    private static ModMetadata fallbackLoaderMetadata() {
        ModMetadata metadata = new ModMetadata();
        metadata.id = "brush";
        metadata.version = "unknown";
        return metadata;
    }

    /**
     * 加载器版本（依赖解析中 {@code brush} id 的比对对象）= 自身元数据的 version
     * （构建期展开，单一来源 = build.gradle version）。public 供自检报告复用（selftest.Selftest）。
     */
    public static String loaderVersion() {
        return loaderMetadata().version;
    }

    /** 打印问题报告块（game-side 日志；app 侧随后打一行 FATAL 摘要）。 */
    private static void reportProblems(Path modsDirAbs, int jarCount, List<String> problems) {
        LOGGER.error("[brush] MOD LOADING FAILED -- " + problems.size() + " problem(s) ("
                + modsDirAbs + ", " + jarCount + " jar(s)):");
        for (String problem : problems) {
            LOGGER.error("[brush]   - " + problem);
        }
        LOGGER.error("[brush] no mods were instantiated; aborting launch before the game starts");
    }

    private static String quote(String value) {
        return value == null ? "(missing)" : "\"" + value + "\"";
    }

    /**
     * 受检异常经 {@code ModContext.callWith}（{@code Supplier} 边界）的中转载体：
     * 只为把 {@link ReflectiveOperationException} 原样带过函数式接口，随即在调用处拆出 ——
     * 不是用户可见的失败类型，不承载语义（{@code ModContext} 不存在于 {@code mod} 包，
     * 其签名不引入任何受检异常）。
     */
    private static final class ReflectiveCarrier extends RuntimeException {
        ReflectiveCarrier(ReflectiveOperationException cause) {
            super(cause);
        }
    }

    /**
     * 入口接口契约失败（未实现入口接口 / 接口方法抛出任意 {@code Throwable}）经
     * {@code ModContext.callWith}（{@code Supplier} 边界）的中转载体，见 {@link #instantiateEntry}；
     * 调用处拆出后转为可读 {@link IOException}（cause 保留）。与 {@link ReflectiveCarrier} 同款，
     * 不是用户可见的失败类型。
     */
    private static final class EntryFailure extends RuntimeException {
        EntryFailure(String message) {
            super(message);
        }

        EntryFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
