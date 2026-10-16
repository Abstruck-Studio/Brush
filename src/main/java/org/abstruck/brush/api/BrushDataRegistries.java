package org.abstruck.brush.api;

import com.mojang.serialization.Codec;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import org.abstruck.brush.content.ContentLedger;
import org.abstruck.brush.registry.DataRegistryEngine;

import java.util.Objects;

/**
 * 数据包注册表登记门面（L2 语义层，G1，docs/21）—— mod 自定义数据包注册表的唯一登记入口。
 *
 * <p><b>用法</b>（登记 + 读取两步，读取经 {@link BrushRegistry#dataRegistry(ResourceKey)}）：
 * <pre>{@code
 * static final ResourceKey<Registry<DemoMaterial>> DEMO_MATERIAL =
 *         ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("example", "demo_material"));
 *
 * // 在 mod 入口注册监听器，于 CONTENT_REGISTRATION 监听器体内登记（内容注册窗口，见下）：
 * BrushDataRegistries.register(DEMO_MATERIAL, DemoMaterial.CODEC);
 * }</pre>
 * 条目文件放 {@code data/<数据包命名空间>/<键路径>/*.json}（如
 * {@code data/example/demo_material/ruby.json}）—— 条目 id 的命名空间来自<b>数据包目录</b>
 * （与键的命名空间无关，vanilla 同款语义），多包按包序合并（后加载覆盖）。
 * 可被 Brush 生态的其他 mod 数据与玩家数据包（{@code run/world/datapacks}）新增 / 覆盖。</p>
 *
 * <p><b>登记时机</b>：内容注册窗口或之后任意时刻（无窗口约束 —— 与本包内容注册组不同，
 * 数据包注册表不经 vanilla 注册表）；迟登记在下次装载生效（装载时机 = 集成服务器构造完成 /
 * 数据重载完成，见 {@code DataRegistryEngine} javadoc）。bootstrap 期不得调用
 * （本类引 MC 类型，须遵守与 {@link BrushRegistry} 相同的类加载纪律）。</p>
 *
 * <p><b>失败语义</b>（都在发生点记归账拒绝并抛可读异常，不吞）：
 * <ul>
 *   <li>无 mod 上下文 —— 复用 {@link BrushRegistry} 的守卫（拒绝路径与归账口径一致）；</li>
 *   <li>标识符重复 —— 已有同标识符注册表（含不同键实例）时显式拒绝
 *       （{@code IllegalStateException}），不做静默覆盖。</li>
 * </ul>
 * 装载期数据错误（条目 JSON 坏）<b>不中断世界加载</b> —— 旧表保留 + 逐键 ERROR 日志
 * （mod 经 {@code dataRegistry(...)} 空判降级），见 {@code DataRegistryEngine} javadoc 错误语义节。</p>
 *
 * <p><b>键身份（重要）</b>：{@code ResourceKey} 在 26.1.2 为身份相等（无 equals/hashCode 覆写，
 * javap 实证）—— 登记、读取与 codec 中引用该注册表<B>必须使用同一键实例</B>
 * （静态常量）；不同实例即使标识符相同也不会命中。</p>
 */

public final class BrushDataRegistries {

    private BrushDataRegistries() {
    }

    /**
     * 登记一个数据包注册表（幂等拒绝重复标识符；登记后经
     * {@link BrushRegistry#dataRegistry(ResourceKey)} 读取装载结果）。
     *
     * @param key   注册表键（须为静态常量单实例 —— 见类 javadoc 键身份）
     * @param codec 条目 codec
     * @throws IllegalStateException 无 mod 上下文，或标识符与已登记注册表重复
     */
    public static <T> void register(ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(codec, "codec");
        String modId = BrushRegistry.requireModContext(key.identifier(), "datapack registry registration");
        if (!DataRegistryEngine.register(key, codec)) {
            String reason = "a datapack registry with identifier '" + key.identifier()
                    + "' is already registered (duplicate registration rejected)";
            ContentLedger.recordRejection(modId, key.identifier().toString(), reason);
            throw new IllegalStateException("[brush] datapack registry registration by mod '"
                    + modId + "' rejected: " + reason);
        }
        ContentLedger.record(modId, "datapack-registry", key.identifier().toString());
    }
}
