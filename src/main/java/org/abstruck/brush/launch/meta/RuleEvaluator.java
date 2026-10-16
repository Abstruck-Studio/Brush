package org.abstruck.brush.launch.meta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * rules 评估器，语义与官方 launcher 一致：
 * <ul>
 *   <li>rules 为空 / null → 适用</li>
 *   <li>否则：遍历规则，命中的最后一条决定结果（allow/disallow），初始为不适用</li>
 *   <li>os 条件：name/arch 精确匹配；version 为正则（旧格式，匹配对象见 {@link #OS_VERSION}）；
 *       versionRange 为数值区间（26.1.2 新增，仅出现在 {@code arguments.default-user-jvm}）</li>
 *   <li>features 条件：逐项比较特性开关，本装载器 P1 阶段无任何特性开启</li>
 * </ul>
 *
 * <p><b>versionRange 语义</b>：区间取 {@code [min, max)} —— min 含下界、max 排他。
 * 26.1.2 的两组规则以 10.0.17134 为界互补（{@code >=} 用 ZGC / {@code <} 用 G1），
 * 该选择使两区间无重叠、全覆盖。边界排他性取值依据：官方 launcher 源码未公开，本机无法
 * 观测到恰好落在边界上的 build —— 现取半开区间使规则集自洽；日后若观测到官方语义与此不同，
 * 以实测为准更新此处。区间语义由 {@code RuleEvaluatorTest} 的用例锁定（恰落边界上的 build
 * 无法在本机构造，两个端点不做实机断言，见该测试 javadoc）。
 *
 * <p><b>versionRange 的版本串来源</b>：Java 报告的 {@code os.version} 在 Windows 上恒为 "10.0"，
 * 无法区分 build（对比：官方 launcher 用真实 build 号，如 "10.0.26200"）。
 * 因此 Windows 下经注册表 {@code CurrentBuildNumber}（locale 无关）拼成
 * "10.0.&lt;build&gt;"；探测失败（非 Windows / 注册表不可用）回退 {@code os.version}。
 * 该探测为懒执行（仅当真的遇到 versionRange 规则），有 5s 超时。
 */
public final class RuleEvaluator {

    /** 当前目标环境：Windows x86_64（P1 仅客户端，CLAUDE.md §2）。 */
    public static final String OS_NAME = "windows";
    public static final String OS_ARCH = "x86_64";

    /** 旧格式 {@code os.version} 正则的匹配对象（Java 报告的 os.version）。 */
    public static final String OS_VERSION = "10.0";

    /** 特性开关：P1 全关（如 is_demo_user 等均为 false）。 */
    public static final Map<String, Boolean> FEATURES = Map.of();

    /** {@link #rangeVersion()} 的缓存（volatile 双检无锁版：探测幂等，最坏重复一次）。 */
    private static volatile String cachedRangeVersion;

    private RuleEvaluator() { }

    /** 评估一组规则在当前环境下是否适用。 */
    public static boolean applies(List<VersionInfo.Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allow = false;
        for (VersionInfo.Rule rule : rules) {
            if (matches(rule)) {
                allow = "allow".equals(rule.action);
            }
        }
        return allow;
    }

    private static boolean matches(VersionInfo.Rule rule) {
        if (rule.os != null) {
            if (rule.os.name != null && !OS_NAME.equals(rule.os.name)) {
                return false;
            }
            if (rule.os.arch != null && !OS_ARCH.equals(rule.os.arch)) {
                return false;
            }
            if (rule.os.version != null && !OS_VERSION.matches(rule.os.version)) {
                return false;
            }
            if (rule.os.versionRange != null && !inVersionRange(rangeVersion(), rule.os.versionRange)) {
                return false;
            }
        }
        if (rule.features != null) {
            for (Map.Entry<String, Boolean> feature : rule.features.entrySet()) {
                boolean actual = FEATURES.getOrDefault(feature.getKey(), false);
                if (feature.getValue() == null || actual != feature.getValue()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 区间判定：{@code [min, max)}，两端均可缺省（缺省即无界）。 */
    private static boolean inVersionRange(String actual, VersionInfo.VersionRange range) {
        if (range.min != null && compareVersions(actual, range.min) < 0) {
            return false;
        }
        if (range.max != null && compareVersions(actual, range.max) >= 0) {
            return false;
        }
        return true;
    }

    /**
     * 点分版本比较：逐段数值比较，段数不等时缺段按 0（"10.0" 等价 "10.0.0"）。
     * 非数字段按 0 处理（26.1.2 数据全为数字；容忍而非抛错 —— 该评估只影响
     * default-user-jvm 的审计输出，不应因此中断启动管线）。
     */
    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int segments = Math.max(a.length, b.length);
        for (int i = 0; i < segments; i++) {
            int result = Integer.compare(segment(a, i), segment(b, i));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static int segment(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** versionRange 比较用版本串（懒探测 + 缓存），见类 javadoc「版本串来源」。公开供启动日志审计引用。 */
    public static String rangeVersion() {
        String cached = cachedRangeVersion;
        if (cached == null) {
            cached = detectRangeVersion();
            cachedRangeVersion = cached;
        }
        return cached;
    }

    private static String detectRangeVersion() {
        String fallback = System.getProperty("os.version", "");
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return fallback;
        }
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/v", "CurrentBuildNumber")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return fallback;
            }
            // reg 输出含 GBK 字节，但我们需要的信息（值名/类型/数字）均为 ASCII，UTF-8 读取安全
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String line : output.split("\\R")) {
                if (line.contains("CurrentBuildNumber")) {
                    String[] tokens = line.trim().split("\\s+");
                    String build = tokens[tokens.length - 1];
                    if (build.matches("\\d+")) {
                        return "10.0." + build;
                    }
                }
            }
        } catch (IOException e) {
            // 探测失败回退，不中断
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fallback;
    }
}
