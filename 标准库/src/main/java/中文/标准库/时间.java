package 中文.标准库;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 时间：java.time 常用操作的中文别名。
 */
public final class 时间 {

    private 时间() {
    }

    public static LocalDate 今天() {
        return LocalDate.now();
    }

    public static LocalDateTime 此刻() {
        return LocalDateTime.now().withNano(0);
    }

    /** System.currentTimeMillis */
    public static long 时间戳() {
        return System.currentTimeMillis();
    }

    public static String 格式化(LocalDateTime 时刻, String 模板) {
        return 时刻.format(DateTimeFormatter.ofPattern(模板));
    }

    public static long 相差天数(LocalDate 起, LocalDate 止) {
        return ChronoUnit.DAYS.between(起, 止);
    }

    public static LocalDate 加天数(LocalDate 日期, long 天数) {
        return 日期.plusDays(天数);
    }
}
