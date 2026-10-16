package org.abstruck.brush.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 依赖声明的取值归一化（docs/25 §3.3，缺口 #4）。
 *
 * <p>Fabric 的 {@code depends} / {@code breaks} / {@code conflicts} / {@code recommends} /
 * {@code suggests}，每个键的取值可以是<b>单个字符串</b>或<b>字符串数组</b>，数组表示 OR
 * （原文："In the case of an array, an 'OR' relationship is assumed - that is, only one range has
 * to match for the collective range to be satisfied"）—— 语义来源：Fabric Wiki
 * {@code fabric.mod.json} › Dependency resolution（2026-10-14 抓取）。</p>
 *
 * <p><b>实现取舍（为什么不改模型）</b>：本加载器的 {@link ModMetadata} 里这些表是
 * {@code Map<String, String>}，而 {@link VersionRange} <b>本来就支持</b> {@code ||} 的 OR 语法
 * （README §1.1）—— 所以数组形态既不需要新模型、也不需要自定义 Gson 适配器：在
 * {@code gson.fromJson} <b>之前</b>把数组归一化为 {@code " || "} 连接的单个区间串即可。
 * 语义等价、实现最小，且<b>向后兼容</b>（单字符串形态原样不动）。</p>
 */
final class DeclarationSpecs {

    /** 五张声明表的 JSON 键（与 {@link ModMetadata} 的字段名一致）。 */
    static final String[] KEYS = { "depends", "breaks", "conflicts", "recommends", "suggests" };

    private DeclarationSpecs() {
    }

    /**
     * 原地归一化：数组 → {@code " || "} 连接的串；单字符串原样保留。
     *
     * @param metadata 已解析的 {@code brush.mod.json} 根对象
     * @param fileName 报告用的 jar 文件名
     * @throws IOException 数组为空 / 含非字符串 / 含空白项 —— 元数据错误 fail-fast，
     *                     不允许"某个字段写错却静默忽略"
     */
    static void normalizeDeclarationArrays(JsonObject metadata, String fileName) throws IOException {
        for (String key : KEYS) {
            JsonElement table = metadata.get(key);
            if (table == null || !table.isJsonObject()) {
                continue;
            }
            // 两阶段：先收集改写，迭代结束后再写回 —— 迭代中 addProperty 会 ConcurrentModification
            List<Map.Entry<String, String>> rewrites = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : table.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (!value.isJsonArray()) {
                    continue;   // 单字符串形态：原样保留（向后兼容）
                }
                String where = "'" + key + "." + entry.getKey() + "'";
                JsonArray array = value.getAsJsonArray();
                if (array.isEmpty()) {
                    throw new IOException(fileName + ": " + where + " is an empty array -- declare at"
                            + " least one version range, or use \"*\" for any version");
                }
                List<String> ranges = new ArrayList<>(array.size());
                for (JsonElement element : array) {
                    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                        throw new IOException(fileName + ": " + where
                                + " array entries must be version range strings");
                    }
                    String range = element.getAsString();
                    if (range.isBlank()) {
                        throw new IOException(fileName + ": " + where
                                + " array contains a blank version range");
                    }
                    ranges.add(range);
                }
                rewrites.add(Map.entry(entry.getKey(), String.join(" || ", ranges)));
            }
            for (Map.Entry<String, String> rewrite : rewrites) {
                table.getAsJsonObject().addProperty(rewrite.getKey(), rewrite.getValue());
            }
        }
    }
}
