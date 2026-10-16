package org.abstruck.brush.launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 构建身份读取（C.3 版本集中）。
 *
 * <p>数据源 = 类路径资源 {@code /brush-build.properties}。该资源由构建生成：
 * {@code src/main/resources/brush-build.properties} 是<b>模板</b>（含 {@code ${...}} 占位符），
 * {@code processResources} 在构建期从单一来源 {@code gradle.properties} 展开为字面值后打包。
 * 单一来源 = {@code gradle.properties} 的 {@code mcVersion} / {@code loaderVersion}。
 *
 * <p><b>回退语义</b>：资源缺失（如被脱离构建流程直接运行、或打包遗漏）时回退
 * {@link #FALLBACK_MC_VERSION} / {@link #FALLBACK_LOADER_VERSION} 并打 WARN，不抛错 ——
 * 版本串只影响默认值与展示，不应中断启动管线。占位符未展开（值仍含 {@code ${}，说明拿到的是
 * 模板本身而非产物）同样按缺失处理并 WARN。
 *
 * <p><b>维护约束</b>：两个 {@code FALLBACK_*} 常量须与 {@code gradle.properties} 保持同步
 * （改动单一时同步改两处）；{@code gradle.properties} 与模板文件内均有指向本处的注释。
 */
public final class BuildProperties {

    /** 回退值：MC 目标版本（须与 gradle.properties 的 mcVersion 同步）。 */
    public static final String FALLBACK_MC_VERSION = "26.1.2";

    /** 回退值：Brush 加载器版本（须与 gradle.properties 的 loaderVersion 同步）。 */
    public static final String FALLBACK_LOADER_VERSION = "26.10.1";

    private static final String RESOURCE = "/brush-build.properties";
    private static final String KEY_MC_VERSION = "mcVersion";
    private static final String KEY_LOADER_VERSION = "loaderVersion";

    private static final Logger LOGGER = LogManager.getLogger(BuildProperties.class);

    /** 加载一次；缺资源时为空 Properties（读取一律走回退路径）。 */
    private static final Properties PROPERTIES = load();

    private BuildProperties() { }

    /** 目标 MC 版本（默认值 / 路径构造用；如 {@code "26.1.2"}）。 */
    public static String mcVersion() {
        return value(KEY_MC_VERSION, FALLBACK_MC_VERSION);
    }

    /** Brush 加载器版本（如 {@code "26.10.1"}）。 */
    public static String loaderVersion() {
        return value(KEY_LOADER_VERSION, FALLBACK_LOADER_VERSION);
    }

    // ------------------------------------------------------------------
    // 默认路径（P1-10 收尾：评审"默认路径写死"整改）
    //
    // 此前 {@code Path.of("cache")} / {@code Path.of("run")} /
    // {@code Path.of(mcVersion(), mcVersion() + ".jar")} 在 6 处入口
    // （launch.Brush / meta.MetadataMain / deps.LibrariesMain / assets.AssetsMain /
    // tools.FabricHostHarness / tools.NeoForgeHostHarness）各自独立写死。版本升级或
    // 目录约定变更时"必然漏改其一"。现统一收进本类：
    //   cacheRoot() / gameDir() / clientJar() / fabricInstanceDir() / neoforgeInstanceDir()
    // 全部派生自单一来源（{@link #mcVersion()} + 固定目录名常量），改动一处 = 全仓口径变更。
    // 这些是<b>工作目录相对</b>的路径（Gradle 任务在 run/ 等独立目录运行，由任务
    // {@code workingDir} 决定 CWD；本类只给"默认值"，不改变相对语义）。
    // ------------------------------------------------------------------

    /** 缓存根目录名（相对当前工作目录；P1.2/P1.3/P1.5.0 的产物根，只读消费 version json 在此）。 */
    public static final String CACHE_DIR = "cache";

    /** 游戏运行目录名（相对当前工作目录；S1 的 mods / saves / logs 落此）。 */
    public static final String GAME_DIR = "run";

    /** S2 宿主实例目录名（{@code run/fabric}；与 S1 的 {@link #GAME_DIR} 隔离，§11.8）。 */
    public static final String FABRIC_INSTANCE_DIR = "fabric";

    /** S3 宿主实例目录名（{@code run/neoforge}）。 */
    public static final String NEOFORGE_INSTANCE_DIR = "neoforge";

    /** 缓存根目录（相对 CWD；默认值，可由 {@code --cache=} 覆盖）。 */
    public static Path cacheRoot() {
        return Path.of(CACHE_DIR);
    }

    /** 游戏运行目录（相对 CWD；默认值，可由 {@code --game-dir=} 覆盖）。 */
    public static Path gameDir() {
        return Path.of(GAME_DIR);
    }

    /**
     * 本地 vanilla client jar 路径（相对 CWD；默认 = {@code <mcVersion>/<mcVersion>.jar}，
     * 可由 {@code --client-jar=} 覆盖）。26.1.2/ 为本地只读资产（CLAUDE.md §1.3）。
     */
    public static Path clientJar() {
        return Path.of(mcVersion(), mcVersion() + ".jar");
    }

    /** S2 宿主实例目录（相对 CWD；默认 = {@code run/fabric}）。 */
    public static Path fabricInstanceDir() {
        return Path.of(GAME_DIR, FABRIC_INSTANCE_DIR);
    }

    /** S3 宿主实例目录（相对 CWD；默认 = {@code run/neoforge}）。 */
    public static Path neoforgeInstanceDir() {
        return Path.of(GAME_DIR, NEOFORGE_INSTANCE_DIR);
    }

    private static String value(String key, String fallback) {
        String loaded = PROPERTIES.getProperty(key);
        if (loaded != null && !loaded.isBlank() && !loaded.contains("${")) {
            return loaded.trim();
        }
        if (loaded != null) {
            LOGGER.warn("[brush] " + RESOURCE + " key '" + key + "' is unusable (" + loaded
                    + ") —— template not expanded? falling back to " + fallback);
        }
        return fallback;
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = BuildProperties.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[brush] " + RESOURCE + " not found on classpath —— falling back to "
                        + FALLBACK_MC_VERSION + " / " + FALLBACK_LOADER_VERSION
                        + " (expected when not running the build output)");
                return properties;
            }
            properties.load(in);
        } catch (IOException e) {
            LOGGER.warn("[brush] failed to read " + RESOURCE + ": " + e
                    + " —— falling back to " + FALLBACK_MC_VERSION + " / " + FALLBACK_LOADER_VERSION);
        }
        return properties;
    }
}
