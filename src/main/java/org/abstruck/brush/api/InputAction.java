package org.abstruck.brush.api;

/**
 * 输入动作 —— GLFW 动作值的语义化枚举。
 *
 * <p><b>值与来源</b>：GLFW 的 {@code GLFW_RELEASE=0 / GLFW_PRESS=1 / GLFW_REPEAT=2}
 * （GLFW 官方常量；26.1.2 的 {@code KeyboardHandler#keyPress} 与 {@code MouseHandler#onButton}
 * 的 {@code action} 参数原样携带该值）。
 * 未知值（未来 GLFW 扩展）映射为 {@link #UNKNOWN}，不抛异常 —— 监听器按需处理。
 *
 * <p>键盘事件可取 {@link #RELEASE} / {@link #PRESS} / {@link #REPEAT}；
 * 鼠标按键事件只取 {@link #RELEASE} / {@link #PRESS}（无 repeat 概念）。
 */
public enum InputAction {

    /** GLFW_RELEASE = 0。 */
    RELEASE(0),
    /** GLFW_PRESS = 1。 */
    PRESS(1),
    /** GLFW_REPEAT = 2（仅键盘）。 */
    REPEAT(2),
    /** 未识别的动作值（防御性；当前 GLFW 无此情形）。 */
    UNKNOWN(-1);

    private final int id;

    InputAction(int id) {
        this.id = id;
    }

    /** 原始 GLFW 动作值（{@link #UNKNOWN} 为 -1）。 */
    public int id() {
        return this.id;
    }

    /** 动作值 → 枚举（未知值 → {@link #UNKNOWN}，不抛异常）。 */
    public static InputAction of(int id) {
        return switch (id) {
            case 0 -> RELEASE;
            case 1 -> PRESS;
            case 2 -> REPEAT;
            default -> UNKNOWN;
        };
    }
}
