package org.abstruck.brush.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/**
 * 标题界面 "Mods" 按钮：20x20 方形按钮，内容 = brush.png 图标（16x16 居中）。
 *
 * <p><b>为什么自绘而不是 {@code SpriteIconButton}</b>：{@code SpriteIconButton} 的图标来自
 * 原版 gui 图集的 {@code Identifier} sprite，无法指向运行期从 brush.png 解码出的自有纹理
 * （{@link ModIconLoader}）。本类走 {@code AbstractButton}，实现其两个抽象方法
 * {@code onPress} 与 {@code extractContents}。
 *
 * <p><b>按钮背景</b>：基类 {@code extractWidgetRenderState}（final）只调 {@code extractContents}
 * + {@code handleCursor}，<b>不画任何背景</b>；背景由 {@code extractDefaultSprite}
 * （{@code protected final}）提供：{@code widget/button} / {@code widget/button_disabled} /
 * {@code widget/button_highlighted} 三态 sprite，按 {@code active} 与
 * {@code isHoveredOrFocused()} 选择 —— vanilla 的 {@code SpriteIconButton$CenteredIcon}
 * （辅助功能按钮实现）正是在 {@code extractContents} 首行调用它。本类照此复刻，观感与
 * 辅助功能按钮一致（含悬停高亮）。
 *
 * <p><b>绘制口径</b>：图标走
 * {@code blit(RenderPipeline, Identifier, x, y, u, v, w, h, texW, texH, color)}
 * （纹理按 Identifier 从 {@code TextureManager} 解析；{@link ModIconLoader} 上传时已注册），
 * 颜色 = {@code ARGB.white(getAlpha())} —— 与背景 sprite 同款，因此标题界面的淡入
 * （{@code Screen#fadeWidgets} → {@code AbstractWidget#setAlpha}）对本按钮的<b>背景与图标一并生效</b>
 * （2026-10-15 修复：旧实现用无颜色参数的 {@code GpuTextureView} 重载，图标不淡入）。
 * 纹理在按钮挂载前已由 {@code ModIconLoader.ensureDefaultIcon()} 预载（构造 + 上传需 GL 上下文，
 * 见其 javadoc），此处只读缓存。
 *
 * <p><b>图标缺失</b>：默认图标资源加载失败时（ModIconLoader 已告警）跳过绘制，按钮仍可点击
 * —— 功能不因素材缺失而消失。不设 tooltip；label 仍作为旁白 / 语义标签
 * （{@link #updateWidgetNarration}）保留。
 */
final class BrushIconButton extends AbstractButton {

    /** 方形按钮边长（与辅助功能按钮一致：{@code CommonButtons.accessibility(20, ...)} → 20x20）。 */
    static final int SIZE = 20;

    /** 图标绘制尺寸（默认图标 brush.png = 16x16）。 */
    private static final int ICON_SIZE = 16;

    /** 图标在按钮内的偏移（居中：(20-16)/2 = 2）。 */
    private static final int ICON_OFFSET = (SIZE - ICON_SIZE) / 2;

    BrushIconButton(int x, int y, Component label) {
        super(x, y, SIZE, SIZE, label);
        // 无 tooltip：见类 javadoc。
    }

    @Override
    public void onPress(InputWithModifiers input) {
        ModsTitleButton.openModsScreen();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // 原版按钮背景（三态 sprite，见类 javadoc）—— 必须自绘：基类不画背景。
        extractDefaultSprite(extractor);
        ModIconLoader.IconTexture icon = ModIconLoader.defaultIcon();
        if (icon == null) {
            return;
        }
        // 2026-10-15（用户报：标题界面淡入时本按钮的图标"直接显示"、不随其他控件淡入）：
        // 原版淡入 = Screen#fadeWidgets 遍历子控件调 AbstractWidget#setAlpha(float)，而 alpha **只能经
        // 绘制调用的颜色参数**传入（背景的 extractDefaultSprite 内部即 blitSprite(..., ARGB.white(alpha))，
        // 所以背景本来就淡入正常）。旧实现用 blit(GpuTextureView, GpuSampler, ...) —— 该重载**没有颜色
        // 参数**，alpha 被丢掉。这里改用带颜色的纹理重载（同 vanilla LogoRenderer 画 logo 的做法），
        // 纹理按 Identifier 从 TextureManager 解析（ModIconLoader.upload 已注册）。
        extractor.blit(RenderPipelines.GUI_TEXTURED, icon.id(),
                getX() + ICON_OFFSET, getY() + ICON_OFFSET,
                0.0f, 0.0f,
                ICON_SIZE, ICON_SIZE,
                icon.width(), icon.height(),
                ARGB.white(getAlpha()));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
