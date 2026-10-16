package org.abstruck.brush.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.CreativeModeTab;

import org.abstruck.brush.api.BrushCreativeTab;
import org.abstruck.brush.api.BrushCreativeTabs;
import org.abstruck.brush.host.fabric.FabricCreativeTabCompat;
import org.abstruck.brush.transform.mixin.CreativeScreenAccess;
import org.abstruck.brush.transform.mixin.ScreenAccess;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 创造物品栏左侧面板的挂载与激活实现 —— {@link TabSidebar} 已经把 MC 相关逻辑全部下沉到本类
 * （类加载边界见其 javadoc：本类首个屏幕事件时才被加载）。
 *
 * <p><b>类型过滤</b>：L1 事件对<b>所有</b>屏幕派发（{@code HookEvents.SCREEN_INIT} javadoc：
 * 类型过滤是监听器职责），此处只处理 {@link CreativeModeInventoryScreen}，其余直接返回。
 * 非创造模式玩家打开物品栏时该屏的 {@code init()} 会自行换成 InventoryScreen，与本面板无关。</p>
 *
 * <p><b>幂等（先移除再添加）</b>：与 {@link ModsTitleButton} 同一时序论证 —— 重复
 * {@code Screen.init(II)V} 与 {@code Screen.resize(II)V} 都经 {@code repositionElements} →
 * {@code rebuildWidgets}（= clearWidgets + clearFocus + init() + setInitialFocus），widgets
 * 已被清空时 {@code removeWidget} 为空操作，未清空时移除防止重复挂载。缩放后面板随之重挂，
 * <b>页位随后承接</b>（旧面板的当前页经构造参数传入新面板，翻页位置不因 resize 丢失；见
 * {@link SidebarPanel#currentPage()}）。</p>
 *
 * <p><b>位置</b>：x = {@code leftPos - }{@value #COLUMN_INSET}（INSET=28：未选中主体右缘恰压
 * 容器左缘、无细缝；选中版右扩 3px 叠上容器 3px，"选中更贴近面板"与原版页签同语义）、
 * y = {@code topPos}。竖排 = 上按钮 {@value #UP_BUTTON_HEIGHT}px + 4×28 行 + 下按钮
 * {@value #DOWN_BUTTON_HEIGHT}px + 计数区 {@value #COUNTER_HEIGHT}：12 + 112 + 12 =
 * {@value #CREATIVE_IMAGE_HEIGHT} = 创造栏高恰好相等（按钮与行块直接相接、无收缝）。<b>自有
 * 页签 ≤ {@value #ROWS_PER_PAGE}（单页）时两端按钮不绘制</b>——行块钉在 {@value #ROWS_TOP}..
 * {@value #ROWS_BOTTOM} 槽位不重排（与"末页空槽不重排"同一语言），其上下 12px 带留空；多页时
 * 三态按钮照常。{@code leftPos/topPos} 无公开访问面，按 {@link #CREATIVE_IMAGE_WIDTH} 常量复算。
 * <b>不夹取到屏内</b>：贴附对齐优先，极窄窗口下面板可能局部出屏（纯视觉、不崩溃）。</p>
 *
 * <p><b>外部状态关联（§10.2：不给 vanilla 类加字段）</b>：屏幕实例 → 面板的对应关系存于本类
 * {@link WeakHashMap}（弱键）—— 值（SidebarPanel）不捕获屏幕，屏幕回收后条目自动消失。
 * <b>不使用任何 {@code @Unique} 字段</b>。另设静态 {@link #lastActivated}（见
 * {@link #restoreSelection} javadoc）。</p>
 *
 * <p><b>激活辅助（S3）</b>：{@link #activateTab}（面板行点击 → 构建内容 → <b>S2 补 Fabric
 * 分页页码</b>（{@code host.fabric.FabricCreativeTabCompat}，否则 Fabric 的 {@code selectTab}
 * 守卫会取消选中 / 屏幕下次 init 抛 page 缺失）→ {@code brush$selectTab} 选中桥实例 → 回读
 * 校验）与 {@link #isSelected}（逐帧选中判据，纹理变体选择）。选择经 {@link CreativeScreenAccess}
 * （{@code @Invoker}/{@code @Accessor} 接口 mixin，§10.4 首选，不计注入点）。</p>
 *
 * <p><b>线程</b>：仅在渲染线程经 {@link TabSidebar} 的事件入口 / 面板渲染 / 点击
 * （{@link SidebarPanel}）调用；WeakHashMap 与 {@link #lastActivated} 无并发访问。</p>
 */
final class TabSidebarMount {

    /** 页签纹理宽 = 面板宽（32×28 纹理 1:1；gui 图集 sprite）。 */
    static final int TAB_WIDTH = 32;

    /** 行高 = 纹理高（28px）。 */
    static final int ROW_HEIGHT = 28;

    /**
     * 创造屏 imageWidth / imageHeight。ACS.init 以它们计算
     * {@code leftPos=(width-imageWidth)/2}、{@code topPos=(height-imageHeight)/2}。
     */
    private static final int CREATIVE_IMAGE_WIDTH = 195;
    private static final int CREATIVE_IMAGE_HEIGHT = 136;

    /**
     * 列内水平缩进：{@code x = leftPos - COLUMN_INSET}。32 宽纹理右侧 3px 为透明贴合留白，
     * 未选中主体（行与端帽按钮同 x 区间 x4..x28）右缘 = {@code leftPos - COLUMN_INSET + 28}；
     * INSET=28 使主体右缘恰压上 {@code leftPos}（无细缝），选中版右扩 3px 叠上容器 3px
     * （"选中更贴近面板"与原版页签同语义）。
     */
    private static final int COLUMN_INSET = 28;

    /** 每页行数（4 行 = 112px）。 */
    static final int ROWS_PER_PAGE = 4;

    /** 上翻按钮纹理高（{@code tab_left_above}，32×12）。 */
    static final int UP_BUTTON_HEIGHT = 12;

    /** 下翻按钮纹理高（{@code tab_left_below}，32×12）。 */
    static final int DOWN_BUTTON_HEIGHT = 12;

    /** 页签块（第 0 行上缘）在面板局部坐标中的 y：上按钮高（12；按钮与行块直接相接）。 */
    static final int ROWS_TOP = UP_BUTTON_HEIGHT;                                       // 12

    /** 页签块下缘（面板局部）：ROWS_TOP + 4×28 = 124（= DOWN_BUTTON_TOP，按钮直接相接）。 */
    static final int ROWS_BOTTOM = ROWS_TOP + ROWS_PER_PAGE * ROW_HEIGHT;               // 124

    /** 堆叠总高 = 容器高（12 + 4×28 + 12 = 136，按钮与行块直接相接、无收缝）。 */
    static final int STACK_HEIGHT = CREATIVE_IMAGE_HEIGHT;                              // 136

    /** 下翻按钮顶（面板局部）：容器底 − 按钮高 = 124。 */
    static final int DOWN_BUTTON_TOP = CREATIVE_IMAGE_HEIGHT - DOWN_BUTTON_HEIGHT;      // 124

    /** 计数区顶（面板局部，堆叠之下）。 */
    static final int COUNTER_TOP = STACK_HEIGHT;                                        // 136

    /** 计数区高（堆叠下 24px 带）。 */
    static final int COUNTER_HEIGHT = 24;

    /** 面板总高：堆叠 136 + 计数区 24。 */
    static final int PANEL_HEIGHT = COUNTER_TOP + COUNTER_HEIGHT;                       // 160

    private static final Logger LOGGER = LogManager.getLogger(TabSidebarMount.class);

    /** 创造屏实例 → 我们挂载的面板（弱键；内存泄漏防护见类 javadoc）。 */
    private static final Map<Screen, SidebarPanel> PANELS = new WeakHashMap<>();

    /**
     * 用户最后一次经侧栏激活的自有页（还原逻辑的意图追踪；仅渲染线程读写）。
     *
     * <p>为什么不持久在屏幕实例上：宿主分页复位发生在 {@code init()} 期间，而 S3 下屏幕会被
     * 重建 / 重开，实例字段丢历史；静态字段同时覆盖"resize"与"重开创造屏"两种丢选场景。
     * 值仅在 {@link #activateTab} 成功时更新（用户显式点击 = 意图），经
     * {@link #observeFrame} 逐帧自愈（改点原版页签 / 选中被宿主取消时归位为 null）。</p>
     */
    private static BrushCreativeTab lastActivated;

    private TabSidebarMount() {
    }

    /** 布局事件转交入口（{@link TabSidebar} 调用）：只对创造物品栏屏挂载面板，其余屏幕直接返回。 */
    static void onLayout(Object[] args) {
        if (args.length == 0 || !(args[0] instanceof CreativeModeInventoryScreen creative)) {
            return;
        }
        ScreenAccess access = (ScreenAccess) creative;
        SidebarPanel previous = PANELS.remove(creative);
        if (previous != null) {
            // 已被 rebuildWidgets 清空时为空操作（Screen.removeWidget 对缺席 widget 无副作用）
            access.brush$removeWidget(previous);
        }
        int leftPos = (creative.width - CREATIVE_IMAGE_WIDTH) / 2;
        int topPos = (creative.height - CREATIVE_IMAGE_HEIGHT) / 2;
        restoreSelection(creative);     // S3 宿主分页复位后的选中还原（S1/S2 为 no-op；见其 javadoc）
        List<BrushCreativeTab> tabs = BrushCreativeTabs.registeredTabs();
        if (tabs.isEmpty()) {
            // 空态：无自有页签则整个面板不存在
            if (previous == null) {
                LOGGER.info("[brush] tab sidebar: no owned tabs -- panel skipped");
            }
            return;
        }
        int x = leftPos - COLUMN_INSET;     // INSET=28：主体右缘压上容器左缘
        int y = topPos;                     // 堆叠顶 = 容器顶（12+4×28+12 = 136 = 容器高）
        int initialPage = previous == null ? 0 : previous.currentPage();    // resize 保留页位
        SidebarPanel panel = new SidebarPanel(x, y, tabs, initialPage);
        access.brush$addRenderableWidget(panel);
        PANELS.put(creative, panel);
        TabSidebar.onPanelMounted();
        if (previous == null) {
            // 每屏实例只记首挂（resize 重挂可连续高频，避免日志刷屏；挂载总量见 TabSidebar.panelsMounted）
            LOGGER.info("[brush] tab sidebar: panel attached to creative inventory at ("
                    + x + ", " + y + "), " + TAB_WIDTH + "x" + PANEL_HEIGHT
                    + ", rows=" + ROWS_PER_PAGE + "/page");
        }
    }

    /**
     * 逐帧选中态观测（{@link SidebarPanel#extractWidgetRenderState} 每帧调用一次）：
     * 把当前屏的活读选中态翻译为"意图页"—— 选中在原版页签上 = 用户离开了自有页（含宿主
     * 复位后的默认选中），归位为 {@code null}；选中在自有桥页上 = 维持对应意图。自愈语义见
     * {@link #lastActivated}。
     *
     * <p>无 GUI 打开 / 非创造屏时同样归位为 {@code null}（下一次开屏即无还原目标）——
     * 与"关屏即忘"的直觉一致。代价 = 每帧 1 次实例判定 + 1 次 O(≤页签数) 恒等比较，无分配。</p>
     */
    static void observeFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof CreativeModeInventoryScreen creative)) {
            lastActivated = null;
            return;
        }
        lastActivated = BrushCreativeTabs.tabOfBridge(
                ((CreativeScreenAccess) creative).brush$getSelectedTab());
    }

    /**
     * 选中还原（S3 下缩放丢选）。{@link #onLayout} 在屏幕重建后调用。
     *
     * <p><b>根因（NeoForge patched 字节码行为）</b>：NeoForge patched 的
     * {@code CreativeModeInventoryScreen#init()} 在开头做了分页守卫 —— 当前 {@code selectedTab}
     * 不在当前页可见页签集合（{@code currentPage.getVisibleTabs()}）内时，<b>把选中复位为当前页
     * 第一个页签</b>（NeoForge 引入分页后的固有行为）。resize 走
     * {@code repositionElements → rebuildWidgets → init()}，且 {@code selectedTab} 在 S3 patched
     * 版里不是可跨 init 存活的静态字段语义 —— 于是缩放后必丢选。我们的挂载点在
     * {@code Screen#init} TAIL（L1 {@code SCREEN_INIT}），晚于该复位，此刻还原即可。</p>
     *
     * <p><b>为什么 S1/S2 天然是 no-op</b>：{@code selectedTab} 是 vanilla 的<b>静态</b>字段
     * （{@code private static CreativeModeTab selectedTab}），init 不触碰它 ——
     * 还原前恒等检查（{@code already selected}）直接命中短路，不发任何 extra 调用，行为零扰动。</p>
     *
     * <p><b>还原被拒绝 / 失败</b>：宿主守卫取消选中（无异常）→ 一次 warn；构建 / 选中抛
     * Throwable → error 记录并保持宿主复位后的状态（不崩游戏）。两者都不清空
     * {@link #lastActivated}（下一次布局仍会再试；改点原版页签时由 {@link #observeFrame}
     * 归位）。</p>
     */
    private static void restoreSelection(CreativeModeInventoryScreen creative) {
        BrushCreativeTab wanted = lastActivated;
        if (wanted == null) {
            return;
        }
        CreativeModeTab bridge = wanted.peekVanilla();     // 只读探测；我们激活过的页必然已构建
        if (bridge == null || !bridge.shouldDisplay()) {
            return;
        }
        CreativeScreenAccess access = (CreativeScreenAccess) creative;
        if (access.brush$getSelectedTab() == bridge) {
            return;     // S1/S2：静态 selectedTab 跨 init 存活，vanilla 已保留选中 —— 零扰动短路
        }
        try {
            FabricCreativeTabCompat.prepareSelection(creative, bridge);     // S2 页码补写（S1/S3 no-op）
            access.brush$selectTab(bridge);
        } catch (Throwable failure) {
            LOGGER.error("[brush] tab sidebar: re-selection of tab " + wanted.id()
                    + " after screen layout failed (content build or selection)", failure);
            return;
        }
        if (access.brush$getSelectedTab() != bridge) {
            LOGGER.warn("[brush] tab sidebar: re-selection of tab " + wanted.id()
                    + " after screen layout was refused by the host; nothing re-selected");
        } else {
            LOGGER.info("[brush] tab sidebar: re-selected tab " + wanted.id()
                    + " after screen layout (host pagination reset the selection)");
        }
    }

    /**
     * 激活一个自有标签页（{@link SidebarPanel} 行点击调用）：
     * 以当前玩家环境构建内容（{@link BrushCreativeTab#buildContents} —— 活动集合 clear +
     * 经公开集合访问面全量重填）→ <b>S2 补 Fabric 分页页码</b>
     * （{@link FabricCreativeTabCompat#prepareSelection}；S1/S3 no-op）→ 经
     * {@code CreativeScreenAccess#brush$selectTab} 选中桥实例（vanilla {@code selectTab} 的
     * {@code Type.CATEGORY} 分支以该标签页 {@code getDisplayItems()} 填充菜单）→ 回读
     * {@code brush$getSelectedTab} 确认选中未被宿主守卫取消。成功后记入 {@link #lastActivated}
     * （还原逻辑的意图来源；失败不记 —— 意图只在选中真正生效时成立）。
     *
     * <p>环境取值与 vanilla {@code CreativeModeInventoryScreen} 自身刷新的取值同源：
     * {@code player.connection.enabledFeatures()} / {@code brush$hasPermissions(player)}
     * （= vanilla 静态 {@code hasPermissions}）/ {@code player.level().registryAccess()}。</p>
     *
     * <p><b>异常策略</b>：mod 生成器、桥构建、Fabric 兼容（页码补写）或选中抛出的任何 Throwable
     * 在此<b>记录并返回 false</b>（不选中、不播音）—— 单次点击失败不应打崩整个游戏；构建期的
     * 硬性证据由自检探针承担（探针在同一个 {@code buildContents} 面上硬失败，见
     * {@code SelftestCreativeTabProbe}）。<b>选中被静默取消</b>（S2 下 Fabric 守卫对空页 /
     * 不可见页 {@code ci.cancel()}，无异常）由回读校验捕获并记 warn —— 同样返回 false，
     * 不让"点了没反应"无日志可查。</p>
     *
     * @return 是否成功完成"构建 + 选中"
     */
    static boolean activateTab(BrushCreativeTab tab) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof CreativeModeInventoryScreen creative) || minecraft.player == null) {
            LOGGER.warn("[brush] tab sidebar: cannot activate tab " + tab.id()
                    + " -- creative inventory screen or player not available");
            return false;
        }
        LocalPlayer player = minecraft.player;
        CreativeScreenAccess access = (CreativeScreenAccess) creative;
        CreativeModeTab bridge = tab.vanillaTab();
        try {
            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                    player.connection.enabledFeatures(),
                    access.brush$hasPermissions(player),
                    player.level().registryAccess()));
            FabricCreativeTabCompat.prepareSelection(creative, bridge);
            access.brush$selectTab(bridge);
        } catch (Throwable failure) {
            LOGGER.error("[brush] tab sidebar: activation of tab " + tab.id()
                    + " failed (content build or selection)", failure);
            return false;
        }
        if (access.brush$getSelectedTab() != bridge) {
            LOGGER.warn("[brush] tab sidebar: selection of tab " + tab.id() + " was refused by the"
                    + " host (selectTab cancel -- e.g. Fabric's isTabVisible guard on an empty or"
                    + " paginated-away tab); nothing selected");
            return false;
        }
        lastActivated = tab;    // 意图记录（仅成功路径；供 restoreSelection 于 S3 缩放 / 重开后还原）
        LOGGER.info("[brush] tab sidebar: activated tab " + tab.id());
        return true;
    }

    /**
     * 逐帧选中判据（面板纹理变体选择）：活读 vanilla 静态 {@code selectedTab}，与桥实例直比对 ——
     * 与 vanilla 自身刷新循环的比对同口径（{@code tab == selectedTab} 静态直比对）。桥未构建
     * （{@link BrushCreativeTab#peekVanilla()} = {@code null}，只读探测、不触发构建）或当前
     * 非创造屏 = false。
     */
    static boolean isSelected(BrushCreativeTab tab) {
        CreativeModeTab vanilla = tab.peekVanilla();
        if (vanilla == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof CreativeModeInventoryScreen creative)) {
            return false;
        }
        return ((CreativeScreenAccess) creative).brush$getSelectedTab() == vanilla;
    }
}
