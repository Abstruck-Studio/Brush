package org.abstruck.brush.resource;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 多 jar 资源包的 {@code PackResources} 实现：把若干 mod jar 的 {@code assets/**}
 * 或 {@code data/**}（由构造期 {@link #servedType} 指定）作为一个资源包读出。
 * 逐方法镜像 vanilla {@code FilePackResources}
 * （条目路径规则 / namespace 提取与校验 / listResources 前缀语义
 * 与 invalid 告警文案），差异仅在三点：
 * <ul>
 *   <li>内容来自多个 jar —— 同名条目按 {@code jars} 顺序 first-wins（确定性）；</li>
 *   <li>服务类型参数化（E1，2026-10-12）：CLIENT_RESOURCES 实例只应答
 *       {@code assets/} 树、SERVER_DATA 实例只应答 {@code data/} 树 —— 非本实例
 *       服务类型的调用返回空 / null（与 vanilla 单类型包行为一致）。</li>
 * </ul>
 *
 * <p><b>句柄模型</b>：每个实例在构造时打开自己的 {@link ZipFile} 句柄、{@link #close()} 释放，
 * 与 vanilla 一致 —— 资源 reload 结束时由资源管理器统一 close。
 */
final class ModPackResources extends AbstractPackResources {

    private static final Logger LOGGER = LogManager.getLogger(ModPackResources.class);

    private final List<ZipFile> zips = new ArrayList<>();
    private final PackType servedType;
    private volatile boolean closed;

    /** 构造即打开全部 jar 句柄；任一打开失败 → 关闭已开的并抛出（fail-fast）。 */
    ModPackResources(PackLocationInfo location, List<Path> jars, PackType servedType) throws IOException {
        super(location);
        this.servedType = servedType;
        try {
            for (Path jar : jars) {
                this.zips.add(new ZipFile(jar.toFile()));
            }
        } catch (IOException e) {
            for (ZipFile zip : this.zips) {
                try {
                    zip.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    /** 根级资源（{@code pack.mcmeta} / {@code pack.png}）：无前缀路径。mod jar 通常没有 → null。 */
    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return findEntry(String.join("/", elements));
    }

    /** 类型化资源读取：路径 = {@code <type 目录>/<namespace>/<path>}（镜像 FilePackResources）。 */
    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (type != servedType) {
            return null;
        }
        return findEntry(type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath());
    }

    /**
     * namespace 枚举（镜像 FilePackResources.getNamespaces）：prefix = {@code <type 目录>/}，
     * 经 {@code extractNamespace} 提取；空跳过；{@code Identifier.isValidNamespace} 通过才收；
     * 非空非法 → 告警（文案与 vanilla 一致）。仅本实例的服务类型 —— 其余类型返回空集。
     */
    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type != servedType) {
            return Set.of();
        }
        String prefix = type.getDirectory() + "/";
        Set<String> namespaces = new LinkedHashSet<>();
        for (ZipFile zip : zips) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String namespace = extractNamespace(prefix, entry.getName());
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
        }
        return namespaces;
    }

    /**
     * 前缀列举（镜像 FilePackResources.listResources）：仅本实例的服务类型；
     * pathPrefix = {@code prefix + path + "/"}；跳过目录；相对路径含扩展名交给
     * {@code Identifier.tryBuild}，null → 告警（文案与 vanilla 一致）。
     * 同名条目按 {@code jars} 顺序 first-wins；supplier 绑定的是长生命周期句柄
     * （{@link #zips}，由使用方 {@code close()} 统一释放）。
     */
    @Override
    public void listResources(PackType type, String namespace, String path,
                              ResourceOutput output) {
        if (type != servedType) {
            return;
        }
        String prefix = type.getDirectory() + "/" + namespace + "/";
        String pathPrefix = prefix + path + "/";
        Set<Identifier> seen = new LinkedHashSet<>();
        for (ZipFile zip : zips) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(pathPrefix)) {
                    continue;
                }
                String relative = name.substring(prefix.length());
                Identifier id = Identifier.tryBuild(namespace, relative);
                if (id == null) {
                    LOGGER.warn("Invalid path in datapack: {}:{}, ignoring", namespace, relative);
                } else if (seen.add(id)) {
                    ZipFile capturedZip = zip;
                    ZipEntry capturedEntry = entry;
                    output.accept(id, () -> capturedZip.getInputStream(capturedEntry));
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ZipFile zip : zips) {
            try {
                zip.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close brush mod resource pack jar handle: " + e);
            }
        }
    }

    /** 逐 jar first-wins 查找条目；命中 → IoSupplier，未命中 → null。 */
    private IoSupplier<InputStream> findEntry(String path) {
        for (ZipFile zip : zips) {
            ZipEntry entry = zip.getEntry(path);
            if (entry != null && !entry.isDirectory()) {
                return IoSupplier.create(zip, entry);
            }
        }
        return null;
    }

    /**
     * 镜像 vanilla {@code FilePackResources.extractNamespace} 的控制流：
     * 不以 prefix 开头 → 空串；否则取 prefix 后首个 {@code '/'} 之前（无则余下全部）。
     * （包级可见：核心资源包 {@link BrushCorePackResources} 复用同一语义。）
     */
    static String extractNamespace(String prefix, String name) {
        if (!name.startsWith(prefix)) {
            return "";
        }
        int start = prefix.length();
        int end = name.indexOf('/', start);
        return end == -1 ? name.substring(start) : name.substring(start, end);
    }
}
