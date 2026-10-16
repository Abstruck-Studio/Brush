package org.abstruck.brush.launch.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 官方日志配置的「控制台格式」修补（仅 S1 启动路径使用）。
 *
 * <p>背景：vanilla {@code client-*.xml} 里 {@code SysOut} appender 用
 * {@code <LegacyXMLLayout/>} —— stdout 输出 log4j 1.x XML（{@code <log4j:Event ...>}），
 * 是 Mojang 给官方启动器解析事件用的机器可读形态；人读格式只在 File appender。
 * 本启动器在 stdout 没有任何 XML 消费方，XML 只会淹没控制台 ——
 * 控制台改为与日志文件一致的人读格式。
 *
 * <p>修补范围刻意最小：只把该元素替换为与 File appender 逐字一致的 PatternLayout，
 * File appender / Tracy listener / MarkerFilter / 级别一概不动。派生物写入新文件，
 * 原始配置保持只读并继续受 sha1 校验，不构成对 vanilla 磁盘文件的改动。
 */
public final class LoggingConfigPatch {

    /** 与 File appender 逐字一致的 PatternLayout。 */
    public static final String CONSOLE_PATTERN = "[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n";

    /** vanilla 形态（自闭合、允许空白）；非该形态视为上游变化，不猜（返回 null）。 */
    private static final Pattern LEGACY_XML_LAYOUT = Pattern.compile("<LegacyXMLLayout\\s*/>");

    private LoggingConfigPatch() {
    }

    /**
     * @param xml 官方日志配置全文
     * @return 替换后的文本；若不含 {@code <LegacyXMLLayout/>} 返回 {@code null}
     *         （调用方保留原配置并明确记日志，不做静默替换）
     */
    public static String toConsolePatternLayout(String xml) {
        Matcher matcher = LEGACY_XML_LAYOUT.matcher(xml);
        if (!matcher.find()) {
            return null;
        }
        String replacement = "<PatternLayout pattern=\"" + CONSOLE_PATTERN + "\" />";
        return matcher.replaceAll(Matcher.quoteReplacement(replacement));
    }
}
