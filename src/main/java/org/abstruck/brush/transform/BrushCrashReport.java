package org.abstruck.brush.transform;

import org.abstruck.brush.BrushProperties;

/**
 * 崩溃报告里的 Brush 段（docs/25 §3.2，缺口 #2）—— 纯文本构造，无副作用、无 MC 类型引用。
 *
 * <p><b>为什么单独一个类</b>：注入 handler（{@code CoreHooks}）只负责"把本段追加进
 * vanilla 的报告正文"，内容构造独立成纯函数 → 可离线单测（{@code BrushCrashReportTest}），
 * 不必真制造一次崩溃。</p>
 *
 * <p><b>数据来源全部经系统属性桥接</b>：{@code transform} 层<b>不得</b>引用
 * {@code mod} / {@code launch} 层的类型（CLAUDE.md §9.3：跨层只经 JDK 类型与系统属性通信）。
 * 因此加载器版本与已装 mod 清单分别读 {@link BrushProperties#LOADER_VERSION} /
 * {@link BrushProperties#LOADED_MODS}（由 {@code mod.ModBootstrap} 在加载成功后写入），
 * 宿主信息读 {@link BrushProperties#HOST} / {@link BrushProperties#HOST_VERSION} /
 * {@link BrushProperties#HOST_MODS}（由宿主适配写入）。缺失一律降级为可读占位串，不抛异常
 * —— 崩溃处理路径上再抛异常会吞掉原始崩溃证据。</p>
 */
public final class BrushCrashReport {

    /** 段标题：崩溃报告里靠它一眼定位我们的块。 */
    public static final String HEADER = "-- Brush --";

    private BrushCrashReport() {
    }

    /**
     * 构造要追加到崩溃报告正文的段落（以换行开头，便于直接 append 到 vanilla 正文尾部）。
     *
     * @return 多行文本；任何来源缺失都降级为占位串（绝不抛异常，见类 javadoc）
     */
    public static String section() {
        StringBuilder out = new StringBuilder(512);
        out.append('\n').append(HEADER).append('\n');
        out.append("Loader: Brush ").append(property(BrushProperties.LOADER_VERSION, "unknown"))
                .append(" (game ").append(property(BrushProperties.GAME_VERSION, "unknown"))
                .append(", brush.standalone=").append(property(BrushProperties.STANDALONE, "false"))
                .append(')').append('\n');
        out.append("Host: ").append(hostDescription()).append('\n');
        out.append("Brush mods: ").append(modsDescription()).append('\n');
        out.append("Injection: ").append(InjectionLedger.summary()).append('\n');
        return out.toString();
    }

    /** 宿主描述：未设置 {@code brush.host} = standalone（vanilla）。 */
    private static String hostDescription() {
        String host = System.getProperty(BrushProperties.HOST);
        if (host == null || host.isBlank()) {
            return "vanilla (standalone)";
        }
        String hostMods = System.getProperty(BrushProperties.HOST_MODS);
        int count = hostMods == null || hostMods.isBlank() ? 0 : hostMods.split(",").length;
        return host + " " + property(BrushProperties.HOST_VERSION, "unknown")
                + " (host mods loaded: " + count + ")";
    }

    /** 已装 Brush mod 描述：计数 + {@code id@version} 清单（缺失 = 加载未完成或未装 mod）。 */
    private static String modsDescription() {
        String mods = System.getProperty(BrushProperties.LOADED_MODS);
        if (mods == null || mods.isBlank()) {
            return "(none loaded)";
        }
        return mods.split(",").length + " -- " + mods;
    }

    /** 读系统属性；缺失 / 空白 → 占位串（崩溃路径不抛异常，见类 javadoc）。 */
    private static String property(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
