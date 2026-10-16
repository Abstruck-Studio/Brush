package org.abstruck.brush.api;

import org.abstruck.brush.mod.ModBootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * L2 模组元数据查询 API：列出已加载的 Brush mod、按 id 查询、读取版本信息。
 *
 * <p><b>零注入点</b> —— 纯查询，构建在加载器自身的登记表之上（{@link ModBootstrap}），
 * 不触碰任何 vanilla 类，因而天然满足三运行时一致（S1/S2/S3 同名同义）。
 *
 * <p><b>时机语义</b>：{@link #list()} 的数据源在<b>实例化任何入口之前</b>就已全量登记 ——
 * 因此在任意 mod 的 {@code onInitialize()} 里调用，看到的是<b>本次加载的全部 mod</b>
 * （含正在初始化的自身），顺序为依赖拓扑序。加载失败（依赖缺失等）时进程在中止路径上，
 * 本 API 不参与失败语义。
 *
 * <p><b>不含加载器自身</b>：{@code brush} 不在 {@link #list()} 中（它不是 {@code brush-mods/} 加载项）；
 * 加载器版本走 {@link #loaderVersion()}，游戏版本走 {@link #gameVersion()}。
 */
public final class BrushMods {

    /**
     * 一个已加载 mod 的元数据视图（只读快照；jar 为磁盘路径）。
     *
     * @param id          mod id（唯一）
     * @param name        展示名（元数据缺省时回退 = id）
     * @param version     模组版本串（原样，如 {@code "1.0.0"} / {@code "2.0.0-beta"}）
     * @param description 描述（可为空串）
     * @param authors     作者列表（可为空列表；只读）
     * @param jar         mod jar 路径
     */
    public record ModInfo(String id, String name, String version, String description,
            List<String> authors, Path jar) { }

    private BrushMods() {
    }

    /** 已加载的全部 Brush mod（依赖拓扑序）。 */
    public static List<ModInfo> list() {
        List<ModInfo> out = new ArrayList<>();
        for (ModBootstrap.ModEntry entry : ModBootstrap.modEntries()) {
            out.add(new ModInfo(entry.id(), entry.name(), entry.version(), entry.description(),
                    entry.authors(), entry.jar()));
        }
        return List.copyOf(out);
    }

    /** 按 id 查询单个已加载 mod（不存在 = 空 Optional）。 */
    public static Optional<ModInfo> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (ModInfo info : list()) {
            if (info.id().equals(id)) {
                return Optional.of(info);
            }
        }
        return Optional.empty();
    }

    /** id 是否已加载。 */
    public static boolean isLoaded(String id) {
        return find(id).isPresent();
    }

    /** 加载器（Brush）版本串 —— 与依赖解析中 {@code brush} id 的比对对象同源。 */
    public static String loaderVersion() {
        return ModBootstrap.loaderVersion();
    }

    /** 游戏版本串（version json 的 id，如 {@code "26.1.2"}）；加载器尚未初始化时返回 {@code "unknown"}。 */
    public static String gameVersion() {
        return ModBootstrap.gameVersion();
    }
}
