package org.abstruck.brush.api;

import org.abstruck.brush.content.ModContext;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvent;

import java.util.Objects;
import java.util.function.Function;

/**
 * L2 语义事件类型句柄（mod 对 Brush 的唯一合法注册入口）。
 *
 * <p>实例只由 Brush 自身创建（{@link BrushEvents} 与 {@link BrushCreativeTabs}；包私有构造器
 * —— 对 mod 而言事件是封闭集合）；mod 拿到的是类型安全的 {@code Event<XxxListener>}，
 * 看不到 L1 派发表的任何内部结构。注册动作最终落到 L1 {@link Dispatcher} 的同一张派发表上
 * （引擎只有一张，L2 是类型化包装）。
 *
 * <p><b>可链式</b>：同一事件可注册任意多监听器，按注册先后调用；
 * 一个监听器的异常/注销不影响其他监听器。不提供任何独占语义。
 *
 * @param <T> 监听器类型（各事件类内嵌的 {@code Listener} 接口）
 */
public final class Event<T> {

    /** 承载本事件的 L1 id（强类型 key，评审 P2-1 整改；值 = {@code HookEvents.X} 的 id 字符串）。 */
    private final HookEvent l1Event;
    private final Function<T, Dispatcher.Listener> bridge;

    /** 包私有：只有 {@link BrushEvents} 能创建（见类 javadoc）。 */
    Event(HookEvent l1Event, Function<T, Dispatcher.Listener> bridge) {
        this.l1Event = Objects.requireNonNull(l1Event, "l1Event");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    /**
     * 注册一个监听器。
     *
     * <p><b>注册</b>：线程安全，可在任意线程 —— 含 mod 入口类构造期（此时游戏尚未就绪）。
     * 注册动作只触碰 L1 {@link Dispatcher}（纯 JDK），不解析任何 MC 类型：本包的 MC 类型引用
     * 一律是<b>惰性</b>的（出现在载荷事件类的监听器签名 / 桥接 lambda 体内，只在事件真正触发、
     * 载荷被读取时才解析）—— 因此入口构造期的注册安全（与
     * {@link BrushRegistry} 的"窗口内才引用"约束互补）。
     *
     * <p><b>调用</b>：监听器在事件触发线程上被调用（见各事件类 javadoc 的"触发线程"）；
     * 监听器抛出的异常被包装为 {@link IllegalStateException} 并立即上抛
     * （快速失败，不静默吞掉）。
     *
     * @param listener 监听器（非 null）
     * @return 注册句柄：{@link EventRegistration#unregister()} 可注销（幂等）
     */
    public EventRegistration register(T listener) {
        Objects.requireNonNull(listener, "listener");
        // 归账上下文捕获：注册通常发生在 mod 入口构造期（ModBootstrap
        // 设置的 ModContext 内）；把该上下文包进 L1 监听器，窗口到达、监听器执行时
        // BrushRegistry 才能把注册归属到正确的 mod。非 mod 上下文（测试 / 宿主适配）原样透传。
        return new EventRegistration(Dispatcher.register(l1Event,
                ModContext.wrap(bridge.apply(listener))));
    }
}
