package 中文.标准库;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 日志：java.util.logging 的中文别名。
 * 只转发 JDK 日志能力，输出格式与级别语义可自行调整。
 */
public final class 日志 {

    private 日志() {
    }

    /** Logger.getLogger(名称) */
    public static Logger 取(String 名称) {
        return Logger.getLogger(名称);
    }

    /**
     * 控制台日志器：不继承父处理器，输出「日期 时间 级别 内容」，默认 INFO 级别。
     */
    public static Logger 控制台(String 名称) {
        Logger 日志器 = Logger.getLogger(名称);
        日志器.setUseParentHandlers(false);
        for (var 处理器 : 日志器.getHandlers()) {
            日志器.removeHandler(处理器);
        }
        ConsoleHandler 处理器 = new ConsoleHandler();
        处理器.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord 记录) {
                return String.format("%tF %<tT %s %s%n", 记录.getMillis(), 记录.getLevel(), 记录.getMessage());
            }
        });
        处理器.setLevel(Level.ALL);
        日志器.addHandler(处理器);
        日志器.setLevel(Level.INFO);
        return 日志器;
    }

    /** Logger.info */
    public static void 信息(Logger 日志器, String 内容) {
        日志器.info(内容);
    }

    /** Logger.warning */
    public static void 警告(Logger 日志器, String 内容) {
        日志器.warning(内容);
    }

    /** Logger.severe */
    public static void 错误(Logger 日志器, String 内容) {
        日志器.severe(内容);
    }

    /** Logger.fine（需要日志器级别为 FINE 或更低才会输出） */
    public static void 调试(Logger 日志器, String 内容) {
        日志器.fine(内容);
    }

    /** Logger.setLevel */
    public static void 设置级别(Logger 日志器, Level 级别) {
        日志器.setLevel(级别);
    }
}
