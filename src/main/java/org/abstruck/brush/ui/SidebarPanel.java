package org.abstruck.brush.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import org.abstruck.brush.api.BrushCreativeTab;

import java.util.List;

/**
 * 创造栏左侧 Brush 侧栏面板：竖列页签行（纹理 + 图标 + 选中变体）+ 翻页按钮 + 页数计数。
 *
 * <p><b>数据源</b>：构造期快照（{@code TabSidebarMount} 传入 {@link org.abstruck.brush.api.BrushCreativeTabs#registeredTabs()}
 * 的注册序快照 —— 挂载发生于 {@code SCREEN_INIT}，mod 客户端入口早已执行完、注册集不再变化）。
 * 空快照不会出现（挂载方在空集时整个面板不挂载；此处仍留防御性早退）。</p>
 *
 * <p><b>竖排</b>：上按钮（{@code tab_left_above} 32×12）+ tab1..4
 * （每行 32×28 纹理 + 16×16 图标 {@code fakeItem} 居中）+ 下按钮（{@code tab_left_below} 32×12）
 * + 计数区；12 + 4×28 + 12 = 136 = 创造栏高恰好相等（无收缝，按钮与行块直接相接；单页时
 * 省略两端按钮，见下文"翻页与计数"）。
 * 行纹理恒为 {@code tab_left_middle}，选中走 {@code tab_left_middle_selected}
 * （"选中更贴近面板"与原版页签同语义）。全部 1:1 {@code blitSprite}
 * （{@code assets/brush/textures/gui/sprites/tab_left_*.png}，行高
 * {@value org.abstruck.brush.ui.TabSidebarMount#ROW_HEIGHT}px）。无 hover 底色（原版页签同款）——
 * 悬停行为 = 标题 tooltip + {@code POINTING_HAND} 光标。按钮纯纹理三态（不叠
 * ▲/▼ 字符；方向靠上下位置与圆角朝向区分；高亮 / 禁用由纹理变体表达）。</p>
 *
 * <p><b>翻页与计数</b>：按钮绘制于堆叠顶 / 底（面板局部 {@code 0..UP_BUTTON_HEIGHT} /
 * {@code DOWN_BUTTON_TOP..STACK_HEIGHT}），钉住两端、不随行数浮动；三态纹理 —— 常态
 * {@code tab_left_above} / {@code tab_left_below}、悬停高亮 {@code *_highlighted}（仅"多页且
 * 方向可用"时）、不可用 {@code *_disabled}（多页时的端点方向；禁用态无视悬停、不给手型）。
 * <b>自有页签 ≤ {@value org.abstruck.brush.ui.TabSidebarMount#ROWS_PER_PAGE}
 * （单页）时两端按钮不绘制</b>——行块钉在原位不重排（与"末页空槽不重排"
 * 同一槽位语言），单页下禁用纹理不再出现、多页端点方向仍用。计数区在堆叠之下
 * 24px 带，画 {@code n/m}（{@code textWithBackdrop}），仅页数 &gt; 1 时绘制。可见行数 =
 * {@value org.abstruck.brush.ui.TabSidebarMount#ROWS_PER_PAGE} 行/页；末页空槽不绘制任何内容。</p>
 *
 * <p><b>指针行为</b>：命中面板矩形一律<b>消费</b>（返回 true）—— 容器级路由经 {@code getChildAt}
 * 命中子项后一律消费，面板矩形内的按下不会落入
 * vanilla 槽位 / 外部投放逻辑（面板矩形覆盖按钮 / 计数区 / 列左缘空白 ——
 * 左侧空白点击若不消费会触发容器 outside-click 丢手持物品；当前堆叠重排后面板高 160）。点击分区
 * （面板局部 y）：上按钮 {@code [0, ROWS_TOP)} / 行块 {@code [ROWS_TOP, ROWS_BOTTOM)} /
 * 下按钮与计数区 {@code [ROWS_BOTTOM, PANEL_HEIGHT)}（单页时两端按钮区无绘制，
 * 点击照常消费、无动作）。行点击 →
 * {@link TabSidebarMount#activateTab(BrushCreativeTab)}（失败返回 false：异常已在其内记录、不选中；
 * 成功也不播音 —— 切换页签无声、仅翻页有声）；翻页按钮点击 → 改页
 * + {@code playDownSound}（仅实际改页时）。
 * 不调 {@code super.mouseClicked}（无 {@code setFocused}/{@code setDragging} 副作用）。
 * 释放未覆写（{@code mouseReleased} 默认行为）。</p>
 *
 * <p><b>渲染路径</b>：框架入口 {@code AbstractWidget.extractRenderState}
 * （public final）→ 本类 {@link #extractWidgetRenderState}；widget 经
 * {@code Screen.extractRenderState} 的 renderables 遍历绘制，且
 * {@code AbstractContainerScreen.extractContents} 先调用基类（= widget 先于容器内容绘制，
 * 平移变换之前，坐标系即屏幕坐标）。每帧首行先调 {@link TabSidebarMount#observeFrame()}（
 * 选中态观测，还原逻辑的意图追踪）。</p>
 */
final class SidebarPanel extends AbstractWidget {

    /** 旁白 / 标记文本（字面量：三运行一致、与语言环境无关）。 */
    private static final Component PANEL_LABEL = Component.literal("TAB");

    // ---- 页签行纹理（行恒 middle，选中走 selected 变体；gui 图集 sprite，
    //      文件 assets/brush/textures/gui/sprites/）----
    private static final Identifier TAB_MIDDLE = Identifier.fromNamespaceAndPath("brush", "tab_left_middle");
    private static final Identifier TAB_MIDDLE_SELECTED =
            Identifier.fromNamespaceAndPath("brush", "tab_left_middle_selected");

    // ---- 翻页按钮纹理（端帽三态，32×12，替代原版 button 九宫格 + ▲/▼ 字符占位）----
    private static final Identifier PAGE_UP_TEXTURE =
            Identifier.fromNamespaceAndPath("brush", "tab_left_above");
    private static final Identifier PAGE_UP_HIGHLIGHTED =
            Identifier.fromNamespaceAndPath("brush", "tab_left_above_highlighted");
    private static final Identifier PAGE_UP_DISABLED =
            Identifier.fromNamespaceAndPath("brush", "tab_left_above_disabled");
    private static final Identifier PAGE_DOWN_TEXTURE =
            Identifier.fromNamespaceAndPath("brush", "tab_left_below");
    private static final Identifier PAGE_DOWN_HIGHLIGHTED =
            Identifier.fromNamespaceAndPath("brush", "tab_left_below_highlighted");
    private static final Identifier PAGE_DOWN_DISABLED =
            Identifier.fromNamespaceAndPath("brush", "tab_left_below_disabled");

    /** 行内图标 x（32 宽纹理内水平居中：16×16 图标 → 偏移 8）。 */
    private static final int ICON_X = (TabSidebarMount.TAB_WIDTH - 16) / 2;

    /** 行内图标 y（28 高行内垂直居中）。 */
    private static final int ICON_Y = (TabSidebarMount.ROW_HEIGHT - 16) / 2;

    /** 文本色（计数，白）。 */
    private static final int COLOR_TEXT = 0xFFFFFFFF;

    /** 挂载期快照（注册序即展示序；见类 javadoc"数据源"）。 */
    private final List<BrushCreativeTab> tabs;

    /** 当前页（0 基；resize 重建时经构造参数承接旧页位，见 {@link #currentPage()}）。 */
    private int page;

    SidebarPanel(int x, int y, List<BrushCreativeTab> tabs, int initialPage) {
        super(x, y, TabSidebarMount.TAB_WIDTH, TabSidebarMount.PANEL_HEIGHT, PANEL_LABEL);
        this.tabs = tabs;
        this.page = Math.min(Math.max(0, initialPage), pageCount() - 1);
    }

    /** 当前页（0 基；{@code TabSidebarMount.onLayout} 在 resize 重挂时读出并承接）。 */
    int currentPage() {
        return page;
    }

    /** 总页数（非空快照；空快照防御 = 1，与"无行不画控件"配套）。 */
    private int pageCount() {
        return Math.max(1, (tabs.size() + TabSidebarMount.ROWS_PER_PAGE - 1) / TabSidebarMount.ROWS_PER_PAGE);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!this.visible || tabs.isEmpty()) {
            return;
        }
        // 选中态逐帧观测（还原逻辑的意图追踪；见 TabSidebarMount#observeFrame javadoc）
        TabSidebarMount.observeFrame();
        Font font = Minecraft.getInstance().font;
        // 多页时先画上 / 下按钮（堆叠两端），再画行块（12/12 无收缝，互不重叠）
        // 自有页签 ≤ ROWS_PER_PAGE（单页）不绘制翻页键 —— 不占位、不用禁用纹理；
        // 行块槽位固定，与单页与否无关（见下方循环）
        if (pageCount() > 1) {
            drawPageButtons(extractor, mouseX, mouseY);
        }
        int first = page * TabSidebarMount.ROWS_PER_PAGE;
        int last = Math.min(tabs.size(), first + TabSidebarMount.ROWS_PER_PAGE);
        for (int index = first; index < last; index++) {
            BrushCreativeTab tab = tabs.get(index);
            int rowY = getY() + TabSidebarMount.ROWS_TOP
                    + (index - first) * TabSidebarMount.ROW_HEIGHT;
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED,
                    TabSidebarMount.isSelected(tab) ? TAB_MIDDLE_SELECTED : TAB_MIDDLE,
                    getX(), rowY, TabSidebarMount.TAB_WIDTH, TabSidebarMount.ROW_HEIGHT);
            ItemStack icon = tab.iconStack();
            if (icon != null && !icon.isEmpty()) {
                extractor.fakeItem(icon, getX() + ICON_X, rowY + ICON_Y);
            }
            if (isRowHovered(mouseX, mouseY, index - first)) {
                // 原版页签同款悬停行为：标题提示 + 手型光标（无 hover 底色）
                extractor.setTooltipForNextFrame(font, tab.title(), mouseX, mouseY);
                extractor.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
        drawCounter(extractor, font);
    }

    private boolean isRowHovered(int mouseX, int mouseY, int rowInPage) {
        int rowY = getY() + TabSidebarMount.ROWS_TOP + rowInPage * TabSidebarMount.ROW_HEIGHT;
        return mouseX >= getX() && mouseX < getX() + TabSidebarMount.TAB_WIDTH
                && mouseY >= rowY && mouseY < rowY + TabSidebarMount.ROW_HEIGHT;
    }

    /**
     * 翻页按钮绘制（纯纹理端帽三态 —— 上 {@code tab_left_above} 钉堆叠顶、下
     * {@code tab_left_below} 钉堆叠底，不叠字符、不随行数浮动）。<b>仅多页时由调用方调起</b>
     * （单页不绘制）；不可用（已到端点）→ {@code *_disabled}；可用 + 悬停 →
     * {@code *_highlighted}；其余 → 常态。禁用态无视悬停；仅可用方向悬停给手型光标。
     */
    private void drawPageButtons(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        // 调用方保证 pages > 1，端点判据据此简化
        int pages = pageCount();
        boolean upEnabled = page > 0;
        boolean downEnabled = page < pages - 1;
        boolean upHovered = upEnabled && isInZone(mouseX, mouseY, 0, TabSidebarMount.ROWS_TOP);
        boolean downHovered = downEnabled
                && isInZone(mouseX, mouseY, TabSidebarMount.ROWS_BOTTOM, TabSidebarMount.STACK_HEIGHT);
        extractor.blitSprite(RenderPipelines.GUI_TEXTURED,
                !upEnabled ? PAGE_UP_DISABLED : upHovered ? PAGE_UP_HIGHLIGHTED : PAGE_UP_TEXTURE,
                getX(), getY(), TabSidebarMount.TAB_WIDTH, TabSidebarMount.UP_BUTTON_HEIGHT);
        extractor.blitSprite(RenderPipelines.GUI_TEXTURED,
                !downEnabled ? PAGE_DOWN_DISABLED : downHovered ? PAGE_DOWN_HIGHLIGHTED : PAGE_DOWN_TEXTURE,
                getX(), getY() + TabSidebarMount.DOWN_BUTTON_TOP,
                TabSidebarMount.TAB_WIDTH, TabSidebarMount.DOWN_BUTTON_HEIGHT);
        if (upHovered || downHovered) {
            extractor.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    /** 页数计数绘制（堆叠之下 24px 带内居中 {@code n/m}；仅多页时）。 */
    private void drawCounter(GuiGraphicsExtractor extractor, Font font) {
        int pages = pageCount();
        if (pages <= 1) {
            return;
        }
        String counter = (page + 1) + "/" + pages;
        int textWidth = font.width(counter);
        int textY = getY() + TabSidebarMount.COUNTER_TOP
                + (TabSidebarMount.COUNTER_HEIGHT - font.lineHeight) / 2;
        extractor.textWithBackdrop(font, Component.literal(counter),
                getX() + (TabSidebarMount.TAB_WIDTH - textWidth) / 2, textY, textWidth, COLOR_TEXT);
    }

    /** 面板局部 y 带 {@code [zoneTop, zoneBottom)} 内且 x 在列宽内（hover / 光标判定）。 */
    private boolean isInZone(double mouseX, double mouseY, int zoneTop, int zoneBottom) {
        return mouseX >= getX() && mouseX < getX() + TabSidebarMount.TAB_WIDTH
                && mouseY >= getY() + zoneTop && mouseY < getY() + zoneBottom;
    }

    private boolean isPanelHovered(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 见类 javadoc"指针行为"：不调 super（无点击音、无 onClick、无焦点/拖拽副作用）。
        if (!this.visible || !this.active) {
            return false;
        }
        if (!isPanelHovered(event.x(), event.y())) {
            return false;   // 面板之外：不消费，交给容器路由
        }
        if (event.button() != 0 || tabs.isEmpty()) {
            return true;    // 面板内一律消费（非左键无动作；空快照无行）
        }
        int localY = (int) event.y() - getY();      // 面板局部 y（分区基准）
        int pages = pageCount();
        if (localY < TabSidebarMount.ROWS_TOP) {                     // 上按钮区（单页不绘制、仍消费）
            if (pages > 1 && page > 0) {
                page--;
                playDownSound(Minecraft.getInstance().getSoundManager());
            }
            return true;
        }
        if (localY >= TabSidebarMount.ROWS_BOTTOM) {                 // 下按钮 / 计数区
            if (pages > 1 && page < pages - 1 && localY < TabSidebarMount.STACK_HEIGHT) {
                page++;
                playDownSound(Minecraft.getInstance().getSoundManager());
            }
            return true;
        }
        int index = page * TabSidebarMount.ROWS_PER_PAGE
                + (localY - TabSidebarMount.ROWS_TOP) / TabSidebarMount.ROW_HEIGHT;
        if (index < tabs.size()) {
            TabSidebarMount.activateTab(tabs.get(index));   // 切换页签无声（仅翻页播音）
        }
        return true;    // 其余区域（计数区 / 禁用按钮 / 列左缘空白）：消费、无动作（见类 javadoc）
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
