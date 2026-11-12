/*
 * 中文 Java 原生运行时（M4）
 */
#include "cjkrt.h"

#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#endif

/* ---------------- 内置类描述符 ---------------- */

cjk_class CJK_CLS_对象 = {0, "对象", NULL, 0, NULL, NULL, 0};
cjk_class CJK_CLS_异常 = {1, "异常", &CJK_CLS_对象, 0, NULL, NULL, 0};
cjk_class CJK_CLS_运行时异常 = {2, "运行时异常", &CJK_CLS_异常, 0, NULL, NULL, 0};
cjk_class CJK_CLS_算术异常 = {3, "算术异常", &CJK_CLS_运行时异常, 0, NULL, NULL, 0};
cjk_class CJK_CLS_空指针异常 = {4, "空指针异常", &CJK_CLS_运行时异常, 0, NULL, NULL, 0};
cjk_class CJK_CLS_数组越界异常 = {5, "数组越界异常", &CJK_CLS_运行时异常, 0, NULL, NULL, 0};
cjk_class CJK_CLS_非法参数异常 = {6, "非法参数异常", &CJK_CLS_运行时异常, 0, NULL, NULL, 0};
cjk_class CJK_CLS_数字格式异常 = {7, "数字格式异常", &CJK_CLS_运行时异常, 0, NULL, NULL, 0};
cjk_class CJK_CLS_类转换异常 = {8, "类转换异常", &CJK_CLS_运行时异常, 0, NULL, NULL, 0};

cjk_handler *cjk_handler_top = NULL;

#if defined(_WIN64)
/* LLVM 后端按此布局直接发射 IR；布局变化必须同步修改 原生\后端LLVM。 */
_Static_assert(sizeof(jmp_buf) == 256, "jmp_buf 布局变化：LLVM 后端需同步");
_Static_assert(sizeof(cjk_handler) == 272, "cjk_handler 布局变化：LLVM 后端需同步");
_Static_assert(offsetof(cjk_handler, prev) == 256, "cjk_handler.prev 偏移变化");
_Static_assert(offsetof(cjk_handler, exception) == 264, "cjk_handler.exception 偏移变化");
#endif

static const char *源路径 = "";

static uint16_t 空字面量[] = {'n', 'u', 'l', 'l'};
static cjk_string 空字面量串 = {4, 空字面量};

void cjk_rt_init(void) {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
    cjk_gc_init();
}

void cjk_set_source(const char *path) {
    源路径 = path == NULL ? "" : path;
}

/* ---------------- 异常 ---------------- */

cjk_exception *cjk_new_exception(cjk_class *cls, cjk_string *message) {
    cjk_exception *e = (cjk_exception *)cjk_gc_alloc(sizeof(cjk_exception));
    e->cls = cls;
    e->message = message;
    return e;
}

cjk_exception *cjk_new_exception_utf8(cjk_class *cls, const char *message) {
    return cjk_new_exception(cls, message == NULL ? NULL : cjk_str_from_utf8(message));
}

static void 未捕获(cjk_exception *e, int32_t line) {
    fflush(stdout);
    if (源路径 != NULL && 源路径[0] != '\0') {
        fprintf(stderr, "%s", 源路径);
        if (line > 0) {
            fprintf(stderr, ":%d", (int)line);
        }
        fprintf(stderr, "：");
    } else if (line > 0) {
        fprintf(stderr, "第 %d 行：", (int)line);
    }
    fprintf(stderr, "%s", e != NULL && e->cls != NULL ? e->cls->name : "异常");
    if (e != NULL && e->message != NULL) {
        fputs("：", stderr);
        /* 消息以 UTF-16 保存，转成 UTF-8 写到 stderr。 */
        for (int32_t i = 0; i < e->message->len; i++) {
            uint32_t 单元 = e->message->data[i];
            if (单元 >= 0xD800 && 单元 <= 0xDBFF && i + 1 < e->message->len) {
                uint32_t 低 = e->message->data[i + 1];
                if (低 >= 0xDC00 && 低 <= 0xDFFF) {
                    单元 = 0x10000 + ((单元 - 0xD800) << 10) + (低 - 0xDC00);
                    i++;
                }
            }
            unsigned char 缓冲[4];
            size_t n;
            if (单元 < 0x80) {
                缓冲[0] = (unsigned char)单元;
                n = 1;
            } else if (单元 < 0x800) {
                缓冲[0] = (unsigned char)(0xC0 | (单元 >> 6));
                缓冲[1] = (unsigned char)(0x80 | (单元 & 0x3F));
                n = 2;
            } else if (单元 < 0x10000) {
                缓冲[0] = (unsigned char)(0xE0 | (单元 >> 12));
                缓冲[1] = (unsigned char)(0x80 | ((单元 >> 6) & 0x3F));
                缓冲[2] = (unsigned char)(0x80 | (单元 & 0x3F));
                n = 3;
            } else {
                缓冲[0] = (unsigned char)(0xF0 | (单元 >> 18));
                缓冲[1] = (unsigned char)(0x80 | ((单元 >> 12) & 0x3F));
                缓冲[2] = (unsigned char)(0x80 | ((单元 >> 6) & 0x3F));
                缓冲[3] = (unsigned char)(0x80 | (单元 & 0x3F));
                n = 4;
            }
            fwrite(缓冲, 1, n, stderr);
        }
    }
    fputc('\n', stderr);
    exit(1);
}

void cjk_throw(cjk_exception *e, int32_t line) {
    if (cjk_handler_top != NULL) {
        cjk_handler *h = cjk_handler_top;
        h->exception = e;
        longjmp(h->buf, 1);
    }
    未捕获(e, line);
}

void cjk_throw_message(cjk_class *cls, const char *message, int32_t line) {
    cjk_throw(cjk_new_exception_utf8(cls, message), line);
}

/* ---------------- 对象 ---------------- */

cjk_object *cjk_new_object(cjk_class *cls, size_t size) {
    cjk_object *o = (cjk_object *)cjk_gc_alloc(size);
    o->cls = cls;
    return o;
}

static int32_t 类匹配(cjk_class *当前, cjk_class *目标) {
    if (当前 == NULL) {
        return 0;
    }
    if (当前 == 目标) {
        return 1;
    }
    if (类匹配(当前->parent, 目标)) {
        return 1;
    }
    for (int32_t i = 0; i < 当前->interface_count; i++) {
        if (类匹配(当前->interfaces[i], 目标)) {
            return 1;
        }
    }
    return 0;
}

int32_t cjk_is_instance(cjk_object *obj, cjk_class *target) {
    if (obj == NULL) {
        return 0;
    }
    return 类匹配(obj->cls, target);
}

cjk_object *cjk_null_check(cjk_object *obj, int32_t line) {
    if (obj == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), line);
    }
    return obj;
}

cjk_string *cjk_object_to_string(cjk_object *obj) {
    if (obj == NULL) {
        return &空字面量串;
    }
    if (obj->cls != NULL && obj->cls->to_string != NULL) {
        cjk_string *文本 = obj->cls->to_string(obj);
        return 文本 == NULL ? &空字面量串 : 文本;
    }
    for (cjk_class *当前 = obj->cls == NULL ? NULL : obj->cls->parent;
         当前 != NULL; 当前 = 当前->parent) {
        if (当前->to_string != NULL) {
            cjk_string *文本 = 当前->to_string(obj);
            return 文本 == NULL ? &空字面量串 : 文本;
        }
    }
    char 缓冲[128];
    snprintf(缓冲, sizeof(缓冲), "%s@%llx",
             obj->cls == NULL ? "对象" : obj->cls->name,
             (unsigned long long)(uintptr_t)obj);
    return cjk_str_from_utf8(缓冲);
}

cjk_string *cjk_virtual_to_string(cjk_object *obj, int32_t slot) {
    if (obj == NULL) {
        return &空字面量串;
    }
    if (obj->cls == NULL || slot < 0 || slot >= obj->cls->slot_count) {
        return cjk_object_to_string(obj);
    }
    void *函数 = obj->cls->methods[slot];
    if (函数 == NULL) {
        return cjk_object_to_string(obj);
    }
    return ((cjk_string *(*)(cjk_object *))函数)(obj);
}

/* ---------------- UTF-8 / UTF-16 ---------------- */

static cjk_string *新字符串(int32_t len) {
    cjk_string *s = (cjk_string *)cjk_gc_alloc(sizeof(cjk_string));
    s->len = len;
    s->data = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)(len > 0 ? len : 1));
    return s;
}

static uint32_t 取码点(const unsigned char *s, size_t 可用, size_t *消耗) {
    unsigned char c = s[0];
    if (c < 0x80) {
        *消耗 = 1;
        return c;
    }
    int 长度;
    uint32_t 值;
    if ((c & 0xE0) == 0xC0) {
        长度 = 2;
        值 = c & 0x1F;
    } else if ((c & 0xF0) == 0xE0) {
        长度 = 3;
        值 = c & 0x0F;
    } else if ((c & 0xF8) == 0xF0) {
        长度 = 4;
        值 = c & 0x07;
    } else {
        *消耗 = 1;
        return 0xFFFD;
    }
    if ((size_t)长度 > 可用) {
        *消耗 = 1;
        return 0xFFFD;
    }
    for (int i = 1; i < 长度; i++) {
        unsigned char 续 = s[i];
        if ((续 & 0xC0) != 0x80) {
            *消耗 = (size_t)i;
            return 0xFFFD;
        }
        值 = (值 << 6) | (uint32_t)(续 & 0x3F);
    }
    if (值 > 0x10FFFF || (值 >= 0xD800 && 值 <= 0xDFFF)) {
        *消耗 = (size_t)长度;
        return 0xFFFD;
    }
    *消耗 = (size_t)长度;
    return 值;
}

cjk_string *cjk_str_from_utf8(const char *s) {
    if (s == NULL) {
        return &空字面量串;
    }
    size_t 字节数 = strlen(s);
    cjk_string *结果 = 新字符串((int32_t)字节数);
    int32_t n = 0;
    size_t i = 0;
    while (i < 字节数) {
        size_t 消耗 = 1;
        uint32_t 码点 = 取码点((const unsigned char *)s + i, 字节数 - i, &消耗);
        i += 消耗;
        if (码点 > 0xFFFF) {
            码点 -= 0x10000;
            结果->data[n++] = (uint16_t)(0xD800 + (码点 >> 10));
            结果->data[n++] = (uint16_t)(0xDC00 + (码点 & 0x3FF));
        } else {
            结果->data[n++] = (uint16_t)码点;
        }
    }
    结果->len = n;
    return 结果;
}

static void 写码点(FILE *f, uint32_t 码点) {
    unsigned char 缓冲[4];
    size_t n;
    if (码点 < 0x80) {
        缓冲[0] = (unsigned char)码点;
        n = 1;
    } else if (码点 < 0x800) {
        缓冲[0] = (unsigned char)(0xC0 | (码点 >> 6));
        缓冲[1] = (unsigned char)(0x80 | (码点 & 0x3F));
        n = 2;
    } else if (码点 < 0x10000) {
        缓冲[0] = (unsigned char)(0xE0 | (码点 >> 12));
        缓冲[1] = (unsigned char)(0x80 | ((码点 >> 6) & 0x3F));
        缓冲[2] = (unsigned char)(0x80 | (码点 & 0x3F));
        n = 3;
    } else {
        缓冲[0] = (unsigned char)(0xF0 | (码点 >> 18));
        缓冲[1] = (unsigned char)(0x80 | ((码点 >> 12) & 0x3F));
        缓冲[2] = (unsigned char)(0x80 | ((码点 >> 6) & 0x3F));
        缓冲[3] = (unsigned char)(0x80 | (码点 & 0x3F));
        n = 4;
    }
    fwrite(缓冲, 1, n, f);
}

static void 输出码元串(const uint16_t *数据, int32_t 长度) {
    for (int32_t i = 0; i < 长度; i++) {
        uint32_t 单元 = 数据[i];
        if (单元 >= 0xD800 && 单元 <= 0xDBFF && i + 1 < 长度) {
            uint32_t 低 = 数据[i + 1];
            if (低 >= 0xDC00 && 低 <= 0xDFFF) {
                写码点(stdout, 0x10000 + ((单元 - 0xD800) << 10) + (低 - 0xDC00));
                i++;
                continue;
            }
        }
        if (单元 >= 0xD800 && 单元 <= 0xDFFF) {
            fputc('?', stdout);
        } else {
            写码点(stdout, 单元);
        }
    }
}

/* ---------------- 浮点转文本（对齐 Java） ---------------- */

static void 格式化浮点(char *输出, size_t 容量, double 值, int 单精度) {
    if (isnan(值)) {
        snprintf(输出, 容量, "NaN");
        return;
    }
    if (isinf(值)) {
        snprintf(输出, 容量, 值 < 0 ? "-Infinity" : "Infinity");
        return;
    }
    if (值 == 0.0) {
        snprintf(输出, 容量, signbit(值) ? "-0.0" : "0.0");
        return;
    }
    int 负 = signbit(值) != 0;
    double 绝对值 = 负 ? -值 : 值;
    char 临时[64];
    int 最大位数 = 单精度 ? 8 : 16;
    int 精度 = 最大位数;
    for (int p = 0; p <= 最大位数; p++) {
        snprintf(临时, sizeof(临时), "%.*e", p, 绝对值);
        int 可回读 = 单精度
                ? (strtof(临时, NULL) == (float)绝对值)
                : (strtod(临时, NULL) == 绝对值);
        if (可回读) {
            精度 = p;
            break;
        }
    }
    snprintf(临时, sizeof(临时), "%.*e", 精度, 绝对值);

    char 数字[40];
    int n = 0;
    int i = 0;
    while (临时[i] != '\0' && 临时[i] != 'e' && 临时[i] != 'E') {
        if (临时[i] >= '0' && 临时[i] <= '9') {
            数字[n++] = 临时[i];
        }
        i++;
    }
    数字[n] = '\0';
    int 指数 = 0;
    if (临时[i] != '\0') {
        指数 = (int)strtol(临时 + i + 1, NULL, 10);
    }
    while (n > 1 && 数字[n - 1] == '0') {
        数字[--n] = '\0';
    }

    char 结果[128];
    int k = 0;
    if (负) {
        结果[k++] = '-';
    }
    if (指数 >= -3 && 指数 <= 6) {
        if (指数 >= 0) {
            int 整数位数 = 指数 + 1;
            if (n <= 整数位数) {
                memcpy(结果 + k, 数字, (size_t)n);
                k += n;
                for (int z = n; z < 整数位数; z++) {
                    结果[k++] = '0';
                }
                结果[k++] = '.';
                结果[k++] = '0';
            } else {
                memcpy(结果 + k, 数字, (size_t)整数位数);
                k += 整数位数;
                结果[k++] = '.';
                memcpy(结果 + k, 数字 + 整数位数, (size_t)(n - 整数位数));
                k += n - 整数位数;
            }
        } else {
            结果[k++] = '0';
            结果[k++] = '.';
            for (int z = 0; z < -指数 - 1; z++) {
                结果[k++] = '0';
            }
            memcpy(结果 + k, 数字, (size_t)n);
            k += n;
        }
    } else {
        结果[k++] = 数字[0];
        结果[k++] = '.';
        if (n > 1) {
            memcpy(结果 + k, 数字 + 1, (size_t)(n - 1));
            k += n - 1;
        } else {
            结果[k++] = '0';
        }
        结果[k++] = 'E';
        k += snprintf(结果 + k, sizeof(结果) - (size_t)k, "%d", 指数);
    }
    结果[k] = '\0';
    snprintf(输出, 容量, "%s", 结果);
}

/* ---------------- 字符串操作 ---------------- */

cjk_string *cjk_str_from_i32(int32_t v) {
    char 缓冲[32];
    snprintf(缓冲, sizeof(缓冲), "%" PRId32, v);
    return cjk_str_from_utf8(缓冲);
}

cjk_string *cjk_str_from_i64(int64_t v) {
    char 缓冲[32];
    snprintf(缓冲, sizeof(缓冲), "%" PRId64, v);
    return cjk_str_from_utf8(缓冲);
}

cjk_string *cjk_str_from_f32(float v) {
    char 缓冲[128];
    格式化浮点(缓冲, sizeof(缓冲), (double)v, 1);
    return cjk_str_from_utf8(缓冲);
}

cjk_string *cjk_str_from_f64(double v) {
    char 缓冲[128];
    格式化浮点(缓冲, sizeof(缓冲), v, 0);
    return cjk_str_from_utf8(缓冲);
}

cjk_string *cjk_str_from_bool(int32_t v) {
    return cjk_str_from_utf8(v ? "true" : "false");
}

cjk_string *cjk_str_from_char(uint16_t v) {
    cjk_string *s = 新字符串(1);
    s->data[0] = v;
    return s;
}

cjk_string *cjk_str_concat(cjk_string *a, cjk_string *b) {
    if (a == NULL) {
        a = &空字面量串;
    }
    if (b == NULL) {
        b = &空字面量串;
    }
    cjk_string *结果 = 新字符串(a->len + b->len);
    memcpy(结果->data, a->data, sizeof(uint16_t) * (size_t)a->len);
    memcpy(结果->data + a->len, b->data, sizeof(uint16_t) * (size_t)b->len);
    return 结果;
}

cjk_string *cjk_str_trim(cjk_string *s) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), 0);
    }
    int32_t 起 = 0;
    int32_t 止 = s->len;
    while (起 < 止 && s->data[起] <= 0x20) {
        起++;
    }
    while (止 > 起 && s->data[止 - 1] <= 0x20) {
        止--;
    }
    cjk_string *结果 = 新字符串(止 - 起);
    memcpy(结果->data, s->data + 起, sizeof(uint16_t) * (size_t)(止 - 起));
    return 结果;
}

int32_t cjk_str_len(cjk_string *s, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), line);
    }
    return s->len;
}

int32_t cjk_str_is_empty(cjk_string *s, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), line);
    }
    return s->len == 0;
}

int32_t cjk_str_equal(cjk_string *a, cjk_string *b) {
    if (a == NULL || b == NULL) {
        return a == b;
    }
    if (a->len != b->len) {
        return 0;
    }
    return memcmp(a->data, b->data, sizeof(uint16_t) * (size_t)a->len) == 0;
}

int32_t cjk_parse_int(cjk_string *s, int32_t line) {
    if (s == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), line);
    }
    char 缓冲[64];
    int32_t n = s->len < 63 ? s->len : 63;
    for (int32_t i = 0; i < n; i++) {
        uint16_t 单元 = s->data[i];
        缓冲[i] = 单元 < 0x80 ? (char)单元 : '?';
    }
    缓冲[n] = '\0';
    char *止 = NULL;
    long 值 = strtol(缓冲, &止, 10);
    if (止 == 缓冲 || *止 != '\0') {
        char 消息[128];
        snprintf(消息, sizeof(消息), "For input string: \"%s\"", 缓冲);
        cjk_throw_message(&CJK_CLS_数字格式异常, 消息, line);
    }
    return (int32_t)值;
}

/* ---------------- 打印 ---------------- */

void cjk_print_str(cjk_string *s) {
    if (s == NULL) {
        fputs("null", stdout);
        return;
    }
    输出码元串(s->data, s->len);
}

void cjk_print_i32(int32_t v) {
    printf("%" PRId32, v);
}

void cjk_print_i64(int64_t v) {
    printf("%" PRId64, v);
}

void cjk_print_f32(float v) {
    char 缓冲[128];
    格式化浮点(缓冲, sizeof(缓冲), (double)v, 1);
    fputs(缓冲, stdout);
}

void cjk_print_f64(double v) {
    char 缓冲[128];
    格式化浮点(缓冲, sizeof(缓冲), v, 0);
    fputs(缓冲, stdout);
}

void cjk_print_bool(int32_t v) {
    fputs(v ? "true" : "false", stdout);
}

void cjk_print_char(uint16_t v) {
    if (v >= 0xD800 && v <= 0xDFFF) {
        fputc('?', stdout);
    } else {
        写码点(stdout, v);
    }
}

void cjk_newline(void) {
    fputc('\n', stdout);
}

/* ---------------- 整数除法 ---------------- */

int32_t cjk_div_i32(int32_t a, int32_t b, int32_t line) {
    if (b == 0) {
        cjk_throw_message(&CJK_CLS_算术异常, "/ by zero", line);
    }
    if (a == INT32_MIN && b == -1) {
        return INT32_MIN;
    }
    return a / b;
}

int32_t cjk_rem_i32(int32_t a, int32_t b, int32_t line) {
    if (b == 0) {
        cjk_throw_message(&CJK_CLS_算术异常, "/ by zero", line);
    }
    if (a == INT32_MIN && b == -1) {
        return 0;
    }
    return a % b;
}

int64_t cjk_div_i64(int64_t a, int64_t b, int32_t line) {
    if (b == 0) {
        cjk_throw_message(&CJK_CLS_算术异常, "/ by zero", line);
    }
    if (a == INT64_MIN && b == -1) {
        return INT64_MIN;
    }
    return a / b;
}

int64_t cjk_rem_i64(int64_t a, int64_t b, int32_t line) {
    if (b == 0) {
        cjk_throw_message(&CJK_CLS_算术异常, "/ by zero", line);
    }
    if (a == INT64_MIN && b == -1) {
        return 0;
    }
    return a % b;
}

/* ---------------- 数组 ---------------- */

cjk_array *cjk_array_new(int32_t len, int32_t kind, int32_t elem_size, int32_t line) {
    if (len < 0) {
        cjk_throw_message(&CJK_CLS_数组越界异常, "数组长度为负数", line);
    }
    if (elem_size <= 0) {
        elem_size = 1;
    }
    cjk_array *a = (cjk_array *)cjk_gc_alloc(sizeof(cjk_array));
    a->len = len;
    a->kind = kind;
    a->data = cjk_gc_alloc((size_t)(len > 0 ? len : 1) * (size_t)elem_size);
    return a;
}

cjk_array *cjk_array_from(int32_t kind, int32_t elem_size, int32_t len, const void *data) {
    cjk_array *a = cjk_array_new(len, kind, elem_size, 0);
    if (len > 0 && data != NULL) {
        memcpy(a->data, data, (size_t)elem_size * (size_t)len);
    }
    return a;
}

int32_t cjk_array_len(cjk_array *a, int32_t line) {
    if (a == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), line);
    }
    return a->len;
}

void *cjk_array_at(cjk_array *a, int32_t index, int32_t elem_size, int32_t line) {
    if (a == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), line);
    }
    if (index < 0 || index >= a->len) {
        char 消息[96];
        snprintf(消息, sizeof(消息), "Index %d out of bounds for length %d",
                 (int)index, (int)a->len);
        cjk_throw_message(&CJK_CLS_数组越界异常, 消息, line);
    }
    return (unsigned char *)a->data + (size_t)index * (size_t)elem_size;
}

void cjk_array_copy(cjk_array *src, int32_t src_pos, cjk_array *dst, int32_t dst_pos,
                    int32_t len, int32_t elem_size, int32_t line) {
    if (src == NULL || dst == NULL) {
        cjk_throw(cjk_new_exception(&CJK_CLS_空指针异常, NULL), line);
    }
    if (src_pos < 0 || dst_pos < 0 || len < 0
            || src_pos + len > src->len || dst_pos + len > dst->len) {
        cjk_throw_message(&CJK_CLS_数组越界异常, "数组复制范围超出", line);
    }
    if (len > 0) {
        memmove((unsigned char *)dst->data + (size_t)dst_pos * (size_t)elem_size,
                (unsigned char *)src->data + (size_t)src_pos * (size_t)elem_size,
                (size_t)elem_size * (size_t)len);
    }
}
