package org.abstruck.brush.transform;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

/**
 * mixin 宿主日志适配（S1）：把 sponge-mixin 的内部日志接到 log4j。
 *
 * <p><b>为什么必需</b>：0.17.4 的 {@code MixinServiceAbstract.createLogger} 默认返回
 * {@code LoggerAdapterDefault} —— verbose 表自述 "Default Logger (No Logging)"，即<b>所有
 * mixin 消息被丢弃</b>（S1 下连 WARN 级冲突/注入告警都不出现；S2 的 Knot 在同样位置走
 * log4j 通路）。本类把 S1 对齐到 S2 行为，同时是 §7.3（overwrite 告警）与 §14.2（自检报告
 * "无 Mixin 冲突日志"）依赖的日志来源。</p>
 *
 * <p><b>实现</b>：形态同 Knot 的 MixinLogger（{@code extends LoggerAdapterAbstract}，实现
 * {@code getType / catching / log×2 / throwing} 五个方法）；格式化用 fork 自带的
 * {@link LoggerAdapterAbstract.FormattedMessage}（{@code {}} 占位 + 末尾 Throwable 提取）；
 * {@code catching / throwing} 的级别与报文与同 fork 的 {@code LoggerAdapterConsole} 一致
 * （WARN + "Catching {}: {}" / "Throwing {}: {}"）。</p>
 *
 * <p>类归属同 {@link BrushMixinService}（游戏侧类空间，docs/06 §5 的硬约束）。</p>
 */
final class BrushMixinLoggerAdapter extends LoggerAdapterAbstract {

    private final org.apache.logging.log4j.Logger logger;

    BrushMixinLoggerAdapter(String name) {
        super(name);
        this.logger = LogManager.getLogger(name);
    }

    /** verbose 表中的 "Logger Adapter Type" 行会显示本字符串（自检取证点）。 */
    @Override
    public String getType() {
        return "Log4j (Brush)";
    }

    /** 与 LoggerAdapterConsole 同款（WARN + "Catching {}: {}"）。 */
    @Override
    public void catching(Level level, Throwable t) {
        log(Level.WARN, "Catching {}: {}", t.getClass().getName(), t.getMessage(), t);
    }

    @Override
    public void log(Level level, String message, Object... params) {
        org.apache.logging.log4j.Level log4jLevel = toLog4j(level);
        if (!logger.isEnabled(log4jLevel)) {
            return;
        }
        FormattedMessage formatted = new FormattedMessage(message, params);
        if (formatted.hasThrowable()) {
            logger.log(log4jLevel, formatted.getMessage(), formatted.getThrowable());
        } else {
            logger.log(log4jLevel, formatted.getMessage());
        }
    }

    @Override
    public void log(Level level, String message, Throwable t) {
        logger.log(toLog4j(level), message, t);
    }

    /** 与 LoggerAdapterConsole 同款（WARN + "Throwing {}: {}"，抛回原异常）。 */
    @Override
    public <T extends Throwable> T throwing(T t) {
        log(Level.WARN, "Throwing {}: {}", t.getClass().getName(), t.getMessage(), t);
        return t;
    }

    /** {@code Level} 与 log4j 的枚举名一一对应（FATAL/ERROR/WARN/INFO/DEBUG/TRACE，两者同名同序）。 */
    private static org.apache.logging.log4j.Level toLog4j(Level level) {
        return switch (level) {
            case FATAL -> org.apache.logging.log4j.Level.FATAL;
            case ERROR -> org.apache.logging.log4j.Level.ERROR;
            case WARN -> org.apache.logging.log4j.Level.WARN;
            case INFO -> org.apache.logging.log4j.Level.INFO;
            case DEBUG -> org.apache.logging.log4j.Level.DEBUG;
            case TRACE -> org.apache.logging.log4j.Level.TRACE;
        };
    }
}
