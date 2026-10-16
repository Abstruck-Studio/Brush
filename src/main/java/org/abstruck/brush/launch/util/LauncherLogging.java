package org.abstruck.brush.launch.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * app 侧（启动器 / 工具）log4j2 初始化：程序化构建 console appender。
 *
 * <p><b>为什么程序化配置而不是打包一个 log4j2.xml 资源</b>：log4j2 会在首次初始化时按
 * classpath 自动发现配置资源。我们若在 resources 里放 log4j2.xml，它会被<b>任何</b>把本工程产物
 * 放在 classpath 上的 JVM 选中——overlay 模式（S2/S3）下会与宿主（Fabric/NeoForge 启动链）的
 * 日志配置互相劫持。程序化配置只影响显式调用 {@link #install()} 的这条 app 侧 context，
 * 对游戏侧（MC 自带 log4j，经 {@code -Dlog4j.configurationFile} 显式指向其 config）零影响。
 *
 * <p><b>与游戏侧 context 的隔离</b>：app 侧类的 log4j-api/core 来自 Gradle 依赖（app classloader），
 * 游戏侧类的来自 MC 自带库（BrushClassLoader）。log4j2 的 LoggerContext 按<b>类加载器</b>选取，
 * 两者天然是两个 context：app 侧走本类的程序化配置，游戏侧走 MC 的 client-1.12.xml。
 * 两边各写各的 appender，不共享文件 appender——MC 的 latest.log 挂着
 * {@code OnStartupTriggeringPolicy}（启动即滚动归档），两个 context 共用同一
 * 文件 appender 会在归档时互相踩踏。
 *
 * <p><b>真实流（stream）捕获语义</b>：Console appender 在 context 启动时捕获当时的
 * {@code System.out}。MC 在启动过程中会把 {@code System.out} 换成
 * {@code LoggingPrintStream}（println → {@code Logger.info("[STDOUT]: ...")}），
 * 而本类的 {@code install()} 在 {@code Brush.main} 第一条语句执行 —— 早于任何 MC 类加载，
 * 因此 app 侧 appender 捕获的是 JVM 原始 stdout：app 侧日志直达终端、不进入游戏日志管线（良性），
 * 代价是启动晚期（游戏接管 stdout 之后）的 app 侧日志不再被镜像进 latest.log，仅出现在终端。
 */
public final class LauncherLogging {

    /** 与 MC 的 latest.log 使用的 PatternLayout 一致。 */
    private static final String PATTERN = "[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n";

    private static boolean installed;

    private LauncherLogging() { }

    /**
     * 幂等安装 app 侧日志配置：INFO 级、console（SYSTEM_OUT）、pattern 与 MC 的 latest.log 一致。
     *
     * <p>必须在任何 app 侧 main 的第一条语句调用（含错误路径：参数解析失败也要能打日志）。
     *
     * <p><b>为何用 {@code reconfigure} 而非 {@code initialize}</b>：
     * {@code Configurator.initialize(Configuration)} 最终走
     * {@code Log4jContextFactory.getContext(..., Configuration)}，而该方法<b>仅在 context 状态为
     * INITIALIZED 时</b>才调用 {@code ctx.start(configuration)}。本工程各 Main 类均有
     * {@code static final Logger} 字段 —— 类初始化早于 {@code main()}（更早于本方法），已把 context
     * 以 DefaultConfiguration（root=ERROR）推入 STARTED 状态，于是 initialize 传入的配置被<b>静默丢弃</b>：
     * 全部 INFO/WARN 消失且无任何报错。{@code Configurator.reconfigure(Configuration)}
     * （2.13.0+）走 {@code LoggerContext.reconfigure → setConfiguration}：无条件替换配置，并经
     * {@code updateLoggers()} 把所有已存在的 Logger 重新指向新 LoggerConfig，对 INITIALIZED 与
     * STARTED 两种状态都正确。
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.WARN);
        builder.setConfigurationName("brush-launcher");
        AppenderComponentBuilder console = builder.newAppender("console", "Console")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        console.add(builder.newLayout("PatternLayout").addAttribute("pattern", PATTERN));
        builder.add(console);
        builder.add(builder.newRootLogger(Level.INFO).add(builder.newAppenderRef("console")));
        // 显式配置：不读取 log4j.configurationFile 属性（该属性留给游戏侧 context）。
        // reconfigure（而非 initialize）：对"类初始化期已有静态 Logger、context 已 STARTED"的
        // 情形必须能替换配置，理由与方法 javadoc 相同。
        Configurator.reconfigure(builder.build());
    }
}
