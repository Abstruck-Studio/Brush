package org.abstruck.brush.transform.mixin;

import net.minecraft.client.Minecraft;
import org.abstruck.brush.transform.ClientBoot;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端生命周期注入 —— L1 hook {@code client.run} / {@code client.tick}。
 *
 * <p>目标 = {@code Minecraft.run()}（主循环入口）与 {@code Minecraft.tick()}（每帧），两者均为
 * 该类直接声明的 public 方法。S1 运行时命名空间 = 官方名（CLAUDE.md §5.2，三运行时统一），注解
 * 直接写官方目标名；配置不声明 refmap —— 三运行时同名无需转换，且 NeoForge 侧带 refmap 会坏
 * （§5.3 / §11.2）。</p>
 *
 * <p>织入语义（§10.1）：每个目标方法上仅 1 个注入点（共 2 点、2 方法）；每点织入仅 1 条
 * {@code Dispatcher.fire} 静态调用（{@code run} 经 {@link ClientBoot#onClientRun()} 引导+派发
 * 一体，同 {@code BootstrapMixin} → {@code EarlyBoot} 形态）；不改控制流、不加字段；
 * 可链式 {@code @Inject}（§7.2）；{@code require = 1} 未命中即启动期硬失败（§7.3 形态 A）。</p>
 *
 * <p>冷热：{@code run} 非热路径（每进程 1 次）；{@code tick} 热路径（每秒约 20 次）—— 无监听器
 * 时 = 一次 CHM 查找 + 空判断（零分配），项目自身监听器只在 AtomicLong 上自增并按节流打印，
 * 单点开销可忽略（§10.3 第 5 条）。</p>
 *
 * <p>footprint：2 个注入点、1 个被注入类（台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class MinecraftMixin {

    /**
     * 目标：{@code Minecraft.run()} 主循环入口 HEAD（非热路径，仅触发一次）。
     *
     * <p>织入 {@link ClientBoot#onClientRun()}（引导+派发一体）：先派发全部客户端入口
     * （{@code entrypoints.client} 迟绑定），再派发 {@link HookEvents#CLIENT_RUN} ——
     * 顺序为结构性保证（docs/09 §7）。
     */
    @Inject(method = "run()V", at = @At("HEAD"), require = 1)
    private void brush$fireClientRun(CallbackInfo ci) {
        ClientBoot.onClientRun();
    }

    /**
     * 目标：{@code Minecraft.tick()} 每帧 HEAD（热路径，每秒约 20 次；开销论证见类 javadoc）。
     */
    @Inject(method = "tick()V", at = @At("HEAD"), require = 1)
    private void brush$fireClientTick(CallbackInfo ci) {
        Dispatcher.fire(HookEvents.CLIENT_TICK);
    }
}
