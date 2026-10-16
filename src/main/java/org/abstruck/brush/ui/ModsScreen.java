package org.abstruck.brush.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.mod.ModBootstrap;
import org.abstruck.brush.mod.ModMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * 已加载 mod 列表界面（标题界面 "Mods" 按钮打开）：每 mod 一张卡片，点击 morph 成整宽详情条
 * （手风琴式，同时至多展开一个）。整张列表是一个画布条目 —— 卡片与详情条由同一布局函数算出
 * 矩形，渲染时在起 / 讫两套布局间逐元素矩形插值（ease-in-out）：展开、收起、中途改点均为同一
 * 插值的特例，落点即稳态布局（零跳变）。内容区一切点击（含右键）由 {@link ModList#mouseClicked}
 * 消费（落到 vanilla 条目路径会画出黑底选中框），仅滚动条条带转交 vanilla；空白处左键 = 收起。
 */
public final class ModsScreen extends Screen {

    private static final int TITLE_Y = 12;
    private static final int SUBTITLE_Y = 26;
    private static final int LIST_TOP = 42;
    private static final int FOOTER_HEIGHT = 36;

    /** 网格：卡片边长 56（行高 60 = 卡片 56 + 上下留白 4）、卡片间距 8；自左缘起排布。 */
    private static final int CARD_SIZE = 56;
    private static final int CARD_GAP = 8;
    private static final int GRID_ROW_HEIGHT = CARD_SIZE + 4;

    /** 行盒相对内容盒的每侧留白：行高 = 内容高 + 2×本值（60=56+4、76=72+4）。数值恰等于 vanilla
     *  {@code Entry.CONTENT_PADDING}=2（内容盒 = 行盒每侧内缩 2）—— 绘制坐标一律取内容盒
     *  （{@code getContentX/Y()} 起算），行内不再二次内缩。 */
    private static final int CONTENT_INSET = 2;

    /** 卡片内容：图标 32x32 顶部居中（内容盒 y+4）；名称至多两行（自 y+38 起，行距 = font.lineHeight），
     *  最宽 48；第二行仍放不下时末尾以 "..." 截断。 */
    private static final int CARD_ICON_SIZE = 32;
    private static final int CARD_ICON_Y = 4;
    private static final int CARD_NAME_Y = 38;
    private static final int CARD_NAME_MAX_WIDTH = CARD_SIZE - 8;

    /** 展开条（完全展开态）：行高 76、内容盒高 72；图标 48x48（x+8，内容盒内垂直居中 → y+12）；
     *  右栏文本自 x+64、y+14 起，行距 11。以上均为"完全展开"锚点，动画中随形态系数 bar 插值。 */
    private static final int EXPANDED_HEIGHT = 76;
    private static final int BAR_CONTENT_HEIGHT = EXPANDED_HEIGHT - 2 * CONTENT_INSET;
    private static final int EXPANDED_ICON_SIZE = 48;
    private static final int EXPANDED_ICON_X = 8;
    private static final int EXPANDED_TEXT_X = 64;
    private static final int EXPANDED_TEXT_Y = 14;
    private static final int TEXT_RIGHT_MARGIN = 8;

    /** 展开条文本行距（内容盒 72：4 行 14..47，末行底 56，留白均衡）。 */
    private static final int LINE_HEIGHT = 11;

    /** 形态过渡时长（毫秒）：展开 / 收起 / 手风琴切换共用；插值曲线 ease-in-out（见 {@link #easeInOut}）。 */
    private static final int EXPAND_ANIM_MS = 200;

    /** 方块边框宽（1px：外框色 1px + 内底色）。 */
    private static final int BORDER_WIDTH = 1;

    /** 文本色（<b>必须全 ARGB</b>：alpha=0 的文本会被渲染层静默丢弃、画出"不可见文本"）。 */
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_GRAY = 0xFFA0A0A0;
    private static final int TEXT_ACCENT = 0xFFFFFF55;

    /** 卡片 / 展开条配色（边框 + 底色 + 悬停底色）。 */
    private static final int BOX_BORDER = 0xFF404040;
    private static final int BOX_FILL = 0xFF202020;
    private static final int BOX_FILL_HOVER = 0xFF303030;

    private final Screen parent;

    /** 当前展开的 mod id（null = 无展开；收起开始即置 null，见 {@link ModList#close}）。 */
    private String expandedId;

    private ModList list;

    /**
     * Brush 自身的条目（列表首位；元数据取自类路径 {@code /brush.mod.json}，与普通 mod 同格式
     * 同位置）：无 jar / 无入口类 —— 加载器本体不是 brush-mods/ 加载项，不进
     * {@code ModBootstrap.modEntries()}（核心与自检口径不动），仅在此显示层构造。
     */
    private static final ModBootstrap.ModEntry LOADER_SELF = buildLoaderSelf();

    /** 显示用条目 = {@link #LOADER_SELF} + {@code ModBootstrap.modEntries()}（{@link #init()} 每次重建）。 */
    private List<ModBootstrap.ModEntry> displayMods = List.of();

    private static ModBootstrap.ModEntry buildLoaderSelf() {
        // 加载器自身元数据与普通 mod 同格式同位置（jar 根 brush.mod.json，类路径读取）。
        // id/version 由 loaderMetadata() 的回退保证非 null；空值再防御一层仅为卡片渲染不崩
        // （卡片对 null 名称回退显示 id）。entryCount=0：加载器本体没有 mod 入口类。
        ModMetadata metadata = ModBootstrap.loaderMetadata();
        String id = metadata.id == null ? "brush" : metadata.id;
        String version = metadata.version == null ? "unknown" : metadata.version;
        return new ModBootstrap.ModEntry(id, metadata.name, version, metadata.description,
                metadata.authors == null ? List.of() : List.copyOf(metadata.authors),
                metadata.icon,
                metadata.depends == null ? List.of() : List.copyOf(metadata.depends.keySet()),
                null, 0);
    }

    public ModsScreen(Screen parent) {
        super(Component.literal("Loaded Mods"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 显示列表 = Brush 自身 + 已加载 mod（核心 modEntries()：按 mod 登记 ——
        // 多入口类 mod 合并为一张卡片；loadedMods() 按入口登记，仅用于生命周期投递）。
        List<ModBootstrap.ModEntry> loaded = ModBootstrap.modEntries();
        List<ModBootstrap.ModEntry> display = new ArrayList<>(loaded.size() + 1);
        display.add(LOADER_SELF);
        display.addAll(loaded);
        this.displayMods = List.copyOf(display);
        // 预载全部图标（默认 + 各 mod）：渲染线程 + GL 上下文就绪；绘制阶段只读缓存，不建纹理。
        ModIconLoader.ensureLoaded(this.displayMods);
        this.list = new ModList(this.minecraft, this.width, this.height - LIST_TOP - FOOTER_HEIGHT,
                LIST_TOP, GRID_ROW_HEIGHT);
        this.addRenderableWidget(this.list);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(this.width / 2 - 60, this.height - 28, 120, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // 先推进过渡动画（若有）：逐帧设置画布高度并重排，super 渲染即按当前插值布局绘制。
        this.list.tickAnimation();
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        extractor.centeredText(this.font, this.title, this.width / 2, TITLE_Y, TEXT_WHITE);
        extractor.centeredText(this.font, this.subtitle(), this.width / 2, SUBTITLE_Y, TEXT_GRAY);
    }

    /**
     * 列表外屏幕空白（标题带 / 页脚带）点击：同样收起当前展开（无展开时不消耗）。列表内由
     * {@link ModList#mouseClicked} 处理；Done 按钮由 super 分发。
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (this.list != null && this.expandedId != null) {
            this.list.close();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    /** 头部副标题：加载器版本 / 游戏版本 / 运行时 / mod 数（桥接属性真源 = {@link BrushProperties}）。 */
    private Component subtitle() {
        String host = System.getProperty(BrushProperties.HOST);
        String runtime = host == null || host.isBlank() ? "vanilla" : host;
        return Component.literal("Brush " + ModBootstrap.loaderVersion()
                + " | Minecraft " + System.getProperty(BrushProperties.GAME_VERSION, "unknown")
                + " | runtime: " + runtime
                + " | " + this.displayMods.size() + " brush mod(s)");
    }

    // ------------------------------------------------------------------
    // 布局模型（矩形插值动画的数据结构）
    // ------------------------------------------------------------------

    /**
     * 单个 mod 的形态矩形（内容盒坐标：x 自内容左缘、y 自内容顶）。
     *
     * @param bar 形态系数：0 = 正方形卡片、1 = 整宽详情条；中间值 = morph 中（框宽 / 图标 / 文本随其演化）
     */
    private record Cell(int x, int y, int w, int h, float bar) {}

    /** 全部 mod 的布局快照（cells 与 {@link #displayMods} 同序同长）+ 总高（= 画布条目高）。 */
    private record Layout(Cell[] cells, int height) {}

    private static Cell lerp(Cell a, Cell b, float t) {
        if (t <= 0.0f) {
            return a;
        }
        if (t >= 1.0f) {
            return b;
        }
        return new Cell(
                Math.round(a.x + (b.x - a.x) * t),
                Math.round(a.y + (b.y - a.y) * t),
                Math.round(a.w + (b.w - a.w) * t),
                Math.round(a.h + (b.h - a.h) * t),
                a.bar + (b.bar - a.bar) * t);
    }

    private static Layout lerp(Layout a, Layout b, float t) {
        if (a.cells.length != b.cells.length || t <= 0.0f) {
            return t <= 0.0f ? a : b;
        }
        if (t >= 1.0f) {
            return b;
        }
        Cell[] cells = new Cell[a.cells.length];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = lerp(a.cells[i], b.cells[i], t);
        }
        return new Layout(cells, Math.round(a.height + (b.height - a.height) * t));
    }

    /** 平滑进出（两端速度为零）：收起末端"滑入到位"而非跳变。 */
    private static float easeInOut(float t) {
        return t < 0.5f
                ? 4.0f * t * t * t
                : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 3) / 2.0f;
    }

    /**
     * 计算当前展开状态（{@link #expandedId}）下的稳态布局：按内容宽分组为网格行；展开的 mod
     * <b>不占卡片槽</b> —— 其前置卡片行先行收尾，该行位置放一整宽详情条（此后卡片换行排布）。
     *
     * <p>坐标一律为<b>内容盒相对</b>（原点 = 内容盒左上，见 {@link #CONTENT_INSET}）：行内容高
     * = 行高 - 2×CONTENT_INSET（卡片 56 / 详情条 72），故 cell 的 y 直接取行顶、h 取行内容高，
     * 2px 留白由 vanilla 内容盒内缩天然给出。
     *
     * @param contentWidth 内容盒宽（{@code Entry#getContentWidth()} = 行宽 - 4，构造期即可读）
     */
    private Layout layout(int contentWidth) {
        List<ModBootstrap.ModEntry> mods = this.displayMods;
        Cell[] cells = new Cell[mods.size()];
        int perRow = Math.max(1, (contentWidth + CARD_GAP) / (CARD_SIZE + CARD_GAP));
        int y = 0;
        int slot = 0;
        for (int i = 0; i < mods.size(); i++) {
            if (mods.get(i).id().equals(this.expandedId)) {
                if (slot > 0) {
                    y += GRID_ROW_HEIGHT;
                    slot = 0;
                }
                cells[i] = new Cell(0, y, contentWidth, BAR_CONTENT_HEIGHT, 1.0f);
                y += EXPANDED_HEIGHT;
            } else {
                cells[i] = new Cell(slot * (CARD_SIZE + CARD_GAP), y, CARD_SIZE, CARD_SIZE, 0.0f);
                slot++;
                if (slot >= perRow) {
                    y += GRID_ROW_HEIGHT;
                    slot = 0;
                }
            }
        }
        if (slot > 0) {
            y += GRID_ROW_HEIGHT;
        }
        return new Layout(cells, y);
    }

    // ------------------------------------------------------------------
    // 绘制基元
    // ------------------------------------------------------------------

    /** 画一张卡片：内容盒满高（56）+ 边框 + 图标 + 名称（悬停换底色；行级 hovered 参数不可用，逐卡判定）。 */
    private void extractCard(GuiGraphicsExtractor extractor, ModBootstrap.ModEntry mod, int x, int y, boolean hovered) {
        extractBox(extractor, x, y, x + CARD_SIZE, y + CARD_SIZE, hovered);
        ModIconLoader.IconTexture icon = ModIconLoader.forMod(mod);
        if (icon != null) {
            int iconX = x + (CARD_SIZE - CARD_ICON_SIZE) / 2;
            int iconY = y + CARD_ICON_Y;
            extractor.blit(icon.texture().getTextureView(), icon.texture().getSampler(),
                    iconX, iconY, iconX + CARD_ICON_SIZE, iconY + CARD_ICON_SIZE,
                    0.0f, 1.0f, 0.0f, 1.0f);
        }
        List<String> nameLines = this.cardNameLines(this.displayName(mod), CARD_NAME_MAX_WIDTH);
        int lineY = y + CARD_NAME_Y;
        for (String line : nameLines) {
            extractor.centeredText(this.font, Component.literal(line), x + CARD_SIZE / 2, lineY, TEXT_WHITE);
            lineY += this.font.lineHeight;
        }
    }

    /**
     * 变形态详情条（bar = 形态系数 0..1）：框宽 / 图标 / 文本随 bar 从"卡片形态"演化到"完全展开形态"。
     * 展开（bar 0→1）：框变宽、图标 32→48 并向左漂移、文本在后半程淡入且被"当前框右缘"裁切
     * —— 观感是"框把文字让出来"；收起反向（先淡出文本再收框）。完全展开态的版面锚点见常量区注释。
     */
    private void extractMorph(GuiGraphicsExtractor extractor, ModBootstrap.ModEntry mod,
            int x, int y, int w, int h, float bar) {
        extractBox(extractor, x, y, x + w, y + h, false);

        ModIconLoader.IconTexture icon = ModIconLoader.forMod(mod);
        if (icon != null) {
            int iconSize = Math.round(CARD_ICON_SIZE + (EXPANDED_ICON_SIZE - CARD_ICON_SIZE) * bar);
            int cardIconX = x + (CARD_SIZE - CARD_ICON_SIZE) / 2;
            int iconX = Math.round(cardIconX + (x + EXPANDED_ICON_X - cardIconX) * bar);
            int cardIconY = y + CARD_ICON_Y;
            int expandedIconY = y + (h - EXPANDED_ICON_SIZE) / 2;
            int iconY = Math.round(cardIconY + (expandedIconY - cardIconY) * bar);
            extractor.blit(icon.texture().getTextureView(), icon.texture().getSampler(),
                    iconX, iconY, iconX + iconSize, iconY + iconSize,
                    0.0f, 1.0f, 0.0f, 1.0f);
        }

        int textX = x + EXPANDED_TEXT_X;
        int lineClip = x + w - TEXT_RIGHT_MARGIN - textX;
        float textFade = (bar - 0.5f) / 0.5f;
        if (textFade <= 0.0f || lineClip < 16) {
            return;
        }
        int alpha = Math.round(255 * Math.min(1.0f, textFade));
        int lineY = y + EXPANDED_TEXT_Y;

        // L1：名称（白）+ 版本（黄）——名称按版本所需宽度裁切，保证版本可见。
        String version = mod.version();
        String name = this.clip(this.displayName(mod),
                Math.max(16, lineClip - this.font.width(version) - 8));
        extractor.text(this.font, Component.literal(name), textX, lineY, fade(TEXT_WHITE, alpha));
        extractor.text(this.font, Component.literal(version),
                textX + this.font.width(name) + 8, lineY, fade(TEXT_ACCENT, alpha));
        lineY += LINE_HEIGHT;

        // L2：id + jar 文件名（Brush 自身条目无 jar：显示 (built-in)）+ 多入口标注。
        String location = mod.id()
                + (mod.jar() == null ? "    (built-in)" : "    jar: " + mod.jar().getFileName())
                + (mod.entryCount() > 1 ? "    (" + mod.entryCount() + " entries)" : "");
        extractor.text(this.font, Component.literal(this.clip(location, lineClip)), textX, lineY,
                fade(TEXT_GRAY, alpha));
        lineY += LINE_HEIGHT;

        // L3：描述（可选，长文裁切为单行）。
        String description = mod.description();
        if (description != null && !description.isBlank()) {
            extractor.text(this.font, Component.literal(this.clip(description, lineClip)),
                    textX, lineY, fade(TEXT_GRAY, alpha));
            lineY += LINE_HEIGHT;
        }

        // L4：作者 / 依赖（各自可缺省）。
        String footer = this.footerLine(mod);
        if (!footer.isEmpty()) {
            extractor.text(this.font, Component.literal(this.clip(footer, lineClip)),
                    textX, lineY, fade(TEXT_GRAY, alpha));
        }
    }

    /** 方块基元：1px 边框 + 底色。 */
    private void extractBox(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2, boolean hovered) {
        extractor.fill(RenderPipelines.GUI, x1, y1, x2, y2, BOX_BORDER);
        extractor.fill(RenderPipelines.GUI,
                x1 + BORDER_WIDTH, y1 + BORDER_WIDTH, x2 - BORDER_WIDTH, y2 - BORDER_WIDTH,
                hovered ? BOX_FILL_HOVER : BOX_FILL);
    }

    // ------------------------------------------------------------------
    // 文本辅助
    // ------------------------------------------------------------------

    /** 展示名：{@code name} 缺省回退 id。 */
    private String displayName(ModBootstrap.ModEntry mod) {
        return mod.name() != null && !mod.name().isBlank() ? mod.name() : mod.id();
    }

    /** 单行裁切（CJK 安全，超出宽度截断；长度按像素宽度而非字符数）。 */
    private String clip(String text, int maxWidth) {
        return this.font.plainSubstrByWidth(text, maxWidth);
    }

    /**
     * 卡片名称换行：至多两行，按像素宽度逐行取满；第二行仍放不下时末尾以 {@code "..."} 截断。
     * 单行放得下 → 单行（无省略号）；空名 → 空列表（防御）。
     */
    private List<String> cardNameLines(String name, int maxWidth) {
        List<String> lines = new java.util.ArrayList<>(2);
        String remainder = name;
        for (int lineIndex = 0; lineIndex < 2 && !remainder.isEmpty(); lineIndex++) {
            String line = this.font.plainSubstrByWidth(remainder, maxWidth);
            if (line.isEmpty()) {
                break;   // 单个字符都放不下（行极窄）—— 防御，不画
            }
            if (lineIndex == 1 && line.length() < remainder.length()) {
                // 第二行仍溢出：为 "..." 让出宽度后重切
                String body = this.font.plainSubstrByWidth(remainder,
                        Math.max(1, maxWidth - this.font.width("...")));
                lines.add(body + "...");
                break;
            }
            lines.add(line);
            remainder = remainder.substring(line.length()).stripLeading();
        }
        return lines;
    }

    /** 替换 ARGB 的 alpha 分量（展开动画文本淡入用）；下限 1 —— alpha=0 会被静默丢弃（见文本色常量）。 */
    private static int fade(int argb, int alpha) {
        return (Math.max(1, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
    }

    /** 展开条末行：{@code by 作者列表} 与 {@code depends: id, id}（各自可缺省；都缺省返回空串）。 */
    private String footerLine(ModBootstrap.ModEntry mod) {
        StringBuilder builder = new StringBuilder();
        if (!mod.authors().isEmpty()) {
            builder.append("by ").append(String.join(", ", mod.authors()));
        }
        if (!mod.depends().isEmpty()) {
            if (builder.length() > 0) {
                builder.append("    ");
            }
            builder.append("depends: ").append(String.join(", ", mod.depends()));
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 列表与画布条目
    // ------------------------------------------------------------------

    /**
     * 列表：<b>恰一个画布条目</b>（{@link ModCanvasEntry}），承载全部卡片 / 详情条的绘制与点击命中；
     * ASL 只负责背景 / 滚动条 / 裁剪与滚动量。
     *
     * <p>过渡动画（{@link #tickAnimation}）在起 / 讫两套 {@link Layout} 间按 ease-in-out 插值：
     * 逐帧把画布高度设为当前插值总高，再调 {@code setScrollAmount(scrollAmount())} 触发 ASL 的
     * "clamp + repositionEntries"重排条目 Y。{@link #current()} 同时是绘制与点击命中的唯一
     * 数据源，故动画中途的点击落在"看得见的矩形"上。
     */
    private final class ModList extends ObjectSelectionList<ModCanvasEntry> {

        private final ModCanvasEntry canvas;

        /** 过渡起点（点击时的视觉快照）/ 终点（目标稳态布局）；相等 = 静止。 */
        private Layout from;
        private Layout to;

        /** 当前过渡的起始毫秒时间戳（{@link #current} 按此现算进度）。 */
        private long animStart;

        /** 待滚入视口的展开目标（{@link #revealStep} 逐帧跟进至完全可见后清除；null = 无）。 */
        private String revealId;

        ModList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            this.canvas = new ModCanvasEntry(this);
            // displayMods 恒含 Brush 自身条目（≥1），无"空表"分支。
            this.addEntry(this.canvas, GRID_ROW_HEIGHT);
            this.from = this.to = ModsScreen.this.layout(this.canvas.getContentWidth());
            this.canvas.setHeight(this.to.height());
            this.setScrollAmount(this.scrollAmount());
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, this.getWidth() - 40);
        }

        /**
         * 选中框绝缘：该开关为 true 时条目才会绘制选中框（1px 描边 + 纯黑内里）；我们没有任何
         * "选中"语义，恒 false。
         */
        @Override
        protected boolean entriesCanBeSelected() {
            return false;
        }

        /** 当前视觉布局（静止时 = {@link #to}；过渡中 = 起讫插值）。绘制与点击命中共用。 */
        private Layout current() {
            if (this.from == this.to) {
                return this.to;
            }
            float t = Math.min(1.0f, (System.currentTimeMillis() - this.animStart) / (float) EXPAND_ANIM_MS);
            return lerp(this.from, this.to, easeInOut(t));
        }

        /**
         * 每帧推进过渡（由 {@link ModsScreen#extractRenderState} 在 super 渲染前调用）：高度变化时
         * 重排条目 Y；进度到 1 即落定（from = to）。落定后仍会执行 {@link #revealStep} 的尾账
         * （高度长满前滚动上限未放开，差值需在后续帧补完）。不涉及条目增删 —— 全程只有一个画布条目，
         * 这也是"收起落点零跳变"的结构性原因。
         */
        void tickAnimation() {
            if (this.from != this.to) {
                Layout cur = this.current();
                if (this.canvas.getHeight() != cur.height()) {
                    this.canvas.setHeight(cur.height());
                    this.setScrollAmount(this.scrollAmount());
                }
                if (System.currentTimeMillis() - this.animStart >= EXPAND_ANIM_MS) {
                    this.from = this.to;
                    this.canvas.setHeight(this.to.height());
                    this.setScrollAmount(this.scrollAmount());
                }
            }
            if (this.revealId != null) {
                this.revealStep();
            }
        }

        /** 手风琴切换：点已展开（或正在展开的）mod → 收起；点其他 → 从当前视觉快照续接到新目标。 */
        void toggle(String modId) {
            if (modId.equals(ModsScreen.this.expandedId)) {
                close();
                return;
            }
            ModsScreen.this.expandedId = modId;
            retarget();
            this.revealId = modId;
        }

        void close() {
            this.revealId = null;
            if (ModsScreen.this.expandedId != null) {
                ModsScreen.this.expandedId = null;
                retarget();
            }
        }

        /** 把起 / 讫快照接到"当前视觉状态 → 新目标布局"，动画重新计时（中途改点不跳变）。 */
        private void retarget() {
            this.from = this.current();
            this.to = ModsScreen.this.layout(this.canvas.getContentWidth());
            this.animStart = System.currentTimeMillis();
        }

        /**
         * 展开时把目标详情条滚入视口（仅"未完全可见"才滚；已能完整看到则不打扰）。高度动画期间
         * ASL 的滚动上限随画布高度尚未放开 —— 一次性 setScrollAmount 会被 clamp 吃掉差额，故不做
         * 一次精算，改为<b>逐帧跟进</b>：每帧按当前几何算差值、把差值加上去（clamp 能吃多少吃多少），
         * 高度长满即精确到位、差值归零清除。观感 = 列表随动画平滑滑到目标位置。
         */
        private void revealStep() {
            List<ModBootstrap.ModEntry> mods = ModsScreen.this.displayMods;
            for (int i = 0; i < mods.size(); i++) {
                if (!mods.get(i).id().equals(this.revealId)) {
                    continue;
                }
                Cell bar = this.to.cells[i];
                int top = this.canvas.getContentY() + bar.y();
                int bottom = top + bar.h();
                double delta = 0.0;
                if (bottom > this.getBottom()) {
                    delta = bottom - this.getBottom();
                } else if (top < this.getY()) {
                    delta = top - this.getY();
                }
                if (delta == 0.0) {
                    this.revealId = null;
                } else {
                    this.setScrollAmount(this.scrollAmount() + delta);
                }
                return;
            }
            this.revealId = null;   // 防御：目标不在列表（界面存续期内列表不变，正常到不了）
        }

        /** 命中当前视觉布局中哪个 mod 的矩形；空白（矩形外）返回 null。 */
        private String hitTest(double mouseX, double mouseY) {
            Layout cur = this.current();
            List<ModBootstrap.ModEntry> mods = ModsScreen.this.displayMods;
            int originX = this.canvas.getContentX();
            int originY = this.canvas.getContentY();
            for (int i = 0; i < cur.cells.length && i < mods.size(); i++) {
                Cell cell = cur.cells[i];
                int x = originX + cell.x();
                int y = originY + cell.y();
                if (mouseX >= x && mouseX < x + cell.w() && mouseY >= y && mouseY < y + cell.h()) {
                    return mods.get(i).id();
                }
            }
            return null;
        }

        /**
         * 点击分发：滚动条条带转交 vanilla（左键拖拽；右键仅消费）；<b>内容区一切点击全部由我们
         * 消费</b> —— 命中卡片 / 详情条（按当前插值矩形）→ 左键切换展开；空白 → 左键收起。绝不
         * 落到 vanilla 默认路径（避免条目选中路径画出黑底选中框，见类 javadoc）。
         */
        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.x() >= this.scrollBarX()) {
                return event.button() == 0 && super.mouseClicked(event, doubleClick);
            }
            String hitId = this.hitTest(event.x(), event.y());
            if (hitId != null) {
                if (event.button() == 0) {
                    this.toggle(hitId);
                }
            } else if (event.button() == 0) {
                this.close();
            }
            return true;
        }
    }

    /**
     * 画布条目（{@link ModList} 的唯一子项）：把当前插值布局里的每个 mod 按形态系数分派到
     * {@link #extractCard}（bar≈0）/ {@link #extractMorph}（形态过渡中与详情条）。
     *
     * <p>为什么与 {@link ModList} 平级而不是嵌套于它：Java 禁止类在自己的 extends 子句里引用
     * 自身嵌套类（{@code ModList extends ObjectSelectionList<ModCanvasEntry>} 会编译失败）；
     * 改为持 {@link #owner} 反向引用（同一顶层类的嵌套类之间 private 成员可互访）。
     */
    private final class ModCanvasEntry extends ObjectSelectionList.Entry<ModCanvasEntry> {

        /** 宿主列表（读当前插值布局与内容盒坐标换算）。 */
        private final ModList owner;

        ModCanvasEntry(ModList owner) {
            this.owner = owner;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                boolean hovered, float partialTick) {
            Layout cur = this.owner.current();
            List<ModBootstrap.ModEntry> mods = ModsScreen.this.displayMods;
            int originX = this.getContentX();
            int originY = this.getContentY();
            for (int i = 0; i < cur.cells.length && i < mods.size(); i++) {
                Cell cell = cur.cells[i];
                int x = originX + cell.x();
                int y = originY + cell.y();
                if (cell.bar() <= 0.001f) {
                    boolean cardHovered = mouseX >= x && mouseX < x + CARD_SIZE
                            && mouseY >= y && mouseY < y + CARD_SIZE;
                    ModsScreen.this.extractCard(extractor, mods.get(i), x, y, cardHovered);
                } else {
                    ModsScreen.this.extractMorph(extractor, mods.get(i), x, y,
                            cell.w(), cell.h(), cell.bar());
                }
            }
        }

        @Override
        public Component getNarration() {
            StringBuilder builder = new StringBuilder();
            for (ModBootstrap.ModEntry mod : ModsScreen.this.displayMods) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(ModsScreen.this.displayName(mod));
            }
            return Component.literal(builder.toString());
        }
    }
}
