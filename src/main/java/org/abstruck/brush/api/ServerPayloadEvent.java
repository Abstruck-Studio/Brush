package org.abstruck.brush.api;

import net.minecraft.resources.Identifier;

/**
 * 语义事件类型：**服务端**收到客户端经 Brush 通道发来的载荷（L2；对应 L1
 * {@code network.server.payload}）。
 *
 * <p><b>为什么需要它</b>（docs/27 §2.7 发现 B）：{@link BrushEvents#NETWORK_PAYLOAD} 只覆盖
 * <b>客户端侧</b>接收，{@link BrushNetwork#send} 只覆盖<b>客户端 → 服务器</b>方向 ——
 * 也就是"单向"。任何**双向**网络 mod（服务器驱动的同步 / 查询）因此写不出来：它要回包就得构造
 * {@code ClientboundCustomPayloadPacket} 与核心内部件 {@code BrushPayload}，而普通 mod
 * **不得触碰 L1 / 内部件**（CLAUDE.md §6）。这是一个**硬阻塞**，不是便利问题。</p>
 *
 * <p><b>触发时机</b>：服务端收到自定义载荷包的入口（{@code ServerGamePacketListenerImpl}
 * 的 {@code handleCustomPayload}）HEAD，且仅当载荷是本生态的 {@code BrushPayload} 实例时派发。
 * 触发线程 = 服务端网络线程。</p>
 *
 * <p><b>只有单向的 {@code send} 被升级为"可回包"</b>：监听器拿到的 {@link Reply} 句柄由
 * <b>库模组</b>实现（它可以引用 L1 与内部件），对 mod 只暴露本接口 —— 于是"双向"这件事
 * 对 mod 而言**仍然只有 L2**，不会把 vanilla 类型泄进 mod 的依赖面。</p>
 *
 * <p>与其他事件类惯例一致：本类是监听器接口的命名空间（无 {@code INSTANCE}，不可实例化）。</p>
 */
public final class ServerPayloadEvent {

    private ServerPayloadEvent() {
    }

    /** 监听器接口（{@link BrushEvents#SERVER_PAYLOAD} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param reply   回包句柄（服务端 → 客户端；可多次调用）
         * @param channel 通道 id（已注册的 Brush 通道）
         * @param data    载荷字节
         */
        void onServerPayload(Reply reply, Identifier channel, byte[] data);
    }

    /**
     * 回包句柄（服务端 → 客户端）。实现由库模组提供（包装服务端连接），
     * mod 只经本接口使用 —— 这样 mod 的依赖面里**不出现任何 vanilla 网络类型**。
     */
    @FunctionalInterface
    public interface Reply {

        /**
         * 向发来该载荷的客户端回一个包。
         *
         * @param channel 通道 id（须已注册；未注册则由编解码链按 vanilla 语义丢弃）
         * @param data    载荷字节（长度须 ≤ 该通道注册时的上限）
         * @throws IllegalArgumentException 载荷超过该通道上限
         */
        void send(Identifier channel, byte[] data);
    }
}
