package org.abstruck.brush.mod;

import java.nio.file.Path;

/**
 * 已发现（已解析 brush.mod.json）的 mod：jar 路径 + 元数据。
 *
 * @param jar      mod jar 路径（{@code brush-mods/} 下）
 * @param metadata 已通过结构校验的 brush.mod.json 模型
 */
public record DiscoveredMod(Path jar, ModMetadata metadata) {

    /** 报告用展示名：{@code name} 缺失时回退 {@code id}。 */
    public String displayName() {
        return metadata.name == null || metadata.name.isBlank() ? metadata.id : metadata.name;
    }

    /** jar 文件名（报告用）。 */
    public String fileName() {
        return jar.getFileName().toString();
    }
}
