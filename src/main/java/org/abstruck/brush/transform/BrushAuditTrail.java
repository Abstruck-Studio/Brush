package org.abstruck.brush.transform;

import org.spongepowered.asm.service.IMixinAuditTrail;

import java.util.ArrayList;
import java.util.List;

/**
 * mixin 施加审计记录（F：mod mixin 命中台账的 S1 数据源）—— 被动记录器，
 * <b>不改变任何变换行为</b>：实现 sponge-mixin 的 {@link IMixinAuditTrail}，经
 * {@link BrushMixinService#getAuditTrail()} 装进 mixin 核心，在每次<b>真实施加</b>时收一条记录。
 *
 * <p><b>数据来源（源码实证，sponge-mixin 0.17.4）</b>：mixin 核心在三处回调 ——
 * {@code MixinApplicatorStandard.apply()}（:216）对每个 mixin×目标类调
 * {@code onApply(targetName, mixin.toString())}；{@code MixinProcessor.applyMixins}（:297）
 * 对 passthrough 目标调 {@code onPostProcess}；{@code MixinClassGenerator}（:85）对生成类调
 * {@code onGenerate}。全部调用点均带 {@code auditTrail != null} 守卫，且 trail 于
 * {@code MixinProcessor}（:233）与 {@code MixinApplicatorStandard}（:204）构造器各取一次
 * （{@code MixinService.getService().getAuditTrail()}）—— 返回非空实现是受支持路径。
 * {@code onApply} 的 mixin 参数为 {@code MixinInfo.toString()}，其格式（0.17.4 MixinInfo.java:1406）
 * = {@code "<config>:<mixin FQN> from mod <modid>"} —— 配置名与 mod 归属在记录里天然可解析
 * （自检报告据此把施加记录归到各 mod 的 mixin 配置名下）。</p>
 *
 * <p><b>MixinEnvironment.audit() 评估（本类采纳 auditTrail 而非 audit 的理由）</b>：
 * {@code MixinEnvironment.audit()}（:1714）→ {@code MixinProcessor.audit()}（:241）的实测语义
 * = 对<b>尚未处理</b>的目标类逐个 {@code getClassProvider().findClass(target, false)}
 * <b>强制加载</b>（副作用类加载！）并经 {@code mixin.audit} logger 只记日志 ——
 * (a) 无结构化返回值（无法进自检报告数据流）；(b) 报告对象只有 unhandled 集合（对已施加部分零信息）；
 * (c) 强制加载在自检退出点（首个 {@code client.tick}）属多余且引入副作用。
 * 因此<b>不作为报告机制</b>：自检退出点所有目标类的装载已由正常游戏流程完成，
 * "目标是否已装载"改由 mixin 子系统自身的公开状态回答（{@code Config.isVisited()} /
 * {@code IMixinConfig.getTargets()}，见 {@code Selftest#modMixinsSection}），
 * "哪些 mixin 实际施加"由本 trail 回答。</p>
 *
 * <p><b>S2/S3 边界（如实登记）</b>：宿主模式下 mixin 子系统归宿主（Knot / FML 的
 * {@code IMixinService}），{@code MixinService.getService().getAuditTrail()} 返回宿主的实现
 * （或无 —— 接口允许 null），本类在 S2/S3 <b>保持空</b>；自检报告相应降级为
 * "registered / visited / targets"（三运行时通用的公开面证据），applied 列标
 * {@code n/a (host mixin service)}，不假装有数据。</p>
 */
public final class BrushAuditTrail implements IMixinAuditTrail {

    /** 服务单例（{@link BrushMixinService#getAuditTrail()} 的返回值；每进程一个）。 */
    public static final BrushAuditTrail INSTANCE = new BrushAuditTrail();

    /**
     * 单次施加记录：mixin 类 {@link #mixin()} 作用于目标类 {@link #target()}（{@code onApply} 一次一条）。
     *
     * @param target 目标类全名（点分；mixin 核心传入的 {@code targetName}）
     * @param mixin  mixin 标识串（{@code MixinInfo.toString()}，形如
     *               {@code "<config>:<mixin FQN> from mod <modid>"}）
     */
    public record Applied(String target, String mixin) { }

    private final List<Applied> applied = new ArrayList<>();
    private int generated;
    private int postProcessed;

    /** 包私有构造：{@link #INSTANCE} 之外仅供同包单测（{@code BrushAuditTrailTest}）建独立实例。 */
    BrushAuditTrail() {
    }

    @Override
    public void onApply(String className, String mixinName) {
        synchronized (this) {
            this.applied.add(new Applied(className, mixinName));
        }
    }

    @Override
    public void onPostProcess(String className) {
        synchronized (this) {
            this.postProcessed++;
        }
    }

    @Override
    public void onGenerate(String className, String generatorName) {
        synchronized (this) {
            this.generated++;
        }
    }

    /** {@link #onApply} 记录快照（首见序；不可变）。 */
    public synchronized List<Applied> applied() {
        return List.copyOf(this.applied);
    }

    /** {@code onGenerate} 次数（生成类 —— 单独计数，不进台账明细）。 */
    public synchronized int generatedCount() {
        return this.generated;
    }

    /** {@code onPostProcess} 次数（passthrough 目标 —— 单独计数，不进台账明细）。 */
    public synchronized int postProcessedCount() {
        return this.postProcessed;
    }
}
