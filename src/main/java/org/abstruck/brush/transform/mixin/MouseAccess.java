package org.abstruck.brush.transform.mixin;

import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code MouseHandler} 私有成员访问门（CLAUDE.md §10.4：优先 {@code @Accessor} / {@code @Invoker}
 * 方案）—— 自检探针的 live-fire 载体（docs/19 §9）。
 *
 * <p>目标：{@code private void onButton(long window, MouseButtonInfo info, int action)} 与
 * {@code private void onScroll(long window, double xOffset, double yOffset)}。探针以伪窗口句柄
 * （{@code 0L}）调用：{@link MouseHandlerMixin} 的 HEAD 注入照常派发，vanilla 体在方法头部的
 * 窗口句柄守卫处立即返回 —— 全链路可断言且零副作用（{@code SelftestInputHudProbe}）。</p>
 *
 * <p>footprint：{@code @Invoker} 不产生注入点、不计入台账（接口被 mixin 合并进
 * {@code MouseHandler} 使目标类多出 2 个 {@code brush$} 前缀方法，mixin 机制固有产物）；
 * 无全局可见性改动（台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.client.MouseHandler")
public interface MouseAccess {

    /** {@code MouseHandler.onButton} 的访问门（见类 javadoc）。 */
    @Invoker("onButton")
    void brush$onButton(long window, MouseButtonInfo info, int action);

    /** {@code MouseHandler.onScroll} 的访问门（见类 javadoc）。 */
    @Invoker("onScroll")
    void brush$onScroll(long window, double xOffset, double yOffset);
}
