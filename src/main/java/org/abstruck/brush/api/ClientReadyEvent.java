package org.abstruck.brush.api;

/**
 * 语义事件：客户端主循环开始（L2；对应 L1 {@code client.run}）。
 *
 * <p><b>L1 来源</b>：既有注入点 {@code net.minecraft.client.Minecraft#run()V} HEAD
 * —— <b>0 新增 footprint</b>；派发链
 * mixin → {@code Dispatcher.fire} → 本事件监听器。
 *
 * <p><b>触发时机（精确语义）</b>：游戏构造器已完成（窗口与 GL 上下文已创建）、
 * {@code Minecraft#run()} 刚进入、主循环尚未推进 —— 资源加载与主菜单在此之后。每进程恰一次。
 *
 * <p><b>触发线程</b>：游戏主线程。
 *
 * <p>事件对象为无载荷单例（{@link #INSTANCE}）；请勿依赖对象身份做去重或状态存储。
 */
public final class ClientReadyEvent {

    /** 共享单例（无载荷、不可变；桥接零分配）。 */
    public static final ClientReadyEvent INSTANCE = new ClientReadyEvent();

    private ClientReadyEvent() {
    }

    /** 监听器接口（{@link BrushEvents#CLIENT_READY} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {
        void onClientReady(ClientReadyEvent event);
    }
}
