package org.abstruck.brush.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.abstruck.brush.mod.ModBootstrap;
import org.abstruck.brush.transform.mixin.ScreenAccess;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 标题界面 "Mods" 按钮的挂载实现 —— {@link ModsUi} 已经把 MC 相关逻辑全部下沉到本类
 * （类加载边界见 {@code ModsUi} javadoc：本类首个屏幕事件时才被加载，彼时 {@code Screen} /
 * {@code TitleScreen} 必然已加载）。
 *
 * <p><b>位置与形态</b>：20x20 方形图标按钮（{@link BrushIconButton}，自绘 brush.png 图标，
 * 与辅助功能按钮同款观感），挂在<b>辅助功能按钮右侧</b>（+4px 间距，同一行）。位置<b>锚定到
 * 运行时的辅助功能按钮实例</b>而非硬编码坐标 —— 该按钮在三运行时均存在但所在行不同，锚定
 * 实例对布局差异天然免疫。图标 = {@code src/main/resources/assets/brush/textures/gui/icon.png}
 * （随资源打包，运行期由 {@link ModIconLoader} 解码为纹理；挂载前预载，失败时按钮仍可点击）。
 *
 * <p><b>锚点查找</b>：先按文案精确匹配（辅助功能按钮文案 = {@code options.accessibility}）；
 * 失配则回退"行内最右的 {@code SpriteIconButton}"（vanilla 布局下即辅助功能按钮）；两者都
 * 找不到时回退旧版右上角位置并在日志记明 —— 按钮永不静默消失。
 *
 * <p><b>幂等（先移除再添加）</b>：重复的 {@code Screen.init(II)V} 与 {@code Screen.resize(II)V}
 * 都经 {@code repositionElements} → {@code rebuildWidgets}（= clearWidgets + clearFocus +
 * init() + setInitialFocus）。因此每次事件都"移除旧按钮（若在）→ 重新添加"：清空已发生时移除为
 * 空操作（{@code Screen.removeWidget} 对缺席 widget 无副作用），未清空时移除防止重复挂载 ——
 * 两种时序下都恰好一枚按钮；缩放后按钮随辅助功能按钮重排而归位。
 *
 * <p><b>外部状态关联（§10.2：不给 vanilla 类加字段）</b>：屏幕实例 → 按钮的对应关系存于
 * 本类的 {@link WeakHashMap}（弱键）。值（Button）的点击回调不捕获屏幕（lambda 体仅调用
 * 本类静态方法），不存在"值强引键"的泄漏路径；屏幕被回收后条目自动消失。
 *
 * <p><b>线程</b>：仅在渲染线程经 {@link ModsUi} 的事件入口调用；WeakHashMap 无并发访问。
 */
final class ModsTitleButton {

    /** 按钮文案（字面量：Brush 不带语言文件，三运行时一致、与宿主语言环境无关）；作为旁白 / 语义标签。 */
    private static final Component BUTTON_LABEL = Component.literal("Mods");

    /** 与辅助功能按钮的间距（与原版行内相邻控件间距一致）。 */
    private static final int ANCHOR_GAP = 4;

    /** 锚点缺失时的回落位置（右上角）边距。 */
    private static final int FALLBACK_MARGIN = 4;

    /** 辅助功能按钮文案（辅助功能按钮所用翻译键）。 */
    private static final Component ACCESSIBILITY_LABEL = Component.translatable("options.accessibility");

    private static final Logger LOGGER = LogManager.getLogger(ModsTitleButton.class);

    /** 标题界面实例 → 我们挂载的按钮（弱键；内存泄漏防护见类 javadoc）。 */
    private static final Map<Screen, BrushIconButton> TITLE_BUTTONS = new WeakHashMap<>();

    private ModsTitleButton() {
    }

    /** 布局事件转交入口（{@link ModsUi} 调用）：只对标题界面挂载按钮，其余屏幕直接返回。 */
    static void onLayout(Object[] args) {
        if (args.length == 0 || !(args[0] instanceof TitleScreen titleScreen)) {
            return;
        }
        ScreenAccess access = (ScreenAccess) titleScreen;
        BrushIconButton previous = TITLE_BUTTONS.remove(titleScreen);
        if (previous != null) {
            // 已被 rebuildWidgets 清空时为空操作（Screen.removeWidget 对缺席 widget 无副作用）
            access.brush$removeWidget(previous);
        }
        // 预载默认图标（brush.png → 纹理）：构造/上传需渲染线程 + GL 上下文，此处即屏幕生命周期内；
        // 失败仅告警（按钮无图标但可点击，见 ModIconLoader）。
        ModIconLoader.ensureDefaultIcon();
        Button anchor = findAccessibilityButton(titleScreen);
        int x = anchor != null
                ? anchor.getX() + anchor.getWidth() + ANCHOR_GAP
                : titleScreen.width - FALLBACK_MARGIN - BrushIconButton.SIZE;
        int y = anchor != null ? anchor.getY() : FALLBACK_MARGIN;
        BrushIconButton button = new BrushIconButton(x, y, BUTTON_LABEL);
        access.brush$addRenderableWidget(button);
        TITLE_BUTTONS.put(titleScreen, button);
        ModsUi.onButtonAttached();
        if (previous == null) {
            // 每屏实例只记首挂（resize 重挂可连续高频，避免日志刷屏；挂载总量见 Selftest ui.mods 行）。
            // 锚点坐标一并入日志：与其他 mod 的按钮移动竞态（如 Mod Menu 的 shiftButtons）诊断时，
            // 本行与后续锚点位置对比即证据。
            LOGGER.info("[brush] mods UI: 'Mods' button attached to title screen at ("
                    + button.getX() + ", " + button.getY() + "), "
                    + BrushIconButton.SIZE + "x" + BrushIconButton.SIZE
                    + (anchor == null
                        ? " [fallback: accessibility anchor not found]"
                        : " [anchor=(" + anchor.getX() + ", " + anchor.getY() + ")]")
                    + " -- brush mods: " + ModBootstrap.loadedMods().size());
        }
    }

    /**
     * 查找辅助功能按钮（挂载锚点）：先按文案精确匹配；失配回退"行内最右的 {@code SpriteIconButton}"
     * （vanilla 布局下即辅助功能按钮）；再无则返回 {@code null}（调用方走右上角回落位置）。
     *
     * <p>文案比较经 {@code getString()}（按当前语言解析；两侧同键同语言，相等性不依赖
     * {@code Component.equals} 的实现细节）。扫描发生在移除旧按钮之后，不会锚定到我们自己的
     * 按钮。
     */
    private static Button findAccessibilityButton(TitleScreen titleScreen) {
        String accessibilityText = ACCESSIBILITY_LABEL.getString();
        Button rightmostIconButton = null;
        for (GuiEventListener child : titleScreen.children()) {
            if (!(child instanceof SpriteIconButton iconButton)) {
                continue;
            }
            if (accessibilityText.equals(iconButton.getMessage().getString())) {
                return iconButton;
            }
            if (rightmostIconButton == null || iconButton.getX() > rightmostIconButton.getX()) {
                rightmostIconButton = iconButton;
            }
        }
        return rightmostIconButton;
    }

    /** 按钮动作（{@link BrushIconButton#onPress} 调用入口）：以当前屏幕为父屏打开列表；关闭时返回父屏（{@code ModsScreen.onClose}）。 */
    static void openModsScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ModsScreen(minecraft.screen));
    }
}
