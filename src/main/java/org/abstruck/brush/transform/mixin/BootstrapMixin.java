package org.abstruck.brush.transform.mixin;

import org.abstruck.brush.transform.EarlyBoot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 最早锚点注入 —— L1 hook {@code bootstrap.early}（Brush 由 vanilla 最早的执行锚点驱动引导，
 * 设计与顺序保证见 {@link EarlyBoot} 类 javadoc）。
 *
 * <p>目标 = {@code Bootstrap.bootStrap()}：游戏启动路径上最早且必然执行的 vanilla 方法（注册表
 * 初始化、内容注册窗口都发生在它体内）。HEAD 处类初始化已完成、守卫检查尚未执行 —— 重复调用
 * 的防重由 {@link EarlyBoot} 自己的 once 标志承担，不依赖 vanilla 字段。宿主模式（S2/S3）下
 * 引导由此在宿主自己的 bootstrap 中先行完成，不再等待宿主 mod 入口点。</p>
 *
 * <p>顺序保证：HEAD 先于同一方法内的 {@code BuiltInRegistries.bootStrap()}（内容注册窗口）——
 * "引导先于内容注册"是结构性事实。目标名用官方名（26.1 起未混淆，CLAUDE.md §5.1）。</p>
 *
 * <p>织入语义（§10.1）：仅 1 条 {@code EarlyBoot.onBootstrap()} 静态调用（引导即该锚点的语义，
 * 内部完成引导与首次 {@code Dispatcher.fire}）；可链式 {@code @Inject}（§7.2）；
 * {@code require=1} 未命中即启动期硬失败（§7.3 形态 A 防御）。目标方法是 static，handler 必须
 * 同为 static。</p>
 *
 * <p>冷热：非热路径（每进程 1 次）。</p>
 *
 * <p>footprint：1 个注入点、1 个被注入类（台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.server.Bootstrap")
public abstract class BootstrapMixin {

    /** 目标：{@code Bootstrap.bootStrap()} 的 HEAD（vanilla 最早执行锚点）。 */
    @Inject(method = "bootStrap()V", at = @At("HEAD"), require = 1)
    private static void brush$bootstrapEarly(CallbackInfo ci) {
        EarlyBoot.onBootstrap();
    }
}
