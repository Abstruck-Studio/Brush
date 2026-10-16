package org.abstruck.brush.api;

/**
 * 语义事件类型：鼠标按键回调（L2；对应 L1 {@code input.mouse.button}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code MouseHandler#onButton} 的 HEAD
 * （先于方法头部的窗口句柄守卫执行 —— 见 {@code HookEvents} 与 {@code MouseHandlerMixin}
 * javadoc）。派发链 mixin → {@code Dispatcher.fire} → 本事件。
 *
 * <p><b>触发时机</b>：每次鼠标按键回调。触发线程 = 游戏主线程
 * （onButton 经 {@code Minecraft.execute} 转发）。
 *
 * <p><b>载荷语义</b>：{@code button} / {@code modifiers} 为 GLFW 原始值（与 vanilla
 * {@code MouseButtonInfo} record 的字段一一对应）；{@code action} 见 {@link InputAction}
 * （鼠标只有 RELEASE / PRESS）。不携带窗口句柄。
 *
 * <p>与其他事件类惯例一致：本类是监听器接口的命名空间（无 {@code INSTANCE}，不可实例化）。
 */
public final class MouseButtonEvent {

    private MouseButtonEvent() {
    }

    /** 监听器接口（{@link BrushEvents#INPUT_MOUSE_BUTTON} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param action    GLFW 动作（RELEASE / PRESS）
         * @param button    GLFW 鼠标键码（GLFW_MOUSE_BUTTON_* 值）
         * @param modifiers GLFW 修饰键位掩码
         */
        void onMouseButton(InputAction action, int button, int modifiers);
    }
}
