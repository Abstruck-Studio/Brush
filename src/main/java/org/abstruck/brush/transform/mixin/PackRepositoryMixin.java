package org.abstruck.brush.transform.mixin;

import net.minecraft.server.packs.repository.RepositorySource;
import org.abstruck.brush.resource.ModResourcePacks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * 资源包仓库注入 —— L1 hook {@code packs.registered}（模组模型 / 材质接入点）。
 *
 * <p>目标 = {@code PackRepository#<init>(RepositorySource...)}（26.1.2 仅此一个构造器）。在 RETURN
 * 处读实例字段 {@code sources}（{@code @Shadow}）并写回 {@code ModResourcePacks.attach(...)} 的
 * 返回值 —— <b>刻意不读构造器参数</b>：与本注入点共存的其他注入（如 Fabric 资源加载器在同一
 * 位置读 {@code this.sources} 重建集合）无论先后施加，双方都基于"字段的当前内容"工作、互不抹除。</p>
 *
 * <p>顺序链：注入发生在构造器返回时，早于 vanilla 调用 {@code reload()} 遍历 sources 做发现与
 * 选择 —— 我们的来源必然参与；attach 以 {@code instanceof ClientPackSource} 判定客户端仓库
 * （世界创建界面的仓库仅含 {@code ServerPacksSource}，自动跳过、不误加）。</p>
 *
 * <p>织入语义（§10.1 / §7.2 / §10.3）：每目标方法 1 个注入点；RETURN 处 1 条 handler 调用
 * （体内 = 1 次 {@code attach} + 1 次字段写回，注入点工作体语义）；不改控制流、不加字段；
 * 可链式 {@code @Inject}；{@code require = 1} 未命中即启动期硬失败（§7.3 形态 A）。</p>
 *
 * <p>{@code @Mutable} 仅为写回 {@code final} 字段所必需，不改变字段签名与类结构。</p>
 *
 * <p>冷热：非热路径（进程内仓库构造次数为个位数）。</p>
 *
 * <p>footprint：1 个注入点、1 个被注入类（台账 docs/footprint.md）。</p>
 */
@Mixin(targets = "net.minecraft.server.packs.repository.PackRepository")
public abstract class PackRepositoryMixin {

    /** 目标字段（vanilla：{@code private final Set<RepositorySource> sources}）。 */
    @Final
    @Mutable
    @Shadow
    private Set<RepositorySource> sources;

    /** 目标：{@code PackRepository.<init>} 的 RETURN（构造完成的唯一时机）。 */
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void brush$attachModResourcePacks(RepositorySource[] initialSources, CallbackInfo ci) {
        this.sources = ModResourcePacks.attach(this.sources);
    }
}
