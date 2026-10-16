package org.abstruck.brush.transform.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code Screen} 保护成员访问门（CLAUDE.md §10.4：优先 {@code @Accessor} / {@code @Invoker}
 * 这类<b>不产生全局可见性变化</b>的方案，替代 access widener）。
 *
 * <p>目标：{@code protected <T extends GuiEventListener & Renderable & NarratableEntry> T
 * addRenderableWidget(T)}（泛型擦除后按最左界描述符声明，本接口注释值 = 目标方法名）与
 * {@code protected void removeWidget(GuiEventListener)}。{@code removeWidget} 对不在列表中的
 * widget 是纯空操作（{@code List.remove} 语义）—— 挂载方"先移除再添加"的幂等性依赖此性质；
 * 两者成对使用不留残余（remove 覆盖 add 触及的全部三个列表）。</p>
 *
 * <p>footprint：{@code @Invoker} 不产生注入点、不计入台账（接口被 mixin 合并进 {@code Screen}
 * 使目标类多出 2 个 {@code brush$} 前缀方法，mixin 机制固有产物）；无全局可见性改动
 * （台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.Screen")
public interface ScreenAccess {

    /** {@code Screen.addRenderableWidget} 的访问门（擦除后签名，见类 javadoc）。 */
    @Invoker("addRenderableWidget")
    GuiEventListener brush$addRenderableWidget(GuiEventListener widget);

    /** {@code Screen.removeWidget} 的访问门（对缺席 widget 为空操作，见类 javadoc）。 */
    @Invoker("removeWidget")
    void brush$removeWidget(GuiEventListener widget);
}
