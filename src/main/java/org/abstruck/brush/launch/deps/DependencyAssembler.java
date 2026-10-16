package org.abstruck.brush.launch.deps;

import org.abstruck.brush.launch.meta.RuleEvaluator;
import org.abstruck.brush.launch.meta.VersionInfo;
import org.abstruck.brush.launch.util.Downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * P1.3 依赖装配：rules 过滤 → classifier 修正（x64）→ 逐项下载并校验 → 解压 natives。
 *
 * <p>关键约束：
 * <ul>
 *   <li>下载 URL / sha1 / size 一律取自 version json 的 {@code downloads.artifact}，<b>禁止拼主机名</b></li>
 *   <li>rules 过滤后仍须按 classifier 再筛一次 —— 库 rules 不含 {@code os.arch}，
 *       windows 三变体（natives-windows / -x86 / -arm64）都会通过</li>
 *   <li>校验失败即抛异常，绝不吞错（CLAUDE.md §3.5）</li>
 * </ul>
 */
public final class DependencyAssembler {

    /**
     * 目标架构的 natives classifier。
     *
     * <p>当前目标 {@link RuleEvaluator#OS_ARCH} = x86_64 → 保留 {@code natives-windows}，
     * 其余 windows 变体一律剔除并在报告中登记。
     */
    public static final String NATIVE_CLASSIFIER = "natives-windows";

    /**
     * 无显式 {@code extract.exclude} 时的默认排除前缀。
     *
     * <p>理由：natives 目录只需 DLL；{@code META-INF/} 为签名/清单等元数据，解压进去只会带来噪音。
     * 26.1.2 数据中库均无 {@code extract} 字段；每次解压都会统计跳过项，
     * 报告中可核对"跳过项是否全部为 META-INF/*"。
     */
    private static final List<String> DEFAULT_NATIVE_EXCLUDES = List.of("META-INF/");

    private DependencyAssembler() { }

    /** 装配结果（P1.5 启动管线复用）。路径均为绝对路径。 */
    public static final class Result {

        /** 全部保留库的本地 jar 路径（含 natives jar，其内无 class 文件，对类加载无影响）。 */
        public List<Path> classpath;

        /** natives 解压目录。 */
        public Path nativesDirectory;

        public int totalLibraries;
        public int applicable;
        public List<String> droppedClassifiers;
        public int kept;
        public int duplicates;
        public int downloaded;
        public int cached;
        public long downloadedBytes;
        public Map<String, Integer> urlHosts;
        public int nativeJars;
        public int nativeEntriesWritten;
        public Map<String, Integer> nativeExcludedByPrefix;
    }

    /**
     * 执行装配。幂等：已通过校验的缓存文件不重复下载；natives 每次覆盖解压（体积小、保证确定性）。
     *
     * @param info       version json（P1.2 解析产物）
     * @param cacheRoot  缓存根目录（与 P1.2 一致，默认 {@code cache}）
     * @param downloader 下载器
     * @param refresh    为 true 时全部重新下载
     */
    public static Result assemble(VersionInfo info, Path cacheRoot, Downloader downloader, boolean refresh) throws IOException {
        Path librariesRoot = cacheRoot.resolve("libraries").toAbsolutePath().normalize();
        Path nativesDir = cacheRoot.resolve("natives").resolve(info.id).toAbsolutePath().normalize();

        Result result = new Result();
        result.totalLibraries = info.libraries.size();
        result.droppedClassifiers = new ArrayList<>();
        result.urlHosts = new TreeMap<>();
        result.nativeExcludedByPrefix = new TreeMap<>();

        // 1) rules 过滤（windows/x86_64）
        List<VersionInfo.Library> applicable = new ArrayList<>();
        for (VersionInfo.Library library : info.libraries) {
            if (RuleEvaluator.applies(library.rules)) {
                applicable.add(library);
            }
        }
        result.applicable = applicable.size();

        // 2) classifier 修正：保留无 classifier 与目标架构 natives，其余变体剔除并登记
        List<VersionInfo.Library> kept = new ArrayList<>();
        for (VersionInfo.Library library : applicable) {
            String classifier = library.classifier();
            if (classifier != null && !NATIVE_CLASSIFIER.equals(classifier)) {
                result.droppedClassifiers.add(library.name);
                continue;
            }
            kept.add(library);
        }
        result.kept = kept.size();

        // 3) 按 artifact.path 去重（理论无重复；有则如实计数）
        Map<String, VersionInfo.Library> byPath = new LinkedHashMap<>();
        for (VersionInfo.Library library : kept) {
            byPath.putIfAbsent(artifactOf(library).path, library);
        }
        result.duplicates = kept.size() - byPath.size();

        // 4) 下载并校验
        List<Path> classpath = new ArrayList<>();
        List<NativeJar> nativeJars = new ArrayList<>();
        int downloaded = 0;
        int cached = 0;
        long bytes = 0L;
        for (VersionInfo.Library library : byPath.values()) {
            VersionInfo.Artifact artifact = artifactOf(library);
            Path target = librariesRoot.resolve(artifact.path).normalize();
            if (!target.startsWith(librariesRoot)) {
                throw new IOException("artifact path escapes libraries root: " + artifact.path + " (" + library.name + ")");
            }
            URI uri = URI.create(artifact.url);
            result.urlHosts.merge(String.valueOf(uri.getHost()), 1, Integer::sum);
            Downloader.Outcome outcome = downloader.fetch(uri, target, artifact.sha1, artifact.size, refresh);
            if (outcome.downloaded) {
                downloaded++;
                bytes += outcome.size;
            } else {
                cached++;
            }
            classpath.add(target);
            if (NATIVE_CLASSIFIER.equals(library.classifier())) {
                nativeJars.add(new NativeJar(target, excludesOf(library)));
            }
        }
        result.downloaded = downloaded;
        result.cached = cached;
        result.downloadedBytes = bytes;
        result.classpath = List.copyOf(classpath);
        result.nativeJars = nativeJars.size();

        // 5) natives 解压（覆盖式，幂等）
        int written = 0;
        for (NativeJar nativeJar : nativeJars) {
            written += extract(nativeJar, nativesDir, result.nativeExcludedByPrefix);
        }
        result.nativeEntriesWritten = written;
        result.nativesDirectory = nativesDir;
        return result;
    }

    private static VersionInfo.Artifact artifactOf(VersionInfo.Library library) throws IOException {
        if (library.downloads == null || library.downloads.artifact == null
                || library.downloads.artifact.path == null || library.downloads.artifact.url == null) {
            throw new IOException("library has no downloadable artifact: " + library.name);
        }
        return library.downloads.artifact;
    }

    private static List<String> excludesOf(VersionInfo.Library library) {
        if (library.extract != null && library.extract.exclude != null && !library.extract.exclude.isEmpty()) {
            return library.extract.exclude;
        }
        return DEFAULT_NATIVE_EXCLUDES;
    }

    private static int extract(NativeJar nativeJar, Path nativesDir, Map<String, Integer> excludedByPrefix) throws IOException {
        int written = 0;
        try (ZipFile zip = new ZipFile(nativeJar.jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                String excludedBy = matchedExclude(name, nativeJar.excludes);
                if (excludedBy != null) {
                    excludedByPrefix.merge(excludedBy, 1, Integer::sum);
                    continue;
                }
                Path out = nativesDir.resolve(name).normalize();
                if (!out.startsWith(nativesDir)) {
                    throw new IOException("zip entry escapes natives dir: " + name + " in " + nativeJar.jar.getFileName());
                }
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                written++;
            }
        }
        return written;
    }

    /** 前缀匹配（官方 launcher 语义）；返回命中的前缀，未命中返回 null。 */
    private static String matchedExclude(String name, List<String> excludes) {
        for (String exclude : excludes) {
            if (name.startsWith(exclude)) {
                return exclude;
            }
        }
        return null;
    }

    /** natives classifier jar + 其解压排除规则。 */
    private record NativeJar(Path jar, List<String> excludes) { }
}
