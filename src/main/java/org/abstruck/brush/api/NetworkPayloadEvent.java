package org.abstruck.brush.api;

import net.minecraft.resources.Identifier;

/**
 * 语义事件类型：收到 Brush 通道载荷（L2；对应 L1 {@code network.payload}）。
 *
 * <p><b>L1 来源</b>：注入点
 * {@code ClientCommonPacketListenerImpl#handleCustomPayload(ClientboundCustomPayloadPacket)}
 * 的 HEAD（投递侧；仅当载荷是本生态的 {@code BrushPayload} 时派发 ——
 * vanilla 载荷零开销，见 {@code HookEvents} 与 {@code ClientCommonPacketListenerMixin} javadoc）。
 * 派发链 mixin → {@code Dispatcher.fire} → 本事件。
 *
 * <p><b>触发时机</b>：客户端从服务器收到一个<b>已注册通道</b>的 Brush 载荷包时
 * （通道经 {@link BrushNetwork#register} 注册；编码/解码链保证未注册 id 不会到达此事件）。
 * 触发线程 = 处理该包的线程（连接建起的包处理路径；通常为游戏主线程或网络线程，
 * 取决于 vanilla 的包调度 —— 监听器如需触碰客户端状态应自行切回主线程
 * {@code Minecraft.getInstance().execute(...)}）。
 *
 * <p><b>载荷语义</b>：{@code channel} = 通道 id、{@code data} = 载荷字节（解码时已按
 * 通道上限校验；{@code data} 为本次解码新建的数组，监听器可安全持有）。
 *
 * <p><b>端到端限制（如实登记）</b>：本事件覆盖"服务器→客户端"方向的投递。
 * 与世界内服务器的完整往返需要服务端存在同名通道对端 —— 专有服务器不在本项目范围，
 * 单机世界的集成服务器为 vanilla 代码（未知通道按 vanilla 语义
 * 丢弃，这是 vanilla 自定义载荷机制的设计行为，非故障）。编码/解码/派发链的正确性由
 * 自检探针离线断言（codec 往返 + 负控，{@code SelftestNetworkProbe}）。
 *
 * <p>与其他事件类惯例一致：本类是监听器接口的命名空间（无 {@code INSTANCE}，不可实例化）。
 */
public final class NetworkPayloadEvent {

    private NetworkPayloadEvent() {
    }

    /** 监听器接口（{@link BrushEvents#NETWORK_PAYLOAD} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param channel 通道 id（注册时的 Identifier）
         * @param data    载荷字节（长度已按通道上限校验）
         */
        void onNetworkPayload(Identifier channel, byte[] data);
    }
}
