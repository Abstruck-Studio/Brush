package org.abstruck.brush.transform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * mixin 配置注册 + <b>复核</b>（三个运行时共用的核心助手，docs/19 §3）。
 *
 * <p>注册动作本身是运行时无关的：S1 自研服务、S2 Fabric、S3 NeoForge 使用的是<b>同一份</b>
 * sponge-mixin 0.17.4 的 {@code org.spongepowered.asm.mixin.Mixins} 注册表，经各自的
 * {@code IMixinService} 读配置资源（读法差异由运行时接面在调用前铺好 —— S3 的 TcCL 窗口、
 * S1/S2 的类加载器可见性，见对应桥接实现）。</p>
 *
 * <p>复核理由：{@code Mixins.createConfiguration} 在 {@code Config.create} 抛异常时只记一条
 * ERROR 就丢弃配置 —— 若不复核，配置解析失败将表现为"启动正常、日志干净，但全部 hook 静默
 * 不存在"（CLAUDE.md §13.1 静默失效形态的近亲，必须硬失败）。</p>
 *
 * <p>调用时机：本方法首次调用发生在任何目标类被变换之前（mod 加载期，vanilla 内容类尚未加载），
 * 待选队列中的新配置会在下一次变换时被拾取并 prepare（{@code MixinProcessor.checkSelect} 惰性
 * 选择）—— 因此晚注册仍然早于目标类加载。</p>
 */
public final class MixinConfigRegistry {

    private static final Logger LOGGER = LogManager.getLogger(MixinConfigRegistry.class);

    /**
     * 注册时抓住的配置句柄（注册名 → {@link Config}；每进程只增，量 = 我们的配置数 + mod 配置数）。
     *
     * <p><b>为什么必须在此抓住</b>：{@code Mixins.getConfigs()} 是<b>待选集</b> —— 配置被
     * mixin 子系统选择后即从该集合<b>移除</b>（0.17.4 {@code MixinProcessor.selectConfigs}
     * :539 {@code iter.remove()}；首版自检台账按待选集查找，把已正常施加的配置误判为
     * "not in registry" —— T1 实测坐实）。已消费配置的存活引用只剩此处句柄与处理器内部表；
     * 句柄对象自身不失效：{@code isVisited()}（{@code MixinConfig.select()} :1123 置位）与
     * {@code getConfig().getTargets()}（prepare 后有意义）在报告期均可读 —— 供
     * {@code Selftest#modMixinsSection} 的三运行时通用证据列（visited / targets）读取，
     * 全部走公开面，无反射。</p>
     */
    private static final Map<String, Config> REGISTERED = new LinkedHashMap<>();

    private MixinConfigRegistry() {
    }

    /**
     * 注册一个 mixin 配置资源并复核其确实进入待选队列。
     *
     * @throws IllegalStateException 未入列（配置缺失 / 不可读 / JSON 非法 —— Mixin 已在日志中
     *         记 ERROR，此处硬失败并给出定位）
     */
    public static void registerAndVerify(String configResource) {
        Set<Config> before = new HashSet<>(Mixins.getConfigs());
        Mixins.addConfiguration(configResource);
        for (Config config : Mixins.getConfigs()) {
            if (!before.contains(config)) {
                synchronized (REGISTERED) {
                    REGISTERED.put(config.getName(), config);   // 句柄保留（见字段 javadoc）
                }
                LOGGER.info("[brush]   mixin config registered: " + config.getName());
                return;
            }
        }
        throw new IllegalStateException("mixin config '" + configResource
                + "' was not registered (see the ERROR logged by Mixins.createConfiguration above)");
    }

    /**
     * 经本助手注册过的配置句柄（按注册名；自检台账用，见 {@link #REGISTERED}）。
     * 返回 {@code null} = 该名从未经本助手注册成功（登记在册的 mod mixin 配置不应出现此情形）。
     */
    public static synchronized Config registeredConfig(String configName) {
        return REGISTERED.get(configName);
    }
}
