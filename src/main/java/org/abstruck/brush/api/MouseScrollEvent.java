package org.abstruck.brush.api;

/**
 * 语义事件类型：鼠标滚轮回调（L2；对应 L1 {@code input.mouse.scroll}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code MouseHandler#onScroll} 的 HEAD
 * （先于方法头部的窗口句柄守卫执行 —— 见 {@code HookEvents} 与 {@code MouseHandlerMixin}
 * javadoc）。派发链 mixin → {@code Dispatcher.fire} → 本事件。
 *
 * <p><b>触发时机</b>：每次滚轮回调（一次滚轮步 1 次；高分辨率滚轮可连发）。
 * 触发线程 = 游戏主线程（onScroll 经 {@code Minecraft.execute} 转发）。
 *
 * <p><b>载荷语义</b>：{@code xOffset} / {@code yOffset} 为 GLFW 原始滚轮增量
 * （水平 / 垂直；vanilla 在此之上应用 {@code discreteMouseScroll} 等选项，本事件是
 * <b>选项处理之前</b>的原始值 —— HEAD 位置决定）。不携带窗口句柄。
 *
 * <p>与其他事件类惯例一致：本类是监听器接口的命名空间（无 {@code INSTANCE}，不可实例化）。
 */
public final class MouseScrollEvent {

    private MouseScrollEvent() {
    }

    /** 监听器接口（{@link BrushEvents#INPUT_MOUSE_SCROLL} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param xOffset 水平滚轮增量（GLFW 原始值）
         * @param yOffset 垂直滚轮增量（GLFW 原始值）
         */
        void onMouseScroll(double xOffset, double yOffset);
    }
}
