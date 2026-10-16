package org.abstruck.brush.launch.meta;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * version json 模型（P1.2）。
 *
 * <p>字段形态以 26.1.2 数据为准：
 * <ul>
 *   <li>natives 为新式独立库条目（name 内带 classifier），无旧式 {@code natives} 映射字段</li>
 *   <li>无 {@code extract} 字段、无 {@code downloads.classifiers} 映射；
 *       <b>无 {@code client_mappings}</b> —— 26.1 起未混淆，mappings 下载项缺失属预期
 *       （CLAUDE.md §4.2 第 9 条），不是错误</li>
 *   <li>新增 {@code arguments.default-user-jvm}（可被启动器覆盖的默认 JVM 参数）</li>
 *   <li>新增规则条件 {@code os.versionRange}（仅出现在 default-user-jvm 的规则中）</li>
 *   <li>保留旧格式字段仅为兼容，使用时必须判空</li>
 * </ul>
 */
public final class VersionInfo {

    @SerializedName("id")
    public String id;

    @SerializedName("type")
    public String type;

    @SerializedName("mainClass")
    public String mainClass;

    @SerializedName("assets")
    public String assets;

    @SerializedName("releaseTime")
    public String releaseTime;

    @SerializedName("minimumLauncherVersion")
    public int minimumLauncherVersion;

    @SerializedName("javaVersion")
    public JavaVersion javaVersion;

    @SerializedName("assetIndex")
    public AssetIndex assetIndex;

    @SerializedName("libraries")
    public List<Library> libraries;

    @SerializedName("arguments")
    public Arguments arguments;

    @SerializedName("downloads")
    public Downloads downloads;

    @SerializedName("logging")
    public Logging logging;

    /** 从 version json 文件加载（Gson + {@link ArgumentDeserializer} 归一化），P1.2 起共用。 */
    public static VersionInfo load(Path file) throws IOException {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Argument.class, new ArgumentDeserializer())
                .create();
        return gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), VersionInfo.class);
    }

    /** javaVersion：官方要求的 Java 运行时。 */
    public static final class JavaVersion {

        @SerializedName("component")
        public String component;

        @SerializedName("majorVersion")
        public int majorVersion;
    }

    /** assetIndex：assets 索引（P1.3 下载用）。 */
    public static final class AssetIndex {

        @SerializedName("id")
        public String id;

        @SerializedName("url")
        public String url;

        @SerializedName("sha1")
        public String sha1;

        @SerializedName("size")
        public long size;

        @SerializedName("totalSize")
        public long totalSize;
    }

    /** arguments.game / arguments.jvm / arguments.default-user-jvm。 */
    public static final class Arguments {

        @SerializedName("game")
        public List<Argument> game;

        @SerializedName("jvm")
        public List<Argument> jvm;

        /**
         * 26.1.2 新增：可被启动器覆盖的默认 JVM 参数（全为 JVM 旗标：-Xms/-Xmx/GC 选项，
         * 无 {@code -D} 属性）。进程内启动无法应用 —— 仅按 rules 评估后审计打印（见 Brush）。
         * 旧版本 json 无此字段，使用时判空。
         */
        @SerializedName("default-user-jvm")
        public List<Argument> defaultUserJvm;
    }

    /**
     * 单条启动参数，已由 {@link ArgumentDeserializer} 归一化：
     * rules 为空/null 表示无条件，否则需按 {@link RuleEvaluator} 评估。
     */
    public static final class Argument {

        public List<Rule> rules;

        public List<String> values;
    }

    /** 单个库。name 为 Maven 坐标；4 段式（g:a:v:classifier）表示带平台 classifier。 */
    public static final class Library {

        @SerializedName("name")
        public String name;

        @SerializedName("downloads")
        public LibraryDownloads downloads;

        @SerializedName("natives")
        public Map<String, String> natives;

        @SerializedName("extract")
        public Extract extract;

        @SerializedName("rules")
        public List<Rule> rules;

        /** 取 name 中的 classifier（第 4 段）；无则返回 null。 */
        public String classifier() {
            String[] parts = name.split(":");
            return parts.length >= 4 ? parts[3] : null;
        }
    }

    /** 库的下载信息。 */
    public static final class LibraryDownloads {

        @SerializedName("artifact")
        public Artifact artifact;

        @SerializedName("classifiers")
        public Map<String, Artifact> classifiers;
    }

    /** 单个下载产物。 */
    public static final class Artifact {

        @SerializedName("path")
        public String path;

        @SerializedName("sha1")
        public String sha1;

        @SerializedName("size")
        public long size;

        @SerializedName("url")
        public String url;
    }

    /** 解压排除规则（26.1.2 数据中未出现，P1.3 使用时判空）。 */
    public static final class Extract {

        @SerializedName("exclude")
        public List<String> exclude;
    }

    /** 规则条目：action=allow/disallow；os / features 均为可空条件。 */
    public static final class Rule {

        @SerializedName("action")
        public String action;

        @SerializedName("os")
        public Os os;

        @SerializedName("features")
        public Map<String, Boolean> features;
    }

    /** os 条件。version 为匹配 os 版本字符串的正则；versionRange 为 26.1.2 新增的数值区间条件。 */
    public static final class Os {

        @SerializedName("name")
        public String name;

        @SerializedName("arch")
        public String arch;

        @SerializedName("version")
        public String version;

        @SerializedName("versionRange")
        public VersionRange versionRange;
    }

    /**
     * os.versionRange（26.1.2 新增，仅出现在 {@code arguments.default-user-jvm} 的规则中）：
     * {@code {"min": "10.0.17134"}} / {@code {"max": "..."}}，两端均可缺省。
     * 比较语义（区间开闭、版本串来源）见 {@link RuleEvaluator}。
     */
    public static final class VersionRange {

        @SerializedName("min")
        public String min;

        @SerializedName("max")
        public String max;
    }

    /**
     * logging：客户端 log4j2 配置（P1.5 启动用；26.1.2 数据仅有 {@code client} 一项，
     * 结构 {@code {"client": {"argument": "-Dlog4j.configurationFile=${path}", "file": {...}, "type": "log4j2-xml"}}}）。
     */
    public static final class Logging {

        @SerializedName("client")
        public LoggingEntry client;
    }

    /** 单个 logging 条目：{@code argument} 为 JVM 参数模板（含 {@code ${path}} 占位符），{@code file} 为配置本体。 */
    public static final class LoggingEntry {

        @SerializedName("argument")
        public String argument;

        @SerializedName("file")
        public LoggingFile file;

        @SerializedName("type")
        public String type;
    }

    /**
     * logging 引用的配置文件。形态为 {@code {"id", "sha1", "size", "url"}}
     * —— 与 {@link Artifact} 的 {@code path} 字段互异（此处是文件名 {@code id}），故单独建模。
     */
    public static final class LoggingFile {

        @SerializedName("id")
        public String id;

        @SerializedName("sha1")
        public String sha1;

        @SerializedName("size")
        public long size;

        @SerializedName("url")
        public String url;
    }

    /**
     * downloads：client / server / client_mappings / server_mappings。
     * 26.1.2 未混淆：两个 mappings 项缺失属预期（CLAUDE.md §4.2 第 9 条）。
     */
    public static final class Downloads {

        @SerializedName("client")
        public Artifact client;

        @SerializedName("server")
        public Artifact server;

        @SerializedName("client_mappings")
        public Artifact clientMappings;

        @SerializedName("server_mappings")
        public Artifact serverMappings;
    }
}
