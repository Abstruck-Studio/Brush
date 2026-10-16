package org.abstruck.brush.api;

/**
 * L2 契约面：进程原始 stdout 直写（实现见 {@code org.abstruck.brush.transform.RawStdout}，
 * 薄委托，与 {@code Event}→{@code Dispatcher} 同一模式）。
 *
 * <p><b>什么时候需要它</b>：{@link BrushEvents#CLIENT_STOPPING} 的监听器在 JVM 关闭序列中执行，
 * 此时游戏侧 log4j 已（或将）关闭，且 MC 启动时已把 {@code System.out} 换成 log4j 承接的流
 * —— 经 log4j 或 {@code System.out} 的关闭期输出会被<b>静默丢弃</b>（无异常）。
 * 关闭期需要可靠留痕（如"已保存状态""已刷盘"）的监听器应使用本通道。
 *
 * <p><b>语义（三运行时 S1/S2/S3 一致）</b>：
 * <ul>
 *   <li>输出直达进程 stdout（终端 / 启动器捕获）——<b>不进 latest.log</b>；</li>
 *   <li>不经过 log4j、不经过 {@code System.out} 的重定向 —— 任意线程、任意时刻可用；</li>
 *   <li>永久不抛异常（尽力而为通道）。</li>
 * </ul>
 *
 * <p>其余事件（{@code CLIENT_READY} / {@code CLIENT_TICK}）触发时 log4j 正常存活，
 * 常规 log4j 日志即可；本通道不是它们的替代品。
 */
public final class RawStdout {

    private RawStdout() {
    }

    /** 输出一行到进程原始 stdout。永不抛出。任意线程可调用。 */
    public static void println(String message) {
        org.abstruck.brush.transform.RawStdout.println(message);
    }
}
