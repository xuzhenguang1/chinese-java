/*
 * M5 值模型：cjk_value、泛型值盒、值转文本/相等/比较/哈希。
 */
#include "cjkrt.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#endif

/* ---------------- 字符串小工具 ---------------- */

static cjk_string *串新建(int32_t len) {
    cjk_string *s = (cjk_string *)cjk_gc_alloc(sizeof(cjk_string));
    s->len = len;
    s->data = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)(len > 0 ? len : 1));
    return s;
}

cjk_string *cjk_str_from_cstr_raw(const char *s, int32_t len) {
    cjk_string *r = 串新建(len);
    for (int32_t i = 0; i < len; i++) {
        r->data[i] = (uint16_t)(unsigned char)s[i];
    }
    return r;
}

static int32_t 码元相等(cjk_string *a, cjk_string *b) {
    if (a == b) {
        return 1;
    }
    if (a == NULL || b == NULL || a->len != b->len) {
        return 0;
    }
    for (int32_t i = 0; i < a->len; i++) {
        if (a->data[i] != b->data[i]) {
            return 0;
        }
    }
    return 1;
}

/* ---------------- 值构造与取用 ---------------- */

cjk_value cjk_v_null(void) {
    cjk_value v;
    v.kind = CJK_V_NULL;
    v.u.i = 0;
    return v;
}

cjk_value cjk_v_i32(int32_t x) {
    cjk_value v;
    v.kind = CJK_V_I32;
    v.u.i = x;
    return v;
}

cjk_value cjk_v_i64(int64_t x) {
    cjk_value v;
    v.kind = CJK_V_I64;
    v.u.i = x;
    return v;
}

cjk_value cjk_v_f32(float x) {
    cjk_value v;
    v.kind = CJK_V_F32;
    v.u.d = (double)x;
    return v;
}

cjk_value cjk_v_f64(double x) {
    cjk_value v;
    v.kind = CJK_V_F64;
    v.u.d = x;
    return v;
}

cjk_value cjk_v_bool(int32_t x) {
    cjk_value v;
    v.kind = CJK_V_BOOL;
    v.u.i = x != 0;
    return v;
}

cjk_value cjk_v_char(uint16_t x) {
    cjk_value v;
    v.kind = CJK_V_CHAR;
    v.u.i = x;
    return v;
}

cjk_value cjk_v_str(cjk_string *x) {
    cjk_value v;
    v.kind = CJK_V_STR;
    v.u.p = x;
    return v;
}

cjk_value cjk_v_ref(void *x) {
    cjk_value v;
    v.kind = CJK_V_REF;
    v.u.p = x;
    return v;
}

static void 值判空(cjk_value v, int32_t line) {
    if (v.kind == CJK_V_NULL) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_空指针异常, "空值不能拆箱"), line);
    }
}

int32_t cjk_v_as_i32(cjk_value v, int32_t line) {
    值判空(v, line);
    switch (v.kind) {
        case CJK_V_I32:
        case CJK_V_I64:
        case CJK_V_CHAR:
        case CJK_V_BOOL:
            return (int32_t)v.u.i;
        case CJK_V_F32:
        case CJK_V_F64:
            return (int32_t)v.u.d;
        default:
            return 0;
    }
}

int64_t cjk_v_as_i64(cjk_value v, int32_t line) {
    值判空(v, line);
    switch (v.kind) {
        case CJK_V_F32:
        case CJK_V_F64:
            return (int64_t)v.u.d;
        case CJK_V_STR:
            return cjk_parse_i64((cjk_string *)v.u.p, line);
        default:
            return v.u.i;
    }
}

float cjk_v_as_f32(cjk_value v, int32_t line) {
    值判空(v, line);
    if (v.kind == CJK_V_F32 || v.kind == CJK_V_F64) {
        return (float)v.u.d;
    }
    return (float)v.u.i;
}

double cjk_v_as_f64(cjk_value v, int32_t line) {
    值判空(v, line);
    if (v.kind == CJK_V_F32 || v.kind == CJK_V_F64) {
        return v.u.d;
    }
    return (double)v.u.i;
}

int32_t cjk_v_as_bool(cjk_value v, int32_t line) {
    值判空(v, line);
    if (v.kind == CJK_V_BOOL) {
        return v.u.i != 0;
    }
    if (v.kind == CJK_V_STR) {
        cjk_string *s = (cjk_string *)v.u.p;
        return s != NULL && s->len == 4 && s->data[0] == 't';
    }
    return v.u.i != 0;
}

uint16_t cjk_v_as_char(cjk_value v, int32_t line) {
    值判空(v, line);
    return (uint16_t)v.u.i;
}

cjk_string *cjk_v_as_str(cjk_value v, int32_t line) {
    (void)line;
    if (v.kind == CJK_V_NULL) {
        return NULL;
    }
    if (v.kind == CJK_V_STR) {
        return (cjk_string *)v.u.p;
    }
    return cjk_value_to_string(v);
}

void *cjk_v_as_ref(cjk_value v, int32_t line) {
    (void)line;
    if (v.kind == CJK_V_NULL) {
        return NULL;
    }
    if (v.kind == CJK_V_REF) {
        return v.u.p;
    }
    return NULL;
}

/* ---------------- 泛型值盒 ---------------- */

typedef struct cjk_box {
    cjk_class *cls;
    cjk_value v;
} cjk_box;

static cjk_string *值盒转文本(cjk_object *self);

static cjk_class CJK_CLS_值盒 = {91, "对象", &CJK_CLS_对象, 0, NULL, NULL, 0, 值盒转文本};

static cjk_string *值盒转文本(cjk_object *self) {
    return cjk_value_to_string(((cjk_box *)self)->v);
}

cjk_object *cjk_box_value(cjk_value v) {
    if (v.kind == CJK_V_NULL) {
        return NULL;
    }
    cjk_box *b = (cjk_box *)cjk_gc_alloc(sizeof(cjk_box));
    b->cls = &CJK_CLS_值盒;
    b->v = v;
    return (cjk_object *)b;
}

cjk_value cjk_unbox_value(cjk_object *obj, int32_t line) {
    (void)line;
    if (obj == NULL) {
        return cjk_v_null();
    }
    if (obj->cls == &CJK_CLS_值盒) {
        return ((cjk_box *)obj)->v;
    }
    return cjk_v_ref(obj);
}

/* ---------------- 文本化 ---------------- */

cjk_string *cjk_value_to_string(cjk_value v) {
    switch (v.kind) {
        case CJK_V_NULL:
            return cjk_str_from_utf8("null");
        case CJK_V_I32:
            return cjk_str_from_i32((int32_t)v.u.i);
        case CJK_V_I64:
            return cjk_str_from_i64(v.u.i);
        case CJK_V_F32:
            return cjk_str_from_f32((float)v.u.d);
        case CJK_V_F64:
            return cjk_str_from_f64(v.u.d);
        case CJK_V_BOOL:
            return cjk_str_from_bool(v.u.i != 0);
        case CJK_V_CHAR:
            return cjk_str_from_char((uint16_t)v.u.i);
        case CJK_V_STR:
            return v.u.p == NULL ? cjk_str_from_utf8("null") : (cjk_string *)v.u.p;
        case CJK_V_REF:
            return cjk_object_to_string((cjk_object *)v.u.p);
        default:
            return cjk_str_from_utf8("null");
    }
}

/* ---------------- 相等 / 比较 / 哈希 ---------------- */

static int32_t 是数(cjk_value v) {
    return v.kind == CJK_V_I32 || v.kind == CJK_V_I64 || v.kind == CJK_V_F32
            || v.kind == CJK_V_F64 || v.kind == CJK_V_CHAR || v.kind == CJK_V_BOOL;
}

static int32_t 是浮(cjk_value v) {
    return v.kind == CJK_V_F32 || v.kind == CJK_V_F64;
}

static double 取双(cjk_value v) {
    return 是浮(v) ? v.u.d : (double)v.u.i;
}

int32_t cjk_value_equal(cjk_value a, cjk_value b) {
    if (a.kind == CJK_V_NULL || b.kind == CJK_V_NULL) {
        return a.kind == b.kind;
    }
    if (a.kind == CJK_V_STR && b.kind == CJK_V_STR) {
        return 码元相等((cjk_string *)a.u.p, (cjk_string *)b.u.p);
    }
    if (是数(a) && 是数(b)) {
        if (是浮(a) || 是浮(b)) {
            return 取双(a) == 取双(b);
        }
        return a.u.i == b.u.i;
    }
    if (a.kind != b.kind) {
        return 0;
    }
    return a.u.p == b.u.p;
}

int32_t cjk_value_compare(cjk_value a, cjk_value b) {
    if (a.kind == CJK_V_NULL || b.kind == CJK_V_NULL) {
        return a.kind == b.kind ? 0 : (a.kind == CJK_V_NULL ? -1 : 1);
    }
    if (a.kind == CJK_V_STR && b.kind == CJK_V_STR) {
        return cjk_str_compare((cjk_string *)a.u.p, (cjk_string *)b.u.p);
    }
    if (a.kind == CJK_V_STR || b.kind == CJK_V_STR) {
        return cjk_str_compare(cjk_value_to_string(a), cjk_value_to_string(b));
    }
    if (是数(a) && 是数(b)) {
        if (是浮(a) || 是浮(b)) {
            double x = 取双(a);
            double y = 取双(b);
            return x < y ? -1 : (x > y ? 1 : 0);
        }
        return a.u.i < b.u.i ? -1 : (a.u.i > b.u.i ? 1 : 0);
    }
    if (a.u.p == b.u.p) {
        return 0;
    }
    return cjk_str_compare(cjk_value_to_string(a), cjk_value_to_string(b));
}

int32_t cjk_value_hash(cjk_value v) {
    switch (v.kind) {
        case CJK_V_NULL:
            return 0;
        case CJK_V_I32:
        case CJK_V_CHAR:
            return (int32_t)v.u.i;
        case CJK_V_I64:
        case CJK_V_BOOL:
            return (int32_t)(v.u.i ^ (v.u.i >> 32));
        case CJK_V_F32:
        case CJK_V_F64: {
            uint64_t 位;
            double d = v.u.d;
            memcpy(&位, &d, sizeof(位));
            return (int32_t)(位 ^ (位 >> 32));
        }
        case CJK_V_STR: {
            cjk_string *s = (cjk_string *)v.u.p;
            int32_t h = 0;
            if (s != NULL) {
                for (int32_t i = 0; i < s->len; i++) {
                    h = (int32_t)((uint32_t)h * 31u + s->data[i]);
                }
            }
            return h;
        }
        default:
            return (int32_t)((uintptr_t)v.u.p ^ ((uintptr_t)v.u.p >> 32));
    }
}

/* ---------------- 程序参数 ---------------- */

static cjk_array *参数数组 = NULL;

#ifdef _WIN32
/* 宽字符参数转 cjk_string：Windows 的 argv 用 ANSI 代码页，中文路径会丢字。 */
static cjk_string *宽参数转字符串(const wchar_t *宽) {
    int 八长 = WideCharToMultiByte(CP_UTF8, 0, 宽, -1, NULL, 0, NULL, NULL);
    if (八长 <= 0) {
        return cjk_str_from_utf8("");
    }
    char *缓冲 = (char *)cjk_gc_alloc((size_t)八长);
    WideCharToMultiByte(CP_UTF8, 0, 宽, -1, 缓冲, 八长, NULL, NULL);
    return cjk_str_from_utf8(缓冲);
}
#endif

void cjk_set_args(int argc, char **argv) {
#ifdef _WIN32
    /* 对齐 Java：程序参数不含 argv[0]；从宽字符命令行读取以保留中文路径。 */
    (void)argc;
    (void)argv;
    int 宽数 = 0;
    LPWSTR *宽列表 = CommandLineToArgvW(GetCommandLineW(), &宽数);
    int 数 = (宽列表 != NULL && 宽数 > 0) ? 宽数 - 1 : 0;
    cjk_string **项 = (cjk_string **)cjk_gc_alloc(sizeof(cjk_string *) * (size_t)(数 > 0 ? 数 : 1));
    for (int i = 0; i < 数; i++) {
        项[i] = 宽参数转字符串(宽列表[i + 1]);
    }
    if (宽列表 != NULL) {
        LocalFree(宽列表);
    }
    参数数组 = cjk_array_from(CJK_KIND_REF, (int32_t)sizeof(cjk_string *), 数, 项);
#else
    /* 对齐 Java：程序参数不含 argv[0]（可执行文件路径）。 */
    int 数 = argc > 0 ? argc - 1 : 0;
    cjk_string **项 = (cjk_string **)cjk_gc_alloc(sizeof(cjk_string *) * (size_t)(数 > 0 ? 数 : 1));
    for (int i = 0; i < 数; i++) {
        项[i] = cjk_str_from_utf8(argv[i + 1]);
    }
    参数数组 = cjk_array_from(CJK_KIND_REF, (int32_t)sizeof(cjk_string *), 数, 项);
#endif
}

cjk_array *cjk_program_args(void) {
    if (参数数组 == NULL) {
        参数数组 = cjk_array_new(0, CJK_KIND_REF, (int32_t)sizeof(cjk_string *), 0);
    }
    return 参数数组;
}

/* ---------------- 解析 ---------------- */

int64_t cjk_parse_i64(cjk_string *s, int32_t line) {
    if (s == NULL || s->len == 0) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数字格式异常, "空字符串不能解析为整数"), line);
    }
    int32_t i = 0;
    int32_t 负 = 0;
    if (s->data[0] == '-' || s->data[0] == '+') {
        负 = s->data[0] == '-';
        i = 1;
    }
    if (i >= s->len) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数字格式异常, "字符串不是合法整数"), line);
    }
    int64_t 值 = 0;
    for (; i < s->len; i++) {
        uint16_t c = s->data[i];
        if (c < '0' || c > '9') {
            cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数字格式异常, "字符串不是合法整数"), line);
        }
        值 = 值 * 10 + (c - '0');
    }
    return 负 ? -值 : 值;
}

double cjk_parse_f64(cjk_string *s, int32_t line) {
    if (s == NULL || s->len == 0) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数字格式异常, "空字符串不能解析为小数"), line);
    }
    char 缓冲[128];
    int32_t n = s->len < (int32_t)sizeof(缓冲) - 1 ? s->len : (int32_t)sizeof(缓冲) - 1;
    for (int32_t i = 0; i < n; i++) {
        uint16_t c = s->data[i];
        缓冲[i] = c < 128 ? (char)c : '?';
    }
    缓冲[n] = '\0';
    char *尾 = NULL;
    double 值 = strtod(缓冲, &尾);
    if (尾 == 缓冲) {
        cjk_throw(cjk_new_exception_utf8(&CJK_CLS_数字格式异常, "字符串不是合法小数"), line);
    }
    return 值;
}
