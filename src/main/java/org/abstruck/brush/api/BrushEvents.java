package org.abstruck.brush.api;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import org.abstruck.brush.transform.HookEvents;

import java.util.Optional;

/**
 * Brush 对外语义事件（L2）—— mod 订阅客户端生命周期与注册相位的唯一入口。
 *
 * <p><b>用法</b>（mod 入口类构造期即可注册，此时游戏尚未就绪）：
 * <pre>{@code
 * BrushEvents.CONTENT_REGISTRATION.register(event -> { ... });
 * BrushEvents.CLIENT_TICK.register(event -> { ... });
 * BrushEvents.SCREEN_INIT.register(screen -> { ... });
 * }</pre>
 *
 * <p><b>三运行时同一语义</b>：事件 id、注册与派发机制只依赖 JDK 与 Brush 自身的
 * 派发表（L1）—— 同一份字节码在 S1 / S2 / S3 行为一致。本包对 MC 类型的引用均为<b>惰性载荷
 * 引用</b>（如 {@link ScreenInitEvent.Listener} 的 {@code Screen} 参数、
 * {@link BrushCreativeTabs} 的 {@code CreativeModeTab} 键类型）：注册路径
 * （{@link Event#register}）不解析任何 MC 类型，载荷只在事件真正触发、监听器被调用时才解析。
 * 需要客户端实例时用 {@code net.minecraft.client.Minecraft.getInstance()}（官方名，三运行时一致）。
 *
 * <p><b>触发线程</b>：{@link #CONTENT_REGISTRATION} 与 {@link #CLIENT_REGISTRATION}
 * 在执行 bootstrap 的线程（S1 为游戏主线程，早于 {@link #CLIENT_READY}；同一 vanilla 方法
 * 的 HEAD / TAIL 两点）；{@link #PACKS_REGISTERED} 在构造资源包仓库的线程
 * （客户端主仓库 = 游戏主线程）；{@link #CLIENT_READY} / {@link #CLIENT_TICK} /
 * {@link #SCREEN_INIT} / {@link #SCREEN_RESIZE} 在游戏主线程；
 * {@link #CLIENT_STOPPING} 在 JVM 关闭线程；{@link #DATA_LOADED} 在构造该服务器的线程
 * （同步），{@link #DATA_RELOADED} 在重载 future 的完成线程（<b>异步</b> —— 监听器不得
 * 触碰屏幕 / 渲染 / 未同步的游戏状态）。各事件的精确时机与语义见对应事件类 javadoc。
 */
public final class BrushEvents {

    /** 内容注册相位（唯一合法注册窗口，每进程恰一次；时机/线程见 {@link ContentRegistrationEvent}）。 */
    public static final Event<ContentRegistrationEvent.Listener> CONTENT_REGISTRATION = new Event<>(
            HookEvents.REGISTRIES_BOOTSTRAP,
            listener -> args -> listener.onContentRegistration(ContentRegistrationEvent.INSTANCE));

    /**
     * 客户端注册窗口（每进程恰一次；缓冲注册统一落地 + 最后一批注册机会，
     * 时机/线程见 {@link ClientRegistrationEvent}）。
     */
    public static final Event<ClientRegistrationEvent.Listener> CLIENT_REGISTRATION = new Event<>(
            HookEvents.CLIENT_REGISTRATION,
            listener -> args -> listener.onClientRegistration(ClientRegistrationEvent.INSTANCE));

    /** 资源包仓库构建完成（每实例一次，时机/线程见 {@link ResourcePacksRegisteredEvent}）。 */
    public static final Event<ResourcePacksRegisteredEvent.Listener> PACKS_REGISTERED = new Event<>(
            HookEvents.PACKS_REGISTERED,
            listener -> args -> listener.onPacksRegistered(ResourcePacksRegisteredEvent.INSTANCE));

    /** 屏幕布局完成（对所有屏幕派发，载荷/时机见 {@link ScreenInitEvent}）。 */
    public static final Event<ScreenInitEvent.Listener> SCREEN_INIT = new Event<>(
            HookEvents.SCREEN_INIT,
            listener -> args -> listener.onScreenInit((Screen) args[0]));

    /** 屏幕缩放完成（对所有屏幕派发，载荷/时机见 {@link ScreenResizeEvent}）。 */
    public static final Event<ScreenResizeEvent.Listener> SCREEN_RESIZE = new Event<>(
            HookEvents.SCREEN_RESIZE,
            listener -> args -> listener.onScreenResize((Screen) args[0]));

    /** 客户端主循环开始（每进程恰一次；时机/线程见 {@link ClientReadyEvent}）。 */
    public static final Event<ClientReadyEvent.Listener> CLIENT_READY = new Event<>(
            HookEvents.CLIENT_RUN,
            listener -> args -> listener.onClientReady(ClientReadyEvent.INSTANCE));

    /** 客户端每帧 tick（热路径；零分配桥接见 {@link ClientTickEvent}）。 */
    public static final Event<ClientTickEvent.Listener> CLIENT_TICK = new Event<>(
            HookEvents.CLIENT_TICK,
            listener -> args -> listener.onClientTick(ClientTickEvent.INSTANCE));

    /** 客户端进程关闭（延后事件：进程退出时触发，见 {@link ClientStoppingEvent}）。 */
    public static final Event<ClientStoppingEvent.Listener> CLIENT_STOPPING = new Event<>(
            HookEvents.CLIENT_STOPPING,
            listener -> args -> listener.onClientStopping(ClientStoppingEvent.INSTANCE));

    /** HUD 提取完成（每渲染帧 1 次；热路径，载荷/时机见 {@link HudExtractEvent}）。 */
    public static final Event<HudExtractEvent.Listener> HUD_EXTRACT = new Event<>(
            HookEvents.HUD_EXTRACT,
            listener -> args -> listener.onHudExtract(
                    (GuiGraphicsExtractor) args[0], (DeltaTracker) args[1]));

    /** 键盘按键回调（GLFW 原始值，语义见 {@link KeyPressEvent}）。 */
    public static final Event<KeyPressEvent.Listener> INPUT_KEY_PRESS = new Event<>(
            HookEvents.INPUT_KEY_PRESS,
            listener -> args -> {
                KeyEvent event = (KeyEvent) args[0];
                listener.onKeyPress(InputAction.of((Integer) args[1]),
                        event.key(), event.scancode(), event.modifiers());
            });

    /** 鼠标按键回调（GLFW 原始值，语义见 {@link MouseButtonEvent}）。 */
    public static final Event<MouseButtonEvent.Listener> INPUT_MOUSE_BUTTON = new Event<>(
            HookEvents.INPUT_MOUSE_BUTTON,
            listener -> args -> {
                MouseButtonInfo info = (MouseButtonInfo) args[0];
                listener.onMouseButton(InputAction.of((Integer) args[1]),
                        info.button(), info.modifiers());
            });

    /** 鼠标滚轮回调（GLFW 原始增量，语义见 {@link MouseScrollEvent}）。 */
    public static final Event<MouseScrollEvent.Listener> INPUT_MOUSE_SCROLL = new Event<>(
            HookEvents.INPUT_MOUSE_SCROLL,
            listener -> args -> listener.onMouseScroll((Double) args[0], (Double) args[1]));

    /** 收到 Brush 通道载荷（投递侧，语义与限制见 {@link NetworkPayloadEvent}）。 */
    public static final Event<NetworkPayloadEvent.Listener> NETWORK_PAYLOAD = new Event<>(
            HookEvents.NETWORK_PAYLOAD,
            // 载荷契约 = (Identifier id, byte[] data)（2026-10-15 改）：桥不再持有具体载荷类型。
            listener -> args -> listener.onNetworkPayload((Identifier) args[0], (byte[]) args[1]));

    /**
     * 服务端收到 Brush 通道载荷（双向通信的服务端侧；语义见 {@link ServerPayloadEvent}）。
     *
     * <p>载荷：[0] = {@link ServerPayloadEvent.Reply}（回包句柄，实现由库模组 {@code brush-api}
     * 提供）、[1] = {@code Identifier}（通道 id）、[2] = {@code byte[]}（数据）。
     * **mod 侧看不到任何 vanilla 网络类型** —— 这是"双向对 mod 仍然只是 L2"的关键。</p>
     */
    public static final Event<ServerPayloadEvent.Listener> SERVER_PAYLOAD = new Event<>(
            HookEvents.SERVER_PAYLOAD,
            listener -> args -> listener.onServerPayload((ServerPayloadEvent.Reply) args[0],
                    (Identifier) args[1], (byte[]) args[2]));

    /** 服务器数据加载完成（每服务器实例一次；载荷/时机见 {@link DataLoadedEvent}）。 */
    public static final Event<DataLoadedEvent.Listener> DATA_LOADED = new Event<>(
            HookEvents.DATA_LOADED,
            listener -> args -> listener.onDataLoaded((MinecraftServer) args[0]));

    /**
     * 服务器数据重载完成（每次重载一次；载荷/时机见 {@link DataReloadedEvent}）。
     * 载荷 [1] = {@code Optional<Throwable>}（派发侧构造保证 —— 见 {@code DataRegistryEngine}）。
     */
    @SuppressWarnings("unchecked") // 桥接处唯一强转点：Optional<Throwable> 无法从 Object 免检取回
    public static final Event<DataReloadedEvent.Listener> DATA_RELOADED = new Event<>(
            HookEvents.DATA_RELOADED,
            listener -> args -> listener.onDataReloaded(
                    (MinecraftServer) args[0], (Optional<Throwable>) args[1]));

    private BrushEvents() {
    }
}
