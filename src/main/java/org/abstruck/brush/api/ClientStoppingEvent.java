package org.abstruck.brush.api;

/**
 * 语义事件：客户端进程关闭（L2；对应 L1 {@code client.stopping}）—— <b>延后事件</b>。
 *
 * <p><b>L1 来源</b>：<b>不是注入点</b>，而是 Brush 安装的 JVM shutdown hook
 * （关闭时刻无可复用注入点；shutdown hook 覆盖面严格更大、三运行时一致，零 footprint）。
 * 这使它在三运行时（S1/S2/S3）行为完全一致，与宿主无关。
 *
 * <p><b>触发时机（精确语义）</b>：JVM 关闭序列中 —— 正常退窗、{@code System.exit}
 * （含 {@code --brush.selftest} 的自检退出）均触发；<b>仅当客户端确实启动过</b>
 * （{@link BrushEvents#CLIENT_READY} 已触发）时派发。不触发：进程被强杀
 * （任务管理器 / SIGKILL）、{@code Runtime.halt}。
 *
 * <p><b>触发线程</b>：JVM 关闭线程（<b>非游戏主线程</b>）——
 * 该线程上不得假定游戏状态可用（游戏线程可能已结束），且进程很快退出：
 * 监听器应只做轻量清理（刷盘 / 写配置），不要做交互式或长耗时操作；
 * 也不要依赖与其他 mod/宿主的关闭代码的先后顺序（JVM 不保证 shutdown hook 间顺序）。
 *
 * <p><b>日志注意</b>：触发时游戏侧 log4j 已（或将）关闭，且 {@code System.out} 已被 MC
 * 换成 log4j 承接的流 —— 此时经 log4j / {@code System.out} 的输出会被<b>静默丢弃</b>
 * （无异常）。监听器需要留下证据时请用 {@link RawStdout}
 * （fd 1 直写，关闭期仍然送达）。
 *
 * <p>事件对象为无载荷单例（{@link #INSTANCE}）；请勿依赖对象身份做去重或状态存储。
 */
public final class ClientStoppingEvent {

    /** 共享单例（无载荷、不可变；桥接零分配）。 */
    public static final ClientStoppingEvent INSTANCE = new ClientStoppingEvent();

    private ClientStoppingEvent() {
    }

    /** 监听器接口（{@link BrushEvents#CLIENT_STOPPING} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {
        void onClientStopping(ClientStoppingEvent event);
    }
}
