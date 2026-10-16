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
 * Brush 模组资源包的仓库来源：向 {@code PackRepository.discoverAvailable()} 投递
 * 一个 omnibus 资源包（id {@link ModResourcePacks#PACK_ID}），内容 = 全部已加载 mod jar 的资源树。
 *
 * <p><b>包构件用公开 API 直接构造</b>（{@code Pack} 公开构造器，纯字段赋值、零 I/O）——
 * 绕过 {@code Pack.readMetaAndCreate} 的 {@code pack.mcmeta} 读取：mod jar
 * 无需携带元数据文件。元数据缺失由 {@code AbstractPackResources.getMetadataSection} 的
 * null-safe 路径兜底（pack.mcmeta 不存在 → {@code ResourceMetadata.EMPTY}）。
 *
 * <p><b>站位与保活</b>：{@code required=true} + {@code BOTTOM} + {@code fixedPosition=false} ——
 * {@code PackRepository.rebuildSelected} 会把 required 且不在选中列表的包按 defaultPosition
 * 重新插回，因此每次 {@code setSelected}/{@code reload} 后我们的包都不掉；
 * 优先级：S1/S2 = 用户资源包 &gt; mod 资源 &gt; vanilla；
 * S3 宿主重排使我们的包位于列表最底（S3 下不覆盖 vanilla / 宿主 mod 资源）。
 */
final class ModRepositorySource implements RepositorySource {

    private final List<Path> jars;

    ModRepositorySource(List<ModBootstrap.LoadedMod> mods) {
        this.jars = ModResourcePacks.jarList(mods);
    }

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("Resources shipped by Brush mods (" + jars.size() + " jar(s))"),
                PackCompatibility.COMPATIBLE,
                FeatureFlagSet.of(),
                List.of());
        PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.BOTTOM, false);
        consumer.accept(new Pack(ModResourcePacks.location(), new ModResourcesSupplier(),
                metadata, selection));
    }

    /**
     * 每次 {@code openPrimary}/{@code openFull} 返回新实例（各自打开自己的 {@code ZipFile}
     * 句柄，由使用方 {@code close()} 释放）—— 与 vanilla {@code FilePackResources} 的句柄模型一致。
     * 打开失败（jar 损坏 / 被独占锁定）包装为非受检异常：资源 reload 以失败中止
     * （fail-fast：不静默跳过）。
     */
    private final class ModResourcesSupplier implements Pack.ResourcesSupplier {

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
                return new ModPackResources(packLocation, jars, PackType.CLIENT_RESOURCES);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "brush mod resource pack cannot open mod jar(s): " + e, e);
            }
        }
    }
}
