/*
 * 中文 Java 原生运行时（M3 最小集）
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

static uint16_t 空字面量[] = {'n', 'u', 'l', 'l'};
static cjk_string 空字面量串 = {4, 空字面量};

void cjk_rt_init(void) {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
}

void cjk_fatal(int32_t line, const char *message) {
    fflush(stdout);
    if (line > 0) {
        fprintf(stderr, "第 %d 行：%s\n", (int)line, message);
    } else {
        fprintf(stderr, "%s\n", message);
    }
    exit(1);
}

/* ---------------- UTF-8 / UTF-16 ---------------- */

static cjk_string *新字符串(int32_t len) {
    cjk_string *s = (cjk_string *)malloc(sizeof(cjk_string));
    s->len = len;
    s->data = (uint16_t *)malloc(sizeof(uint16_t) * (size_t)(len > 0 ? len : 1));
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
        return &空字面量串;
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
        cjk_fatal(line, "空指针异常：字符串为 null");
    }
    return s->len;
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
        cjk_fatal(line, "算术异常：整数除以零");
    }
    if (a == INT32_MIN && b == -1) {
        return INT32_MIN;
    }
    return a / b;
}

int32_t cjk_rem_i32(int32_t a, int32_t b, int32_t line) {
    if (b == 0) {
        cjk_fatal(line, "算术异常：整数取余除以零");
    }
    if (a == INT32_MIN && b == -1) {
        return 0;
    }
    return a % b;
}

int64_t cjk_div_i64(int64_t a, int64_t b, int32_t line) {
    if (b == 0) {
        cjk_fatal(line, "算术异常：长整数除以零");
    }
    if (a == INT64_MIN && b == -1) {
        return INT64_MIN;
    }
    return a / b;
}

int64_t cjk_rem_i64(int64_t a, int64_t b, int32_t line) {
    if (b == 0) {
        cjk_fatal(line, "算术异常：长整数取余除以零");
    }
    if (a == INT64_MIN && b == -1) {
        return 0;
    }
    return a % b;
}

/* ---------------- 数组 ---------------- */

cjk_array *cjk_array_new(int32_t len, int32_t kind, int32_t elem_size, int32_t line) {
    if (len < 0) {
        cjk_fatal(line, "数组长度为负数");
    }
    if (elem_size <= 0) {
        elem_size = 1;
    }
    cjk_array *a = (cjk_array *)malloc(sizeof(cjk_array));
    a->len = len;
    a->kind = kind;
    a->data = calloc((size_t)(len > 0 ? len : 1), (size_t)elem_size);
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
        cjk_fatal(line, "空指针异常：数组为 null");
    }
    return a->len;
}

void *cjk_array_at(cjk_array *a, int32_t index, int32_t elem_size, int32_t line) {
    if (a == NULL) {
        cjk_fatal(line, "空指针异常：数组为 null");
    }
    if (index < 0 || index >= a->len) {
        cjk_fatal(line, "数组越界异常：下标超出范围");
    }
    return (unsigned char *)a->data + (size_t)index * (size_t)elem_size;
}

void cjk_array_copy(cjk_array *src, int32_t src_pos, cjk_array *dst, int32_t dst_pos,
                    int32_t len, int32_t elem_size, int32_t line) {
    if (src == NULL || dst == NULL) {
        cjk_fatal(line, "空指针异常：数组复制遇到 null");
    }
    if (src_pos < 0 || dst_pos < 0 || len < 0
            || src_pos + len > src->len || dst_pos + len > dst->len) {
        cjk_fatal(line, "数组越界异常：数组复制范围超出");
    }
    if (len > 0) {
        memmove((unsigned char *)dst->data + (size_t)dst_pos * (size_t)elem_size,
                (unsigned char *)src->data + (size_t)src_pos * (size_t)elem_size,
                (size_t)elem_size * (size_t)len);
    }
}
