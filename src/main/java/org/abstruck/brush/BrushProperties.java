package org.abstruck.brush;

/**
 * 跨层系统属性常量中枢（桥接协议的唯一真源）。
 *
 * <p><b>为什么集中在这里</b>：这些属性是 app 侧 / 宿主适配 / 游戏侧 / UI 之间的跨层通信协议
 * （CLAUDE.md §9.3：只经 JDK 类型与系统属性通信，不共享自定义类型身份）。此前常量分散在
 * 各使用类内（{@code Selftest} / {@code TransformBootstrap} / {@code EarlyBoot} /
 * {@code RealityProbe}），使"自检工具类"沦为数十处生产代码的公共依赖。现全部集中到本类：
 * 根包、零逻辑、零静态副作用；任何包引用它都不形成反向依赖。</p>
 *
 * <p><b>类加载纪律</b>：全部为编译期常量 —— 引用在编译期内联进使用方，<b>不触发本类的运行时
 * 加载</b>。因此 app 侧（系统类加载器）与游戏侧（{@code BrushClassLoader} / 宿主加载器）
 * 引用同一组常量不会产生任何类加载器身份问题（与搬迁前 {@code Selftest} 常量同款语义）。</p>
 *
 * <p><b>审计</b>：禁止在代码中散写 {@code "brush.xxx"} 属性名字面量 ——
 * {@code grep -rn '"brush\.' src/main/java} 除本类外只应命中文件名（{@code brush.mod.json} /
 * {@code brush.mixins.json}）。新增跨层属性必须在此登记。</p>
 */
public final class BrushProperties {

    /** 自检开关（app 侧解析 {@code --brush.selftest} 后写入 {@code "true"}）。 */
    public static final String SELFTEST = "brush.selftest";

    /** 实机探针开关（app 侧解析 {@code --brush.reality} 后写入 {@code "true"}）。 */
    public static final String REALITY = "brush.reality";

    /**
     * mod 实机测试开关（app 侧解析 {@code --brush.mod-test} / {@code --brush.mod-test=<modId>}
     * 后写入）。值为 {@code "true"} = 运行全部已声明测试装置的 mod；值为具体 mod id = 只运行该
     * mod。游戏侧 {@code org.abstruck.brush.selftest.ModTestProbe} 读取本属性做 armed 判定与
     * 目标过滤（§mod-test，docs/improvements 提案）。与 {@link #SELFTEST} / {@link #REALITY}
     * 同款桥接约定（编译期常量，跨层只经系统属性通信，不共享自定义类型身份）。
     */
    public static final String MOD_TEST = "brush.mod.test";

    /**
     * 注入管线快速排除开关（默认开启；值 {@code "false"} 整体关闭 —— 全部类照送 mixin 管线，
     * 行为与关闭前逐字节一致）。读取方：{@code transform.TransformBootstrap.FastExclusion}
     * （设计 docs/24：保守前缀判定，vanilla / 宿主 / mod / 未知命名空间一律放行）。
     */
    public static final String FAST_EXCLUSION = "brush.fast.exclusion";

    /**
     * mixin 调试薄别名开关（P1-6，实现记录 docs/20 §6.3）：值为 {@code "true"} 时，
     * {@code launch.Brush}（S1 启动器 app 侧最早语句）把它翻译成上游 sponge-mixin 的
     * {@code mixin.debug.export}（变换后类转储到 {@code .mixin.out/}），并注册 JVM 退出钩子
     * 把注入台账（{@code transform.BrushAuditTrail} 的 onApply 记录）落盘到
     * {@code .mixin.out/brush-audit-trail.log}。仅 S1 启动器路径读取本属性；
     * S2/S3 下 Brush 不是入口，直接传上游 {@code -Dmixin.debug.export=true}。
     */
    public static final String MIXIN_DUMP = "brush.mixin.dump";

    /** standalone 标记：仅 {@code TransformBootstrap.install()} 设置它，宿主引导路径不会。 */
    public static final String STANDALONE = "brush.standalone";

    /**
     * 游戏目录桥接属性（宿主启动装置在子 JVM 参数中设置；锚点路径读取）。
     * mods 目录 = 本属性值 + {@code /brush-mods}（§11.8：与宿主 {@code mods/} 隔离）。
     */
    public static final String GAME_DIR = "brush.game.dir";

    /** MC 版本桥接属性（S1 由 app 侧从 version json 解析；S2/S3 由宿主适配从宿主 mod 元数据读取）。 */
    public static final String GAME_VERSION = "brush.game.version";

    /** 宿主标识桥接属性（宿主适配写入："fabric" / "neoforge"；未设置 = standalone 或未知）。 */
    public static final String HOST = "brush.host";

    /** 宿主版本桥接属性（如 Fabric Loader "0.19.5"；宿主适配写入）。 */
    public static final String HOST_VERSION = "brush.host.version";

    /** 宿主已加载 mod 清单桥接属性（"{@code id@version,id@version}"，含宿主自身与第三方 mod）。 */
    public static final String HOST_MODS = "brush.host.mods";

    /**
     * 已加载的 Brush mod 清单桥接属性（{@code "id@version,id@version"}）—— 由
     * {@code mod.ModBootstrap} 在加载成功（含入口实例化）后写入。
     *
     * <p><b>消费者</b>：崩溃报告段 {@code transform.BrushCrashReport}。它在 {@code transform}
     * 层，<b>不得</b>直接引用 {@code mod} 层类型（§9.3：跨层只经 JDK 类型与系统属性通信），
     * 故经本属性取值 —— 与 {@link #HOST_MODS} 同款桥接约定。</p>
     */
    public static final String LOADED_MODS = "brush.loaded.mods";

    /**
     * 加载器版本桥接属性（由 {@code mod.ModBootstrap} 写入，值 = 其 {@code loaderVersion()}）。
     * 与 {@link #LOADED_MODS} 同一条消费链（崩溃报告段）。
     */
    public static final String LOADER_VERSION = "brush.loader.version";

    private BrushProperties() {
    }
}
