package org.abstruck.brush.api;

/**
 * 语义事件：资源包仓库构建完成（L2；对应 L1 {@code packs.registered}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code PackRepository#<init>} RETURN ——
 * <b>0 新增 footprint</b>；派发链 mixin → {@code Dispatcher.fire} → 本事件监听器。
 *
 * <p><b>触发时机（精确语义）</b>：每个 {@code PackRepository} 实例构造完成时
 * （{@code ModResourcePacks.attach} 的 finally 中派发）—— 客户端主仓库 1 次 +
 * 世界创建界面的数据包选择仓库若干次；触发时"模组资源包已接入来源集合"，
 * vanilla 随后的 {@code reload()} 会将其纳入发现与选中
 * （顺序链见 {@code HookEvents#PACKS_REGISTERED} javadoc）。
 *
 * <p><b>触发线程</b>：构造该仓库的线程（客户端主仓库 = 游戏主线程）。
 *
 * <p>事件对象为无载荷单例（{@link #INSTANCE}）；请勿依赖对象身份做去重或状态存储。
 */
public final class ResourcePacksRegisteredEvent {

    /** 共享单例（无载荷、不可变；桥接零分配）。 */
    public static final ResourcePacksRegisteredEvent INSTANCE = new ResourcePacksRegisteredEvent();

    private ResourcePacksRegisteredEvent() {
    }

    /** 监听器接口（{@link BrushEvents#PACKS_REGISTERED} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {
        void onPacksRegistered(ResourcePacksRegisteredEvent event);
    }
}
