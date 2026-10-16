package org.abstruck.brush.transform;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;

/**
 * 进程原始 stdout 直写通道（L1 实现）。mod 侧请用 L2 契约面
 * {@code org.abstruck.brush.api.RawStdout}（薄委托——与 {@code Dispatcher}/{@code Event} 同一模式），
 * 勿从 mod 直接引用本类（CLAUDE.md §6：mod 只能看到 L2）。
 *
 * <p><b>为什么需要绕过 {@code System.out}</b>（分析见 docs/14 §4）：
 * <ol>
 *   <li>MC 启动过程中把 {@code System.out} 换成 log4j 承接的 {@code LoggingPrintStream} ——
 *       println 变为 {@code Logger.info("[STDOUT]: ...")}；</li>
 *   <li>关闭序列中游戏侧 log4j 先行 shutdown —— 自检退出更是在 {@code System.exit} 前显式
 *       {@code LogManager.shutdown()}（{@code Selftest.report()}）。</li>
 * </ol>
 * 两相叠加 → 关闭期经 {@code System.out}（= 经 log4j）的输出被<b>静默吞掉</b>（无异常、无日志行）。</p>
 *
 * <p>本类改为经 {@link FileDescriptor#out} 直写进程 fd 1：
 * <ul>
 *   <li>不经过任何 Java 层重定向（{@code System.setOut(...)} 与它无关）；</li>
 *   <li>不经过 log4j（其存活与否与它无关）—— 关闭钩子线程中同样送达；</li>
 *   <li>纯 JDK，S1/S2/S3 三运行时语义一致（无 MC / 宿主类型引用）。</li>
 * </ul></p>
 *
 * <p><b>语义</b>：尽力而为的日志通道 —— {@link PrintStream} 契约本身<b>永不抛出</b>
 * （写入失败仅置内部 trouble 标志）。fd 打开失败时（如 javaw 无控制台句柄的极端环境）
 * 回退 {@code System.out}：降级而非失败 —— 证据通道绝不能因打印失败中断关闭序列。</p>
 *
 * <p><b>输出去向</b>：进程 stdout（终端 / 启动器捕获），<b>不进 latest.log</b>
 * （那是 log4j File appender 的通道；这是本通道的已知边界，见 docs/14 §8）。</p>
 */
public final class RawStdout {

    /** 直写 fd 1；构造失败回退 System.out（见类 javadoc"语义"）。 */
    private static final PrintStream OUT = open();

    private RawStdout() {
    }

    private static PrintStream open() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        } catch (Exception e) {
            // 防御性回退：语义降级（回到可能被 log4j 承接的 System.out），但不是失败 ——
            // 本通道是尽力而为设施，不得因打印失败中断关闭序列。
            return System.out;
        }
    }

    /** 输出一行到进程原始 stdout。永不抛出（PrintStream 契约）。任意线程可调用。 */
    public static void println(String message) {
        OUT.println(message);
    }
}
