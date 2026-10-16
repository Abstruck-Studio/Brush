package org.abstruck.brush.ui;

import org.abstruck.brush.BrushModule;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 左侧标签栏入口：安装屏幕布局监听器，在<b>创造物品栏屏幕</b>左侧挂载自绘面板
 * （竖列页签行 + 翻页 + 点击激活）。
 *
 * <p><b>零新增注入点</b>：完全复用模组列表界面已建的
 * {@link HookEvents#SCREEN_INIT} / {@link HookEvents#SCREEN_RESIZE} 注入点（注入面零变化，
 * §10.5"新增前先评估能否复用已有注入点"的复用实践）；挂载经 {@code ScreenAccess}
 * （{@code @Invoker} 接口 mixin，§10.4 首选，不计注入点）；渲染与指针路由由 vanilla widget
 * 框架承担（{@code Screen.extractRenderState} 遍历 renderables；
 * {@code ContainerEventHandler.mouseClicked} 经 {@code getChildAt} 命中即消费）。
 *
 * <p><b>类加载边界（本类在引导期被加载，必须零 MC 类型引用）</b>：语义与 {@link ModsUi}
 * 相同 —— 一切 {@code net.minecraft.*} 逻辑下沉到 {@link TabSidebarMount}（首个屏幕事件
 * 被调用时才加载）。
 *
 * <p><b>三运行时同一份实现</b>：不引用任何宿主类型（§9.3）；S1/S2/S3 下行为一致
 * （宿主 Fabric / NeoForge 的翻页按钮在上下两行 tab 区域，我们在左侧，物理隔离；
 * 若未来发现宿主占用左侧，走 {@code HostAdapter} 能力探测隐藏）。
 *
 * <p><b>线程</b>：两条事件均在渲染线程触发（屏幕生命周期）。
 */
public final class TabSidebar implements BrushModule {

    private static final Logger LOGGER = LogManager.getLogger(TabSidebar.class);

    /** 布局事件累计（screen.init + screen.resize，含非创造屏）—— 自检信息项预留数据源。 */
    private static final AtomicLong LAYOUT_EVENTS = new AtomicLong();

    /** 面板挂载次数累计（含 resize 后的重挂）—— 自检信息项预留数据源。 */
    private static final AtomicLong PANELS_MOUNTED = new AtomicLong();

    private static boolean installed;

    /** public 无参构造器：{@link BrushModule} SPI 经 {@code ServiceLoader} 实例化（Phase D）。 */
    public TabSidebar() {
    }

    @Override
    public String name() {
        return "ui.tab-sidebar";
    }

    /**
     * 安装（幂等）。调用方：{@code EarlyBoot.boot()} 经 {@link BrushModule} SPI（Phase D）——
     * 在 {@code ModsUi.install()} 之后（services 文件顺序）、任何屏幕存在之前
     * （首屏布局必然晚于引导，注册无时序风险）。
     */
    @Override
    public void install() {
        // 幂等守卫为静态（跨实例有效）：ServiceLoader 每次 load 都会新建实例，守卫必须在类上。
        synchronized (TabSidebar.class) {
            if (installed) {
                return;
            }
            installed = true;
        }
        Dispatcher.register(HookEvents.SCREEN_INIT, TabSidebar::onScreenLayout);
        Dispatcher.register(HookEvents.SCREEN_RESIZE, TabSidebar::onScreenLayout);
        LOGGER.info("[brush] tab sidebar: listeners installed on " + HookEvents.SCREEN_INIT
                + " / " + HookEvents.SCREEN_RESIZE
                + " (creative inventory left panel; zero new injection points)");
    }

    /**
     * 布局事件入口（两条事件共用）：计数后转交 {@link TabSidebarMount}（类加载边界见类 javadoc）；
     * 屏幕类型过滤在彼处完成，面板只挂创造物品栏屏。
     */
    private static void onScreenLayout(Object[] args) {
        LAYOUT_EVENTS.incrementAndGet();
        TabSidebarMount.onLayout(args);
    }

    /** 自检信息项预留：布局事件累计。 */
    public static long layoutEvents() {
        return LAYOUT_EVENTS.get();
    }

    /** 自检信息项预留：面板挂载累计（含 resize 重挂）。 */
    public static long panelsMounted() {
        return PANELS_MOUNTED.get();
    }

    /** 供 {@link TabSidebarMount} 上报一次挂载（包内调用）。 */
    static void onPanelMounted() {
        PANELS_MOUNTED.incrementAndGet();
    }
}
