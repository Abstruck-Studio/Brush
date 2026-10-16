package org.abstruck.brush.api;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 语义事件类型：HUD 提取完成（L2；对应 L1 {@code hud.extract}）。
 *
 * <p><b>L1 来源</b>：注入点 {@code Gui#extractRenderState} 的 TAIL（每渲染帧 1 次；
 * TAIL 时 vanilla HUD 元素已全部写入 extractor，见 {@code HookEvents} 与
 * {@code GuiMixin} javadoc）。派发链 mixin → {@code Dispatcher.fire} → 本事件。
 *
 * <p><b>触发时机（精确语义）</b>：每个渲染帧的 vanilla HUD 提取完成之后 —— 监听器可在
 * {@code extractor} 上继续追加绘制。触发线程 = 渲染线程（主线程）。
 *
 * <p><b>载荷</b>：监听器直接接收目标方法的两个原始参数（对象同一性；每次触发零分配）——
 * 与 {@code ScreenInitEvent} 的"载荷即原始对象"惯例一致。
 *
 * <p><b>热路径提示</b>：本事件每帧触发（约 60–260 次/秒）。监听器应保持廉价；
 * 无监听器时载入侧零开销（派发器空表短路，见 {@code Dispatcher} javadoc）。
 *
 * <p><b>自检口径</b>：自检退出点（首个 {@code client.tick}）位于首帧渲染<b>之前</b>，
 * 本事件在退出点前必然 0 次 —— 自检只做目标类强制加载 + 方法存在断言（注入已被
 * {@code require=1} 硬保证）；实际绘制效果为<b>人工验证项</b>。
 *
 * <p>与其他事件类惯例一致：本类是监听器接口的命名空间（无 {@code INSTANCE}，不可实例化）。
 */
public final class HudExtractEvent {

    private HudExtractEvent() {
    }

    /** 监听器接口（{@link BrushEvents#HUD_EXTRACT} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param extractor   本帧 HUD 提取目标（vanilla 元素已写入，可继续追加）
         * @param deltaTracker 帧时间增量跟踪器（与目标方法同参直传）
         */
        void onHudExtract(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker);
    }
}
