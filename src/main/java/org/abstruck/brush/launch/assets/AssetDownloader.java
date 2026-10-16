package org.abstruck.brush.launch.assets;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.abstruck.brush.launch.meta.VersionInfo;
import org.abstruck.brush.launch.util.Downloader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * P1.5.0 资产装配：下载 asset index 与全部对象到 {@code cache/assets/}（内容寻址布局）。
 *
 * <p>产物布局（与官方 launcher 一致，游戏以 {@code --assetsDir <root> --assetIndex <id>} 消费）：
 * <pre>
 * cache/assets/
 * ├─ indexes/&lt;id&gt;.json          # 由 version json 的 assetIndex 下载（sha1/size 校验）
 * └─ objects/&lt;hash 前两位&gt;/&lt;hash&gt;  # 对象本体，文件名 = 其 sha1
 * </pre>
 *
 * <p><b>对象基址</b>：{@code https://resources.download.minecraft.net/<hash 前两位>/<hash>}。
 * version json <b>不包含</b>该基址（index 只记录 hash/size），此布局为 launcher 协议约定。
 * 经代理访问该主机存在偶发 404 / TLS 失败，由 {@link Downloader} 的有界重试与
 * 本类的两轮下载处置。
 *
 * <p>完整性校验：逐对象 sha1=文件名、size 与 index 一致；index 解析后核对
 * {@code Σsize == assetIndex.totalSize}，不相等即抛错（说明解析或数据异常，绝不静默继续）。
 *
 * <p>去重：按 hash 唯一化（同 hash 条目共享内容）。
 *
 * <p>幂等：命中缓存（sha1+size 校验通过）即跳过，可断点续跑；失败对象汇总为报告，
 * 若第一轮有失败则自动对失败集重试一轮（有界，两次都失败才判失败并给出清单）。
 */
public final class AssetDownloader {

    private static final Logger LOGGER = LogManager.getLogger(AssetDownloader.class);

    /**
     * 对象基址（launcher 协议约定）。
     * 注意：本字符串<b>不来自</b> version json，变更需查证后修改，不得凭记忆修改。
     */
    public static final String OBJECT_BASE_URL = "https://resources.download.minecraft.net/";

    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{40}");

    private static final int PROGRESS_EVERY = 100;

    private static final int FAILURE_PRINT_LIMIT = 30;

    private AssetDownloader() { }

    /** 装配结果。 */
    public static final class Result {

        public String indexId;
        public Path indexFile;
        public Path objectsDir;

        /** index 中的条目数（含重名）。 */
        public int indexEntries;

        /** 按 hash 去重后的对象数。 */
        public int uniqueObjects;

        public int downloaded;
        public int cached;

        /** 本轮实际下载的字节数。 */
        public long bytesDownloaded;

        /** 唯一对象的总字节数（应下载量）。 */
        public long uniqueBytes;

        public long elapsedMillis;

        /** 两轮之后仍失败的对象（hash + 原因）；非空即视为阶段失败。 */
        public final List<String> failures = new ArrayList<>();
    }

    /**
     * 执行资产装配。
     *
     * @param info       version json（提供 assetIndex 元数据）
     * @param cacheRoot  缓存根目录（与 P1.2/P1.3 共用，资产写入其下 {@code assets/}）
     * @param downloader 下载器（建议 quiet 模式：本阶段有数千个对象）
     * @param refresh    为 true 时全部重新下载
     * @param workers    并行下载线程数（≥1；对代理不稳的环境适当降低可减少失败率）
     */
    public static Result download(VersionInfo info, Path cacheRoot, Downloader downloader,
                                  boolean refresh, int workers) throws IOException {
        VersionInfo.AssetIndex assetIndex = info.assetIndex;
        if (assetIndex == null || assetIndex.url == null) {
            throw new IOException("version json has no assetIndex");
        }
        Result result = new Result();
        long start = System.nanoTime();

        Path assetsDir = cacheRoot.resolve("assets").toAbsolutePath().normalize();
        Path indexFile = assetsDir.resolve("indexes").resolve(assetIndex.id + ".json");
        downloader.download(URI.create(assetIndex.url), indexFile, assetIndex.sha1, assetIndex.size, refresh);
        result.indexId = assetIndex.id;
        result.indexFile = indexFile;
        LOGGER.info("[brush] asset index: " + indexFile + " (id=" + assetIndex.id + ")");

        AssetIndexModel model = new Gson().fromJson(
                Files.readString(indexFile, StandardCharsets.UTF_8), AssetIndexModel.class);
        if (model == null || model.objects == null || model.objects.isEmpty()) {
            throw new IOException("asset index has no objects: " + indexFile);
        }

        // 解析 + 去重 + 会计核对
        long sumAll = 0L;
        Map<String, Long> unique = new LinkedHashMap<>();
        for (Map.Entry<String, AssetObject> entry : model.objects.entrySet()) {
            AssetObject object = entry.getValue();
            if (object == null || object.hash == null || !HASH_PATTERN.matcher(object.hash).matches() || object.size < 0) {
                throw new IOException("malformed asset index entry: " + entry.getKey() + " -> "
                        + (object == null ? "null" : object.hash + "/" + object.size));
            }
            sumAll += object.size;
            unique.putIfAbsent(object.hash, object.size);
        }
        if (assetIndex.totalSize > 0 && sumAll != assetIndex.totalSize) {
            throw new IOException("asset index accounting mismatch: sum(entries)=" + sumAll
                    + " but assetIndex.totalSize=" + assetIndex.totalSize + " (" + indexFile + ")");
        }
        result.indexEntries = model.objects.size();
        result.uniqueObjects = unique.size();
        result.uniqueBytes = unique.values().stream().mapToLong(Long::longValue).sum();

        // 并行下载（两轮：首轮全量，次轮仅失败集）
        Path objectsDir = assetsDir.resolve("objects");
        result.objectsDir = objectsDir;
        List<Map.Entry<String, Long>> items = new ArrayList<>(unique.entrySet());
        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger cached = new AtomicInteger();
        AtomicLong newBytes = new AtomicLong();
        ConcurrentLinkedQueue<Failure> failures = new ConcurrentLinkedQueue<>();

        runPass(1, items, objectsDir, downloader, refresh, workers, downloaded, cached, newBytes, failures);
        if (!failures.isEmpty()) {
            List<Map.Entry<String, Long>> retry = new ArrayList<>();
            for (Failure failure : failures) {
                retry.add(Map.entry(failure.hash, failure.size));
            }
            LOGGER.info("[brush] first pass finished with " + retry.size() + " failures; second pass over the failed set");
            failures.clear();
            runPass(2, retry, objectsDir, downloader, refresh, workers, downloaded, cached, newBytes, failures);
        }

        result.downloaded = downloaded.get();
        result.cached = cached.get();
        result.bytesDownloaded = newBytes.get();
        for (Failure failure : failures) {
            result.failures.add(failure.hash + " (" + failure.message + ")");
        }
        result.elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        return result;
    }

    /** 单轮并行下载；worker 内部逐对象吞 IOException 并登记失败（异常明细进清单，不静默）。 */
    private static void runPass(int pass, List<Map.Entry<String, Long>> items, Path objectsDir, Downloader downloader,
                                boolean refresh, int workers, AtomicInteger downloaded, AtomicInteger cached,
                                AtomicLong newBytes, ConcurrentLinkedQueue<Failure> failures) throws IOException {
        if (items.isEmpty()) {
            return;
        }
        AtomicInteger done = new AtomicInteger();
        int threads = Math.max(1, Math.min(workers, items.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "brush-asset-download");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int worker = 0; worker < threads; worker++) {
                final int offset = worker;
                pool.execute(() -> {
                    for (int index = offset; index < items.size(); index += threads) {
                        Map.Entry<String, Long> item = items.get(index);
                        String hash = item.getKey();
                        long size = item.getValue();
                        Path target = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                        try {
                            Downloader.Outcome outcome = downloader.fetch(
                                    URI.create(OBJECT_BASE_URL + hash.substring(0, 2) + "/" + hash),
                                    target, hash, size, refresh);
                            if (outcome.downloaded) {
                                downloaded.incrementAndGet();
                                newBytes.addAndGet(outcome.size);
                            } else {
                                cached.incrementAndGet();
                            }
                        } catch (IOException e) {
                            failures.add(new Failure(hash, size, e.getMessage()));
                        } catch (RuntimeException e) {
                            failures.add(new Failure(hash, size, e.getClass().getSimpleName() + ": " + e.getMessage()));
                        }
                        int current = done.incrementAndGet();
                        if (current % PROGRESS_EVERY == 0 || current == items.size()) {
                            LOGGER.info("[brush] assets pass " + pass + ": " + current + "/" + items.size()
                                    + " objects (" + (newBytes.get() / 1_048_576L) + " MB new, failed=" + failures.size() + ")");
                        }
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(2, TimeUnit.HOURS)) {
                pool.shutdownNow();
                throw new IOException("asset download pool did not terminate in time (pass " + pass + ")");
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("asset download interrupted (pass " + pass + ")", e);
        }
    }

    /** 失败对象（保留原始大小，供第二轮重试）。 */
    private record Failure(String hash, long size, String message) { }

    /** asset index 的 Gson 模型（形态：{"objects": {"<name>": {"hash", "size"}}}）。 */
    private static final class AssetIndexModel {

        @SerializedName("objects")
        Map<String, AssetObject> objects;
    }

    private static final class AssetObject {

        @SerializedName("hash")
        String hash;

        @SerializedName("size")
        long size;
    }

    /** 供 AssetsMain 打印失败清单时截断。 */
    public static int failurePrintLimit() {
        return FAILURE_PRINT_LIMIT;
    }
}
