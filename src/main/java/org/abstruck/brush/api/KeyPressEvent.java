package org.abstruck.brush.api;

/**
 * 语义事件类型：键盘按键回调（L2；对应 L1 {@code input.key.press}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code KeyboardHandler#keyPress} 的 HEAD
 * （GLFW 键盘回调的主线程入口，先于方法头部的窗口句柄守卫执行 —— 见 {@code HookEvents}
 * 与 {@code KeyboardHandlerMixin} javadoc）。派发链 mixin → {@code Dispatcher.fire} → 本事件。
 *
 * <p><b>触发时机</b>：每次按键回调（含 GLFW REPEAT 重复）—— 具体含哪些动作由
 * action 参数区分。触发线程 = 游戏主线程（keyPress 恒在 {@code Minecraft.execute} 内执行）。
 *
 * <p><b>载荷语义</b>：{@code key} / {@code scancode} / {@code modifiers} 为 GLFW 原始值
 * （与 vanilla {@code KeyEvent} record 的字段一一对应，原样透传）；
 * {@code action} 见 {@link InputAction}。不携带窗口句柄（无 mod 用途）。
 *
 * <p>与其他事件类惯例一致：本类是监听器接口的命名空间（无 {@code INSTANCE}，不可实例化）。
 */
public final class KeyPressEvent {

    private KeyPressEvent() {
    }

    /** 监听器接口（{@link BrushEvents#INPUT_KEY_PRESS} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param action    GLFW 动作（RELEASE / PRESS / REPEAT）
         * @param key       GLFW 键码（GLFW_KEY_* 值）
         * @param scancode  平台扫描码
         * @param modifiers GLFW 修饰键位掩码
         */
        void onKeyPress(InputAction action, int key, int scancode, int modifiers);
    }
}
