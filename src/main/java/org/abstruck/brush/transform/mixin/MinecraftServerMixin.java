package org.abstruck.brush.transform.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import org.abstruck.brush.registry.DataRegistryEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * 数据锚点注入 —— L1 hook {@code data.loaded}（{@code <init>} RETURN）与 {@code data.reload}
 * （{@code reloadResources} 整方法包装）。G1（数据包注册表扩展点 + 数据重载事件），docs/21。
 *
 * <p><b>为什么注入这里</b>：{@code data.loaded} —— 26.1.2 {@code MinecraftServer} 只有唯一
 * 构造器（javap 实证，10 参全描述符见下），其 RETURN 是 {@code getResourceManager()} 非空
 * （世界数据全部就位）的<b>最早</b>时机；服务器构造必晚于 {@code BuiltInRegistries.bootStrap}
 * （内容注册窗口），因此无需 {@code EarlyBoot} 兜底。{@code data.reload} ——
 * {@code reloadResources} 的资源交换在返回的 future 完成前发生、方法本身异步返回：
 * 唯一能锚定"交换完成"时刻且可链式（§7.2）的手段是整方法包装 + {@code whenComplete}
 * （{@code @Inject} 只能覆盖方法体入口 / 返回点 —— 返回点是 future 尚未完成的时刻）。</p>
 *
 * <p><b>织入语义（§10.1）</b>：{@code data.loaded} 每注入点 1 条 handler 调用指令
 * （{@code @Inject} RETURN，可链式）；{@code data.reload} 为 MixinExtras {@code @WrapMethod}
 * （可链式，先例 {@code CreativeModeInventoryScreenMixin}），handler 调用原方法取 future、
 * {@code whenComplete} 挂引擎回调、<b>原样返回</b>该 future（不改返回值）。
 * {@code require=1} 双点（§7.3 形态 A 防御）。目标方法均为实例方法，handler 同为实例。</p>
 *
 * <p><b>whenComplete 异步偏离（§3.5，有意）</b>：{@code whenComplete} 回调里任何重抛的
 * 异常都会被 CompletableFuture 静默吞掉 —— 引擎在回调体内 catch + ERROR 记录
 * （{@code DataRegistryEngine#onReloadCompleted}，本工程唯一偏离点）。同步路径
 * （{@code data.loaded}）保持 fail-fast。</p>
 *
 * <p><b>宿主共存</b>：Fabric / NeoForge 均可能对 {@code MinecraftServer} 有自身改动 ——
 * 两个注入点分别可链式（@Inject RETURN / @WrapMethod），S2/S3 实测见 compat-status（T2/T3）。</p>
 *
 * <p><b>冷热</b>：均非热路径（每服务器实例 1 次 / 每次重载 1 次）。</p>
 *
 * <p><b>footprint</b>：2 个注入点、1 个被注入类（台账 docs/footprint.md，G1 行）。</p>
 */
@Mixin(targets = "net.minecraft.server.MinecraftServer")
public abstract class MinecraftServerMixin {

    /** 目标：10 参主构造器 RETURN —— {@code data.loaded} 锚点（兼引擎装载时机）。 */
    @Inject(method = "<init>(Ljava/lang/Thread;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/WorldStem;Ljava/util/Optional;Ljava/net/Proxy;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/server/Services;Lnet/minecraft/server/level/progress/LevelLoadListener;Z)V",
            at = @At("RETURN"), require = 1)
    private void brush$fireDataLoaded(CallbackInfo ci) {
        DataRegistryEngine.onServerConstructed((MinecraftServer) (Object) this);
    }

    /** 目标：{@code reloadResources(Collection)} 整方法包装 —— {@code data.reload} 锚点。 */
    @WrapMethod(method = "reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",
            require = 1)
    private CompletableFuture<Void> brush$reloadDataRegistries(Collection<String> selectedIds,
            Operation<CompletableFuture<Void>> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        CompletableFuture<Void> future = original.call(selectedIds);
        // whenComplete 在主方法返回前注册 —— 引擎回调不受外部调用方行为影响；
        // 与调用方后续注册的其他回调的相对顺序无契约（引擎也不依赖该顺序）。
        future.whenComplete((result, error) -> DataRegistryEngine.onReloadCompleted(server, error));
        return future;
    }
}
