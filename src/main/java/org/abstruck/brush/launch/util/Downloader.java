package org.abstruck.brush.launch.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 下载器（P1.2 起共用）。
 *
 * <p>约束：
 * <ul>
 *   <li>支持 sha1 / size 校验；校验不通过一律抛异常，绝不吞错（CLAUDE.md §3.5）</li>
 *   <li>目标文件已存在且校验通过时跳过下载，保证任务可重复执行（对代理不稳的环境可断点续跑）</li>
 *   <li>下载先写临时文件（.part），校验通过后才落盘</li>
 *   <li><b>有界重试</b>：单次 fetch 最多 {@value #MAX_ATTEMPTS} 次尝试（首次 + 2 次重试，CLAUDE.md §3.4
 *       上限）；每次重试都打印原因与退避时长，不是静默重试。校验失败（sha1/size 不符）同样计入重试
 *       —— 代理偶发返回错误内容时重试可自愈；全部尝试失败后抛出含最后一次原因的异常</li>
 *   <li>线程安全：实例可在多线程下共享（{@link HttpClient} 线程安全；fetch 无实例状态写入），
 *       供 P1.5 资产批量并行下载使用</li>
 * </ul>
 */
public final class Downloader {

    private static final Logger LOGGER = LogManager.getLogger(Downloader.class);

    private static final String SHA1 = "SHA-1";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 单请求超时。语义：覆盖到"收到响应头"为止（{@code ofInputStream} 的 future 在头部就绪时完成），
     * 正文流式读取不受此限 —— 因此慢速大文件不会因此失败，
     * 而"连接建立后响应迟迟不来"的停滞会在 2 分钟内失败并进入有界重试。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    /** 单次 fetch 的最大尝试次数（首次 + 2 次重试）。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 重试退避基数（毫秒）：第 n 次失败后等待 n * 本值再重试。 */
    private static final long RETRY_BACKOFF_MILLIS = 1000L;

    /**
     * 下载心跳间隔（毫秒）：verbose 时每这么久打印一次"已收字节数"。
     * 2026-10-15 新增，起因见 {@link #fetch} 内的注释（网络挂住时日志像死锁）。
     */
    private static final long HEARTBEAT_MILLIS = 10_000L;

    private final HttpClient http;
    private final String userAgent;

    /**
     * false 时抑制逐文件的"下载成功 / 命中缓存"日志（P1.5 资产批量下载用，数千文件）；
     * 重试与校验失败等警告<b>始终</b>打印。
     */
    private final boolean verbose;

    public Downloader(String userAgent) {
        this(userAgent, true);
    }

    public Downloader(String userAgent, boolean verbose) {
        this.userAgent = userAgent;
        this.verbose = verbose;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * 下载 {@code uri} 到 {@code target} 并校验。
     *
     * @param expectedSha1 期望 sha1；为 null 时跳过 sha1 校验（如 manifest 自身无校验值）
     * @param expectedSize 期望字节数；≤0 时跳过大小校验
     * @param refresh      为 true 时无条件重新下载
     * @return 目标文件的 sha1
     */
    public String download(URI uri, Path target, String expectedSha1, long expectedSize, boolean refresh) throws IOException {
        return fetch(uri, target, expectedSha1, expectedSize, refresh).sha1;
    }

    /**
     * 同 {@link #download}，但返回 {@link Outcome}，供装配报告区分"新下载 / 命中缓存"（P1.3 起使用）。
     */
    public Outcome fetch(URI uri, Path target, String expectedSha1, long expectedSize, boolean refresh) throws IOException {
        if (refresh) {
            Files.deleteIfExists(target);
        } else if (Files.isRegularFile(target)) {
            String cachedSha1 = sha1(target);
            boolean sha1Ok = expectedSha1 == null || expectedSha1.equalsIgnoreCase(cachedSha1);
            boolean sizeOk = expectedSize <= 0 || Files.size(target) == expectedSize;
            if (sha1Ok && sizeOk) {
                log("cached, skipping download: " + target);
                return new Outcome(target, cachedSha1, Files.size(target), false);
            }
            LOGGER.info("[brush] cached file failed verification, re-downloading: " + target);
        }

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // 2026-10-15（用户报"长时间卡在 MC libraries 之后"）：下载**开始前**先报一行，
                // 否则连接挂住时日志里什么都没有，看起来像死锁（实测：maven.fabricmc.net 不通时
                // 每次尝试要等满 REQUEST_TIMEOUT=2 分钟）。verbose 关闭时（批量资产下载）不打。
                log("downloading (attempt " + attempt + "/" + MAX_ATTEMPTS + "): " + uri
                        + " -> " + target + (expectedSize > 0 ? " (" + expectedSize + " bytes)" : ""));
                return downloadOnce(uri, target, expectedSha1, expectedSize);
            } catch (IOException e) {
                lastFailure = e;
                if (Thread.currentThread().isInterrupted() || attempt >= MAX_ATTEMPTS) {
                    break;
                }
                long backoff = RETRY_BACKOFF_MILLIS * attempt;
                LOGGER.info("[brush] attempt " + attempt + "/" + MAX_ATTEMPTS + " failed: " + e.getMessage()
                        + " -- retrying in " + backoff + " ms: " + uri);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("download failed after " + MAX_ATTEMPTS + " attempts: " + uri
                + " (last error: " + lastFailure.getMessage() + ")", lastFailure);
    }

    private Outcome downloadOnce(URI uri, Path target, String expectedSha1, long expectedSize) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + uri);
            }
            MessageDigest digest = MessageDigest.getInstance(SHA1);
            long size = 0L;
            // 心跳：连接建立后若长时间没有整体完成，每 10 秒报一次已收字节数（verbose 时）——
            // 让"慢"与"死"在日志上可区分。
            long lastHeartbeat = System.currentTimeMillis();
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    size += read;
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                        lastHeartbeat = now;
                        log("  ... still downloading (" + size + " bytes so far): " + uri);
                    }
                }
            }
            String actualSha1 = HexFormat.of().formatHex(digest.digest());
            if (expectedSha1 != null && !expectedSha1.equalsIgnoreCase(actualSha1)) {
                Files.deleteIfExists(temp);
                throw new IOException("sha1 mismatch for " + uri + ": expected " + expectedSha1 + ", actual " + actualSha1);
            }
            if (expectedSize > 0 && size != expectedSize) {
                Files.deleteIfExists(temp);
                throw new IOException("size mismatch for " + uri + ": expected " + expectedSize + ", actual " + size);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            log("downloaded and verified (" + size + " bytes, sha1 " + actualSha1 + "): " + target);
            return new Outcome(target, actualSha1, size, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted: " + uri, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide " + SHA1, e);
        }
    }

    private void log(String message) {
        if (verbose) {
            LOGGER.info("[brush] " + message);
        }
    }

    /** 单次 fetch 的结果：区分"新下载 / 命中缓存"，供装配报告统计（P1.3）。 */
    public static final class Outcome {

        public final Path file;
        public final String sha1;
        public final long size;

        /** true = 本次实际下载；false = 命中已通过校验的缓存。 */
        public final boolean downloaded;

        Outcome(Path file, String sha1, long size, boolean downloaded) {
            this.file = file;
            this.sha1 = sha1;
            this.size = size;
            this.downloaded = downloaded;
        }
    }

    /** 计算文件 sha1（小写十六进制）。 */
    public static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA1);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide " + SHA1, e);
        }
    }
}
