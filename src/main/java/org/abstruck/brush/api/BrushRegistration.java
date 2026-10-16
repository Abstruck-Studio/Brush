package org.abstruck.brush.api;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 注册辅助函数式接口（L2；E2）—— mod 在 {@link BrushRegistry} 的注册方法中提供的
 * 工厂形。<b>为什么需要独立的公开接口</b>：26.1.2 的对应 vanilla 参数类型是
 * <b>包私有</b>的（{@code MenuType$MenuSupplier} / {@code MenuScreens$ScreenConstructor} /
 * {@code BlockEntityType$BlockEntitySupplier}）—— mod 侧无法书写这些类型，连
 * {@code @Invoker} 都编译不过。mod 实现本类的公开接口，Brush 在运行时经
 * {@code MethodHandles} 适配到 vanilla 的包私有形（机制见 {@code registry.ReflectiveSupport}）。
 *
 * <p><b>签名为什么不 1:1 镜像 vanilla</b>（CLAUDE.md §6）：这里镜像的是<b>语义</b>
 * （"从容器 id / 菜单实例创建菜单 / 屏幕 / 方块实体"），并<b>收窄</b>到公开类型；
 * vanilla 侧包私有签名一旦变化，改的是 {@code registry} 包内的桥接，不动本接口。</p>
 *
 * <p><b>调用契约</b>：这些工厂对象本身在构造期不得触碰游戏状态 —— 它们只在对应
 * 菜单 / 屏幕 / 方块实体被创建时（玩家交互 / 世界加载之后）被回调。</p>
 *
 * <p><b>零 MC 惰性约束的例外说明</b>：本类引用 MC 公开内容模型类型
 * （与 {@link BrushRegistry} 同款口径）—— 只在注册窗口内被解析，见该类的"类加载纪律"。</p>
 */
public final class BrushRegistration {

    private BrushRegistration() {
    }

    /**
     * 菜单工厂（对位 {@code MenuType$MenuSupplier#create(int, Inventory)}，包私有）。
     * 典型用法：{@code BrushRegistry.registerMenuType(id, MyMenu::new)}（子类构造器
     * 形如 {@code (int containerId, Inventory inventory)}）。
     */
    @FunctionalInterface
    public interface BrushMenuSupplier<M extends AbstractContainerMenu> {

        /** 创建菜单实例（服务端与客户端各自调用 —— 与 vanilla 菜单工厂同一契约）。 */
        M create(int containerId, Inventory inventory);
    }

    /**
     * 菜单屏幕工厂（对位 {@code MenuScreens$ScreenConstructor#create(M, Inventory, Component)}，
     * 包私有）。类型约束与 vanilla 的 {@code ScreenConstructor} 一致：屏幕必须实现
     * {@link MenuAccess}（{@code AbstractContainerScreen} 子类天然满足）。
     * 典型用法：{@code BrushRegistry.registerMenuScreen(myMenuType, MyScreen::new)}。
     */
    @FunctionalInterface
    public interface BrushScreenFactory<M extends AbstractContainerMenu,
            S extends Screen & MenuAccess<M>> {

        /** 创建屏幕实例（{@code title} 为打开数据包携带的标题 —— 与 vanilla 同一契约）。 */
        S create(M menu, Inventory inventory, Component title);
    }

    /**
     * 方块实体工厂（对位 {@code BlockEntityType$BlockEntitySupplier#create(BlockPos, BlockState)}，
     * 包私有）。典型用法：{@code BrushRegistry.registerBlockEntityType(id, MyBlockEntity::new, block)}。
     */
    @FunctionalInterface
    public interface BrushBlockEntitySupplier<T extends BlockEntity> {

        /** 创建方块实体实例（与 vanilla 方块实体工厂同一契约）。 */
        T create(BlockPos pos, BlockState state);
    }
}
