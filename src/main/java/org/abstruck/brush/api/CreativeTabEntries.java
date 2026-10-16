package org.abstruck.brush.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * 创造栏条目追加句柄（L2）—— 标签页内容构建完成时交给监听器的写入面。
 *
 * <p><b>用法</b>：
 * <pre>{@code
 * BrushCreativeTabs.entriesOf(Identifier.fromNamespaceAndPath("minecraft", "ingredients"))
 *         .register(entries -> entries.add(new ItemStack(MY_ITEM)));
 * }</pre>
 *
 * <p><b>语义</b>：句柄只在一次 {@link CreativeTabOutputEvent.Listener#onCreativeTabOutput} 调用内
 * 有效；{@code add} 为<b>增量追加</b>（vanilla 已生成的条目不受影响，条目出现在标签页末尾）。
 * 每次标签页构建（首建 / 重开创造界面 / 搜索刷新）都会重新调用监听器 —— 对同一构建的重复调用
 * 不会跨构建累积条目（vanilla 每次构建都重建内容集合，见 {@code CreativeModeTabMixin} javadoc）；
 * <b>同一构建内</b>重复 {@code add} 同一 {@link ItemStack} 会在标签页内容里产生重复项
 * （搜索页是 {@code Set}，自动去重）。
 *
 * <p><b>写入线程</b>：调用线程 = 触发线程（生产路径 = 游戏主线程），与 vanilla 生成器写同一集合
 * 同线程，无并发问题。
 */
public interface CreativeTabEntries {

    /** 本次构建的标签页键（即 {@code entriesOf(...)} 订阅时使用的键）。 */
    ResourceKey<CreativeModeTab> tab();

    /** 追加一个条目（标签页 + 搜索页均可见，等同 {@link Visibility#TAB_AND_SEARCH}）。 */
    default void add(ItemStack stack) {
        add(stack, Visibility.TAB_AND_SEARCH);
    }

    /**
     * 按可见性追加一个条目。
     *
     * @param stack      条目（非 null）
     * @param visibility 可见性（非 null）
     */
    void add(ItemStack stack, Visibility visibility);

    /** 当前显示参数是否放行 OP 限定条目（{@code ItemDisplayParameters.hasPermissions()}）。 */
    boolean shouldShowOpRestrictedItems();

    /** 当前显示参数启用的特征集（{@code ItemDisplayParameters.enabledFeatures()}）。 */
    FeatureFlagSet enabledFeatures();

    /** 条目可见性（对齐 vanilla 标签页生成器的三档语义）。 */
    enum Visibility {
        /** 标签页与搜索页均可见（vanilla 默认）。 */
        TAB_AND_SEARCH,
        /** 仅标签页可见，不进搜索页。 */
        TAB_ONLY,
        /** 仅搜索页可见，不进标签页。 */
        SEARCH_ONLY
    }
}
