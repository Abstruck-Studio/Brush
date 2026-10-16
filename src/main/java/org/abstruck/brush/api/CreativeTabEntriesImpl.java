package org.abstruck.brush.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Objects;

/**
 * {@link CreativeTabEntries} 实现（包私有）：把 {@code add} 落到本标签页的两个内容集合 ——
 * 集合引用由 {@code CreativeModeTabMixin} 在 {@code buildContents} TAIL 取出、经 L1 载荷传入
 * （写入的是 vanilla 本次构建的<b>同一</b>集合实例，故追加立即对渲染生效）。
 *
 * <p>写入由派发线程独占（与 vanilla 生成器同线程）；{@link #addedCount()} 供派发方记账，
 * 句柄在本次调用结束后即废弃（不持有跨构建状态）。
 *
 * <p><b>两个使用方（同一实现）</b>：注册表标签页经 {@code CreativeModeTabMixin} TAIL 载荷拿集合；
 * 自有页经 {@link BrushCreativeTab#buildContents} 的公开集合访问面拿集合 —— 两处都是
 * vanilla 本次构建的<b>同一</b>活动集合实例（搜索集合在 vanilla 侧为 {@code Set}、天然去重；
 * 本类按 {@code Collection} 写入，不依赖具体形态）。
 */
final class CreativeTabEntriesImpl implements CreativeTabEntries {

    private final ResourceKey<CreativeModeTab> tabKey;
    private final boolean opRestrictedAllowed;
    private final FeatureFlagSet features;
    private final Collection<ItemStack> displayItems;
    private final Collection<ItemStack> searchTabContents;
    private int addedCount;

    CreativeTabEntriesImpl(ResourceKey<CreativeModeTab> tabKey,
                           CreativeModeTab.ItemDisplayParameters parameters,
                           Collection<ItemStack> displayItems,
                           Collection<ItemStack> searchTabContents) {
        this.tabKey = Objects.requireNonNull(tabKey, "tabKey");
        this.opRestrictedAllowed = parameters.hasPermissions();
        this.features = parameters.enabledFeatures();
        this.displayItems = Objects.requireNonNull(displayItems, "displayItems");
        this.searchTabContents = Objects.requireNonNull(searchTabContents, "searchTabContents");
    }

    @Override
    public ResourceKey<CreativeModeTab> tab() {
        return tabKey;
    }

    @Override
    public void add(ItemStack stack, Visibility visibility) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(visibility, "visibility");
        switch (visibility) {
            case TAB_AND_SEARCH -> {
                displayItems.add(stack);
                searchTabContents.add(stack);
            }
            case TAB_ONLY -> displayItems.add(stack);
            case SEARCH_ONLY -> searchTabContents.add(stack);
        }
        addedCount++;
    }

    @Override
    public boolean shouldShowOpRestrictedItems() {
        return opRestrictedAllowed;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return features;
    }

    /** 本次调用内累计追加的条目数（派发方记录证据后丢弃本实例）。 */
    int addedCount() {
        return addedCount;
    }
}
