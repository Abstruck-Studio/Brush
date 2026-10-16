package org.abstruck.brush.mod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 嵌套 jar（Jar-in-Jar, JiJ）解出器：把 {@code brush.mod.json} 的
 * {@code jars[].file} 声明的条目从 mod jar 里解到缓存目录，供 {@link ModClassLoader}
 * 作为<b>额外类路径</b>（docs/25 §6 缺口 #1）。
 *
 * <p><b>为什么由我们做</b>：嵌套 jar 的类必须对该 mod 的代码可见。S2/S3 下宿主的类加载体系
 * 处理的是宿主 mod（{@code fabric.mod.json} / {@code neoforge.mods.toml}），**不认识
 * {@code brush.mod.json}**；因此解包与可见性必须由我们自己的装配期完成 —— 好处是它天然
 * <b>三运行时一致</b>（S1/S2/S3 走同一条 {@link ModBootstrap} 装配路径）。</p>
 *
 * <p><b>校验语义</b>：声明的条目必须真的存在于 mod jar 内（且是文件条目）。缺失 = 抛
 * {@link IOException}，消息里带<b>声明下标、条目路径、mod jar 文件名</b>，与 {@code mixins}
 * 的"条目缺失即加载失败"同口径 —— 不允许静默跳过（CLAUDE.md §3.5 / §4.5）。</p>
 *
 * <p><b>解包策略（有意选"每次重写"）</b>：目标目录
 * {@code <cacheDir>/nested/<modId>/<下标>_<文件名>}，每次装配都覆盖写。
 * 理由：mod 加载每进程仅一次，嵌套 jar 通常很小；而"内容正确性"优先于"省一次解包"——
 * P1-6 的教训是<b>缓存判据不可靠</b>（当初按 mtime 复用导致读到陈旧副本），
 * 不是"缓存本身不好"。若将来实测到启动开销，再按内容哈希判据（P1-6 的旁车机制）优化，
 * 而不是退回 mtime。文件名带<b>下标前缀</b>：避免不同目录下同名嵌套 jar 互相覆盖。</p>
 */
final class NestedJarExtractor {

    private static final Logger LOGGER = LogManager.getLogger(NestedJarExtractor.class);

    private NestedJarExtractor() {
    }

    /**
     * 解出该 mod 声明的全部嵌套 jar。
     *
     * @param mod      已解析元数据的 mod
     * @param cacheDir 装配缓存目录（与 {@link ModJarPreparer} 同一个根，见其 {@code prepare}）
     * @return 解出的嵌套 jar 路径（声明顺序；未声明 {@code jars} 时为空列表）
     * @throws IOException 声明的条目缺失 / 不可读 / 写出失败（消息可读，带声明下标与条目路径）
     */
    static List<Path> extract(DiscoveredMod mod, Path cacheDir) throws IOException {
        List<ModMetadata.NestedJar> declared = mod.metadata().jars;
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        String modId = mod.metadata().id;
        Path outDir = cacheDir.resolve("nested").resolve(modId);
        Files.createDirectories(outDir);

        List<Path> extracted = new ArrayList<>(declared.size());
        try (ZipFile zip = new ZipFile(mod.jar().toFile())) {
            for (int i = 0; i < declared.size(); i++) {
                // 条目路径在 ModBootstrap.validate 已归一化（去前导 /、拒空白），此处直接使用
                String entryName = declared.get(i).file;
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("jars[" + i + "] declares nested jar '" + entryName
                            + "' which is not present as a file entry of " + mod.fileName()
                            + " (nested jars must be embedded inside the mod jar; the Fabric-shaped"
                            + " declaration is \"jars\": [{\"file\": \"nested/vendor/dep.jar\"}])");
                }
                Path target = outDir.resolve(i + "_" + fileNameOf(entryName));
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                extracted.add(target);
            }
        }
        LOGGER.info("[brush]   nested jars: " + modId + " -> " + extracted.size() + " jar(s) "
                + extracted.stream().map(p -> p.getFileName().toString()).toList()
                + " (added to this mod's class loader)");
        return List.copyOf(extracted);
    }

    /** 取条目路径的文件名部分（用于解出后的落盘名；下标前缀保证唯一）。 */
    private static String fileNameOf(String entryName) {
        int slash = entryName.lastIndexOf('/');
        String name = slash < 0 ? entryName : entryName.substring(slash + 1);
        return name.isEmpty() ? "nested.jar" : name;
    }
}
