package org.abstruck.brush.api;

import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * 服务器数据重载完成事件（L2 语义层，G1，docs/21）—— {@link BrushEvents#DATA_RELOADED}
 * 的监听器类型。
 *
 * <p><b>触发时机</b>：{@code /reload} / 数据包变更触发的
 * {@code MinecraftServer.reloadResources} 完成（返回的 future 完成 = 资源交换完成）。
 * 载荷 {@code error} 为空 = vanilla 重载与 Brush 数据包注册表重载均成功（此时
 * {@link BrushRegistry#dataRegistry} 已是新视图）；非空 = vanilla 重载失败（原异常）
 * 或 Brush 表重载失败（说明"旧表保留"的 {@code IllegalStateException} ——
 * 逐键原因见 ERROR 日志）。</p>
 *
 * <p><b>触发线程（注意）</b>：重载 future 的完成线程 —— <b>异步</b>，不是服务器主线程。
 * 监听器不得触碰屏幕 / 渲染 / 未同步的游戏状态；需要主线程语义的工作请转交调度。</p>
 */
public final class DataReloadedEvent {

    private DataReloadedEvent() {
    }

    @FunctionalInterface
    public interface Listener {

        /** 服务器数据重载完成回调。 */
        void onDataReloaded(MinecraftServer server, Optional<Throwable> error);
    }
}
