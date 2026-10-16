package org.abstruck.brush.api;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Brush 自有创造标签页（L2 模型）—— 出现在创造栏左侧 Brush 侧栏里的标签页定义。
 *
 * <p><b>用法</b>（mod 客户端入口构造期注册，与其它 API 同一时机）：
 * <pre>{@code
 * BrushCreativeTabs.register(BrushCreativeTab
 *         .builder(Identifier.fromNamespaceAndPath("example", "rubies"))
 *         .title(Component.translatable("example.tab.rubies"))
 *         .icon(() -> new ItemStack(MY_ITEM))
 *         .contents(entries -> entries.add(new ItemStack(MY_ITEM)))
 *         .build());
 * }</pre>
 *
 * <p><b>为什么不进注册表</b>：{@code Bootstrap#bootStrap()} 在生产路径上无条件调用
 * {@code CreativeModeTabs.validate()}，vanilla 14 个标签页的 (row,column) 槽位已占满，
 * 自注册会产生重复槽位并直接中止启动。因此 Brush 自维护已注册列表
 * （{@link BrushCreativeTabs#registeredTabs()}），不触碰任何注册表；{@code BrushRegistry} 对
 * {@code CREATIVE_MODE_TAB} 的拒收守卫不受影响（本模型不经它注册）。</p>
 *
 * <p><b>激活路径</b>：侧栏点击 → {@link #buildContents}（活动集合 clear + 经公开集合访问面
 * 全量重填）→ 访问门 {@code CreativeScreenAccess#brush$selectTab} 选中桥实例 —— vanilla
 * {@code selectTab} 的 {@code Type.CATEGORY} 分支以该标签页 {@code getDisplayItems()} 填充菜单。
 * <b>vanilla 的标签页刷新循环只遍历注册表</b>（{@code tryRefreshInvalidatedTabs}），因此本页
 * 内容由构建方（侧栏激活 / 自检探针）显式驱动，不依赖任何原版刷新。S2 下选中前还须为桥页补
 * Fabric 分页页码（否则 Fabric 屏幕 mixin 的 {@code selectTab} 守卫抛 page 缺失 / 取消选中）
 * —— {@code host.fabric.FabricCreativeTabCompat}，调用点在侧栏激活（{@code TabSidebarMount}）。</p>
 *
 * <p><b>vanilla 桥（懒构建）</b>：{@link #vanillaTab()} 首次调用时才构造内部
 * {@link CreativeModeTab} 实例 —— 行列槽位取 {@code Row.TOP}/0（对本页无意义：永不参与 vanilla
 * 槽位渲染），{@code Type} 取 Builder 构造器默认的 {@code CATEGORY}、生成器取构造器默认的
 * {@code EMPTY_GENERATOR}（该生成器在本类路径下不被执行，见下节）。桥实例不注册、无 registry
 * key；vanilla {@code buildContents} 不再被调用，注入点对自有页不触发。</p>
 *
 * <p><b>内容为什么不经 {@code DisplayItemsGenerator} 的 {@code Output} 写入</b>：
 * {@code Output} 与 {@code TabVisibility} 是 {@code CreativeModeTab} 的 <b>protected</b>
 * 嵌套类型 —— mod 侧代码（不同包）无法命名，{@code displayItems((p, o) -> ...)} 的隐式
 * lambda 参数在 javac 下直接编译失败。改为：生成器留空（默认 {@code EMPTY_GENERATOR}），
 * 桥构建后经<b>公开集合访问面</b>填充 —— {@code getDisplayItems()} /
 * {@code getSearchTabDisplayItems()} 直返活动集合（非副本），向它们 {@code add} 与 vanilla
 * 生成器 {@code output.accept} 写入的是<b>同一集合实例</b>；写入语义复用
 * {@link CreativeTabEntriesImpl}（单份实现，避免第二套三档映射）。</p>
 *
 * <p><b>为什么不调用 vanilla {@code buildContents}</b>：Fabric API 的
 * {@code fabric-creative-tab-api-v1} 在 {@code CreativeModeTabMixin} 里对 {@code buildContents}
 * 的 TAIL 织入 {@code getStacks}，其注入代码<b>首句</b>即
 * {@code BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(this).orElseThrow(() ->
 * new IllegalStateException("Unregistered creative mode tab : " + this))} —— 未注册页调用
 * vanilla {@code buildContents} 在 S2 下必抛（{@code Unregistered creative mode tab}）。
 * 该守卫对自有页无意义（我们本就不进注册表，见上节）；而 vanilla 侧原调用唯一的作用是
 * "EMPTY 生成器重建两个空集合"，vanilla {@code CreativeModeTab} <b>构造器</b>已把两集合初始化为
 * 可变空集，因此改为在<b>活动集合上 clear 后全量重填</b> —— 三运行时同一状态、同一代码路径，
 * S2 不再触碰 Fabric 的守卫。副作用：{@code creative.tab.output} 不再为自有页触发（自有页构建
 * 证据由 {@link #buildInvocations} / {@link #entriesAccepted} 与自检探针承担；注册表页面的
 * 既有路径不受影响）。</p>
 *
 * <p><b>内容语义</b>：{@link #contents} 在每次构建时被调用，句柄复用
 * {@link CreativeTabEntries}（{@code Visibility} 三档与 vanilla {@code TabVisibility} 一一映射）。
 * 与 {@code entriesOf} 的差别只在"写向哪个标签页"：这里是自有页、无注册键。</p>
 *
 * <p><b>诊断面</b>：{@link #buildInvocations()} / {@link #entriesAccepted()}（自检探针证据）、
 * {@link #owner()}（注册时由 {@link BrushCreativeTabs#register} 从 mod 上下文写入 —— 与
 * {@code api.Event.register} 同一归属机制）。</p>
 *
 * <p><b>线程</b>：注册在 mod 客户端入口（主线程）；构建在触发线程（生产路径 = 主线程）。
 * 懒构建双检锁保证桥实例唯一。</p>
 */
public final class BrushCreativeTab {

    private final Identifier id;
    private final ResourceKey<CreativeModeTab> key;
    private final Component title;
    private final Supplier<ItemStack> icon;
    private final Consumer<CreativeTabEntries> contents;

    /** 归属 mod id；由 {@link BrushCreativeTabs#register} 在注册时写入恰一次（其后只读）。 */
    private String owner;

    /** 懒构建的 vanilla 桥（见 {@link #vanillaTab()}）；未构建 = null。 */
    private volatile CreativeModeTab vanillaTab;

    /** 桥构建次数（生成器被调用的次数；自检探针的"构建确实跑到"证据）。 */
    private final AtomicLong buildInvocations = new AtomicLong();

    /** 经本页句柄追加的条目总数（探针证据 + 诊断）。 */
    private final AtomicLong entriesAccepted = new AtomicLong();

    private BrushCreativeTab(Builder builder) {
        this.id = builder.id;
        this.key = ResourceKey.create(Registries.CREATIVE_MODE_TAB, builder.id);
        if (builder.title == null || builder.icon == null || builder.contents == null) {
            throw new IllegalStateException("creative tab " + builder.id
                    + " is incomplete: title / icon / contents are all required");
        }
        this.title = builder.title;
        this.icon = builder.icon;
        this.contents = builder.contents;
    }

    /** 构建器入口（{@link #build()} 前不产生任何副作用、不解析任何游戏状态）。 */
    public static Builder builder(Identifier id) {
        return new Builder(id);
    }

    /** 标签页 id（注册唯一性判据）。 */
    public Identifier id() {
        return id;
    }

    /**
     * 标签页键（{@link CreativeTabEntries#tab()} 的返回值）：按 id 构造 —— 与
     * {@code BrushCreativeTabs.entriesOf(Identifier)} 的键构造同式。自有页未注册，
     * 此键是<b>标识</b>而非注册表解析结果。
     */
    public ResourceKey<CreativeModeTab> key() {
        return key;
    }

    /** 标签页标题（侧栏行内显示；{@link #title()} 在注册日志里也用到）。 */
    public Component title() {
        return title;
    }

    /** 归属 mod id（注册后被写入；未注册 = null）。 */
    public String owner() {
        return owner;
    }

    /**
     * vanilla 桥实例（懒构建，双检锁；见类 javadoc"vanilla 桥"）。
     * 构建方（侧栏激活 / 自检探针）拿到它后调 {@code buildContents(参数)} 再选中。
     */
    public CreativeModeTab vanillaTab() {
        CreativeModeTab tab = vanillaTab;
        if (tab == null) {
            synchronized (this) {
                tab = vanillaTab;
                if (tab == null) {
                    tab = buildVanilla();
                    vanillaTab = tab;
                }
            }
        }
        return tab;
    }

    /** 已构建的桥实例；未构建 = {@code null}（只读探测，不触发构建 —— 侧栏逐帧高亮用）。 */
    public CreativeModeTab peekVanilla() {
        return vanillaTab;
    }

    /** 图标条目（每次调用求值 —— 与 vanilla 图标供应商语义一致；侧栏行渲染用，勿在供应商内做重活）。 */
    public ItemStack iconStack() {
        return icon.get();
    }

    /** 桥构建次数（诊断面，见类 javadoc）。 */
    long buildInvocations() {
        return buildInvocations.get();
    }

    /** 条目追加总数（诊断面，见类 javadoc）。 */
    long entriesAccepted() {
        return entriesAccepted.get();
    }

    /** 注册时写入归属（{@link BrushCreativeTabs#register} 调用；包内封闭）。 */
    void setOwner(String owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /**
     * 构建本页内容到桥实例（激活 / 自检探针的唯一构建入口；流程见类 javadoc"激活路径"）——
     * clear 活动集合（构造器初始化的可变空集，见类 javadoc"为什么不调用 vanilla buildContents"）
     * → 向活动集合全量重填（{@link CreativeTabEntriesImpl}，与注册表页面同一写入语义）→ 由调用方
     * 选中桥实例（S2 下调用方还须先补 Fabric 分页页码）。
     *
     * <p>每次调用先 clear 后全量重填 —— 重复激活不会累积条目（与 vanilla
     * {@code buildContents} 重建集合同效果）。</p>
     */
    public void buildContents(CreativeModeTab.ItemDisplayParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        CreativeModeTab bridge = vanillaTab();
        bridge.getDisplayItems().clear();
        bridge.getSearchTabDisplayItems().clear();
        buildInvocations.incrementAndGet();
        CreativeTabEntriesImpl entries = new CreativeTabEntriesImpl(key, parameters,
                bridge.getDisplayItems(), bridge.getSearchTabDisplayItems());
        contents.accept(entries);
        entriesAccepted.addAndGet(entries.addedCount());
    }

    private CreativeModeTab buildVanilla() {
        // 不设 displayItems：Builder 构造器默认 EMPTY_GENERATOR；内容经公开集合
        // 访问面填充（见类 javadoc"内容为什么不经 Output"）。title / icon 仅为语义完整，
        // 桥实例永不参与 vanilla 槽位渲染。
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(title)
                .icon(icon)
                .build();
    }

    /** 标签页构建器（一次性装配三要素 + id；{@link #build()} 处做完整性校验）。 */
    public static final class Builder {

        private final Identifier id;
        private Component title;
        private Supplier<ItemStack> icon;
        private Consumer<CreativeTabEntries> contents;

        private Builder(Identifier id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        /** 标签页标题（建议 {@code Component.translatable} + 自有语言文件）。 */
        public Builder title(Component title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        /** 标签页图标（每次渲染求值，与 vanilla 同；勿在供应商内做重活）。 */
        public Builder icon(Supplier<ItemStack> icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
            return this;
        }

        /** 图标便捷形：固定栈（包装为常量供应商）。 */
        public Builder icon(ItemStack icon) {
            Objects.requireNonNull(icon, "icon");
            return icon(() -> icon);
        }

        /** 内容生成器（每次构建被调用；语义见类 javadoc）。 */
        public Builder contents(Consumer<CreativeTabEntries> contents) {
            this.contents = Objects.requireNonNull(contents, "contents");
            return this;
        }

        /** 装配（完整性校验见构造器）。 */
        public BrushCreativeTab build() {
            return new BrushCreativeTab(this);
        }
    }
}
