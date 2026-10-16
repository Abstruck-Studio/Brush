package org.abstruck.brush.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Brush 核心资源包：把 Brush 自身类路径资源树（{@code assets/**}）接入
 * 资源栈 —— 仅 standalone（S1）挂载。
 *
 * <p><b>为什么需要</b>：S2/S3 下宿主把 brush 制品作为资源包挂载，brush 命名空间天然可见；
 * S1 没有任何机制挂载我们自己的资源 —— brush 命名空间缺失会让侧栏 sprite
 * （{@code brush:tab_left_*}）不进 gui 图集、{@code blitSprite} 全部落空。
 * mod 列表默认图标不受影响，是因为 {@code ui.ModIconLoader} 走类路径直读、不经资源系统。
 *
 * <p><b>枚举问题与解法</b>：类路径条目（dev = {@code build/resources/main} 目录，产物 = jar）
 * 无法在运行期枚举，而 gui 图集的 directory 源要求逐文件 {@code listResources}
 * （{@code DirectoryLister.run} → {@code FileToIdConverter.listMatchingResources}
 * → 逐包 {@code listResources}）。故文件清单由构建期任务 {@code generateBrushResourceIndex}
 * 扫描 {@code src/main/resources/{assets,data}} 生成 {@code brush-resources.index}，
 * 运行期一次读入充当枚举源；文件内容仍经类路径 {@code getResourceAsStream} 直读
 * （目录 / jar 两形态通用，与 {@code ModIconLoader} 同一通道）。
 *
 * <p><b>站位</b>：与 {@code brush_mods} 同构 —— {@code required=true} +
 * {@code Position.BOTTOM} + {@code fixedPosition=false}（保活、不抢用户资源包）；
 * 两包命名空间不相交，同层内相对顺序无影响。清单由 {@code processResources} 打进类路径根
 * （dev 输出目录与产物 jar 同布局）。
 */
final class BrushCorePackResources extends AbstractPackResources {

    /** 选中的稳定标识（选中列表 / 自检报告 / 日志）。 */
    static final String PACK_ID = "brush_core";

    /** 构建期资源清单的类路径位置（数据生成任务 {@code generateBrushResourceIndex} 产出）。 */
    static final String INDEX_RESOURCE = "brush-resources.index";

    private static final Logger LOGGER = LogManager.getLogger(BrushCorePackResources.class);

    /** 清单懒加载缓存（null = 已加载但缺失；配合 {@link #indexLoaded} 区分"未读"与"读空"）。 */
    private static List<String> indexCache;
    private static boolean indexLoaded;

    /** 包内全部文件（相对类路径根的路径，如 {@code assets/brush/textures/gui/icon.png}）。 */
    private final List<String> entries;

    BrushCorePackResources(PackLocationInfo location, List<String> entries) {
        super(location);
        this.entries = entries;
    }

    /**
     * 读取构建期清单（类路径缺失 → null 并如实告警；只读一次）。attach 在挂载前先查，
     * 缺失时不挂包（自检审计同步置失败 —— 不做静默降级）。
     */
    static synchronized List<String> index() {
        if (!indexLoaded) {
            indexLoaded = true;
            List<String> lines = null;
            try (InputStream in = BrushCorePackResources.class.getClassLoader()
                    .getResourceAsStream(INDEX_RESOURCE)) {
                if (in != null) {
                    lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .lines()
                            .filter(line -> !line.isBlank())
                            .toList();
                }
            } catch (IOException e) {
                LOGGER.error("[brush] failed to read core resource index '" + INDEX_RESOURCE + "'", e);
            }
            if (lines == null) {
                LOGGER.error("[brush] core resource index '" + INDEX_RESOURCE + "' is missing from the"
                        + " classpath -- brush core assets cannot be enumerated; the S1 resource stack"
                        + " will not include the brush namespace"
                        + " (expected from build task generateBrushResourceIndex)");
            } else {
                LOGGER.info("[brush] core resource index: " + lines.size() + " file(s)");
            }
            indexCache = lines;
        }
        return indexCache;
    }

    /** 包位置信息（attach 与自检共用的单一构造点）。命名避开继承自 {@code PackResources} 的
     * 实例方法 {@code location()}（静态同名会构成非法隐藏 / "无法实现"编译错误的诱因）。 */
    static PackLocationInfo locationInfo() {
        return new PackLocationInfo(PACK_ID, Component.literal("Brush core resources"),
                PackSource.BUILT_IN, Optional.empty());
    }

    /** 仓库来源：投递核心资源包（Pack 构件路线与 {@code ModRepositorySource} 完全一致）。 */
    static RepositorySource source(List<String> entries) {
        return new CoreSource(entries);
    }

    /** 幂等存在性判定（attach 对已挂载来源的识别；不依赖具体来源类型可见性）。 */
    static boolean isCoreSource(RepositorySource source) {
        return source instanceof CoreSource;
    }

    // ------------------------------------------------------------------
    // PackResources 实现（读取语义与 ModPackResources 同构，差异仅在来源 = 类路径）
    // ------------------------------------------------------------------

    /** 根级资源（{@code pack.mcmeta} 等）：无前缀路径读取；我们未随包提供该类文件 → null。 */
    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return open(String.join("/", elements));
    }

    /** 类型化资源读取：路径 = {@code <type 目录>/<namespace>/<path>}（镜像 FilePackResources）。 */
    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        return open(type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath());
    }

    /**
     * namespace 枚举（语义与告警文案镜像 {@link ModPackResources} / 原版 {@code FilePackResources}）：
     * prefix = {@code <type 目录>/}，经 {@code extractNamespace} 提取；空跳过；
     * {@code Identifier.isValidNamespace} 通过才收；非空非法 → 告警。
     */
    @Override
    public Set<String> getNamespaces(PackType type) {
        String prefix = type.getDirectory() + "/";
        Set<String> namespaces = new LinkedHashSet<>();
        for (String entry : entries) {
            String namespace = ModPackResources.extractNamespace(prefix, entry);
            if (namespace.isEmpty()) {
                continue;
            }
            if (!Identifier.isValidNamespace(namespace)) {
                LOGGER.warn("Non {} character in namespace {} in pack {}, ignoring",
                        "[a-z0-9_.-]", namespace, location().id());
                continue;
            }
            namespaces.add(namespace);
        }
        return namespaces;
    }

    /**
     * 前缀列举（语义镜像 {@link ModPackResources} / 原版 {@code FilePackResources}）：
     * pathPrefix = {@code prefix + path + "/"}；清单由构建期扫描单一资源树生成 → 条目天然唯一，
     * 无需去重；交付的 supplier 与 {@code IoSupplier.create(Path)} 同语义（读取时缺席抛 IOException，
     * 失败可见而非静默）。
     */
    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        String prefix = type.getDirectory() + "/" + namespace + "/";
        String pathPrefix = prefix + path + "/";
        for (String entry : entries) {
            if (!entry.startsWith(pathPrefix)) {
                continue;
            }
            String relative = entry.substring(prefix.length());
            Identifier id = Identifier.tryBuild(namespace, relative);
            if (id == null) {
                LOGGER.warn("Invalid path in datapack: {}:{}, ignoring", namespace, relative);
            } else {
                output.accept(id, supply(entry));
            }
        }
    }

    /** 无长生命周期句柄（每次读取现开流）—— 空实现，满足 AutoCloseable 契约即可。 */
    @Override
    public void close() {
        // no-op：与 ModPackResources 的 ZipFile 句柄模型不同，本包不持有句柄。
    }

    // ------------------------------------------------------------------
    // 读取通道
    // ------------------------------------------------------------------

    /** getResource / getRootResource 用：不存在 → null（"缺失"契约）；存在 → 现开流的 supplier。 */
    private static IoSupplier<InputStream> open(String path) {
        return exists(path) ? supply(path) : null;
    }

    /** listResources 用：交付与 {@code IoSupplier.create(Path)} 同语义的 supplier（get() 时缺席抛 IOException）。 */
    private static IoSupplier<InputStream> supply(String path) {
        ClassLoader loader = BrushCorePackResources.class.getClassLoader();
        return () -> {
            InputStream in = loader.getResourceAsStream(path);
            if (in == null) {
                throw new IOException("classpath resource not found: " + path);
            }
            return in;
        };
    }

    /** 类路径存在性探测（与 ModIconLoader 同一通道：目录 / jar 两种类路径形态通用）。 */
    private static boolean exists(String path) {
        try (InputStream probe = BrushCorePackResources.class.getClassLoader()
                .getResourceAsStream(path)) {
            return probe != null;
        } catch (IOException e) {
            LOGGER.warn("[brush] core resource probe failed for '" + path + "': " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 来源与 supplier
    // ------------------------------------------------------------------

    /** 包来源：每次 openPrimary/openFull 返回持有同一清单的新实例（零句柄，无需释放）。 */
    private record CoreSource(List<String> entries) implements RepositorySource {

        @Override
        public void loadPacks(Consumer<Pack> consumer) {
            Pack.Metadata metadata = new Pack.Metadata(
                    Component.literal("Brush core resources (loader UI assets)"),
                    PackCompatibility.COMPATIBLE,
                    FeatureFlagSet.of(),
                    List.of());
            PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.BOTTOM, false);
            consumer.accept(new Pack(locationInfo(), new CoreResourcesSupplier(entries), metadata, selection));
        }
    }

    /** Pack.ResourcesSupplier：两个打开口均返回同构实例（镜像 {@code ModRepositorySource}）。 */
    private record CoreResourcesSupplier(List<String> entries) implements Pack.ResourcesSupplier {

        @Override
        public PackResources openPrimary(PackLocationInfo primaryLocation) {
            return new BrushCorePackResources(primaryLocation, entries);
        }

        @Override
        public PackResources openFull(PackLocationInfo primaryLocation, Pack.Metadata metadata) {
            return new BrushCorePackResources(primaryLocation, entries);
        }
    }
}
