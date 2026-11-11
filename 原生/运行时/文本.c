/*
 * M5 字符串扩展：子串、替换、拆分、复读、比较、连接、大小写、空白判断。
 */
#include "cjkrt.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static cjk_string *串新建(int32_t len) {
    cjk_string *s = (cjk_string *)cjk_gc_alloc(sizeof(cjk_string));
    s->len = len;
    s->data = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)(len > 0 ? len : 1));
    return s;
}

static cjk_string *区间复制(cjk_string *s, int32_t 起, int32_t 长) {
    cjk_string *r = 串新建(长);
    for (int32_t i = 0; i < 长; i++) {
        r->data[i] = s->data[起 + i];
    }
    return r;
}

static void 越界(int32_t line) {
    cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数组越界异常, "字符串下标越界"), line);
}

cjk_string *cjk_str_substring(cjk_string *s, int32_t begin, int32_t end, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_空指针异常, "字符串为空"), line);
    }
    if (begin < 0 || end > s->len || begin > end) {
        越界(line);
    }
    return 区间复制(s, begin, end - begin);
}

cjk_string *cjk_str_replace(cjk_string *s, cjk_string *old, cjk_string *new_s) {
    if (s == NULL) {
        return NULL;
    }
    if (old == NULL || old->len == 0) {
        return s;
    }
    int32_t 新长 = 0;
    for (int32_t i = 0; i < s->len;) {
        int32_t 命中 = 1;
        if (i + old->len <= s->len) {
            for (int32_t j = 0; j < old->len; j++) {
                if (s->data[i + j] != old->data[j]) {
                    命中 = 0;
                    break;
                }
            }
        } else {
            命中 = 0;
        }
        if (命中) {
            新长 += new_s == NULL ? 0 : new_s->len;
            i += old->len;
        } else {
            新长 += 1;
            i += 1;
        }
    }
    cjk_string *r = 串新建(新长);
    int32_t 写 = 0;
    for (int32_t i = 0; i < s->len;) {
        int32_t 命中 = 1;
        if (i + old->len <= s->len) {
            for (int32_t j = 0; j < old->len; j++) {
                if (s->data[i + j] != old->data[j]) {
                    命中 = 0;
                    break;
                }
            }
        } else {
            命中 = 0;
        }
        if (命中) {
            if (new_s != NULL) {
                for (int32_t j = 0; j < new_s->len; j++) {
                    r->data[写++] = new_s->data[j];
                }
            }
            i += old->len;
        } else {
            r->data[写++] = s->data[i++];
        }
    }
    return r;
}

cjk_array *cjk_str_split(cjk_string *s, cjk_string *sep, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_空指针异常, "字符串为空"), line);
    }
    if (sep == NULL || sep->len == 0) {
        cjk_string **单项 = (cjk_string **)cjk_gc_alloc(sizeof(cjk_string *));
        单项[0] = s;
        return cjk_array_from(CJK_KIND_REF, (int32_t)sizeof(cjk_string *), 1, 单项);
    }
    int32_t 数 = 1;
    for (int32_t i = 0; i + sep->len <= s->len;) {
        int32_t 命中 = 1;
        for (int32_t j = 0; j < sep->len; j++) {
            if (s->data[i + j] != sep->data[j]) {
                命中 = 0;
                break;
            }
        }
        if (命中) {
            数++;
            i += sep->len;
        } else {
            i++;
        }
    }
    cjk_string **项 = (cjk_string **)cjk_gc_alloc(sizeof(cjk_string *) * (size_t)数);
    int32_t 序号 = 0;
    int32_t 起 = 0;
    for (int32_t i = 0; i + sep->len <= s->len;) {
        int32_t 命中 = 1;
        for (int32_t j = 0; j < sep->len; j++) {
            if (s->data[i + j] != sep->data[j]) {
                命中 = 0;
                break;
            }
        }
        if (命中) {
            项[序号++] = 区间复制(s, 起, i - 起);
            i += sep->len;
            起 = i;
        } else {
            i++;
        }
    }
    项[序号++] = 区间复制(s, 起, s->len - 起);
    /* 对齐 Java String.split：去掉末尾空串。 */
    while (序号 > 0 && 项[序号 - 1]->len == 0) {
        序号--;
    }
    return cjk_array_from(CJK_KIND_REF, (int32_t)sizeof(cjk_string *), 序号, 项);
}

cjk_string *cjk_str_repeat(cjk_string *s, int32_t n, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_空指针异常, "字符串为空"), line);
    }
    if (n < 0) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_非法参数异常, "复读次数不能为负"), line);
    }
    cjk_string *r = 串新建(s->len * n);
    for (int32_t k = 0; k < n; k++) {
        for (int32_t i = 0; i < s->len; i++) {
            r->data[k * s->len + i] = s->data[i];
        }
    }
    return r;
}

int32_t cjk_str_compare(cjk_string *a, cjk_string *b) {
    if (a == b) {
        return 0;
    }
    if (a == NULL) {
        return b == NULL ? 0 : -1;
    }
    if (b == NULL) {
        return 1;
    }
    int32_t n = a->len < b->len ? a->len : b->len;
    for (int32_t i = 0; i < n; i++) {
        if (a->data[i] != b->data[i]) {
            return (int32_t)a->data[i] - (int32_t)b->data[i];
        }
    }
    return a->len - b->len;
}

int32_t cjk_str_contains(cjk_string *s, cjk_string *sub) {
    if (s == NULL || sub == NULL) {
        return 0;
    }
    if (sub->len == 0) {
        return 1;
    }
    for (int32_t i = 0; i + sub->len <= s->len; i++) {
        int32_t 命中 = 1;
        for (int32_t j = 0; j < sub->len; j++) {
            if (s->data[i + j] != sub->data[j]) {
                命中 = 0;
                break;
            }
        }
        if (命中) {
            return 1;
        }
    }
    return 0;
}

int32_t cjk_str_starts_with(cjk_string *s, cjk_string *前缀) {
    if (s == NULL || 前缀 == NULL || 前缀->len > s->len) {
        return 0;
    }
    for (int32_t i = 0; i < 前缀->len; i++) {
        if (s->data[i] != 前缀->data[i]) {
            return 0;
        }
    }
    return 1;
}

int32_t cjk_str_ends_with(cjk_string *s, cjk_string *后缀) {
    if (s == NULL || 后缀 == NULL || 后缀->len > s->len) {
        return 0;
    }
    int32_t 起 = s->len - 后缀->len;
    for (int32_t i = 0; i < 后缀->len; i++) {
        if (s->data[起 + i] != 后缀->data[i]) {
            return 0;
        }
    }
    return 1;
}

static int32_t 是空白码元(uint16_t c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'
            || c == 0x0B || c == 0x3000 || c == 0x00A0;
}

int32_t cjk_str_is_blank(cjk_string *s, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_空指针异常, "字符串为空"), line);
    }
    for (int32_t i = 0; i < s->len; i++) {
        if (!是空白码元(s->data[i])) {
            return 0;
        }
    }
    return 1;
}

cjk_string *cjk_str_upper(cjk_string *s) {
    if (s == NULL) {
        return NULL;
    }
    cjk_string *r = 区间复制(s, 0, s->len);
    for (int32_t i = 0; i < r->len; i++) {
        if (r->data[i] >= 'a' && r->data[i] <= 'z') {
            r->data[i] = (uint16_t)(r->data[i] - 'a' + 'A');
        }
    }
    return r;
}

cjk_string *cjk_str_lower(cjk_string *s) {
    if (s == NULL) {
        return NULL;
    }
    cjk_string *r = 区间复制(s, 0, s->len);
    for (int32_t i = 0; i < r->len; i++) {
        if (r->data[i] >= 'A' && r->data[i] <= 'Z') {
            r->data[i] = (uint16_t)(r->data[i] - 'A' + 'a');
        }
    }
    return r;
}

uint16_t cjk_str_char_at(cjk_string *s, int32_t index, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_空指针异常, "字符串为空"), line);
    }
    if (index < 0 || index >= s->len) {
        越界(line);
    }
    return s->data[index];
}

cjk_string *cjk_str_join(cjk_string *sep, cjk_array *parts) {
    if (parts == NULL || parts->len == 0) {
        return cjk_str_from_utf8("");
    }
    cjk_string **项 = (cjk_string **)parts->data;
    int32_t 总长 = 0;
    for (int32_t i = 0; i < parts->len; i++) {
        总长 += 项[i] == NULL ? 4 : 项[i]->len;
        if (i > 0) {
            总长 += sep == NULL ? 0 : sep->len;
        }
    }
    cjk_string *r = 串新建(总长);
    int32_t 写 = 0;
    for (int32_t i = 0; i < parts->len; i++) {
        if (i > 0 && sep != NULL) {
            for (int32_t j = 0; j < sep->len; j++) {
                r->data[写++] = sep->data[j];
            }
        }
        if (项[i] == NULL) {
            r->data[写++] = 'n';
            r->data[写++] = 'u';
            r->data[写++] = 'l';
            r->data[写++] = 'l';
        } else {
            for (int32_t j = 0; j < 项[i]->len; j++) {
                r->data[写++] = 项[i]->data[j];
            }
        }
    }
    return r;
}
