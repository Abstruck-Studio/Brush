package org.abstruck.brush.transform.mixin;

import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 崩溃报告注入（docs/25 §3.2，缺口 #2）：L1 hook {@code crash.report.details}。
 *
 * <p><b>为什么注入这里</b>：{@code CrashReport#getDetails(StringBuilder)} 是 26.1.2 里
 * <b>报告正文的唯一组装点</b>（javap 实证：{@code getDetails()} 只是它的字符串包装，
 * {@code getFriendlyReport*} 与 {@code saveToFile} 最终都经它取正文）。TAIL 处追加即可把我们的
 * 段落写进用户在崩溃报告里看到的文本 —— 这是"混合环境出问题时能自证我们做了什么"的最短路径
 * （CLAUDE.md §17 E4）。</p>
 *
 * <p><b>织入语义（§10.1）</b>：每注入点 1 条 {@code Dispatcher.fire} 调用指令；
 * {@code @Inject(TAIL)} 可链式（§7.2，不用 {@code @Redirect}/{@code @Overwrite}），
 * 不改控制流、不改返回值、不动 vanilla 类结构。目标为实例方法，handler 同为实例。</p>
 *
 * <p><b>冷热</b>：纯冷路径 —— 只有真的发生崩溃、且有人取报告正文时才走一次。
 * 正常游玩与自检（T1/T2/T3）中<b>恒 0 次</b>，因此该 hook 计入
 * {@code CoreHooks} 的<b>信息集</b>而非期望集（否则 {@code injection.audit} 会误报缺失）。</p>
 *
 * <p><b>用 {@code targets=} 而非类字面量</b>：mixin 配置解析期不需要加载 MC 类型
 * （同 {@code MinecraftServerMixin} 的选择）。</p>
 *
 * <p><b>footprint</b>：1 个注入点、1 个被注入类（台账 docs/footprint.md 缺口 #2 行）。</p>
 */
@Mixin(targets = "net.minecraft.CrashReport")
public abstract class CrashReportMixin {

    /**
     * 目标：{@code getDetails(StringBuilder)} TAIL —— 报告正文组装完成后追加 Brush 段。
     *
     * @param builder vanilla 正在组装的报告正文（handler 直接往里追加）
     */
    @Inject(method = "getDetails(Ljava/lang/StringBuilder;)V", at = @At("TAIL"), require = 1)
    private void brush$appendBrushSection(StringBuilder builder, CallbackInfo callback) {
        Dispatcher.fire(HookEvents.CRASH_REPORT_DETAILS, builder);
    }
}
