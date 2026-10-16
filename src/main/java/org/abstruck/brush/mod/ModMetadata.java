package org.abstruck.brush.mod;

import java.util.List;
import java.util.Map;

/**
 * {@code brush.mod.json} 的模型。
 *
 * <p>字段名即 JSON 键名（Gson 直接映射，不需要注解）。示例：
 * <pre>{@code
 * {
 *   "id": "example",
 *   "name": "Hello Example Mod",
 *   "version": "1.0.0",
 *   "description": "...",              // 可选
 *   "authors": ["..."],                // 可选
 *   "icon": "icon.png",                // 可选：jar 内条目路径
 *   "mixins": {                          // 可选：按运行时分组的 mixin 配置资源名（jar 根下）
 *     "brush": ["example.mixins.json"]       // 默认组；fabric / neoforge 缺省时继承它
 *     //"fabric":   ["..."]                 // 可选；显式空列表 [] = 该运行时无 mod mixin
 *     //"neoforge": ["..."]                 // 可选（同上）
 *   },
 *   "entrypoints": {
 *     "main":   ["org.example.HelloMod"],         // 必需（实现 BrushModInitializer）
 *     "client": ["org.example.HelloModClient"],   // 可选（实现 BrushClientModInitializer）
 *     "server": ["org.example.HelloModServer"]    // 可选（实现 BrushDedicatedServerModInitializer；
 *   },                                            //        仅 dedicated 运行时派发，当前不触发）
 *   "depends":    { "brush": ">=26.10.0" },  // 可选：必需依赖（缺失/不满足 = 硬失败）
 *   "breaks":     { "other": "<1.2" },       // 可选：同存即崩 → 硬失败（命中即拒绝加载）
 *   "conflicts":  { "other": "*" },          // 可选：同存易出 bug → WARN，加载照常
 *   "recommends": { "extra": ">=2.0" },      // 可选：缺失/不满足 → WARN
 *   "suggests":   { "idea": "*" }            // 可选：纯元数据，加载器不读取
 * }</pre>
 *
 * <p>结构校验（id / version / entrypoints 的合法性）在 {@link ModBootstrap} 解析后立即进行，
 * 失败转为可读报告，不抛裸异常。本类是数据载体，无逻辑（便于单测直接构造）。
 */
public final class ModMetadata {

    /** 模组唯一标识（小写字母开头，允许 {@code [a-z0-9_.-]}）。 */
    public String id;

    /** 展示名（可选；缺省时报告里回退用 id）。 */
    public String name;

    /** 模组版本（点分纯数字，见 {@link VersionRange}）。 */
    public String version;

    /** 描述（可选）。 */
    public String description;

    /** 作者列表（可选）。 */
    public List<String> authors;

    /**
     * 模组图标（可选）：<b>jar 内条目路径</b>（如 {@code "icon.png"} 或
     * {@code "assets/example/textures/item/ruby.png"}；前导 {@code /} 会被忽略）。
     *
     * <p>展示性字段，<b>不阻断加载</b>：条目缺失 / 不可读 / 解码失败时，加载期打警告日志，
     * UI 侧回退到 Brush 默认图标（ui.ModIconLoader 是第二层兜底）。
     */
    public String icon;

    /**
     * mod 自带 mixin 配置（可选）：<b>按运行时分组的 jar 根资源名</b>。键 = {@code brush} /
     * {@code fabric} / {@code neoforge}（{@link RuntimeKind#jsonKey()}）；前导 {@code /} 会被忽略。
     * <b>{@code brush} 是默认组：{@code fabric} / {@code neoforge} 键缺省时继承 brush 的列表</b>
     * （显式声明空列表 {@code []} = 该运行时无 mod mixin，不继承）—— 26.1.2 三运行时统一官方名，
     * 常见形态即只写 brush 一组。运行期由 Brush 在实例化任何入口之前，把<b>当前运行时
     * 生效组</b>的每个配置注册进当前运行时的 mixin 子系统（S1 自研服务 / S2 Fabric / S3 NeoForge）。
     *
     * <p><b>功能性字段，条目缺失即加载失败（硬错误）</b> —— 与展示性的 {@link #icon} 不同：
     * 配置不可读意味着 hook 静默不存在，必须在加载期就失败得可读。
     * <b>未知键 = 加载失败</b>（typo 防护）；全部声明组的条目都做 jar 内存在性检查
     * （非当前运行时组只查文件在不在，不注册）。配置的 {@code package} / 目标名用官方名
     * （26.1.2 三运行时统一官方名）；<b>不得</b>写 {@code refmap} 字段（已废弃）。
     */
    public Map<String, List<String>> mixins;

    /** 入口点（必需：{@code main} 非空；可选附加键 {@code test}，见 {@link Entrypoints#test}）。 */
    public Entrypoints entrypoints;

    /**
     * 测试 jar 路径（提案 {@code brush-inworld-mod-test-hook-proposal} §3 方案 A；可选）。
     *
     * <p>mod 把"实机测试装置"（{@code org.abstruck.brush.api.ModClientTest} 实现类 + 配套工具类）打成独立 test jar
     * 时，在此声明其<b>相对 mod jar 根的路径</b>（如 {@code "test/chickens-tests.jar"} 或
     * 绝对路径 {@code "build/libs/<mod>-tests.jar"}）。{@code --brush.mod-test} 模式下，
     * 加载器（app 侧 {@code Brush}）解析本字段并把该 jar 追加进注入栈（与 {@code --inject-libs}
     * 同款机制 —— 提案 §3 "测试类分发：文档化两条路 ② brush.mod.json 声明 testJar，加载器在
     * --brush.mod-test 模式下自动追加"）。
     *
     * <p><b>缺省不报错（向后兼容，提案 §3 / 任务要求 3）</b>：不声明 {@code testJar} 的 mod 在
     * mod-test 模式下仍需经 {@code --inject-libs} 把测试类送进游戏侧（提案 §3 路 ①，现行可用
     * 接线）；本字段是<b>便利</b>而非<b>必需</b>。路径解析失败（文件不存在）= 加载期可读失败
     * （同 {@code mixins} 条目存在性检查口径），不静默跳过。
     */
    public String testJar;

    /** 依赖：mod id → 版本区间（可选）。特殊 id：{@code brush}（加载器）/ {@code minecraft}（游戏）。 */
    public Map<String, String> depends;

    /**
     * 反向依赖「同存会崩」（可选）：mod id → 版本区间。Fabric {@code breaks} 同义
     * （语义来源：Fabric Wiki {@code fabric.mod.json} › Dependency resolution，
     * https://wiki.fabricmc.net/documentation:fabric_mod_json ，2026-10-14 抓取）。
     *
     * <p><b>语义 = 硬失败</b>：命中任一条目（目标 mod <b>在场</b>且版本落入区间）即中止加载，
     * 报告里列出声明方、被命中方与实况版本 —— 与 {@code depends} 缺失同口径（可读报告，
     * 不抛裸异常，CLAUDE.md §4.5）。目标 mod 不在场 = 无事发生。</p>
     *
     * <p>26.1.2 三运行时共用同一套元数据与同一套解析，故本字段语义天然三运行时一致
     * （CLAUDE.md §12.2）。</p>
     */
    public Map<String, String> breaks;

    /**
     * 冲突声明「同存易出 bug」（可选）：mod id → 版本区间。Fabric {@code conflicts} 同义
     * （来源同上）。
     *
     * <p><b>语义 = 警告，不阻断加载</b>。注意与 {@link #breaks} 的分工，<b>别记反</b>：
     * Fabric 原文 {@code breaks} = 「might cause a game crash → trigger a crash」，
     * {@code conflicts} = 「cause some kind of bugs → log a warning」。</p>
     */
    public Map<String, String> conflicts;

    /**
     * 推荐依赖（可选）：mod id → 版本区间。Fabric {@code recommends} 同义（来源同上）。
     *
     * <p><b>语义 = 警告</b>：目标缺失、或在场但版本不满足区间时打印 WARN，不阻断加载。</p>
     */
    public Map<String, String> recommends;

    /**
     * 建议依赖（可选）：mod id → 版本区间。Fabric {@code suggests} 同义（来源同上）。
     *
     * <p><b>纯元数据：加载器不读取、不校验、不告警</b>（Fabric 原文「Use this as a kind of
     * metadata」）。声明在此仅为格式完整与后续可能的 UI 展示；解析器有意跳过它。</p>
     */
    public Map<String, String> suggests;

    /**
     * 别名 id（可选）：本 mod 也"以这些 id 存在"。Fabric {@code provides} 同义
     * （语义来源：Fabric Wiki {@code fabric.mod.json} › Mod loading，
     * https://wiki.fabricmc.net/documentation:fabric_mod_json ，2026-10-14 抓取：
     * "Defines the list of ids of mod. It can be seen as the aliases of the mod. Fabric Loader
     * will treat these ids as mods that exist."）。</p>
     *
     * <p><b>语义</b>：① 其它 mod 的 {@code depends} / {@code breaks} / {@code conflicts} /
     * {@code recommends} 可以指向别名，由本 mod 满足，<b>版本 = 本 mod 的 {@code version}</b>；
     * ② 别名同样参与<b>依赖拓扑排序</b>（指向别名的依赖 = 对本 mod 的依赖，否则可能把依赖方
     * 排到提供者之前）。</p>
     *
     * <p><b>冲突处理（有意偏离 Fabric 的"静默不加载"）</b>：Fabric 原文说若别名与其它 mod 的
     * id 相撞，"they will not be loaded"；本加载器<b>不静默丢弃任何 mod</b>，改为加载期可读失败
     * （§3.5 / §4.5：失败要可读，不许降级成沉默）。两类冲突都报错：别名撞别的 mod 的自身 id、
     * 两个 mod 提供同一别名。</p>
     *
     * <p>别名 id 的格式约束与 {@link #id} 相同（{@code ModBootstrap.validate} 结构校验）。</p>
     */
    public List<String> provides;

    /**
     * 嵌套 jar（Jar-in-Jar，可选）：把第三方依赖打进本 mod jar 一起分发。
     *
     * <p>形态<b>沿用 Fabric Loader 的 {@code jars} 字段</b>（对象数组 + {@code file} 键），便于从
     * Fabric 迁移的 mod 作者直接复用写法：
     * <pre>{@code
     * "jars": [ { "file": "nested/vendor/dependency.jar" } ]
     * }</pre>
     *
     * <p><b>功能性字段，条目缺失即加载失败</b>（同 {@link #mixins} 口径，与展示性的
     * {@link #icon} 相反）：声明的路径在 mod jar 内不存在 = 加载期可读失败，不静默跳过。
     * 条目由 {@link NestedJarExtractor} 在装配期解出，并进入该 mod 的类加载范围
     * （{@link ModClassLoader} 的额外路径）。</p>
     *
     * <p><b>三运行时一致</b>：我们的 mod 在 S1/S2/S3 下都由<b>我们自己的</b>加载器处理
     * （宿主不认识 {@code brush.mod.json}），所以解包由我们完成、三个运行时同样生效 ——
     * 这与 Access Widener 的处境正相反（那个只能 S1 做，故被否决，见 docs/25 §5）。</p>
     */
    public List<NestedJar> jars;

    /** {@link ModMetadata#jars} 的一个条目（Fabric {@code jars[].file} 同形）。 */
    public static final class NestedJar {

        /** mod jar 内的条目路径（前导 {@code /} 会被忽略）。 */
        public String file;
    }

    /**
     * 入口点集合（对齐 Fabric 的 main / client / server 三键）。
     */
    public static final class Entrypoints {

        /**
         * 主入口类全名列表（必需、非空）：类须为 public 具体类 + public 无参构造器 +
         * 实现 {@code org.abstruck.brush.api.BrushModInitializer}。加载期实例化并调用
         * {@code onInitialize()}（每进程恰一次，游戏未就绪）。
         */
        public List<String> main;

        /**
         * 客户端入口类全名列表（可选）：类须为 public 具体类 + public 无参构造器 +
         * 实现 {@code BrushClientModInitializer}。客户端初始化时机（{@code client.run} 锚点）
         * 实例化并调用 {@code onInitializeClient()}——即在客户端实例就绪后、首个 tick
         * 与 {@code CLIENT_READY} 派发之前；类只在此时机被加载（可安全引用客户端类型）。
         */
        public List<String> client;

        /**
         * 专用服务端入口类全名列表（可选）：类须为 public 具体类 + public 无参构造器 +
         * 实现 {@code BrushDedicatedServerModInitializer}。
         *
         * <p><b>只做类名结构校验，类不会被加载、方法不会被调用</b> —— Brush 现有三运行时均为
         * 客户端（无服务端运行时），server 入口是 Fabric 语义下 dedicated 专用的契约占位
         * （见 {@code BrushDedicatedServerModInitializer} javadoc）。不加载也是有意的：
         * dedicated-only 类可能引用客户端不存在的类型。
         */
        public List<String> server;

        /**
         * 实机测试入口类全名列表（可选、可为空；提案 {@code brush-inworld-mod-test-hook-proposal}
         * §3 方案 A，JSON 键 = {@code entrypoints.test}）：类须为 public 具体类 + public 无参
         * 构造器 + 实现 {@code org.abstruck.brush.api.ModClientTest}。
         *
         * <p>仅 {@code --brush.mod-test} 运行中由加载器探针（{@code selftest.ModTestProbe}）在
         * {@code CLIENT_READY} 之后实例化并调用 {@code runClientTest(ClientTestContext)}；
         * 常规 / 自检 / 实机探针运行零加载、零调用（同 {@link #server} 的"客户端运行时不加载"口径）。
         *
         * <p><b>缺省不报错（向后兼容，提案 §3 / 任务要求 3）</b>：不声明 {@code test} 键（或空列表）
         * 的 mod 在本模式下不运行任何测试，加载照常。类名结构校验（匹配 {@code CLASS_NAME_PATTERN}）
         * 在 {@code ModBootstrap.validate} 执行；接口实现检查（{@code instanceof ModClientTest}）在
         * 探针实例化时执行（可读失败，不静默跳过 —— 同 {@code instantiateEntry} 口径）。
         */
        public List<String> test;
    }
}
