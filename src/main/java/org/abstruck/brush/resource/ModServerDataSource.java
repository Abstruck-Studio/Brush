package org.abstruck.brush.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;
import org.abstruck.brush.mod.ModBootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Brush 模组数据包的仓库来源（E1，需求 P0-1，2026-10-12）：向 SERVER_DATA 仓库投递
 * 一个 omnibus 数据包（id {@link ModResourcePacks#SERVER_PACK_ID}），内容 = 全部已加载
 * mod jar 的 {@code data/**} 树（配方 / 进度 / 战利品表等）。
 *
 * <p><b>适用仓库</b>：集成服务器与世界创建用的仓库 —— 由 vanilla {@code ServerPacksSource}
 * 构造的那批（javap 取证链原文见 <b>docs/22-实测快照与外部证据.md §1</b>，P2-4 整改迁出：
 * 实测快照不留在代码注释）。全部经同一 {@code PackRepository#<init>} 构造器，由 R1 已建的
 * RETURN 注入点统一覆盖（<b>E1 零新增注入点</b>，见 docs/footprint.md 变更记录）。
 * 客户端资源仓库（{@code ClientPackSource}）不含本来源 —— 资源与数据分属两个仓库，
 * 互不混装（{@link ModRepositorySource} 服务前者）。
 *
 * <p><b>包构件用公开 API 直接构造</b>（同 {@link ModRepositorySource}）：绕过
 * {@code Pack.readMetaAndCreate} 的 {@code pack.mcmeta} 读取 —— mod jar 无需携带元数据
 * 文件；元数据缺失由 {@code AbstractPackResources.getMetadataSection} 的 null-safe
 * 路径兜底（pack.mcmeta 不存在 → {@code ResourceMetadata.EMPTY}）。</p>
 *
 * <p><b>站位与保活</b>：{@code required=true} + {@code BOTTOM} + {@code fixedPosition=false} ——
 * {@code PackRepository.rebuildSelected} 会把 required 且不在选中列表的包按 defaultPosition
 * 重新插回，因此每次 {@code setSelected} / 资源 reload（{@code /reload}）后我们的数据包
 * 都不掉；优先级：用户数据包（世界 {@code datapacks/} 目录）&gt; mod 数据 &gt; vanilla
 * ——数据包栈的覆盖方向与资源包相反（后者世界侧在上），这是 vanilla
 * {@code PackRepository} 的既有语义，我们不改变它。
 */
final class ModServerDataSource implements RepositorySource {

    private final List<Path> jars;

    ModServerDataSource(List<ModBootstrap.LoadedMod> mods) {
        this.jars = ModResourcePacks.jarList(mods);
    }

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("Data shipped by Brush mods (" + jars.size() + " jar(s))"),
                PackCompatibility.COMPATIBLE,
                FeatureFlagSet.of(),
                List.of());
        PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.BOTTOM, false);
        consumer.accept(new Pack(ModResourcePacks.serverLocation(), new ModServerResourcesSupplier(),
                metadata, selection));
    }

    /**
     * 每次 {@code openPrimary}/{@code openFull} 返回新实例（各自打开自己的 {@code ZipFile}
     * 句柄，由使用方 {@code close()} 释放）—— 与 vanilla {@code FilePackResources} 的句柄模型一致。
     * 打开失败（jar 损坏 / 被独占锁定）包装为非受检异常：资源 reload 以失败中止
     * （fail-fast：不静默跳过）。
     */
    private final class ModServerResourcesSupplier implements Pack.ResourcesSupplier {

        @Override
        public PackResources openPrimary(PackLocationInfo primaryLocation) {
            return newPackResources(primaryLocation);
        }

        @Override
        public PackResources openFull(PackLocationInfo primaryLocation, Pack.Metadata metadata) {
            return newPackResources(primaryLocation);
        }

        private PackResources newPackResources(PackLocationInfo packLocation) {
            try {
                return new ModPackResources(packLocation, jars, PackType.SERVER_DATA);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "brush mod data pack cannot open mod jar(s): " + e, e);
            }
        }
    }
}
