package 中文.标准库;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * 列表：java.util.ArrayList 的中文别名。
 * 保留 ArrayList 的全部方法，同时提供中文方法名。
 */
public class 列表<T> extends ArrayList<T> {

    public 列表() {
        super();
    }

    public 列表(int 初始容量) {
        super(初始容量);
    }

    public 列表<T> 加(T 值) {
        add(值);
        return this;
    }

    public 列表<T> 加(int 下标, T 值) {
        add(下标, 值);
        return this;
    }

    public T 取(int 下标) {
        return get(下标);
    }

    public T 取首() {
        return isEmpty() ? null : get(0);
    }

    public T 取尾() {
        return isEmpty() ? null : get(size() - 1);
    }

    public int 个数() {
        return size();
    }

    public 列表<T> 去(int 下标) {
        remove(下标);
        return this;
    }

    public 列表<T> 移除(T 值) {
        remove(值);
        return this;
    }

    public boolean 含(T 值) {
        return contains(值);
    }

    public boolean 为空() {
        return isEmpty();
    }

    public 列表<T> 清空() {
        clear();
        return this;
    }

    /** 要求元素实现 Comparable，否则运行期抛 ClassCastException。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public 列表<T> 排序() {
        Collections.sort((ArrayList) this);
        return this;
    }

    public 列表<T> 排序(Comparator<? super T> 比较器) {
        sort(比较器);
        return this;
    }

    @SafeVarargs
    public static <T> 列表<T> 由(T... 值) {
        列表<T> 结果 = new 列表<>();
        Collections.addAll(结果, 值);
        return 结果;
    }
}
