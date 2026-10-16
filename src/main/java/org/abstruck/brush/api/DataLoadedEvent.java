package org.abstruck.brush.api;

import net.minecraft.server.MinecraftServer;

/**
 * 服务器数据加载完成事件（L2 语义层，G1，docs/21）—— {@link BrushEvents#DATA_LOADED} 的监听器类型。
 *
 * <p><b>触发时机</b>：集成服务器构造完成（{@code MinecraftServer#<init>} RETURN）——
 * {@code server.getResourceManager()} 已非空（世界数据全部就位）；Brush 已完成本轮数据包注册表
 * 装载（先装载后派发 —— 监听器读到的是最新视图；装载失败时读到旧表 / 空表并自行降级，
 * 逐键原因见 ERROR 日志，语义见 {@code DataRegistryEngine} javadoc）。每个服务器实例一次
 * （单机 = 进世界 / 建世界时；LAN 主机同）；远端多人服务器不触发（客户端 JVM 无
 * {@code MinecraftServer}，docs/21 已知边界）。</p>
 *
 * <p><b>触发线程</b>：服务器构造线程（同步调用）。监听器内的长时间工作会阻塞服务器构造 ——
 * 重活应转交异步或后续生命周期事件。</p>
 */
public final class DataLoadedEvent {

    private DataLoadedEvent() {
    }

    @FunctionalInterface
    public interface Listener {

        /** 服务器数据加载完成回调。 */
        void onDataLoaded(MinecraftServer server);
    }
}
