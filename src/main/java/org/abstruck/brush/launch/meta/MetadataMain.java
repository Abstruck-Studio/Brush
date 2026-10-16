package org.abstruck.brush.launch.meta;

import com.google.gson.Gson;
import org.abstruck.brush.launch.BuildProperties;
import org.abstruck.brush.launch.util.Downloader;
import org.abstruck.brush.launch.util.LauncherLogging;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P1.2 入口：manifest → version json → 校验 → 解析 → 报告。
 *
 * <p>命令行参数（均可省略）：
 * <ul>
 *   <li>{@code --version=26.1.2} 目标版本</li>
 *   <li>{@code --cache=cache} 缓存根目录（相对当前工作目录；Gradle 下即为项目根）</li>
 *   <li>{@code --refresh} 强制重新下载 manifest 与 version json</li>
 *   <li>{@code --client-jar=26.1.2/26.1.2.jar} 本地 vanilla client jar 路径；存在则做 sha1/size 交叉核验</li>
 * </ul>
 */
public final class MetadataMain {

    private static final Logger LOGGER = LogManager.getLogger(MetadataMain.class);

    /**
     * 官方 launcher meta 入口。
     *
     * <p>全流程唯一硬编码的 URL —— version json 的 URL 一律取自 manifest（CLAUDE.md §4 P1.2 禁止硬编码）。
     */
    private static final URI MANIFEST_URI = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

    private static final String USER_AGENT = "Brush-Loader (P1.2 metadata pipeline)";

    private MetadataMain() { }

    public static void main(String[] args) {
        LauncherLogging.install();
        String versionId = BuildProperties.mcVersion();
        // P1-10 收尾：默认路径统一由 BuildProperties 提供（单一来源）
        Path cacheRoot = BuildProperties.cacheRoot();
        boolean refresh = false;
        Path clientJar = BuildProperties.clientJar();
        for (String arg : args) {
            if (arg.startsWith("--version=")) {
                versionId = arg.substring("--version=".length());
            } else if (arg.startsWith("--cache=")) {
                cacheRoot = Path.of(arg.substring("--cache=".length()));
            } else if (arg.equals("--refresh")) {
                refresh = true;
            } else if (arg.startsWith("--client-jar=")) {
                clientJar = Path.of(arg.substring("--client-jar=".length()));
            } else {
                LOGGER.error("[brush] unknown argument: " + arg);
                System.exit(2);
            }
        }
        try {
            run(versionId, cacheRoot, refresh, clientJar);
        } catch (Exception e) {
            LOGGER.error("[brush] FATAL: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void run(String versionId, Path cacheRoot, boolean refresh, Path clientJar) throws IOException {
        LOGGER.info("[brush] === Brush metadata pipeline (P1.2) ===");
        LOGGER.info("[brush] target version: " + versionId);

        Gson gson = new Gson();
        Downloader downloader = new Downloader(USER_AGENT);

        // 1) manifest（无校验值，缓存命中即跳过；--refresh 强制重下）
        Path metaDir = cacheRoot.resolve("meta");
        Path manifestFile = metaDir.resolve("version_manifest_v2.json");
        downloader.download(MANIFEST_URI, manifestFile, null, 0L, refresh);
        VersionManifest manifest = gson.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8), VersionManifest.class);
        LOGGER.info("[brush] manifest: " + manifest.versions.size() + " versions, latest release="
                + manifest.latest.release + ", latest snapshot=" + manifest.latest.snapshot);

        // 2) 从 manifest 中取目标版本条目（URL 唯一权威来源）
        VersionManifest.VersionEntry entry = null;
        for (VersionManifest.VersionEntry candidate : manifest.versions) {
            if (candidate.id.equals(versionId)) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            throw new IOException("version '" + versionId + "' not found in manifest");
        }
        LOGGER.info("[brush] entry: " + entry.id + " (" + entry.type + "), releaseTime=" + entry.releaseTime);
        LOGGER.info("[brush] version json url : " + entry.url);
        LOGGER.info("[brush] version json sha1: " + entry.sha1);

        // 3) 下载并校验 version json（sha1 来自 manifest 条目，校验失败即抛错）
        Path versionFile = metaDir.resolve("versions").resolve(versionId + ".json");
        downloader.download(URI.create(entry.url), versionFile, entry.sha1, 0L, refresh);

        // 4) 解析（Argument 归一化在 VersionInfo.load 内完成）
        VersionInfo info = VersionInfo.load(versionFile);

        // 5) 报告
        report(info);

        // 6) 客户端 jar：存在则复核 sha1/size；**不存在则从 version json 的 downloads.client 下载**
        //    （2026-10-15 用户要求"新设备开箱自举"—— 此前缺件只打印 skipped，设备换机必须手工放 jar）
        ensureClientJar(info, clientJar);
    }

    private static void report(VersionInfo info) {
        LOGGER.info("[brush] --- version info ---");
        LOGGER.info("[brush] id=" + info.id + " type=" + info.type + " assets=" + info.assets);
        LOGGER.info("[brush] javaVersion: " + info.javaVersion.component + " (major " + info.javaVersion.majorVersion + ")");
        LOGGER.info("[brush] mainClass: " + info.mainClass);
        LOGGER.info("[brush] minimumLauncherVersion: " + info.minimumLauncherVersion);

        LOGGER.info("[brush] --- libraries (target " + RuleEvaluator.OS_NAME + "/" + RuleEvaluator.OS_ARCH + ") ---");
        int applicable = 0;
        int classifierEntries = 0;
        List<String> windowsClassifierEntries = new ArrayList<>();
        for (VersionInfo.Library library : info.libraries) {
            if (!RuleEvaluator.applies(library.rules)) {
                continue;
            }
            applicable++;
            if (library.classifier() != null) {
                classifierEntries++;
                if (library.natives == null) {
                    windowsClassifierEntries.add(library.name);
                }
            }
        }
        LOGGER.info("[brush] total=" + info.libraries.size() + ", applicable=" + applicable
                + ", with-classifier=" + classifierEntries);
        for (String name : windowsClassifierEntries) {
            LOGGER.info("[brush]   - " + name);
        }
        LOGGER.info("[brush] NOTE: these classifier entries carry no os.arch in rules; on x64 the");
        LOGGER.info("[brush]       -x86 / -arm64 variants must be filtered by classifier (P1.3, docs/02)");

        LOGGER.info("[brush] --- downloads ---");
        printArtifact("client", info.downloads.client);
        printArtifact("server", info.downloads.server);
        printArtifact("client_mappings", info.downloads.clientMappings);
        printArtifact("server_mappings", info.downloads.serverMappings);

        LOGGER.info("[brush] --- asset index ---");
        LOGGER.info("[brush] id=" + info.assetIndex.id + " size=" + info.assetIndex.size
                + " totalSize=" + info.assetIndex.totalSize + " sha1=" + info.assetIndex.sha1);
        LOGGER.info("[brush] url=" + info.assetIndex.url);

        LOGGER.info("[brush] --- arguments ---");
        reportArguments("game", info.arguments.game);
        reportArguments("jvm", info.arguments.jvm);
        if (info.arguments.defaultUserJvm != null) {
            reportArguments("default-user-jvm", info.arguments.defaultUserJvm);
        }
    }

    private static void printArtifact(String label, VersionInfo.Artifact artifact) {
        if (artifact == null) {
            LOGGER.info("[brush] " + label + ": <absent>");
            return;
        }
        LOGGER.info("[brush] " + label + ": size=" + artifact.size + " sha1=" + artifact.sha1);
        LOGGER.info("[brush]   url=" + artifact.url);
    }

    private static void reportArguments(String label, List<VersionInfo.Argument> arguments) {
        int plain = 0;
        int conditional = 0;
        for (VersionInfo.Argument argument : arguments) {
            if (argument.rules == null || argument.rules.isEmpty()) {
                plain++;
            } else {
                conditional++;
            }
        }
        LOGGER.info("[brush] " + label + ": total=" + arguments.size() + " (plain=" + plain + ", conditional=" + conditional + ")");
        for (VersionInfo.Argument argument : arguments) {
            if (argument.rules == null || argument.rules.isEmpty()) {
                LOGGER.info("[brush]   " + String.join(" ", argument.values));
            }
        }
    }

    /**
     * 确保本地客户端 jar 就位（2026-10-15 扩展：**缺失即下载**，服务"新设备开箱自举"）。
     *
     * <p>行为：文件不存在 → 从 version json 的 {@code downloads.client.url} 下到该路径，
     * sha1 与 size 双重校验（{@link Downloader#fetch} 内部完成，不符即抛，最多 3 次重试）；
     * 文件已存在 → 复核 sha1/size，不符即抛（原行为不变）。</p>
     *
     * <p>下载前**先报告缺什么**（CLAUDE.md §1.3：缺失的部分才下载，不静默联网）。</p>
     */
    private static void ensureClientJar(VersionInfo info, Path clientJar) throws IOException {
        LOGGER.info("[brush] --- local client jar ---");
        VersionInfo.Artifact expected = info.downloads == null ? null : info.downloads.client;
        if (expected == null || expected.url == null) {
            throw new IOException("version json has no downloads.client (url) to download or verify against");
        }
        if (!Files.isRegularFile(clientJar)) {
            LOGGER.info("[brush] client jar missing -- downloading it now (report first, then download;"
                    + " CLAUDE.md §1.3)");
            LOGGER.info("[brush]   target=" + clientJar.toAbsolutePath());
            LOGGER.info("[brush]   url=" + expected.url + " size=" + expected.size + " sha1=" + expected.sha1);
            Path parent = clientJar.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Downloader.Outcome outcome = new Downloader(USER_AGENT)
                    .fetch(URI.create(expected.url), clientJar, expected.sha1, expected.size, false);
            LOGGER.info("[brush] downloaded: " + outcome.file.toAbsolutePath()
                    + " size=" + outcome.size + " sha1=" + outcome.sha1);
            return;
        }
        String actualSha1 = Downloader.sha1(clientJar);
        long actualSize = Files.size(clientJar);
        if (actualSha1.equalsIgnoreCase(expected.sha1) && actualSize == expected.size) {
            LOGGER.info("[brush] OK: " + clientJar.toAbsolutePath());
            LOGGER.info("[brush] sha1=" + actualSha1 + " size=" + actualSize);
        } else {
            throw new IOException("client jar mismatch: expected sha1=" + expected.sha1 + " size=" + expected.size
                    + ", actual sha1=" + actualSha1 + " size=" + actualSize);
        }
    }
}
