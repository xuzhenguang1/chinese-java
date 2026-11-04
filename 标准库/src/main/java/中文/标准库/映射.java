package 中文.标准库;

import java.util.LinkedHashMap;

/**
 * 映射：java.util.LinkedHashMap 的中文别名，保留插入顺序。
 */
public class 映射<K, V> extends LinkedHashMap<K, V> {

    public 映射() {
        super();
    }

    public 映射(int 初始容量) {
        super(初始容量);
    }

    public 映射<K, V> 放(K 键, V 值) {
        put(键, 值);
        return this;
    }

    public V 取(K 键) {
        return get(键);
    }

    public V 取或(K 键, V 缺省值) {
        return getOrDefault(键, 缺省值);
    }

    public 映射<K, V> 删(K 键) {
        remove(键);
        return this;
    }

    public boolean 含键(K 键) {
        return containsKey(键);
    }

    public boolean 含值(V 值) {
        return containsValue(值);
    }

    public int 个数() {
        return size();
    }

    public boolean 为空() {
        return isEmpty();
    }

    public 映射<K, V> 清空() {
        clear();
        return this;
    }
}
