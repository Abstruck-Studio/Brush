package org.abstruck.brush.api;

/**
 * 语义事件：内容注册相位（L2；对应 L1 {@code registries.bootstrap}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code net.minecraft.core.registries.BuiltInRegistries#bootStrap()V}
 * HEAD —— 派发链 mixin → {@code Dispatcher.fire} → 本事件监听器。三运行时施加同一份 mixin 配置。
 *
 * <p><b>触发时机（精确语义）</b>：{@code Bootstrap.bootStrap()} 执行到
 * {@code BuiltInRegistries.bootStrap()} 入口的时刻 —— 状态守卫已放行、注册表未冻结；
 * vanilla 的方块 / 物品等内容类已在 {@code Bootstrap} 流程前段完成初始化与自注册
 * （{@code Blocks} / {@code Items} 的 {@code <clinit>} 早于本窗口 —— javap 复核，2026-10-13）。
 * <b>这是注册内容（物品、方块等）的唯一合法窗口</b>：
 * 更早触碰注册表类会被守卫抛 {@code "Not bootstrapped"}，
 * 更晚注册会抛 {@code "Registry is already frozen"}。每进程恰一次。
 *
 * <p><b>在此注册的语义</b>：监听器内执行的注册调用全部合法且进入最终注册表；
 * 注册条目在插入序上<b>追加在 vanilla 条目之后</b>（客户端 / 服务端同序计算，差异已登记为可接受）。
 *
 * <p><b>触发线程</b>：执行 bootstrap 的线程（S1 为游戏主线程；宿主下以各宿主的报告为准）。
 * 监听器异常经 {@code Dispatcher} 包装上抛 → 启动快速失败，不会静默。
 *
 * <p>事件对象为无载荷单例（{@link #INSTANCE}）；请勿依赖对象身份做去重或状态存储。
 */
public final class ContentRegistrationEvent {

    /** 共享单例（无载荷、不可变；桥接零分配）。 */
    public static final ContentRegistrationEvent INSTANCE = new ContentRegistrationEvent();

    private ContentRegistrationEvent() {
    }

    /** 监听器接口（{@link BrushEvents#CONTENT_REGISTRATION} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {
        void onContentRegistration(ContentRegistrationEvent event);
    }
}
