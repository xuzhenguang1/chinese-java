package 中文.标准库;

import java.util.Collections;
import java.util.List;

/**
 * 集合工具：java.util.Collections 常用操作的中文别名。
 */
public final class 集合工具 {

    private 集合工具() {
    }

    public static <T extends Comparable<? super T>> void 排序(List<T> 集合) {
        Collections.sort(集合);
    }

    public static void 反转(List<?> 集合) {
        Collections.reverse(集合);
    }

    public static void 打乱(List<?> 集合) {
        Collections.shuffle(集合);
    }

    public static <T extends Comparable<? super T>> T 最大(List<T> 集合) {
        return Collections.max(集合);
    }

    public static <T extends Comparable<? super T>> T 最小(List<T> 集合) {
        return Collections.min(集合);
    }

    public static <T> void 填充(List<T> 集合, T 值) {
        Collections.fill(集合, 值);
    }
}
