package org.abstruck.brush.launch.deps;

import org.abstruck.brush.launch.BuildProperties;
import org.abstruck.brush.launch.meta.VersionInfo;
import org.abstruck.brush.launch.util.Downloader;
import org.abstruck.brush.launch.util.LauncherLogging;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * P1.3 入口：装配依赖并打印结构化报告。
 *
 * <p>命令行参数（均可省略）：
 * <ul>
 *   <li>{@code --version=26.1.2} 目标版本</li>
 *   <li>{@code --cache=cache} 缓存根目录（与 P1.2 共用）</li>
 *   <li>{@code --refresh} 强制重新下载全部库</li>
 * </ul>
 *
 * <p>前置：先完成 P1.2（version json 已在缓存中）；Gradle 任务 {@code libraries} 已通过
 * {@code dependsOn metadata} 保证这一顺序。
 */
public final class LibrariesMain {

    private static final Logger LOGGER = LogManager.getLogger(LibrariesMain.class);

    private static final String USER_AGENT = "Brush-Loader (P1.3 dependency assembly)";

    private LibrariesMain() { }

    public static void main(String[] args) {
        LauncherLogging.install();
        String versionId = BuildProperties.mcVersion();
        // P1-10 收尾：默认路径统一由 BuildProperties 提供（单一来源）
        Path cacheRoot = BuildProperties.cacheRoot();
        boolean refresh = false;
        for (String arg : args) {
            if (arg.startsWith("--version=")) {
                versionId = arg.substring("--version=".length());
            } else if (arg.startsWith("--cache=")) {
                cacheRoot = Path.of(arg.substring("--cache=".length()));
            } else if (arg.equals("--refresh")) {
                refresh = true;
            } else {
                LOGGER.error("[brush] unknown argument: " + arg);
                System.exit(2);
            }
        }
        try {
            run(versionId, cacheRoot, refresh);
        } catch (Exception e) {
            LOGGER.error("[brush] FATAL: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void run(String versionId, Path cacheRoot, boolean refresh) throws IOException {
        LOGGER.info("[brush] === Brush dependency assembly (P1.3) ===");
        LOGGER.info("[brush] target version: " + versionId);

        Path versionFile = cacheRoot.resolve("meta").resolve("versions").resolve(versionId + ".json");
        if (!Files.isRegularFile(versionFile)) {
            throw new IOException("version json not found: " + versionFile.toAbsolutePath()
                    + " (run `gradlew.bat metadata` first)");
        }
        VersionInfo info = VersionInfo.load(versionFile);

        Downloader downloader = new Downloader(USER_AGENT);
        DependencyAssembler.Result result = DependencyAssembler.assemble(info, cacheRoot, downloader, refresh);
        report(result);
    }

    private static void report(DependencyAssembler.Result result) throws IOException {
        LOGGER.info("[brush] --- libraries ---");
        LOGGER.info("[brush] total=" + result.totalLibraries + ", rules-applicable=" + result.applicable
                + ", kept=" + result.kept + ", dropped-foreign-classifier=" + result.droppedClassifiers.size()
                + ", duplicates=" + result.duplicates);
        for (String name : result.droppedClassifiers) {
            LOGGER.info("[brush]   dropped: " + name);
        }

        LOGGER.info("[brush] --- download ---");
        LOGGER.info("[brush] downloaded=" + result.downloaded + " (" + result.downloadedBytes + " bytes), cached=" + result.cached);
        for (Map.Entry<String, Integer> host : result.urlHosts.entrySet()) {
            LOGGER.info("[brush]   host " + host.getKey() + " -> " + host.getValue());
        }

        LOGGER.info("[brush] --- classpath (" + result.classpath.size() + " entries) ---");
        for (int i = 0; i < result.classpath.size(); i++) {
            LOGGER.info("[brush] cp[" + i + "] " + result.classpath.get(i));
        }

        LOGGER.info("[brush] --- natives ---");
        LOGGER.info("[brush] jars=" + result.nativeJars + ", entries-written=" + result.nativeEntriesWritten
                + ", excluded=" + result.nativeExcludedByPrefix);
        if (result.nativeExcludedByPrefix.keySet().equals(Set.of("META-INF/"))) {
            LOGGER.info("[brush] note: all excluded entries are META-INF/* -> no native payload skipped");
        }
        LOGGER.info("[brush] dir=" + result.nativesDirectory);
        try (Stream<Path> walk = Files.walk(result.nativesDirectory)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            List<Path> dlls = files.stream().filter(p -> p.getFileName().toString().endsWith(".dll")).toList();
            LOGGER.info("[brush] files in natives dir: total=" + files.size() + ", dll=" + dlls.size());
            for (Path dll : dlls) {
                LOGGER.info("[brush]   " + result.nativesDirectory.relativize(dll));
            }
        }
    }
}
