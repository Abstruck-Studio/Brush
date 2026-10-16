package org.abstruck.brush.launch.assets;

import org.abstruck.brush.launch.BuildProperties;
import org.abstruck.brush.launch.meta.VersionInfo;
import org.abstruck.brush.launch.util.Downloader;
import org.abstruck.brush.launch.util.LauncherLogging;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P1.5.0 入口：下载 asset index 与全部对象（内容寻址，sha1 全校验）。
 *
 * <p>命令行参数（均可省略）：
 * <ul>
 *   <li>{@code --version=26.1.2} 目标版本</li>
 *   <li>{@code --cache=cache} 缓存根目录（与 P1.2/P1.3 共用）</li>
 *   <li>{@code --refresh} 强制重新下载 index 与全部对象</li>
 *   <li>{@code --workers=8} 并行线程数（代理不稳时可降低）</li>
 * </ul>
 *
 * <p>退出码：0 = 全部对象通过校验；1 = 存在失败对象或流程错误（失败清单打印，
 * 重跑本任务即从断点继续，见 {@link AssetDownloader}）；2 = 参数错误。
 */
public final class AssetsMain {

    private static final Logger LOGGER = LogManager.getLogger(AssetsMain.class);

    private static final String USER_AGENT = "Brush-Loader (P1.5 asset pipeline)";

    private AssetsMain() { }

    public static void main(String[] args) {
        LauncherLogging.install();
        String versionId = BuildProperties.mcVersion();
        // P1-10 收尾：默认路径统一由 BuildProperties 提供（单一来源）
        Path cacheRoot = BuildProperties.cacheRoot();
        boolean refresh = false;
        int workers = 8;
        for (String arg : args) {
            if (arg.startsWith("--version=")) {
                versionId = arg.substring("--version=".length());
            } else if (arg.startsWith("--cache=")) {
                cacheRoot = Path.of(arg.substring("--cache=".length()));
            } else if (arg.equals("--refresh")) {
                refresh = true;
            } else if (arg.startsWith("--workers=")) {
                try {
                    workers = Integer.parseInt(arg.substring("--workers=".length()));
                } catch (NumberFormatException e) {
                    LOGGER.error("[brush] invalid --workers value: " + arg);
                    System.exit(2);
                }
            } else {
                LOGGER.error("[brush] unknown argument: " + arg);
                System.exit(2);
            }
        }
        try {
            System.exit(run(versionId, cacheRoot, refresh, workers));
        } catch (Exception e) {
            LOGGER.error("[brush] FATAL: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static int run(String versionId, Path cacheRoot, boolean refresh, int workers) throws IOException {
        LOGGER.info("[brush] === Brush asset download (P1.5.0) ===");
        LOGGER.info("[brush] target version: " + versionId + ", workers=" + Math.max(1, workers));

        Path versionFile = cacheRoot.resolve("meta").resolve("versions").resolve(versionId + ".json");
        if (!Files.isRegularFile(versionFile)) {
            throw new IOException("version json not found: " + versionFile.toAbsolutePath()
                    + " (run `gradlew.bat metadata` first)");
        }
        VersionInfo info = VersionInfo.load(versionFile);

        // quiet：本阶段对象数以千计，逐文件日志无意义；重试与校验失败警告仍会打印
        Downloader downloader = new Downloader(USER_AGENT, false);
        AssetDownloader.Result result = AssetDownloader.download(info, cacheRoot, downloader, refresh, workers);

        LOGGER.info("[brush] --- assets ---");
        LOGGER.info("[brush] index id=" + result.indexId + ", entries=" + result.indexEntries
                + ", unique objects=" + result.uniqueObjects);
        LOGGER.info("[brush] downloaded=" + result.downloaded + " (" + result.bytesDownloaded
                + " bytes), cached=" + result.cached + ", unique-bytes=" + result.uniqueBytes);
        LOGGER.info("[brush] objects dir: " + result.objectsDir);
        LOGGER.info("[brush] elapsed: " + (result.elapsedMillis / 1000) + "s");

        if (result.failures.isEmpty()) {
            LOGGER.info("[brush] asset verification: PASSED (all " + result.uniqueObjects + " objects verified)");
            return 0;
        }
        LOGGER.info("[brush] asset verification: FAILED -- " + result.failures.size() + " object(s) could not be verified");
        int limit = Math.min(result.failures.size(), AssetDownloader.failurePrintLimit());
        for (int i = 0; i < limit; i++) {
            LOGGER.info("[brush]   " + result.failures.get(i));
        }
        if (result.failures.size() > limit) {
            LOGGER.info("[brush]   ... (" + (result.failures.size() - limit) + " more)");
        }
        LOGGER.info("[brush] re-run `gradlew.bat assets` to continue from the remaining objects (idempotent)");
        return 1;
    }
}
