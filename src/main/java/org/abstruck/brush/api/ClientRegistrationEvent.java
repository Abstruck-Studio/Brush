package org.abstruck.brush.api;

/**
 * 语义事件：客户端注册窗口（L2；对应 L1 {@code client.registration}，E3）。
 *
 * <p><b>L1 来源</b>：注入点 {@code net.minecraft.core.registries.BuiltInRegistries#bootStrap()V}
 * 的 TAIL —— 派发链 mixin → {@code ClientRegistrationBuffer.flush()} →
 * {@code Dispatcher.fire} → 本事件监听器。三运行时施加同一份 mixin 配置。
 *
 * <p><b>触发时机（精确语义）</b>：vanilla 内容注册完成（注册表已冻结并通过 validate）之后、
 * {@code Minecraft} 构造（渲染器映射构建）之前。这是客户端侧注册的统一时机：
 * 监听器触发前，mod 经 {@code BrushRegistry} 客户端组提交的注册已被统一 flush 落地；
 * 监听器内若再做最后一批客户端注册，flush 后到场的注册<b>立即执行</b>
 * —— 合法性由"实例未构造 ⇒ 映射未构建"守卫保证（{@code Minecraft.getInstance() == null}）。
 * 每进程恰一次。
 *
 * <p><b>触发线程</b>：执行 bootstrap 的线程（S1 为游戏主线程；宿主下以各宿主的报告为准）。
 * 监听器异常经 {@code Dispatcher} 包装上抛 → 启动快速失败，不会静默。
 *
 * <p>事件对象为无载荷单例（{@link #INSTANCE}）；请勿依赖对象身份做去重或状态存储。
 */
public final class ClientRegistrationEvent {

    /** 共享单例（无载荷、不可变；桥接零分配）。 */
    public static final ClientRegistrationEvent INSTANCE = new ClientRegistrationEvent();

    private ClientRegistrationEvent() {
    }

    /** 监听器接口（{@link BrushEvents#CLIENT_REGISTRATION} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {
        void onClientRegistration(ClientRegistrationEvent event);
    }
}
