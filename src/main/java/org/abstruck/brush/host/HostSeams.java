package org.abstruck.brush.host;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 宿主事件总线**接缝**（2026-10-15，docs/26 §7.6 的"引擎搬家"）。
 *
 * <h2>它解决什么</h2>
 *
 * <p>网络引擎（{@code BrushNetwork} / {@code BrushChannels} / {@code BrushPayload}）与它的宿主适配器
 * （原 {@code host.neoforge.NeoForgePayloadBridge}）已按 §7.6 整体搬进库模组 {@code brush-api}。
 * 但 S3 的 mod 事件总线只有核心的 {@code @Mod} 构造器拿得到 —— 若核心去调桥，就变成
 * <b>core → mod</b>（方向非法，CLAUDE.md §18）。</p>
 *
 * <p>于是核心只做"**把事件总线当 {@code Object} 递出去**"这一件事（本类），
 * 由库模组在 {@code onInitialize()} 里注册回调、自己在 S3 下接住并挂载载荷转注册。
 * 核心因此**零 mod 类型引用**；"引用 NeoForge 类型"留在允许引用宿主类的库模组里
 * （用户 2026-10-15 口径：Brush API 可以引用宿主类 —— 平台特定 mixin + {@code @BrushExpectPlatform}）。</p>
 *
 * <h2>时序</h2>
 *
 * <p>核心在 {@code BrushNeoForge} 构造器里、**{@code EarlyBoot.ensure(...)} 之后**触发本接缝
 * （那时 mod 已装载、回调已注册；宿主下 mod 通常更早由 {@code Bootstrap.bootStrap} HEAD 引导完成）。
 * 桥自身幂等，重复挂载无副作用。</p>
 *
 * <p>失败必须响亮：监听器抛出的异常直接上抛（不吞、不静默，CLAUDE.md §3.5）。</p>
 */
public final class HostSeams {

    /** 接缝监听器：拿到 mod 事件总线（对核心而言是 {@code Object}）。 */
    @FunctionalInterface
    public interface ModEventBusListener {

        /** @param modEventBus 宿主注入的 mod 事件总线 */
        void onModEventBus(Object modEventBus);
    }

    private static final List<ModEventBusListener> LISTENERS = new CopyOnWriteArrayList<>();

    private HostSeams() {
    }

    /**
     * 注册接缝监听器（由**库模组**在初始化期调用；幂等由调用方自行保证语义）。
     *
     * @param listener 监听器；非空
     */
    public static void onModEventBus(ModEventBusListener listener) {
        LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 触发接缝（由**宿主入口**调用一次，通常在 {@code @Mod} 构造器内）。
     *
     * @param modEventBus 宿主注入的 mod 事件总线
     */
    public static void fireModEventBus(Object modEventBus) {
        for (ModEventBusListener listener : LISTENERS) {
            listener.onModEventBus(modEventBus);
        }
    }

    /** 已注册的监听器数（自检/诊断用）。 */
    public static int listenerCount() {
        return LISTENERS.size();
    }
}
