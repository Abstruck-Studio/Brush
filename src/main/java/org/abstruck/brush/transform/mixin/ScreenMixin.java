package org.abstruck.brush.transform.mixin;

import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 屏幕布局注入 —— L1 hook {@code screen.init} / {@code screen.resize}（模组列表界面，docs/17）。
 *
 * <p><b>为什么是两个注入点</b>：{@code init(int,int)} 覆盖"首次显示 + 重复 init"（同一实例
 * 重复调用走 {@code repositionElements()}），{@code resize(int,int)} 覆盖"窗口缩放"—— 该路径
 * <b>不经过</b> {@code init(II)V}，单点方案会漏。两条路径最终都以 TAIL 之后"widget 就绪"为
 * 语义（重复 init / 缩放是"先清后建"后的完成态；监听器由此采用"先移除再添加"的幂等挂载，
 * {@code ModsUi}）。TAIL 选址 = CLAUDE.md §10.3 第 2 条首选（只依赖方法存在）。</p>
 *
 * <p><b>priority = 2000</b>：fabric-api 的 {@code ScreenMixin.afterInitScreen} 与本类同在
 * {@code init(II)V} TAIL 注入（默认优先级 1000），且其在标题界面平移全部按钮 —— 2000 使我们的
 * fire 晚于它执行，读到已就位的锚点坐标。</p>
 *
 * <p>织入语义（§10.1）：每点仅 1 条 {@code Dispatcher.fire} 静态调用，派发参数为屏幕实例
 * （对象同一性即目标类实例；本事件对<b>所有</b>屏幕派发，监听器自行过滤类型）。handler 不自行
 * 做任何工作（§8.3 薄注入）；宽度/高度监听器直接读屏幕字段（TAIL 时已赋值）。</p>
 *
 * <p>冷热：非热路径 —— 每屏首次布局 1 次；窗口拖拽缩放期间 {@code resize} 可连续触发，频率受
 * 窗口系统事件驱动，量级远低于每帧 tick。</p>
 *
 * <p>footprint：2 个注入点、1 个被注入类（台账 docs/footprint.md 与 docs/17）。</p>
 */
@Mixin(targets = "net.minecraft.client.gui.screens.Screen", priority = 2000)
public abstract class ScreenMixin {

    /** 目标：{@code Screen.init(int,int)} 的 TAIL（首显 / 重复 init 完成态）。 */
    @Inject(method = "init(II)V", at = @At("TAIL"), require = 1)
    private void brush$fireScreenInit(int width, int height, CallbackInfo ci) {
        Dispatcher.fire(HookEvents.SCREEN_INIT, this);
    }

    /** 目标：{@code Screen.resize(int,int)} 的 TAIL（缩放路径，先清后建完成态）。 */
    @Inject(method = "resize(II)V", at = @At("TAIL"), require = 1)
    private void brush$fireScreenResize(int width, int height, CallbackInfo ci) {
        Dispatcher.fire(HookEvents.SCREEN_RESIZE, this);
    }
}
