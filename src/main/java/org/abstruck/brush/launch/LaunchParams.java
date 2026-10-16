package org.abstruck.brush.launch;

import org.abstruck.brush.launch.meta.RuleEvaluator;
import org.abstruck.brush.launch.meta.VersionInfo;
import org.abstruck.brush.launch.util.Downloader;
import org.abstruck.brush.launch.util.LoggingConfigPatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动参数装配的共享实现。两个消费方：
 * <ul>
 *   <li>{@link Brush} —— S1 进程内启动（app 侧类空间）；</li>
 *   <li>{@code org.abstruck.brush.tools.FabricHostHarness} —— S2/T2 子 JVM 启动器
 *       （tools 源集，测试装置，不进产物 jar）。</li>
 * </ul>
 * 设计说明：这些逻辑与"谁持有 ClassLoader"无关（进程内 vs spawn 只差在 JVM 参数的施加方式），
 * 因此抽到中性位置；JVM 参数的<b>施加</b>仍各自实现（进程内 {-D} 属性 vs 命令行透传）。
 */
public final class LaunchParams {

    private static final Logger LOGGER = LogManager.getLogger(LaunchParams.class);

    /** S1/S2 启动器共用的 User-Agent。 */
    public static final String USER_AGENT = "Brush-Loader (P1.5 launch)";

    /** launcher 身份（占位符 ${launcher_name}/${launcher_version}）。版本单一来源见 BuildProperties（C.3）。 */
    public static final String LAUNCHER_NAME = "Brush";
    public static final String LAUNCHER_VERSION = BuildProperties.loaderVersion();

    /** 离线开发身份。UUID 为固定值（v4 形态），保证可复现。 */
    public static final String PLAYER_NAME = "Dev";
    public static final String PLAYER_UUID = "00000000-0000-4000-8000-000000000000";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private LaunchParams() { }

    /**
     * 校验 client jar 与 version json 的 {@code downloads.client} 一致（sha1 + size）。
     * 不一致即抛错拒绝启动（CLAUDE.md §4.3 P1.2：校验失败视为错误）。
     */
    public static void verifyClientJar(VersionInfo info, Path clientJar) throws IOException {
        VersionInfo.Artifact expected = info.downloads == null ? null : info.downloads.client;
        if (expected == null || expected.sha1 == null) {
            throw new IOException("version json has no downloads.client to verify against");
        }
        String actualSha1 = Downloader.sha1(clientJar);
        long actualSize = Files.size(clientJar);
        if (!actualSha1.equalsIgnoreCase(expected.sha1) || actualSize != expected.size) {
            throw new IOException("client jar mismatch: " + clientJar
                    + " expected sha1=" + expected.sha1 + " size=" + expected.size
                    + ", actual sha1=" + actualSha1 + " size=" + actualSize);
        }
        LOGGER.info("[brush] client jar verified: " + clientJar);
        LOGGER.info("[brush]   sha1=" + actualSha1 + " size=" + actualSize);
    }

    /**
     * 下载 logging.client.file 并按其 argument 模板设置系统属性。
     *
     * <p>控制台格式修补：vanilla 的 SysOut appender 用
     * LegacyXMLLayout，stdout 是 {@code <log4j:Event>} XML（供官方启动器解析）。自家启动器没有该
     * 消费方，改为与 File appender 一致的 PatternLayout。原件保持只读（sha1 已校验）；
     * 派生物每次启动从原件重新生成，不留陈旧副本。
     *
     * @return 生效的 log4j 配置路径（version json 无 logging 项时为 null —— 此时未设置任何属性）
     */
    public static Path downloadLoggingConfig(VersionInfo info, Path cacheRoot, Downloader downloader) throws IOException {
        VersionInfo.Logging logging = info.logging;
        if (logging == null || logging.client == null || logging.client.file == null) {
            LOGGER.info("[brush] version json has no logging.client; log4j runs with its default config");
            return null;
        }
        VersionInfo.LoggingEntry entry = logging.client;
        if (entry.file.url == null) {
            throw new IOException("logging.client.file has no url");
        }
        String fileName = entry.file.id != null ? entry.file.id : "client-log4j.xml";
        Path target = cacheRoot.resolve("logging").resolve(fileName).toAbsolutePath().normalize();
        downloader.download(URI.create(entry.file.url), target, entry.file.sha1, entry.file.size, false);
        LOGGER.info("[brush] log4j config: " + target);

        Path configPath = target;
        String patched = LoggingConfigPatch.toConsolePatternLayout(Files.readString(target));
        if (patched != null) {
            String derivedName = (fileName.endsWith(".xml") ? fileName.substring(0, fileName.length() - 4) : fileName)
                    + ".brush-console.xml";
            Path derived = target.resolveSibling(derivedName);
            Files.writeString(derived, patched);
            configPath = derived;
            LOGGER.info("[brush] console format patched (LegacyXMLLayout -> PatternLayout): " + derived);
        } else {
            // 上游形态变化（不再含 LegacyXMLLayout）——保留原配置并如实说明，不做静默替换
            LOGGER.info("[brush] console format patch not applicable (<LegacyXMLLayout/> not found); config used as-is");
        }

        if (entry.argument != null) {
            // 形态："-Dlog4j.configurationFile=${path}"；形态不符即报错，不猜
            String argument = entry.argument.trim();
            if (!argument.startsWith("-D") || !argument.contains("=")) {
                throw new IOException("unsupported logging.client.argument form: " + argument);
            }
            String body = argument.substring(2);
            int eq = body.indexOf('=');
            String key = body.substring(0, eq);
            String value = substitute(body.substring(eq + 1),
                    Map.of("path", configPath.toString()), "logging.client.argument");
            System.setProperty(key, value);
            LOGGER.info("[brush]   set: " + key + "=" + value);
        }
        return configPath;
    }

    /** arguments.game：rules 过滤 + 占位符替换，得到入口类的完整参数数组。 */
    public static List<String> buildGameArguments(VersionInfo info, Path cacheRoot, Path gameDirAbs) throws IOException {
        if (info.assets == null || info.assets.isEmpty()) {
            throw new IOException("version json has no assets field; --assetIndex cannot be derived");
        }
        Map<String, String> values = new HashMap<>();
        values.put("auth_player_name", PLAYER_NAME);
        values.put("version_name", info.id);
        values.put("game_directory", gameDirAbs.toString());
        values.put("assets_root", cacheRoot.resolve("assets").toAbsolutePath().normalize().toString());
        values.put("assets_index_name", info.assets);
        values.put("auth_uuid", PLAYER_UUID);
        values.put("auth_access_token", "0");
        values.put("clientid", "");
        values.put("auth_xuid", "");
        values.put("user_type", "msa");
        values.put("version_type", info.type == null ? "release" : info.type);

        List<String> gameArgs = new ArrayList<>();
        if (info.arguments != null && info.arguments.game != null) {
            for (VersionInfo.Argument argument : info.arguments.game) {
                if (!RuleEvaluator.applies(argument.rules)) {
                    continue;
                }
                for (String value : argument.values) {
                    gameArgs.add(substitute(value, values, "arguments.game"));
                }
            }
        }
        LOGGER.info("[brush] --- game arguments (" + gameArgs.size() + ") ---");
        for (String arg : gameArgs) {
            LOGGER.info("[brush]   " + arg);
        }
        return gameArgs;
    }

    /** 替换 ${name} 占位符；出现未知占位符即抛错（不猜、不静默留空）。 */
    public static String substitute(String template, Map<String, String> values, String where) throws IOException {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                throw new IOException("unresolved placeholder ${" + key + "} in " + where + ": " + template);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
