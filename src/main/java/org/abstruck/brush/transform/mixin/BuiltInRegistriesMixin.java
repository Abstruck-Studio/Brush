package org.abstruck.brush.transform.mixin;

import org.abstruck.brush.mod.RuntimeBridges;
import org.abstruck.brush.mod.RuntimeKind;
import org.abstruck.brush.registry.ClientRegistrationBuffer;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.EarlyBoot;
import org.abstruck.brush.transform.HookEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

/**
 * 内容注册 / 客户端注册窗口注入 —— L1 hook {@code registries.bootstrap}（HEAD）与
 * {@code client.registration}（TAIL）。
 *
 * <p><b>为什么注入这里</b>：游戏内容注册（物品 / 方块 / …）只能发生在
 * {@code BuiltInRegistries.bootStrap()} 体内 —— 更早的时机（启动管线 / 宿主入口点）会被
 * {@code Bootstrap} 的状态守卫拦截，任何对注册表类的触碰（连类初始化都算）抛
 * {@code IllegalArgumentException("Not bootstrapped ...")}；更晚则注册表已 {@code freeze()}，
 * 注册抛 {@code IllegalStateException}。唯一可达的注入位置就是本方法 HEAD。</p>
 *
 * <p>HEAD 处状态：{@code Bootstrap} 的 bootstrapped 标志已置位（方法入口的防重入检查已通过）、
 * 该类已初始化、未冻结 —— 恰好是窗口本身。插入序注意：vanilla 的方块 / 物品等内容类已在
 * {@code Bootstrap.bootStrap()} 流程前段完成初始化与自注册（{@code FireBlock.bootStrap()} /
 * {@code ComposterBlock.bootStrap()} 的方法体分别读取 {@code Blocks.*} / {@code Items.*}
 * 触发其 {@code <clinit>}，均早于本窗口 —— 2026-10-13 javap 复核），因此在此注册的内容
 * <b>追加在 vanilla 条目之后</b>（差异已登记为可接受）。</p>
 *
 * <p><b>兜底引导（零新增织入指令）</b>：handler 体内在派发前先经 {@link EarlyBoot#ensureAuto()}
 * 保证 Brush 已引导（最早锚点 {@code bootstrap.early} 未先行时，这里仍保证模组在内容注册前完成
 * 加载；正常路径下是幂等 no-op）。调用位于既有 handler 体内，不增加织入指令数。</p>
 *
 * <p><b>TAIL 注入（E3，同方法第 2 注入点）</b>：{@code client.registration} —— handler 标记
 * TAIL 到达后按运行时决定 flush 落地点：S1/S2 当场
 * {@link ClientRegistrationBuffer#flush()}（mod 经缓冲 API 提交的客户端注册在此统一落地）；
 * S3（NeoForge）跳过、推迟到首个 {@code packs.registered}（由 {@code CoreHooks} 的
 * sweeper 统一 flush）—— 理由与 javap 偏移证据见 {@code ClientRegistrationBuffer}
 * "S3 延迟落地"节：neo 内容注册阶段（{@code ClientModLoader.begin}）晚于本 TAIL，
 * TAIL 求值属性 Supplier 实测 NPE 于未绑定的 neo 属性 holder（T3，2026-10-12）。
 * 随后统一派发事件供监听器做最后一批注册，最后关闭窗口标记。
 * 时序论证（内容已齐、渲染器 / 屏幕映射未建 —— S1 下 {@code Minecraft} 尚未构造；
 * S2/S3 下构造已在途但映射构建点晚于本窗口，实测栈见
 * {@code ClientRegistrationBuffer} 窗口标记节）见 {@code HookEvents.CLIENT_REGISTRATION} javadoc。
 * 同方法第 2 注入点经用户批准（2026-10-12，E3），台账登记（16 号点）见变更记录。</p>
 *
 * <p>织入语义（§10.1）：每注入点织入 1 条 handler 调用指令（HEAD handler 体 = 窗口标记 +
 * 兜底引导 + {@code Dispatcher.fire}；TAIL handler 体 = TAIL 到达标记 + 运行时判定
 * flush（S1/S2）或延迟（S3）+ {@code Dispatcher.fire} + 窗口标记关闭 —— 均属注入点
 * 工作体语义，先例 {@code PackRepositoryMixin} 的 attach 调用）；均可链式
 * {@code @Inject}（§7.2）；{@code require=1} 未命中即启动期硬失败（§7.3 形态 A 防御）。
 * 目标方法是 static，两个 handler 必须同为 static。</p>
 *
 * <p>冷热：非热路径（每进程 1 次）。</p>
 *
 * <p>footprint：2 个注入点（同方法 HEAD / TAIL）、1 个被注入类（台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.core.registries.BuiltInRegistries")
public abstract class BuiltInRegistriesMixin {

    /** 目标：{@code BuiltInRegistries.bootStrap()} 的 HEAD（内容注册的唯一合法窗口）。 */
    @Inject(method = "bootStrap()V", at = @At("HEAD"), require = 1)
    private static void brush$fireRegistriesBootstrap(CallbackInfo ci) {
        // 窗口标记（E2/S2 实测修正）：S2/S3 下宿主把 bootStrap 推迟到 Minecraft 构造中途执行，
        // 窗口内 Minecraft 实例可能已存在 —— "过晚注册"守卫按此标记放行窗口内提交。
        ClientRegistrationBuffer.enterWindow();
        // 兜底引导（零新增织入指令）：正常路径（S1 管线 / 最早锚点 bootstrap.early）下为幂等 no-op。
        try {
            EarlyBoot.ensureAuto();
        } catch (IOException e) {
            throw new IllegalStateException("[brush] early boot failed at the content registration window: "
                    + e.getMessage(), e);
        }
        Dispatcher.fire(HookEvents.REGISTRIES_BOOTSTRAP);
    }

    /** 目标：{@code BuiltInRegistries.bootStrap()} 的 TAIL（E3 客户端注册窗口）。 */
    @Inject(method = "bootStrap()V", at = @At("TAIL"), require = 1)
    private static void brush$fireClientRegistration(CallbackInfo ci) {
        // TAIL 到达标记（首行）：sweeper 的 deferredFlushDue() 资格依据 ——
        // 防 S2 序（首个 packs.registered 早于本 TAIL）下 sweeper 提前 flush。
        ClientRegistrationBuffer.markTailReached();
        // flush 落地点按运行时分派（S3 实测修正，见类 javadoc）：
        //   S1/S2：本 TAIL 即最终窗口（内容已 freeze），当场 flush；
        //   S3   ：neo 内容注册（ClientModLoader.begin）晚于本 TAIL —— 在此求值属性
        //          Supplier 会 NPE 于未绑定的 neo 属性 holder（T3 实测）；推迟到首个
        //          packs.registered，由 CoreHooks sweeper 统一 flush。
        if (RuntimeBridges.current().kind() != RuntimeKind.NEOFORGE) {
            ClientRegistrationBuffer.flush();
        }
        // 先受理（S1/S2 已 flush；S3 缓冲待 packs.registered 落地），再派发 ——
        // 事件监听器的注册在三运行时均被完整受理；S3 下事件内提交的注册同样入缓冲。
        Dispatcher.fire(HookEvents.CLIENT_REGISTRATION);
        // 窗口关闭必须在事件派发之后：事件内提交的注册仍属窗口内合法调用。
        ClientRegistrationBuffer.exitWindow();
    }
}
