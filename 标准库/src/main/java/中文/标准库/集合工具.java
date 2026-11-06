package 中文.标准库;

import java.util.Collections;
import java.util.List;

/**
 * 集合工具：java.util.Collections 中静态糖未覆盖的操作。
 * 排序 / 反转 / 打乱 / 最大 / 最小 请直接用语法糖 `集合助手.*`。
 */
public final class 集合工具 {

    private 集合工具() {
    }

    public static <T> void 填充(List<T> 集合, T 值) {
        Collections.fill(集合, 值);
    }
}
