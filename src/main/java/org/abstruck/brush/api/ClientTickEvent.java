package org.abstruck.brush.api;

/**
 * 语义事件：客户端每帧 tick（L2；对应 L1 {@code client.tick}）。<b>热路径</b>（约 20 次/秒）。
 *
 * <p><b>L1 来源</b>：既有注入点 {@code net.minecraft.client.Minecraft#tick()V} HEAD
 * —— <b>0 新增 footprint</b>。
 *
 * <p><b>触发时机（精确语义）</b>：每帧 tick 起始（{@code Minecraft#tick()} 刚进入）。
 * 无监听器时注入点为零分配短路（量化论证见 {@code MinecraftMixin}）；
 * 有监听器时本事件的桥接同样零分配 —— 事件对象为共享无载荷单例（{@link #INSTANCE}），
 * 监听器调用不产生任何每帧新对象。
 *
 * <p><b>触发线程</b>：游戏主线程。监听器应保持轻量（热路径纪律）。
 *
 * <p>事件对象为无载荷单例；请勿依赖对象身份做去重或状态存储
 * （需要每帧状态时在监听器对象自身持有）。
 */
public final class ClientTickEvent {

    /** 共享单例（无载荷、不可变；桥接零分配）。 */
    public static final ClientTickEvent INSTANCE = new ClientTickEvent();

    private ClientTickEvent() {
    }

    /** 监听器接口（{@link BrushEvents#CLIENT_TICK} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {
        void onClientTick(ClientTickEvent event);
    }
}
