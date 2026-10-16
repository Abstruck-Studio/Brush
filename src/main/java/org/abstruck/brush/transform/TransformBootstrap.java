package org.abstruck.brush.transform;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.service.MixinExtrasVersion;
import org.abstruck.brush.BrushProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * 注入层游戏侧引导（docs/06 §5）。
 *
 * <p>本类被 {@code Brush}（应用侧）经反射调用：app 侧只看到 JDK 类型
 * （{@link BiFunction}&lt;String, byte[], byte[]&gt;），与游戏侧不共享任何自定义类型身份。</p>
 *
 * <p><b>与 MC 类的关系</b>：{@code install()} 期间全程不加载任何 {@code net.minecraft.*}
 * 类（mixin 配置此时为空），因此 app 侧可以在入口类被加载<b>之前</b>把返回的管线函数
 * 注册进 {@code BrushClassLoader}，满足"注入必须在类首次加载前完成"（CLAUDE.md §8.4）。</p>
 *
 * <p><b>快速排除（任务 1，§4-2）</b>：管线函数在进入 mixin 的
 * {@code transformClassBytes} 前做保守前缀判定（{@link FastExclusion}）——
 * 确定不含我们注入点的纯库 / JDK 类直接短路（原样返回），
 * 启动期数万次无效调用被消除。排除 / 放行前缀清单见 {@link FastExclusion} javadoc；
 * 开关 {@code -Dbrush.fast.exclusion=false} 可整体关闭（默认开启）；
 * 计数经 {@link #excludedCount()} / {@link #passedCount()} 读取，
 * 管线节流行（每 {@value #LOG_EVERY} 类）打印"排除命中 N / 放行 M"。
 * <b>footprint 不变</b>：这是加载期短路，不新增 / 不修改任何 vanilla 注入点
 * （18 点 / 13 类维持；CLAUDE.md §10.1 / §10.3）。</p>
 *
 * <p><b>调用顺序（顺序即契约，不可换）</b>：
 * <ol>
 *   <li>{@code MixinBootstrap.init()}：引导服务（ServiceLoader 选中 {@link BrushMixinService}）、
 *       把 transformer 工厂交给服务、初始化环境并下发 phaseConsumer（PREINIT 阶段）；</li>
 *   <li>{@code createTransformer()}：创建活动 transformer —— 构造函数会把自身注册为
 *       {@code MixinEnvironment} 的活动 transformer；</li>
 *   <li>{@code MixinExtrasBootstrap.init()}：<b>必须在第 2 步之后</b> —— 其内部经
 *       {@code MixinInternals.registerExtension} 访问
 *       {@code MixinEnvironment.getActiveTransformer()}，transformer 未创建时会空指针；</li>
 *   <li>{@code setSide(CLIENT)}：无平台 agent 时 {@code getSideName()} 为 UNKNOWN，
 *       显式设为客户端（side stripping 语义）；</li>
 *   <li>{@code gotoDefaultPhase()}：经 phaseConsumer 推进到 DEFAULT —— mixin 的
 *       {@code gotoPhase} 是包私有，宿主只能走这条通道（{@code MixinServiceModLauncher}
 *       同款做法）；</li>
 *   <li>{@code Mixins.addConfiguration(brush.mixins.json)}：注册注入点定义并<b>复核注册结果</b>
 *       （解析失败只会记 ERROR 并丢弃配置，不复核则 hook 会静默消失，见 {@link #registerMixinConfig()}）；</li>
 *   <li>{@link CoreHooks#install()}：注册核心 hook 监听器 —— 至此派发表可用；
 *       注册时机必须早于任何类经管线通过（本方法在管线注册之前执行，满足）。</li>
 * </ol>
 */
public final class TransformBootstrap {

    /**
     * 游戏侧日志（MC 自带 log4j，经 {@code log4j.configurationFile} 指向其 client 配置，
     * 见 docs/08-日志系统.md）。本类由 BrushClassLoader 加载，取到的即游戏侧 log4j；
     * 首次取用发生在 {@code Brush} 设置该属性之后（调用链顺序见类 javadoc）。
     */
    private static final Logger LOGGER = LogManager.getLogger(TransformBootstrap.class);

    /** 管线处理量日志节流（首类 + 每 N 类一行）。 */
    private static final long LOG_EVERY = 1000L;

    private static final AtomicLong TRANSFORM_COUNT = new AtomicLong();

    /** 快速排除计数（任务 1，§4-2）：短路掉的类 / 放进 mixin 管线的类。 */
    private static final AtomicLong EXCLUDED_COUNT = new AtomicLong();
    private static final AtomicLong PASSED_COUNT = new AtomicLong();

    private static volatile BiFunction<String, byte[], byte[]> pipeline;

    private TransformBootstrap() {
    }

    /**
     * 保守快速排除（任务 1，§4-2；设计 docs/24）：进入 mixin 管线前短路
     * "确定不含我们注入点"的类（启动期数万次无效 {@code transformClassBytes} 调用）。
     *
     * <p><b>正确性优先于性能</b>：排除规则保守且可审计——
     * <ul>
     *   <li><b>放行（白名单，必须进管线）</b>：{@code net.minecraft.*}（vanilla 注入目标，
     *       全部 18 注入点 / 13 类均在此前缀下）、{@code org.abstruck.brush.*}（我们自己）、
     *       宿主包（{@code net.fabricmc.*} / {@code net.neoforged.*} / {@code cpw.mods.*}）、
     *       mixin 与 ASM 工具库（{@code org.spongepowered.asm.*} / {@code com.llamalad7.mixinextras.*}
     *       / {@code org.objectweb.asm.*}）—— <b>以及所有其余未知命名空间</b>（mod 类、
     *       第三方库 —— 保守放行，宁可多送管线也不误伤；mod 自带 mixin 的目标类绝不能被排除）；</li>
     *   <li><b>排除（确定不含注入点的纯库 / JDK 前缀）</b>：{@code java.} / {@code javax.} /
     *       {@code jdk.} / {@code sun.} / {@code com.sun.} / {@code com.google.gson.} /
     *       {@code com.google.common.} / {@code com.mojang.} / {@code io.netty.} /
     *       {@code org.apache.logging.} / {@code org.lwjgl.} / {@code it.unimi.dsi.} /
     *       {@code org.slf4j.} / {@code kotlin.}。</li>
     * </ul>
     *
     * <p><b>开关</b>：系统属性 {@code brush.fast.exclusion}
     * （{@link BrushProperties#FAST_EXCLUSION}；默认开启；值 {@code "false"}
     * 整体关闭，全部类照送管线，零行为变化）。
     *
     * <p><b>日志</b>：计数经 {@link #excludedCount()} / {@link #passedCount()} 读取，
     * 启动日志（管线节流行）打印"排除命中 N / 放行 M"，供自检与人工证实无误伤。
     */
    public static final class FastExclusion {

        /** 排除前缀（JDK / 确定性纯第三方库；不含 vanilla 目标 / 我们自己的包 / 宿主包 / mod 类）。 */
        private static final String[] EXCLUDE_PREFIXES = {
                "java.",
                "javax.",
                "jdk.",
                "sun.",
                "com.sun.",
                "com.google.gson.",
                "com.google.common.",
                "com.mojang.",
                "io.netty.",
                "org.apache.logging.",
                "org.lwjgl.",
                "it.unimi.dsi.",
                "org.slf4j.",
                "kotlin.",
        };

        /** 放行前缀（vanilla 目标 / 我们自己 / 宿主 / mixin / ASM 工具库 —— 必须进管线）。 */
        private static final String[] PASS_PREFIXES = {
                "net.minecraft.",
                "org.abstruck.brush.",
                "org.spongepowered.asm.",
                "com.llamalad7.mixinextras.",
                "org.objectweb.asm.",
                "net.fabricmc.",
                "net.neoforged.",
                "cpw.mods.",
        };

        private FastExclusion() {
        }

        /** 是否启用快速排除（默认启用；{@code -Dbrush.fast.exclusion=false} 关闭）。属性名唯一来源：{@link BrushProperties#FAST_EXCLUSION}。 */
        public static boolean enabled() {
            return !"false".equals(System.getProperty(BrushProperties.FAST_EXCLUSION));
        }

        /**
         * 保守判定：该类是否<b>必须放行</b>（进 mixin 管线）。
         * 命中任一放行前缀 → true；命中任一排除前缀 → false；都不命中 → true（保守放行）。
         */
        public static boolean shouldPass(String className) {
            if (!enabled()) {
                return true;   // 开关关闭：全部照送
            }
            for (String p : PASS_PREFIXES) {
                if (className.startsWith(p)) {
                    return true;
                }
            }
            for (String p : EXCLUDE_PREFIXES) {
                if (className.startsWith(p)) {
                    return false;
                }
            }
            return true;   // 保守：未知命名空间一律放行（mod 类 / 第三方库不误伤）
        }
    }

    /** 快速排除：短路掉的类计数（供日志 / 自检读取）。 */
    public static long excludedCount() {
        return EXCLUDED_COUNT.get();
    }

    /** 快速排除：放进 mixin 管线的类计数（供日志 / 自检读取）。 */
    public static long passedCount() {
        return PASSED_COUNT.get();
    }

    /**
     * 引导 Mixin 栈并返回变换管线函数（幂等：重复调用返回同一函数）。
     *
     * @return {@code (dotClassName, classBytes) -> transformedBytes}；失败抛
     *         {@link IllegalStateException}（不吞异常，CLAUDE.md §3.5）
     */
    public static BiFunction<String, byte[], byte[]> install() {
        BiFunction<String, byte[], byte[]> existing = pipeline;
        if (existing != null) {
            return existing;
        }

        // 快速排除判定类（{@link FastExclusion}，本类的嵌套类）必须在此刻——管线回调注册之前——
        // 强制加载并初始化完毕。理由（2026-10-14 实测熔断，T1 exit != 0）：
        // 管线回调内部首次引用 FastExclusion 时，加载该类要经 BrushClassLoader.findClass
        // 重新进入回调 → 回调又引用 FastExclusion → 无限递归（StackOverflowError），
        // 且递归发生在放行前缀判定<b>之前</b>，org.abstruck.brush.* 的白名单来不及生效，
        // 任何类进来都必炸（S2/S3 不暴露：宿主加载器不走本回调）。
        // 此处安全的原因：Brush.java 在 install() 返回后才 addTransformer，故此刻 findClass
        // 不会触达管线；提前触碰一次即可把该类定型，后续回调内引用不再触发类加载。
        FastExclusion.enabled();

        // 必须是第一条语句：BrushMixinService.isValid() 依赖此标记在服务被选中前生效
        System.setProperty(BrushProperties.STANDALONE, "true");

        LOGGER.info("[brush] --- P2.1 mixin bootstrap (game-side) ---");
        LOGGER.info("[brush]   sponge-mixin core " + MixinBootstrap.VERSION
                + " (net.fabricmc fork 0.17.4), MixinExtras " + MixinExtrasVersion.LATEST);

        MixinBootstrap.init();
        IMixinService service = MixinService.getService();
        LOGGER.info("[brush]   mixin service selected: " + service.getName()
                + " (" + service.getClass().getName() + ")");

        if (!(service instanceof BrushMixinService brushService)) {
            throw new IllegalStateException(
                    "expected BrushMixinService but the mixin service loader selected "
                            + service.getClass().getName());
        }

        IMixinTransformer transformer = brushService.createTransformer();
        LOGGER.info("[brush]   active transformer: " + transformer.getClass().getName());

        MixinExtrasBootstrap.init();
        LOGGER.info("[brush]   MixinExtras bootstrap done (CHAINABLE injectors available)");

        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        brushService.gotoDefaultPhase();
        LOGGER.info("[brush]   environment: side=" + environment.getSide()
                + ", phase=" + environment.getPhase());

        registerMixinConfig();
        CoreHooks.install();
        LOGGER.info("[brush]   " + InjectionLedger.summary());

        BiFunction<String, byte[], byte[]> created = (name, basicClass) -> {
            if (basicClass == null) {
                return null;
            }
            // 快速排除（任务 1，§4-2）：保守短路"确定不含我们注入点"的类（纯库 / JDK 前缀），
            // 跳过其 transformClassBytes 调用（启动期数万次无效调用）；
            // vanilla / 我们自己的类 / 宿主 / mod 类 / 未知命名空间一律放行（保守，不误伤）。
            // 开关：-Dbrush.fast.exclusion=false 可整体关闭（见 FastExclusion javadoc）。
            if (FastExclusion.enabled() && !FastExclusion.shouldPass(name)) {
                EXCLUDED_COUNT.incrementAndGet();
                return basicClass;   // 原样返回（不进入 mixin 管线）
            }
            long count = TRANSFORM_COUNT.incrementAndGet();
            PASSED_COUNT.incrementAndGet();
            byte[] result;
            try {
                result = transformer.transformClassBytes(name, name, basicClass);
            } catch (Throwable t) {
                throw new IllegalStateException("[brush] mixin transformation failed for " + name, t);
            }
            if (count == 1 || count % LOG_EVERY == 0) {
                LOGGER.info("[brush] mixin pipeline: " + count
                        + " classes went through MixinTransformer (last: " + name + ")"
                        + "; fast-exclusion: excluded=" + EXCLUDED_COUNT.get()
                        + ", passed=" + PASSED_COUNT.get()
                        + (FastExclusion.enabled()
                                ? " (-D" + BrushProperties.FAST_EXCLUSION + "=false to disable)"
                                : ""));
            }
            return result;
        };
        pipeline = created;
        LOGGER.info("[brush]   transform pipeline ready (waiting for first class through BrushClassLoader)");
        return created;
    }

    /** 我们的 mixin 配置资源名（resources 根，经 BrushClassLoader 读取）。 */
    private static final String CONFIG_RESOURCE = "brush.mixins.json";

    /**
     * 配置资源名的**公开只读访问**（2026-10-15）。
     *
     * <p>用途：mixin 审计落盘需要把 {@code brush.mixins.json} 归属到加载器自身
     * （modid = {@code brush}）。此前该栏恒为 {@code from mod (unknown)} —— 落盘注释承诺了
     * {@code from mod <modid>} 而实现没给（现场：外部工程 Chickens-Brush 的实例，
     * 15 条记录 14 条 unknown，见 docs/27 §2.7.7）。</p>
     *
     * @return 核心 mixin 配置资源名
     */
    public static String configResource() {
        return CONFIG_RESOURCE;
    }

    /**
     * 注册我们的 mixin 配置（brush.mixins.json）。
     *
     * <p>注册动作与复核逻辑在 {@link MixinConfigRegistry}（三运行时共用 —— mod 自带 mixin
     * 配置走同一助手，docs/19 §3）；复核理由见该类 javadoc。时机：本方法在管线注册前执行，
     * 此时尚未有任何类被变换，待选队列中的新配置会在首次变换时被拾取并 prepare —— 早于目标类
     * {@code net.minecraft.client.Minecraft} 的加载。
     */
    private static void registerMixinConfig() {
        MixinConfigRegistry.registerAndVerify(CONFIG_RESOURCE);
    }
}
