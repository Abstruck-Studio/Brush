package org.abstruck.brush.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * 语义事件类型：屏幕缩放完成（L2；对应 L1 {@code screen.resize}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code Screen#resize(II)V} TAIL —— <b>0 新增 footprint</b>；
 * 派发链 mixin → {@code Dispatcher.fire} → 本事件的监听器。
 *
 * <p><b>触发时机（精确语义）</b>：窗口缩放完成（widget 已按新尺寸"先清后建"完毕，
 * 全链见 {@code HookEvents#SCREEN_RESIZE} javadoc）。<b>对所有屏幕派发</b> ——
 * 类型过滤是监听器的职责。非热路径但可突发：窗口拖拽期间随窗口系统事件连续触发，
 * 频率远低于每帧 tick。
 *
 * <p><b>触发线程</b>：游戏主线程。
 *
 * <p><b>为什么没有事件对象实例</b>：载荷就是 {@link Screen} 实例本身，监听器直接接收它
 * （对象同一性；每次触发零分配）。与其它事件类的惯例一致，本类只作为监听器接口的命名空间
 * （无 {@code INSTANCE}，不可实例化）。
 */
public final class ScreenResizeEvent {

    private ScreenResizeEvent() {
    }

    /** 监听器接口（{@link BrushEvents#SCREEN_RESIZE} 的注册类型）；载荷 = 完成缩放布局的屏幕实例。 */
    @FunctionalInterface
    public interface Listener {
        void onScreenResize(Screen screen);
    }
}
