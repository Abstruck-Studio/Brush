package org.abstruck.brush.api;

/**
 * 语义事件类型：创造栏内容构建完成（对应 L1 {@code creative.tab.output}）——
 * {@link BrushCreativeTabs#entriesOf} 返回的 {@link Event} 的监听器类型命名空间。
 *
 * <p><b>L1 来源</b>：注入点 {@code CreativeModeTab#buildContents} TAIL ——
 * 派发链 mixin → {@code Dispatcher.fire} → 本事件的（按键过滤的）监听器；
 * 触发时机 / 载荷语义见 {@code HookEvents#CREATIVE_TAB_OUTPUT} javadoc。
 *
 * <p><b>为什么没有事件对象实例</b>：本事件的载荷就是内容追加面本身
 * （{@link CreativeTabEntries} 句柄）—— 监听器直接接收它；与其它事件类的惯例一致，
 * 本类只作为监听器接口的命名空间（无 {@code INSTANCE}，不可实例化）。
 *
 * <p><b>触发线程</b>：调用 {@code buildContents} 的线程 —— 生产路径 = 游戏主线程。
 */
public final class CreativeTabOutputEvent {

    private CreativeTabOutputEvent() {
    }

    /** 监听器接口（{@link BrushCreativeTabs#entriesOf} 的注册类型）。 */
    @FunctionalInterface
    public interface Listener {
        void onCreativeTabOutput(CreativeTabEntries entries);
    }
}
