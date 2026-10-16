package org.abstruck.brush.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * 语义事件类型：屏幕布局完成（L2；对应 L1 {@code screen.init}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code Screen#init(II)V} TAIL —— <b>0 新增 footprint</b>；
 * 派发链 mixin → {@code Dispatcher.fire} → 本事件的监听器。
 *
 * <p><b>触发时机（精确语义）</b>：屏幕首次显示完成 / 同一实例重复 init 完成 —— 两条路径的
 * TAIL 均为"widget 就绪"（首显 = 子类 {@code init()} 完成态，重复 = "先清后建"完成态，
 * 全链见 {@code HookEvents#SCREEN_INIT} javadoc）。<b>对所有屏幕派发</b> ——
 * 类型过滤是监听器的职责（如只处理 {@code TitleScreen}）。窗口缩放路径<b>不经过</b>本事件
 * （走 {@link ScreenResizeEvent}）。
 *
 * <p><b>触发线程</b>：游戏主线程。
 *
 * <p><b>为什么没有事件对象实例</b>：载荷就是 {@link Screen} 实例本身，监听器直接接收它
 * （对象同一性；每次触发零分配）。与其它事件类的惯例一致，本类只作为监听器接口的命名空间
 * （无 {@code INSTANCE}，不可实例化）。
 */
public final class ScreenInitEvent {

    private ScreenInitEvent() {
    }

    /** 监听器接口（{@link BrushEvents#SCREEN_INIT} 的注册类型）；载荷 = 完成布局的屏幕实例。 */
    @FunctionalInterface
    public interface Listener {
        void onScreenInit(Screen screen);
    }
}
