package org.abstruck.brush.transform.mixin;

import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code KeyboardHandler} 私有成员访问门（CLAUDE.md §10.4：优先 {@code @Accessor} / {@code @Invoker}
 * 这类<b>不产生全局可见性变化</b>的方案，替代 access widener）—— 自检探针的 live-fire 载体
 * （docs/19 §9）。
 *
 * <p>目标：{@code private void keyPress(long window, int action, KeyEvent event)}。探针以伪窗口
 * 句柄（{@code 0L}）调用：{@link KeyboardHandlerMixin} 的 HEAD 注入照常派发，vanilla 体在方法
 * 头部的窗口句柄守卫处立即返回 —— 全链路可断言且零副作用（{@code SelftestInputHudProbe}）。</p>
 *
 * <p>footprint：{@code @Invoker} 不产生注入点、不计入台账（接口被 mixin 合并进
 * {@code KeyboardHandler} 使目标类多出 1 个 {@code brush$} 前缀方法，mixin 机制固有产物）；
 * 无全局可见性改动（台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.client.KeyboardHandler")
public interface KeyboardAccess {

    /** {@code KeyboardHandler.keyPress} 的访问门（擦除后签名即描述符，见类 javadoc）。 */
    @Invoker("keyPress")
    void brush$keyPress(long window, int action, KeyEvent event);
}
