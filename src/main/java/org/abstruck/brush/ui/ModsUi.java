package org.abstruck.brush.ui;

import org.abstruck.brush.BrushModule;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模组列表界面入口：安装屏幕布局监听器，在<b>标题界面</b>挂一枚 "Mods" 按钮（点击打开
 * {@link ModsScreen}，列出 Brush 已加载的全部 mod）。
 *
 * <p><b>类加载边界（本类在引导期被加载，必须零 MC 类型引用）</b>：{@code ModsUi} 经
 * {@code EarlyBoot.boot()} 在 {@code Bootstrap#bootStrap} 期加载执行 —— 若其（或可达类的）
 * 方法体引用 MC 屏幕类，类校验/链接可能把 {@code Screen} / {@code TitleScreen} 的<b>加载时刻</b>
 * 提前到引导期。虽然三运行时的 mixin 配置在彼时均已注册（施加结果等价），但把 vanilla 类的
 * 加载时序无谓提前不是我们的目的。因此本类<b>不引用任何 {@code net.minecraft.*} 类型</b>；
 * 一切 MC 相关逻辑（按钮挂载 / 打开列表）在 {@link ModsTitleButton}，该类首个屏幕事件被调用时
 * 才加载 —— 而事件本身就发生在 {@code Screen.init(II)V} 内，彼时 {@code Screen} / {@code TitleScreen}
 * 必然已加载，时序零提前。
 *
 * <p><b>三运行时同一份实现</b>：不引用任何宿主类型（§9.3）—— S1/S2/S3 下行为一致。
 * 与宿主自带入口的关系：S3 的 NeoForge 在 patched TitleScreen 菜单列有自己的 Mods 按钮，
 * 我们的挂在其辅助功能按钮右侧（20x20 方形图标按钮，锚定运行时实例；该区域均无控件，互不
 * 遮挡）；S2 的 Fabric Loader 自身不带 mod 列表按钮。
 *
 * <p><b>挂载机制</b>：监听 L1 事件 {@link HookEvents#SCREEN_INIT} / {@link HookEvents#SCREEN_RESIZE}
 * （注入点 = {@code Screen#init(II)V} / {@code Screen#resize(II)V} 的 TAIL，见
 * {@code transform.mixin.ScreenMixin}）。事件在"该屏幕 widget 已就绪"的完成态派发；
 * 按钮只挂 {@code TitleScreen}（类型过滤在 {@link ModsTitleButton}）。
 *
 * <p><b>线程</b>：两条事件均在渲染线程触发（屏幕生命周期）。
 */
public final class ModsUi implements BrushModule {

    private static final Logger LOGGER = LogManager.getLogger(ModsUi.class);

    /** 布局事件累计（screen.init + screen.resize，含非标题界面）—— Selftest 信息项数据源。 */
    private static final AtomicLong LAYOUT_EVENTS = new AtomicLong();

    /** 按钮挂载次数累计（含 resize 后的重挂）—— Selftest 信息项数据源。 */
    private static final AtomicLong BUTTONS_ATTACHED = new AtomicLong();

    /** 默认图标状态：0 = 尚未尝试（TitleScreen init 晚于自检退出点时的正常态），1 = 已加载，2 = 失败。 */
    private static final int DEFAULT_ICON_NOT_YET = 0;
    private static final int DEFAULT_ICON_LOADED = 1;
    private static final int DEFAULT_ICON_FAILED = 2;

    /** 默认图标状态（Selftest 信息项 {@code ui.icons} 数据源；由 ModIconLoader 上报）。 */
    private static final AtomicInteger DEFAULT_ICON_STATE = new AtomicInteger(DEFAULT_ICON_NOT_YET);
    private static final AtomicInteger DEFAULT_ICON_WIDTH = new AtomicInteger();
    private static final AtomicInteger DEFAULT_ICON_HEIGHT = new AtomicInteger();

    /** 成功加载的 mod 自定义图标数（含失败后按 id 缓存的回退，不重复计数）。 */
    private static final AtomicLong MOD_ICONS_LOADED = new AtomicLong();

    /** 声明了 icon 但读取/解码失败、回退默认图的次数。 */
    private static final AtomicLong ICON_FALLBACKS = new AtomicLong();

    private static boolean installed;

    /** public 无参构造器：{@link BrushModule} SPI 经 {@code ServiceLoader} 实例化（Phase D）。 */
    public ModsUi() {
    }

    @Override
    public String name() {
        return "ui.mods";
    }

    /**
     * 安装（幂等）。调用方：{@code EarlyBoot.boot()} 经 {@link BrushModule} SPI（Phase D）——
     * 在 {@code CoreHooks.install()} 之后、任何屏幕存在之前（首屏布局必然晚于引导，注册无时序风险）。
     */
    @Override
    public void install() {
        // 幂等守卫为静态（跨实例有效）：ServiceLoader 每次 load 都会新建实例，守卫必须在类上。
        synchronized (ModsUi.class) {
            if (installed) {
                return;
            }
            installed = true;
        }
        Dispatcher.register(HookEvents.SCREEN_INIT, ModsUi::onScreenLayout);
        Dispatcher.register(HookEvents.SCREEN_RESIZE, ModsUi::onScreenLayout);
        LOGGER.info("[brush] mods UI: listeners installed on " + HookEvents.SCREEN_INIT
                + " / " + HookEvents.SCREEN_RESIZE + " (title screen button, ModsScreen on click)");
    }

    /**
     * 布局事件入口（两条事件共用）：计数后转交 {@link ModsTitleButton}（类加载边界见类 javadoc ——
     * 首个事件执行时才触发该类的加载）；屏幕类型过滤在彼处完成，按钮只挂标题界面。
     */
    private static void onScreenLayout(Object[] args) {
        LAYOUT_EVENTS.incrementAndGet();
        ModsTitleButton.onLayout(args);
    }

    /** 自检信息项（Selftest 报告 {@code ui.mods} 行）：布局事件累计。 */
    public static long layoutEvents() {
        return LAYOUT_EVENTS.get();
    }

    /** 自检信息项（Selftest 报告 {@code ui.mods} 行）：按钮挂载累计（含 resize 重挂）。 */
    public static long buttonsAttached() {
        return BUTTONS_ATTACHED.get();
    }

    /** 供 {@link ModsTitleButton} 上报一次挂载（包内调用）。 */
    static void onButtonAttached() {
        BUTTONS_ATTACHED.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // 图标计数（自检信息项 ui.icons；由 ModIconLoader 上报 —— 本类保持零 MC 类型）
    // ------------------------------------------------------------------

    /** 默认图标（brush.png）解码/上传成功（ModIconLoader 上报）。 */
    static void onDefaultIconLoaded(int width, int height) {
        DEFAULT_ICON_WIDTH.set(width);
        DEFAULT_ICON_HEIGHT.set(height);
        DEFAULT_ICON_STATE.set(DEFAULT_ICON_LOADED);
    }

    /** 默认图标加载失败（资源缺失 / 解码失败）。 */
    static void onDefaultIconFailed() {
        DEFAULT_ICON_STATE.set(DEFAULT_ICON_FAILED);
    }

    /** 一个 mod 的自定义图标加载成功。 */
    static void onModIconLoaded() {
        MOD_ICONS_LOADED.incrementAndGet();
    }

    /** 一个 mod 声明了 icon 但回退到默认图（条目缺失 / 不可读 / 解码失败）。 */
    static void onModIconFallback() {
        ICON_FALLBACKS.incrementAndGet();
    }

    /** 自检信息项（Selftest 报告 {@code ui.icons} 行）：默认图标状态摘要。 */
    public static String defaultIconState() {
        return switch (DEFAULT_ICON_STATE.get()) {
            case DEFAULT_ICON_LOADED ->
                    "loaded(" + DEFAULT_ICON_WIDTH.get() + "x" + DEFAULT_ICON_HEIGHT.get() + ")";
            case DEFAULT_ICON_FAILED -> "failed";
            default -> "not-yet";
        };
    }

    /** 自检信息项：已加载的 mod 自定义图标数。 */
    public static long modIconsLoaded() {
        return MOD_ICONS_LOADED.get();
    }

    /** 自检信息项：icon 回退默认图的次数。 */
    public static long iconFallbacks() {
        return ICON_FALLBACKS.get();
    }
}
