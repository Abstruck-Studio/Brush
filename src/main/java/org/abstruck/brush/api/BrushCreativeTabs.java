package org.abstruck.brush.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import org.abstruck.brush.content.ModContext;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 创造栏 API（L2）—— 两个方向：向<b>现有</b>（注册表）标签页追加条目（{@link #entriesOf}），
 * 以及注册 Brush 生态<b>自有</b>标签页（{@link #register} —— 展示于创造栏左侧 Brush 侧栏，
 * 不进任何注册表，见 {@link BrushCreativeTab}）。
 *
 * <p><b>用法</b>（mod 入口类构造期即可订阅，与其它事件同一时机）：
 * <pre>{@code
 * // 官方名标签页：minecraft:ingredients / minecraft:building_blocks / ...
 * BrushCreativeTabs.entriesOf(Identifier.fromNamespaceAndPath("minecraft", "ingredients"))
 *         .register(entries -> entries.add(new ItemStack(MY_ITEM)));
 * }</pre>
 *
 * <p><b>语义</b>：每个标签页键对应一个独立的 {@link Event}（同键重复调用返回同一实例 ——
 * 多次订阅叠加为多个监听器，可链式）。监听器在<b>每次</b>该标签页构建时被调用
 * （首建 / 重开创造界面重建 / 搜索刷新），拿到 {@link CreativeTabEntries} 句柄做增量追加；
 * 触发时机与载荷语义见 {@code HookEvents#CREATIVE_TAB_OUTPUT} javadoc。
 *
 * <p><b>为什么自有页不进注册表</b>：vanilla 14 个标签页的 (row,column) 槽位已全部占满，
 * 且 {@code Bootstrap#bootStrap()} 在生产路径上无条件调用 {@code CreativeModeTabs.validate()}，
 * 自注册新标签页会直接中止启动 —— Brush 改为自维护已注册列表 + 创造栏左侧侧栏展示
 * （{@link BrushCreativeTab}）。{@code BrushRegistry} 对 {@code CREATIVE_MODE_TAB} 注册表
 * 保持拒收守卫。
 *
 * <p><b>三运行时同一语义</b>：订阅与派发只依赖 L1 派发表；键过滤、句柄写入语义在三运行时
 * 完全一致（同一份 {@code brush.mixins.json}）。
 *
 * <p><b>搜索页（{@code Type.SEARCH}）可见性 —— 2026-10-16 缺陷修复</b>：自有页不进注册表，
 * 而 vanilla 的搜索页是在 {@code CreativeModeTabs#buildAllTabContents} 的<b>第二趟</b>里
 * 由<b>注册表页签</b>聚合而成 —— 因此自有页的条目在创造搜索栏里搜不到（用户报告）。
 * 修法：核心在首次注册自有页时装一个<b>内部 L1 监听器</b>观察 {@code creative.tab.output}；
 * 当被构建的页签是搜索页时（该钩子在 {@code buildContents} 的 TAIL，vanilla 聚合已完成），
 * 用同一份 {@code ItemDisplayParameters} 重建各自有页，并把它们的<b>搜索集合</b>并入搜索页
 * 显示项 —— {@code TAB_AND_SEARCH} / {@code SEARCH_ONLY} 入、{@code TAB_ONLY} 不入，
 * 与 {@link CreativeTabEntries} 的写入语义同源。该修法**零新增注入点、零新增织入指令**
 * （复用既有钩子，见 {@code HookEvents#CREATIVE_TAB_OUTPUT}）。</p>
 *
 * <p><b>诊断面</b>（{@code subscribedKeys} / {@code registrationsOf} / {@code deliveriesOf} /
 * {@link #audit()}）：为自检报告行与实弹探针提供的只读快照；mod 不需要使用。
 */
public final class BrushCreativeTabs {

    private static final Logger LOGGER = LogManager.getLogger(BrushCreativeTabs.class);

    /** 标签页键 → L2 事件（同键同实例；CHM 保证注册期并发安全）。 */
    private static final Map<ResourceKey<CreativeModeTab>, Event<CreativeTabOutputEvent.Listener>> EVENTS =
            new ConcurrentHashMap<>();

    /** 标签页键 → 派发探针计数（与 {@link #EVENTS} 同生命周期）。 */
    private static final Map<ResourceKey<CreativeModeTab>, TabStats> STATS = new ConcurrentHashMap<>();

    /**
     * 已注册自有标签页（注册序即展示序）。写路径在 {@link #register} 的监视器内串行
     * （含"查重 + 写入"复合操作），读路径（{@link #registeredTabs()}）走 COW 快照、无锁。
     */
    private static final List<BrushCreativeTab> OWNED = new CopyOnWriteArrayList<>();

    /** 搜索页键（懒解析；注册表未就绪/解析失败 = null，此时搜索页桥不装、只告警一次）。 */
    private static volatile ResourceKey<CreativeModeTab> searchKey;

    /** 搜索页桥是否已装（首次注册自有页时安装；见类 javadoc"搜索页可见性"）。 */
    private static volatile boolean searchBridgeInstalled;

    private BrushCreativeTabs() {
    }

    /**
     * 订阅一个标签页的条目追加（官方名 {@link ResourceKey} 形态）。
     *
     * @param tabKey 标签页键（如 {@code ResourceKey.create(Registries.CREATIVE_MODE_TAB, ...)}；
     *               vanilla 的 14 个键字段为 private，mod 经 {@link Identifier} 构造）
     * @return 该标签页的 L2 事件（注册监听器见 {@link Event#register}）
     */
    public static Event<CreativeTabOutputEvent.Listener> entriesOf(ResourceKey<CreativeModeTab> tabKey) {
        Objects.requireNonNull(tabKey, "tabKey");
        return EVENTS.computeIfAbsent(tabKey, BrushCreativeTabs::createEvent);
    }

    /**
     * 订阅一个标签页的条目追加（{@link Identifier} 便捷形）——
     * 等值于 {@code entriesOf(ResourceKey.create(Registries.CREATIVE_MODE_TAB, tabId))}。
     *
     * @param tabId 标签页 id（如 {@code Identifier.fromNamespaceAndPath("minecraft", "ingredients")}）
     */
    public static Event<CreativeTabOutputEvent.Listener> entriesOf(Identifier tabId) {
        Objects.requireNonNull(tabId, "tabId");
        return entriesOf(ResourceKey.create(Registries.CREATIVE_MODE_TAB, tabId));
    }

    /**
     * 注册一个 Brush 自有标签页（{@link BrushCreativeTab}）—— 须在 mod 客户端入口
     * （{@code onInitializeClient()}）的上下文内调用。
     *
     * <p>归属（owner）由本方法从 {@link ModContext} 捕获（与 {@code api.Event.register} 同一
     * 归属机制）；无上下文（非 mod 代码调用）→ 可读拒绝，不静默归属
     * "unknown"。与已注册自有页重复 id → 可读拒绝（快速失败，不覆盖）。
     *
     * @param tab 已构建的标签页模型（{@code BrushCreativeTab.builder(...).build()}）
     * @return 传入的模型（owner 已写入；便利返回）
     * @throws IllegalStateException 无 mod 上下文 / id 重复
     */
    public static BrushCreativeTab register(BrushCreativeTab tab) {
        Objects.requireNonNull(tab, "tab");
        String owner = ModContext.current();
        if (owner == null) {
            throw new IllegalStateException("[brush] creative tab registration for " + tab.id()
                    + " rejected: no mod context active on this thread. Register inside a mod"
                    + " client entrypoint (BrushClientModInitializer#onInitializeClient; docs/18)");
        }
        synchronized (OWNED) {
            for (BrushCreativeTab existing : OWNED) {
                if (existing.id().equals(tab.id())) {
                    throw new IllegalStateException("[brush] duplicate creative tab id " + tab.id()
                            + ": already registered by mod '" + existing.owner()
                            + "', rejected registration by mod '" + owner + "'");
                }
            }
            tab.setOwner(owner);
            OWNED.add(tab);
        }
        LOGGER.info("[brush] creative.tab: mod '" + owner + "' registered tab " + tab.id()
                + " (" + tab.title().getString() + ")");
        installSearchBridge();
        return tab;
    }

    // ------------------------------------------------------------------
    // 搜索页桥（内部；见类 javadoc"搜索页可见性"）
    // ------------------------------------------------------------------

    /**
     * 装搜索页桥（幂等）：挂一个内部 L1 监听器观察创造页签构建。
     *
     * <p>只在首次注册自有页时调用 —— 那时 {@code Bootstrap} 已完成（创造页签注册表就绪），
     * 故能解析出搜索页键。解析失败（异常/空）→ 只告警一次并放弃安装（自有页仍可用，
     * 仅搜索栏看不到它们的条目），不阻断注册。</p>
     */
    private static void installSearchBridge() {
        if (searchBridgeInstalled) {
            return;
        }
        synchronized (BrushCreativeTabs.class) {
            if (searchBridgeInstalled) {
                return;
            }
            searchBridgeInstalled = true;
            try {
                searchKey = BuiltInRegistries.CREATIVE_MODE_TAB
                        .getResourceKey(CreativeModeTabs.searchTab()).orElse(null);
            } catch (Throwable t) {
                searchKey = null;
                LOGGER.warn("[brush] creative.tab: cannot resolve the search tab key (" + t + ");"
                        + " own tabs stay usable but will not appear in the creative search tab");
            }
            if (searchKey == null) {
                return;
            }
            Dispatcher.register(HookEvents.CREATIVE_TAB_OUTPUT, BrushCreativeTabs::onTabBuilt);
            LOGGER.info("[brush] creative.tab: search bridge installed for tab " + searchKey.identifier()
                    + " (own tabs join the creative search tab)");
        }
    }

    /**
     * 内部 L1 监听器：仅在"被构建的页签 = 搜索页"时动手（TAIL ⇒ vanilla 聚合已完成）。
     *
     * <p>把各自有页的<b>搜索集合</b>并入搜索页显示项；已存在者跳过（幂等）。用同一份
     * {@code ItemDisplayParameters} 重建自有页，因此自有页内容与当前构建参数一致。</p>
     */
    @SuppressWarnings("unchecked")
    private static void onTabBuilt(Object[] args) {
        if (args == null || args.length < 3 || OWNED.isEmpty()) {
            return;
        }
        ResourceKey<CreativeModeTab> key = searchKey;
        if (key == null || !key.equals(args[0])) {
            return;
        }
        if (!(args[1] instanceof CreativeModeTab.ItemDisplayParameters parameters)
                || !(args[2] instanceof Collection<?> raw)) {
            return;
        }
        Collection<ItemStack> searchDisplayItems = (Collection<ItemStack>) raw;
        for (BrushCreativeTab tab : OWNED) {
            tab.buildContents(parameters);
            for (ItemStack stack : tab.vanillaTab().getSearchTabDisplayItems()) {
                if (!searchDisplayItems.contains(stack)) {
                    searchDisplayItems.add(stack);
                }
            }
        }
    }

    /** 已注册自有标签页快照（注册序即展示序；侧栏渲染 / 自检探针的数据源）。 */
    public static List<BrushCreativeTab> registeredTabs() {
        return List.copyOf(OWNED);
    }

    /**
     * 桥页签反查：给定 vanilla 页签是否由 Brush 自有注册表生成？是则返回对应
     * {@link BrushCreativeTab}（恒等比较、无分配、可逐帧调用 —— 热路径契约），否则 {@code null}。
     *
     * <p>供 L1 渲染抑制判据（{@code CreativeModeInventoryScreenMixin} 的幻影高亮抑制）与侧栏
     * 选中还原（{@code TabSidebarMount} 的意图追踪）使用；mod 开发者一般不需要。</p>
     */
    public static BrushCreativeTab tabOfBridge(CreativeModeTab candidate) {
        if (candidate == null) {
            return null;
        }
        for (int index = 0; index < OWNED.size(); index++) {
            BrushCreativeTab tab = OWNED.get(index);
            if (tab.peekVanilla() == candidate) {
                return tab;
            }
        }
        return null;
    }

    private static Event<CreativeTabOutputEvent.Listener> createEvent(ResourceKey<CreativeModeTab> key) {
        TabStats stats = new TabStats();
        STATS.put(key, stats);
        return new Event<>(HookEvents.CREATIVE_TAB_OUTPUT, listener -> {
            stats.registrations.incrementAndGet();
            return args -> {
                if (key.equals(args[0])) {
                    deliver(key, stats, listener, args);
                }
            };
        });
    }

    /**
     * 投递一次：L1 载荷 → L2 句柄 → 监听器 → 证据记账（投递计数 / 条目计数 / 日志）。
     *
     * <p>L1 载荷是无类型 {@code Object[]}（派发器契约）；此处的类型还原以注入端
     * （{@code CreativeModeTabMixin} 的静态 fire 调用）为唯一事实来源 —— 单向闭包在包内，
     * {@code @SuppressWarnings} 是擦除边界的定向抑制（载荷类型由我们自己的注入点保证，
     * 非掩盖失败）。
     */
    @SuppressWarnings("unchecked")
    private static void deliver(ResourceKey<CreativeModeTab> key, TabStats stats,
                                CreativeTabOutputEvent.Listener listener, Object[] args) {
        Collection<ItemStack> displayItems = (Collection<ItemStack>) args[2];
        Set<ItemStack> searchTabContents = (Set<ItemStack>) args[3];
        CreativeTabEntriesImpl entries = new CreativeTabEntriesImpl(key,
                (CreativeModeTab.ItemDisplayParameters) args[1], displayItems, searchTabContents);
        try {
            listener.onCreativeTabOutput(entries);
        } finally {
            stats.deliveries.incrementAndGet();
            int added = entries.addedCount();
            if (added > 0) {
                stats.entriesAdded.addAndGet(added);
                LOGGER.info("[brush] creative.tab.output: listener of mod '" + ModContext.current()
                        + "' added " + added + " entr" + (added == 1 ? "y" : "ies")
                        + " to tab " + key.identifier());
            }
        }
    }

    // ------------------------------------------------------------------
    // 诊断面（自检报告行 + 实弹探针的数据源；只读）
    // ------------------------------------------------------------------

    /** 已订阅的标签页键快照（探针逐个构建用）。 */
    public static Set<ResourceKey<CreativeModeTab>> subscribedKeys() {
        return Set.copyOf(EVENTS.keySet());
    }

    /** 某键上的监听器注册数（未订阅 / 未注册 = 0）。 */
    public static long registrationsOf(ResourceKey<CreativeModeTab> tabKey) {
        TabStats stats = STATS.get(tabKey);
        return stats == null ? 0L : stats.registrations.get();
    }

    /** 某键的条目投递次数（监听器实际被调用的次数；未订阅 = 0）。 */
    public static long deliveriesOf(ResourceKey<CreativeModeTab> tabKey) {
        TabStats stats = STATS.get(tabKey);
        return stats == null ? 0L : stats.deliveries.get();
    }

    /** 报告行快照：订阅侧（订阅 / 注册 / 投递 / 条目追加）+ 自有页侧（页数 / 构建 / 条目）。 */
    public static CreativeTabAudit audit() {
        long registrations = 0L;
        long deliveries = 0L;
        long entriesAdded = 0L;
        for (TabStats stats : STATS.values()) {
            registrations += stats.registrations.get();
            deliveries += stats.deliveries.get();
            entriesAdded += stats.entriesAdded.get();
        }
        List<BrushCreativeTab> owned = registeredTabs();
        long ownedBuilds = 0L;
        long ownedEntries = 0L;
        for (BrushCreativeTab tab : owned) {
            ownedBuilds += tab.buildInvocations();
            ownedEntries += tab.entriesAccepted();
        }
        return new CreativeTabAudit(EVENTS.size(), registrations, deliveries, entriesAdded,
                owned.size(), ownedBuilds, ownedEntries);
    }

    /** 创造栏审核快照（纯数据）：订阅侧 + 自有页侧。 */
    public record CreativeTabAudit(int subscribedTabs, long registrations, long deliveries, long entriesAdded,
            int ownedTabs, long ownedBuilds, long ownedEntries) {
    }

    /** 单键派发探针计数。 */
    private static final class TabStats {
        private final AtomicLong registrations = new AtomicLong();
        private final AtomicLong deliveries = new AtomicLong();
        private final AtomicLong entriesAdded = new AtomicLong();
    }
}
