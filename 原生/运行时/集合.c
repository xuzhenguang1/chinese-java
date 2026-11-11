/*
 * M5 集合运行时：清单、集、键值表、文本构建器、可选值、流、比较器。
 *
 * 元素统一用 cjk_value 存取；集合对象首字段为 cjk_class*，可直接参与 GC。
 */
#include "cjkrt.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---------------- 类描述符 ---------------- */

static cjk_string *序列转文本(cjk_object *self);
static cjk_string *字典转文本(cjk_object *self);
static cjk_string *构建器转文本(cjk_object *self);
static cjk_string *可选值转文本(cjk_object *self);

cjk_class CJK_CLS_可比较 = {80, "可比较", &CJK_CLS_对象, 0, NULL, NULL, 0, NULL};
cjk_class CJK_CLS_清单 = {20, "清单", &CJK_CLS_对象, 0, NULL, NULL, 0, 序列转文本};
cjk_class CJK_CLS_集 = {21, "集", &CJK_CLS_对象, 0, NULL, NULL, 0, 序列转文本};
cjk_class CJK_CLS_流 = {22, "流", &CJK_CLS_对象, 0, NULL, NULL, 0, 序列转文本};
cjk_class CJK_CLS_键值表 = {23, "键值表", &CJK_CLS_对象, 0, NULL, NULL, 0, 字典转文本};
cjk_class CJK_CLS_文本构建器 = {24, "文本构建器", &CJK_CLS_对象, 0, NULL, NULL, 0, 构建器转文本};
cjk_class CJK_CLS_可选值 = {25, "可选值", &CJK_CLS_对象, 0, NULL, NULL, 0, 可选值转文本};
cjk_class CJK_CLS_比较器 = {26, "比较器", &CJK_CLS_对象, 0, NULL, NULL, 0, NULL};
cjk_class CJK_CLS_收集器 = {27, "收集器", &CJK_CLS_对象, 0, NULL, NULL, 0, NULL};

/* ---------------- 序列（清单/集/流） ---------------- */

typedef struct cjk_seq {
    cjk_class *cls;
    int32_t len;
    int32_t cap;
    cjk_value *items;
} cjk_seq;

static cjk_seq *序列新建(cjk_class *cls) {
    cjk_seq *s = (cjk_seq *)cjk_gc_alloc(sizeof(cjk_seq));
    s->cls = cls;
    s->len = 0;
    s->cap = 4;
    s->items = (cjk_value *)cjk_gc_alloc(sizeof(cjk_value) * (size_t)s->cap);
    return s;
}

static void 序列扩容(cjk_seq *s, int32_t 需要) {
    if (需要 <= s->cap) {
        return;
    }
    int32_t 新容 = s->cap * 2;
    while (新容 < 需要) {
        新容 *= 2;
    }
    cjk_value *新项 = (cjk_value *)cjk_gc_alloc(sizeof(cjk_value) * (size_t)新容);
    for (int32_t i = 0; i < s->len; i++) {
        新项[i] = s->items[i];
    }
    s->items = 新项;
    s->cap = 新容;
}

static void 序列追加(cjk_seq *s, cjk_value v) {
    序列扩容(s, s->len + 1);
    s->items[s->len++] = v;
}

static int32_t 序列查找(cjk_seq *s, cjk_value v) {
    for (int32_t i = 0; i < s->len; i++) {
        if (cjk_value_equal(s->items[i], v)) {
            return i;
        }
    }
    return -1;
}

static cjk_string *序列转文本(cjk_object *self) {
    cjk_seq *s = (cjk_seq *)self;
    int32_t 总长 = 2;
    cjk_string **片段 = (cjk_string **)cjk_gc_alloc(sizeof(cjk_string *) * (size_t)(s->len > 0 ? s->len : 1));
    for (int32_t i = 0; i < s->len; i++) {
        片段[i] = cjk_value_to_string(s->items[i]);
        总长 += 片段[i]->len + (i > 0 ? 2 : 0);
    }
    cjk_string *r = (cjk_string *)cjk_gc_alloc(sizeof(cjk_string));
    r->len = 总长;
    r->data = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)(总长 > 0 ? 总长 : 1));
    int32_t 写 = 0;
    r->data[写++] = '[';
    for (int32_t i = 0; i < s->len; i++) {
        if (i > 0) {
            r->data[写++] = ',';
            r->data[写++] = ' ';
        }
        for (int32_t j = 0; j < 片段[i]->len; j++) {
            r->data[写++] = 片段[i]->data[j];
        }
    }
    r->data[写++] = ']';
    return r;
}

cjk_object *cjk_list_new(void) {
    return (cjk_object *)序列新建(&CJK_CLS_清单);
}

cjk_object *cjk_list_from(int32_t n, const cjk_value *items) {
    cjk_seq *s = 序列新建(&CJK_CLS_清单);
    序列扩容(s, n);
    for (int32_t i = 0; i < n; i++) {
        s->items[i] = items[i];
    }
    s->len = n;
    return (cjk_object *)s;
}

int32_t cjk_list_add(cjk_object *list, cjk_value v) {
    序列追加((cjk_seq *)list, v);
    return 1;
}

cjk_value cjk_list_get(cjk_object *list, int32_t index, int32_t line) {
    cjk_seq *s = (cjk_seq *)list;
    if (index < 0 || index >= s->len) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数组越界异常, "清单下标越界"), line);
    }
    return s->items[index];
}

cjk_value cjk_list_set(cjk_object *list, int32_t index, cjk_value v, int32_t line) {
    cjk_seq *s = (cjk_seq *)list;
    if (index < 0 || index >= s->len) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数组越界异常, "清单下标越界"), line);
    }
    cjk_value 旧 = s->items[index];
    s->items[index] = v;
    return 旧;
}

cjk_value cjk_list_remove_at(cjk_object *list, int32_t index, int32_t line) {
    cjk_seq *s = (cjk_seq *)list;
    if (index < 0 || index >= s->len) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数组越界异常, "清单下标越界"), line);
    }
    cjk_value 旧 = s->items[index];
    for (int32_t i = index; i + 1 < s->len; i++) {
        s->items[i] = s->items[i + 1];
    }
    s->len--;
    return 旧;
}

int32_t cjk_list_remove_value(cjk_object *list, cjk_value v) {
    int32_t 位 = 序列查找((cjk_seq *)list, v);
    if (位 < 0) {
        return 0;
    }
    cjk_list_remove_at(list, 位, 0);
    return 1;
}

int32_t cjk_list_index_of(cjk_object *list, cjk_value v) {
    return 序列查找((cjk_seq *)list, v);
}

int32_t cjk_list_size(cjk_object *list) {
    return ((cjk_seq *)list)->len;
}

int32_t cjk_list_is_empty(cjk_object *list) {
    return ((cjk_seq *)list)->len == 0;
}

int32_t cjk_list_contains(cjk_object *list, cjk_value v) {
    return 序列查找((cjk_seq *)list, v) >= 0;
}

void cjk_list_clear(cjk_object *list) {
    ((cjk_seq *)list)->len = 0;
}

void cjk_list_fill(cjk_object *list, cjk_value v) {
    cjk_seq *s = (cjk_seq *)list;
    for (int32_t i = 0; i < s->len; i++) {
        s->items[i] = v;
    }
}

static int 自然比较排序(const void *甲, const void *乙) {
    return cjk_value_compare(*(const cjk_value *)甲, *(const cjk_value *)乙);
}

void cjk_list_sort(cjk_object *list) {
    cjk_seq *s = (cjk_seq *)list;
    qsort(s->items, (size_t)s->len, sizeof(cjk_value), 自然比较排序);
}

int32_t cjk_comparator_compare(cjk_object *cmp, cjk_value a, cjk_value b);

void cjk_list_sort_by(cjk_object *list, cjk_object *cmp) {
    cjk_seq *s = (cjk_seq *)list;
    for (int32_t i = 1; i < s->len; i++) {
        cjk_value 键 = s->items[i];
        int32_t j = i - 1;
        while (j >= 0 && cjk_comparator_compare(cmp, s->items[j], 键) > 0) {
            s->items[j + 1] = s->items[j];
            j--;
        }
        s->items[j + 1] = 键;
    }
}

cjk_object *cjk_list_stream(cjk_object *list) {
    cjk_seq *源 = (cjk_seq *)list;
    cjk_seq *s = 序列新建(&CJK_CLS_流);
    序列扩容(s, 源->len);
    for (int32_t i = 0; i < 源->len; i++) {
        s->items[i] = 源->items[i];
    }
    s->len = 源->len;
    return (cjk_object *)s;
}

cjk_object *cjk_list_to_set(cjk_object *list) {
    cjk_seq *源 = (cjk_seq *)list;
    cjk_seq *s = 序列新建(&CJK_CLS_集);
    for (int32_t i = 0; i < 源->len; i++) {
        if (序列查找(s, 源->items[i]) < 0) {
            序列追加(s, 源->items[i]);
        }
    }
    return (cjk_object *)s;
}

static cjk_value 序列极值(cjk_object *list, int32_t line, int32_t 取大) {
    cjk_seq *s = (cjk_seq *)list;
    if (s->len == 0) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_非法参数异常, "空集合没有最大值或最小值"), line);
    }
    cjk_value 结果 = s->items[0];
    for (int32_t i = 1; i < s->len; i++) {
        int32_t 比 = cjk_value_compare(s->items[i], 结果);
        if (取大 ? 比 > 0 : 比 < 0) {
            结果 = s->items[i];
        }
    }
    return 结果;
}

cjk_value cjk_list_max(cjk_object *list, int32_t line) {
    return 序列极值(list, line, 1);
}

cjk_value cjk_list_min(cjk_object *list, int32_t line) {
    return 序列极值(list, line, 0);
}

void cjk_list_reverse(cjk_object *list) {
    cjk_seq *s = (cjk_seq *)list;
    for (int32_t i = 0, j = s->len - 1; i < j; i++, j--) {
        cjk_value 暂 = s->items[i];
        s->items[i] = s->items[j];
        s->items[j] = 暂;
    }
}

void cjk_list_shuffle(cjk_object *list) {
    cjk_seq *s = (cjk_seq *)list;
    for (int32_t i = s->len - 1; i > 0; i--) {
        int32_t j = (int32_t)(((uint32_t)rand()) % (uint32_t)(i + 1));
        cjk_value 暂 = s->items[i];
        s->items[i] = s->items[j];
        s->items[j] = 暂;
    }
}

/* ---------------- 集 ---------------- */

cjk_object *cjk_set_new(void) {
    return (cjk_object *)序列新建(&CJK_CLS_集);
}

cjk_object *cjk_set_from(int32_t n, const cjk_value *items) {
    cjk_seq *s = 序列新建(&CJK_CLS_集);
    for (int32_t i = 0; i < n; i++) {
        if (序列查找(s, items[i]) < 0) {
            序列追加(s, items[i]);
        }
    }
    return (cjk_object *)s;
}

int32_t cjk_set_add(cjk_object *set, cjk_value v) {
    cjk_seq *s = (cjk_seq *)set;
    if (序列查找(s, v) >= 0) {
        return 0;
    }
    序列追加(s, v);
    return 1;
}

int32_t cjk_set_remove(cjk_object *set, cjk_value v) {
    return cjk_list_remove_value(set, v);
}

int32_t cjk_set_contains(cjk_object *set, cjk_value v) {
    return 序列查找((cjk_seq *)set, v) >= 0;
}

int32_t cjk_set_size(cjk_object *set) {
    return ((cjk_seq *)set)->len;
}

int32_t cjk_set_is_empty(cjk_object *set) {
    return ((cjk_seq *)set)->len == 0;
}

void cjk_set_clear(cjk_object *set) {
    ((cjk_seq *)set)->len = 0;
}

cjk_value cjk_set_get(cjk_object *set, int32_t index, int32_t line) {
    return cjk_list_get(set, index, line);
}

cjk_object *cjk_set_stream(cjk_object *set) {
    cjk_seq *源 = (cjk_seq *)set;
    cjk_seq *s = 序列新建(&CJK_CLS_流);
    序列扩容(s, 源->len);
    for (int32_t i = 0; i < 源->len; i++) {
        s->items[i] = 源->items[i];
    }
    s->len = 源->len;
    return (cjk_object *)s;
}

/* ---------------- 键值表 ---------------- */

typedef struct cjk_map {
    cjk_class *cls;
    int32_t len;
    int32_t cap;
    cjk_value *keys;
    cjk_value *vals;
} cjk_map;

static cjk_map *字典新建(void) {
    cjk_map *m = (cjk_map *)cjk_gc_alloc(sizeof(cjk_map));
    m->cls = &CJK_CLS_键值表;
    m->len = 0;
    m->cap = 4;
    m->keys = (cjk_value *)cjk_gc_alloc(sizeof(cjk_value) * (size_t)m->cap);
    m->vals = (cjk_value *)cjk_gc_alloc(sizeof(cjk_value) * (size_t)m->cap);
    return m;
}

static void 字典扩容(cjk_map *m, int32_t 需要) {
    if (需要 <= m->cap) {
        return;
    }
    int32_t 新容 = m->cap * 2;
    while (新容 < 需要) {
        新容 *= 2;
    }
    cjk_value *新键 = (cjk_value *)cjk_gc_alloc(sizeof(cjk_value) * (size_t)新容);
    cjk_value *新值 = (cjk_value *)cjk_gc_alloc(sizeof(cjk_value) * (size_t)新容);
    for (int32_t i = 0; i < m->len; i++) {
        新键[i] = m->keys[i];
        新值[i] = m->vals[i];
    }
    m->keys = 新键;
    m->vals = 新值;
    m->cap = 新容;
}

static int32_t 字典找键(cjk_map *m, cjk_value 键) {
    for (int32_t i = 0; i < m->len; i++) {
        if (cjk_value_equal(m->keys[i], 键)) {
            return i;
        }
    }
    return -1;
}

static cjk_string *字典转文本(cjk_object *self) {
    cjk_map *m = (cjk_map *)self;
    cjk_string **片段 = (cjk_string **)cjk_gc_alloc(sizeof(cjk_string *) * (size_t)(m->len > 0 ? m->len : 1));
    int32_t 总长 = 2;
    for (int32_t i = 0; i < m->len; i++) {
        cjk_string *键 = cjk_value_to_string(m->keys[i]);
        cjk_string *值 = cjk_value_to_string(m->vals[i]);
        片段[i] = cjk_str_concat(cjk_str_concat(键, cjk_str_from_utf8("=")), 值);
        总长 += 片段[i]->len + (i > 0 ? 2 : 0);
    }
    cjk_string *r = (cjk_string *)cjk_gc_alloc(sizeof(cjk_string));
    r->len = 总长;
    r->data = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)(总长 > 0 ? 总长 : 1));
    int32_t 写 = 0;
    r->data[写++] = '{';
    for (int32_t i = 0; i < m->len; i++) {
        if (i > 0) {
            r->data[写++] = ',';
            r->data[写++] = ' ';
        }
        for (int32_t j = 0; j < 片段[i]->len; j++) {
            r->data[写++] = 片段[i]->data[j];
        }
    }
    r->data[写++] = '}';
    return r;
}

cjk_object *cjk_map_new(void) {
    return (cjk_object *)字典新建();
}

cjk_object *cjk_map_from(int32_t n, const cjk_value *平铺) {
    cjk_map *m = 字典新建();
    for (int32_t i = 0; i + 1 < n * 2; i += 2) {
        cjk_map_put((cjk_object *)m, 平铺[i], 平铺[i + 1]);
    }
    return (cjk_object *)m;
}

cjk_value cjk_map_put(cjk_object *map, cjk_value 键, cjk_value 值) {
    cjk_map *m = (cjk_map *)map;
    int32_t 位 = 字典找键(m, 键);
    if (位 >= 0) {
        cjk_value 旧 = m->vals[位];
        m->vals[位] = 值;
        return 旧;
    }
    字典扩容(m, m->len + 1);
    m->keys[m->len] = 键;
    m->vals[m->len] = 值;
    m->len++;
    return cjk_v_null();
}

cjk_value cjk_map_get(cjk_object *map, cjk_value 键) {
    cjk_map *m = (cjk_map *)map;
    int32_t 位 = 字典找键(m, 键);
    return 位 >= 0 ? m->vals[位] : cjk_v_null();
}

cjk_value cjk_map_get_or(cjk_object *map, cjk_value 键, cjk_value 默认值) {
    cjk_map *m = (cjk_map *)map;
    int32_t 位 = 字典找键(m, 键);
    if (位 >= 0) {
        return m->vals[位];
    }
    return 默认值;
}

cjk_value cjk_map_remove(cjk_object *map, cjk_value 键) {
    cjk_map *m = (cjk_map *)map;
    int32_t 位 = 字典找键(m, 键);
    if (位 < 0) {
        return cjk_v_null();
    }
    cjk_value 旧 = m->vals[位];
    for (int32_t i = 位; i + 1 < m->len; i++) {
        m->keys[i] = m->keys[i + 1];
        m->vals[i] = m->vals[i + 1];
    }
    m->len--;
    return 旧;
}

cjk_object *cjk_map_keys(cjk_object *map) {
    cjk_map *m = (cjk_map *)map;
    return cjk_set_from(m->len, m->keys);
}

cjk_object *cjk_map_values(cjk_object *map) {
    cjk_map *m = (cjk_map *)map;
    return cjk_set_from(m->len, m->vals);
}

int32_t cjk_map_contains_key(cjk_object *map, cjk_value 键) {
    return 字典找键((cjk_map *)map, 键) >= 0;
}

int32_t cjk_map_contains_value(cjk_object *map, cjk_value 值) {
    cjk_map *m = (cjk_map *)map;
    for (int32_t i = 0; i < m->len; i++) {
        if (cjk_value_equal(m->vals[i], 值)) {
            return 1;
        }
    }
    return 0;
}

int32_t cjk_map_size(cjk_object *map) {
    return ((cjk_map *)map)->len;
}

int32_t cjk_map_is_empty(cjk_object *map) {
    return ((cjk_map *)map)->len == 0;
}

void cjk_map_clear(cjk_object *map) {
    ((cjk_map *)map)->len = 0;
}

cjk_object *cjk_map_stream(cjk_object *map) {
    cjk_map *m = (cjk_map *)map;
    return cjk_list_stream((cjk_object *)cjk_list_from(m->len, m->vals));
}

void cjk_map_for_each(cjk_object *map, cjk_object *fn, cjk_callback cb) {
    cjk_map *m = (cjk_map *)map;
    for (int32_t i = 0; i < m->len; i++) {
        cb(fn, m->keys[i], m->vals[i], 0);
    }
}

/* ---------------- 文本构建器 ---------------- */

typedef struct cjk_builder {
    cjk_class *cls;
    cjk_string *content;
} cjk_builder;

cjk_object *cjk_builder_new(void) {
    cjk_builder *b = (cjk_builder *)cjk_gc_alloc(sizeof(cjk_builder));
    b->cls = &CJK_CLS_文本构建器;
    b->content = cjk_str_from_utf8("");
    return (cjk_object *)b;
}

cjk_object *cjk_builder_append(cjk_object *builder, cjk_value v) {
    cjk_builder *b = (cjk_builder *)builder;
    b->content = cjk_str_concat(b->content, cjk_value_to_string(v));
    return builder;
}

cjk_string *cjk_builder_to_string(cjk_object *builder) {
    return ((cjk_builder *)builder)->content;
}

int32_t cjk_builder_len(cjk_object *builder) {
    return ((cjk_builder *)builder)->content->len;
}

void cjk_builder_clear(cjk_object *builder) {
    ((cjk_builder *)builder)->content = cjk_str_from_utf8("");
}

static cjk_string *构建器转文本(cjk_object *self) {
    return ((cjk_builder *)self)->content;
}

/* ---------------- 可选值 ---------------- */

typedef struct cjk_optional {
    cjk_class *cls;
    int32_t present;
    cjk_value v;
} cjk_optional;

cjk_object *cjk_optional_empty(void) {
    cjk_optional *o = (cjk_optional *)cjk_gc_alloc(sizeof(cjk_optional));
    o->cls = &CJK_CLS_可选值;
    o->present = 0;
    o->v = cjk_v_null();
    return (cjk_object *)o;
}

cjk_object *cjk_optional_of(cjk_value v) {
    cjk_optional *o = (cjk_optional *)cjk_gc_alloc(sizeof(cjk_optional));
    o->cls = &CJK_CLS_可选值;
    o->present = 1;
    o->v = v;
    return (cjk_object *)o;
}

cjk_object *cjk_optional_of_nullable(cjk_value v) {
    if (v.kind == CJK_V_NULL) {
        return cjk_optional_empty();
    }
    return cjk_optional_of(v);
}

cjk_value cjk_optional_or(cjk_object *opt, cjk_value fallback) {
    cjk_optional *o = (cjk_optional *)opt;
    return o->present ? o->v : fallback;
}

int32_t cjk_optional_is_empty(cjk_object *opt) {
    return !((cjk_optional *)opt)->present;
}

int32_t cjk_optional_is_present(cjk_object *opt) {
    return ((cjk_optional *)opt)->present;
}

cjk_value cjk_optional_get(cjk_object *opt, int32_t line) {
    cjk_optional *o = (cjk_optional *)opt;
    if (!o->present) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_非法参数异常, "可选值中没有值"), line);
    }
    return o->v;
}

void cjk_optional_if_present(cjk_object *opt, cjk_object *fn, cjk_callback cb) {
    cjk_optional *o = (cjk_optional *)opt;
    if (o->present) {
        cb(fn, o->v, cjk_v_null(), 0);
    }
}

static cjk_string *可选值转文本(cjk_object *self) {
    cjk_optional *o = (cjk_optional *)self;
    if (!o->present) {
        return cjk_str_from_utf8("Optional.empty");
    }
    return cjk_str_concat(cjk_str_from_utf8("Optional["),
                          cjk_str_concat(cjk_value_to_string(o->v), cjk_str_from_utf8("]")));
}

/* ---------------- 流 ---------------- */

cjk_object *cjk_stream_filter(cjk_object *stream, cjk_object *fn, cjk_callback cb) {
    cjk_seq *源 = (cjk_seq *)stream;
    cjk_seq *s = 序列新建(&CJK_CLS_流);
    for (int32_t i = 0; i < 源->len; i++) {
        cjk_value 结果 = cb(fn, 源->items[i], cjk_v_null(), 0);
        if (cjk_v_as_bool(结果, 0)) {
            序列追加(s, 源->items[i]);
        }
    }
    return (cjk_object *)s;
}

cjk_object *cjk_stream_map(cjk_object *stream, cjk_object *fn, cjk_callback cb) {
    cjk_seq *源 = (cjk_seq *)stream;
    cjk_seq *s = 序列新建(&CJK_CLS_流);
    for (int32_t i = 0; i < 源->len; i++) {
        序列追加(s, cb(fn, 源->items[i], cjk_v_null(), 0));
    }
    return (cjk_object *)s;
}

void cjk_stream_for_each(cjk_object *stream, cjk_object *fn, cjk_callback cb) {
    cjk_seq *源 = (cjk_seq *)stream;
    for (int32_t i = 0; i < 源->len; i++) {
        cb(fn, 源->items[i], cjk_v_null(), 0);
    }
}

cjk_object *cjk_stream_collect_list(cjk_object *stream) {
    cjk_seq *源 = (cjk_seq *)stream;
    return cjk_list_from(源->len, 源->items);
}

cjk_object *cjk_stream_collect_set(cjk_object *stream) {
    cjk_seq *源 = (cjk_seq *)stream;
    return cjk_set_from(源->len, 源->items);
}

cjk_string *cjk_stream_collect_join(cjk_object *stream, cjk_object *collector) {
    cjk_seq *源 = (cjk_seq *)stream;
    cjk_string *分隔 = cjk_collector_separator(collector);
    cjk_string *结果 = cjk_str_from_utf8("");
    for (int32_t i = 0; i < 源->len; i++) {
        if (i > 0) {
            结果 = cjk_str_concat(结果, 分隔);
        }
        结果 = cjk_str_concat(结果, cjk_value_to_string(源->items[i]));
    }
    return 结果;
}

cjk_object *cjk_stream_find_first(cjk_object *stream) {
    cjk_seq *源 = (cjk_seq *)stream;
    if (源->len == 0) {
        return cjk_optional_empty();
    }
    return cjk_optional_of(源->items[0]);
}

int32_t cjk_stream_count(cjk_object *stream) {
    return ((cjk_seq *)stream)->len;
}

cjk_object *cjk_stream_distinct(cjk_object *stream) {
    cjk_seq *源 = (cjk_seq *)stream;
    cjk_seq *s = 序列新建(&CJK_CLS_流);
    for (int32_t i = 0; i < 源->len; i++) {
        if (序列查找(s, 源->items[i]) < 0) {
            序列追加(s, 源->items[i]);
        }
    }
    return (cjk_object *)s;
}

cjk_object *cjk_stream_sorted(cjk_object *stream) {
    cjk_seq *s = (cjk_seq *)stream;
    cjk_seq *结果 = 序列新建(&CJK_CLS_流);
    for (int32_t i = 0; i < s->len; i++) {
        序列追加(结果, s->items[i]);
    }
    cjk_list_sort((cjk_object *)结果);
    return (cjk_object *)结果;
}

/* ---------------- 收集器 ---------------- */

typedef struct cjk_collector {
    cjk_class *cls;
    int32_t kind; /* 0=清单 1=集 2=拼接 */
    cjk_string *sep;
} cjk_collector;

static cjk_collector *收集器新建(int32_t kind) {
    cjk_collector *c = (cjk_collector *)cjk_gc_alloc(sizeof(cjk_collector));
    c->cls = &CJK_CLS_收集器;
    c->kind = kind;
    c->sep = cjk_str_from_utf8("");
    return c;
}

cjk_object *cjk_collector_list(void) {
    return (cjk_object *)收集器新建(0);
}

cjk_object *cjk_collector_set(void) {
    return (cjk_object *)收集器新建(1);
}

cjk_object *cjk_collector_join(cjk_string *sep) {
    cjk_collector *c = 收集器新建(2);
    c->sep = sep;
    return (cjk_object *)c;
}

cjk_string *cjk_collector_separator(cjk_object *collector) {
    return ((cjk_collector *)collector)->sep;
}

/* ---------------- 比较器 ---------------- */

typedef struct cjk_cmp {
    cjk_class *cls;
    cjk_object *fn;
    cjk_callback cb;
    cjk_object *inner;
} cjk_cmp;

cjk_object *cjk_comparator_by_int(cjk_object *fn, cjk_callback cb) {
    cjk_cmp *c = (cjk_cmp *)cjk_gc_alloc(sizeof(cjk_cmp));
    c->cls = &CJK_CLS_比较器;
    c->fn = fn;
    c->cb = cb;
    c->inner = NULL;
    return (cjk_object *)c;
}

cjk_object *cjk_comparator_reversed(cjk_object *cmp) {
    cjk_cmp *c = (cjk_cmp *)cjk_gc_alloc(sizeof(cjk_cmp));
    c->cls = &CJK_CLS_比较器;
    c->fn = NULL;
    c->cb = NULL;
    c->inner = cmp;
    return (cjk_object *)c;
}

int32_t cjk_comparator_compare(cjk_object *cmp, cjk_value a, cjk_value b) {
    cjk_cmp *c = (cjk_cmp *)cmp;
    if (c->inner != NULL) {
        return -cjk_comparator_compare(c->inner, a, b);
    }
    cjk_value 甲 = c->cb(c->fn, a, cjk_v_null(), 0);
    cjk_value 乙 = c->cb(c->fn, b, cjk_v_null(), 0);
    int32_t x = cjk_v_as_i32(甲, 0);
    int32_t y = cjk_v_as_i32(乙, 0);
    return x < y ? -1 : (x > y ? 1 : 0);
}
