package org.abstruck.brush.transform.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code CreativeModeInventoryScreen} 保护成员访问门（docs/18 §2.6）—— 与 {@link ScreenAccess}
 * 同一机制（§10.4：优先 {@code @Accessor} / {@code @Invoker} 这类<b>不产生全局可见性变化</b>
 * 的方案）。
 *
 * <p>三个成员：
 * <ul>
 *   <li>{@code private void selectTab(CreativeModeTab)} —— 激活标签页：{@code Type.CATEGORY} 下
 *       以该标签页当前 {@code displayItems} 填充菜单（自有标签页经 {@code Builder} 默认
 *       {@code type = Type.CATEGORY}，落入此分支），末尾重置滚动；</li>
 *   <li>{@code private static CreativeModeTab selectedTab} —— <b>静态</b>字段；读它作逐帧高亮
 *       判据，与 vanilla 自身刷新循环（{@code tab == selectedTab} 直比对）同一口径；</li>
 *   <li>{@code private boolean hasPermissions(Player)} = {@code player.canUseGameMasterBlocks()
 *       && this.displayOperatorCreativeTab}，供合成 {@code ItemDisplayParameters} 时取
 *       permissions 位（与 {@code containerTick} 传参同源）。</li>
 * </ul></p>
 *
 * <p>形态约束：{@code @Accessor} 与 {@code @Invoker} 走同一条附加路径 —— <b>接口 mixin 只能用
 * 实例抽象方法形态</b>（JAVA_8+ 下静态方法走重命名分支会报错；实例方法不得 static，
 * {@link ScreenAccess} 同）。实例抽象方法读静态字段是合法形态（访问器生成器按<b>字段</b>
 * 静态性生成 {@code GETSTATIC}；转换期仅一条 INFO 级提示，不是错误）。</p>
 *
 * <p>footprint：{@code @Accessor} / {@code @Invoker} 不产生注入点、不计入台账（目标类因此多出
 * 1 个接口与 3 个 {@code brush$} 前缀桥接方法，mixin 机制固有产物）；无全局可见性改动
 * （台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen")
public interface CreativeScreenAccess {

    /**
     * {@code selectTab} 的访问门 —— 激活（含未注册的）标签页：以该标签页当前
     * {@code displayItems} 填充菜单。
     */
    @Invoker("selectTab")
    void brush$selectTab(CreativeModeTab tab);

    /** {@code selectedTab} 的访问门（静态字段，实例抽象方法形态见类 javadoc）。 */
    @Accessor("selectedTab")
    CreativeModeTab brush$getSelectedTab();

    /** {@code hasPermissions} 的访问门（合成 {@code ItemDisplayParameters} 用）。 */
    @Invoker("hasPermissions")
    boolean brush$hasPermissions(Player player);
}
