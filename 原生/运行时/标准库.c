/*
 * M5 标准库运行时：中文.标准库 10 个类的原生实现。
 *
 * 输出 / 文本 / 数值 / 集合工具 / 时间 / 文件 / 并发 / 网络 / 日志 / 数据。
 * 与 JDK 无法对齐的行为都在函数内给出中文诊断，不做静默降级。
 */
#include "cjkrt.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <process.h>

/* ================= 通用文本缓冲 ================= */

typedef struct {
    uint16_t *数据;
    int32_t 长;
    int32_t 容;
} 文本缓冲;

static void 缓冲初始化(文本缓冲 *b) {
    b->容 = 32;
    b->长 = 0;
    b->数据 = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)b->容);
}

static void 缓冲扩容(文本缓冲 *b, int32_t 需要) {
    if (需要 <= b->容) {
        return;
    }
    int32_t 新容 = b->容 * 2;
    while (新容 < 需要) {
        新容 *= 2;
    }
    uint16_t *新数据 = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)新容);
    for (int32_t i = 0; i < b->长; i++) {
        新数据[i] = b->数据[i];
    }
    b->数据 = 新数据;
    b->容 = 新容;
}

static void 缓冲追加字符(文本缓冲 *b, uint16_t c) {
    缓冲扩容(b, b->长 + 1);
    b->数据[b->长++] = c;
}

static void 缓冲追加ASCII(文本缓冲 *b, const char *s) {
    while (*s != 0) {
        缓冲追加字符(b, (uint16_t)(unsigned char)*s);
        s++;
    }
}

static void 缓冲追加文本(文本缓冲 *b, cjk_string *s) {
    if (s == NULL) {
        return;
    }
    缓冲扩容(b, b->长 + s->len);
    for (int32_t i = 0; i < s->len; i++) {
        b->数据[b->长++] = s->data[i];
    }
}

static cjk_string *缓冲成串(文本缓冲 *b) {
    cjk_string *s = (cjk_string *)cjk_gc_alloc(sizeof(cjk_string));
    s->len = b->长;
    s->data = b->数据;
    return s;
}

/* ================= 输出 ================= */

static void 写UTF8(FILE *f, cjk_string *s) {
    if (s == NULL) {
        fputs("null", f);
        return;
    }
    for (int32_t i = 0; i < s->len; i++) {
        uint32_t 码点 = s->data[i];
        if (码点 >= 0xD800 && 码点 <= 0xDBFF && i + 1 < s->len) {
            uint32_t 低 = s->data[i + 1];
            if (低 >= 0xDC00 && 低 <= 0xDFFF) {
                码点 = 0x10000 + ((码点 - 0xD800) << 10) + (低 - 0xDC00);
                i++;
            }
        }
        if (码点 < 0x80) {
            fputc((int)码点, f);
        } else if (码点 < 0x800) {
            fputc((int)(0xC0 | (码点 >> 6)), f);
            fputc((int)(0x80 | (码点 & 0x3F)), f);
        } else if (码点 < 0x10000) {
            fputc((int)(0xE0 | (码点 >> 12)), f);
            fputc((int)(0x80 | ((码点 >> 6) & 0x3F)), f);
            fputc((int)(0x80 | (码点 & 0x3F)), f);
        } else {
            fputc((int)(0xF0 | (码点 >> 18)), f);
            fputc((int)(0x80 | ((码点 >> 12) & 0x3F)), f);
            fputc((int)(0x80 | ((码点 >> 6) & 0x3F)), f);
            fputc((int)(0x80 | (码点 & 0x3F)), f);
        }
    }
}

static void 输出值们(FILE *f, int32_t n, cjk_value *values) {
    for (int32_t i = 0; i < n; i++) {
        写UTF8(f, cjk_value_to_string(values[i]));
    }
}

void cjk_print_values(int32_t n, cjk_value *values) {
    输出值们(stdout, n, values);
}

void cjk_println_values(int32_t n, cjk_value *values) {
    输出值们(stdout, n, values);
    fputc('\n', stdout);
}

void cjk_print_newline(void) {
    fputc('\n', stdout);
}

void cjk_eprintln_values(int32_t n, cjk_value *values) {
    输出值们(stderr, n, values);
    fputc('\n', stderr);
}

void cjk_output_format(cjk_string *tpl, int32_t n, cjk_value *values, int32_t line) {
    if (tpl == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "格式模板为空", line);
    }
    文本缓冲 b;
    缓冲初始化(&b);
    int32_t 用掉 = 0;
    for (int32_t i = 0; i < tpl->len; i++) {
        uint16_t c = tpl->data[i];
        if (c != '%' || i + 1 >= tpl->len) {
            缓冲追加字符(&b, c);
            continue;
        }
        uint16_t 说明 = tpl->data[++i];
        if (说明 == '%') {
            缓冲追加字符(&b, '%');
        } else if (说明 == 'n') {
            缓冲追加字符(&b, '\n');
        } else if (说明 == 's' || 说明 == 'd' || 说明 == 'f') {
            if (用掉 >= n) {
                cjk_throw_message(&CJK_CLS_非法参数异常, "格式参数不足", line);
            }
            cjk_value v = values[用掉++];
            if (说明 == 's') {
                缓冲追加文本(&b, cjk_value_to_string(v));
            } else if (说明 == 'd') {
                if (v.kind != CJK_V_I32 && v.kind != CJK_V_I64 && v.kind != CJK_V_CHAR) {
                    cjk_throw_message(&CJK_CLS_非法参数异常, "格式 %d 需要整数参数", line);
                }
                缓冲追加文本(&b, cjk_value_to_string(v));
            } else {
                if (v.kind != CJK_V_F32 && v.kind != CJK_V_F64
                        && v.kind != CJK_V_I32 && v.kind != CJK_V_I64) {
                    cjk_throw_message(&CJK_CLS_非法参数异常, "格式 %f 需要数值参数", line);
                }
                double 数 = v.kind == CJK_V_F32 || v.kind == CJK_V_F64
                        ? v.u.d : (double)v.u.i;
                char 暂[64];
                snprintf(暂, sizeof(暂), "%.6f", 数);
                缓冲追加ASCII(&b, 暂);
            }
        } else {
            char 消息[96];
            snprintf(消息, sizeof(消息), "格式模板暂不支持转换符「%c」", (char)说明);
            cjk_throw_message(&CJK_CLS_非法参数异常, 消息, line);
        }
    }
    写UTF8(stdout, 缓冲成串(&b));
    fputc('\n', stdout);
}

/* ================= 文本 / 数值 / 集合工具 ================= */

int32_t cjk_str_is_null_or_empty(cjk_string *s) {
    return s == NULL || s->len == 0;
}

static int32_t 是空白码元(uint16_t c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B
            || c == 0x00A0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
            || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}

int32_t cjk_str_is_null_or_blank(cjk_string *s) {
    if (s == NULL) {
        return 1;
    }
    for (int32_t i = 0; i < s->len; i++) {
        if (!是空白码元(s->data[i])) {
            return 0;
        }
    }
    return 1;
}

/* 对齐 String.strip：按 Unicode 空白裁剪。 */
cjk_string *cjk_str_strip(cjk_string *s, int32_t line) {
    if (s == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "文本为空", line);
    }
    int32_t 起 = 0;
    int32_t 止 = s->len;
    while (起 < 止 && 是空白码元(s->data[起])) {
        起++;
    }
    while (止 > 起 && 是空白码元(s->data[止 - 1])) {
        止--;
    }
    return cjk_str_substring(s, 起, 止, line);
}

cjk_string *cjk_str_join_values(cjk_string *sep, int32_t n, cjk_value *values) {
    文本缓冲 b;
    缓冲初始化(&b);
    for (int32_t i = 0; i < n; i++) {
        if (i > 0) {
            缓冲追加文本(&b, sep);
        }
        缓冲追加文本(&b, cjk_value_to_string(values[i]));
    }
    return 缓冲成串(&b);
}

int32_t cjk_str_is_integer(cjk_string *s) {
    if (s == NULL) {
        return 0;
    }
    int32_t 起 = 0;
    int32_t 止 = s->len;
    while (起 < 止 && 是空白码元(s->data[起])) {
        起++;
    }
    while (止 > 起 && 是空白码元(s->data[止 - 1])) {
        止--;
    }
    if (起 >= 止) {
        return 0;
    }
    int32_t i = 起;
    if (s->data[i] == '+' || s->data[i] == '-') {
        i++;
    }
    if (i >= 止) {
        return 0;
    }
    int64_t 值 = 0;
    for (; i < 止; i++) {
        uint16_t c = s->data[i];
        if (c < '0' || c > '9') {
            return 0;
        }
        值 = 值 * 10 + (c - '0');
        if (值 > 2147483648LL) {
            return 0;
        }
    }
    if (值 > 2147483647LL && s->data[起] != '-') {
        return 0;
    }
    return 1;
}

static void 播种随机(void) {
    static int32_t 已播种 = 0;
    if (!已播种) {
        srand((unsigned int)(cjk_now_millis() & 0x7FFFFFFF));
        已播种 = 1;
    }
}

int32_t cjk_random_int(int32_t hi, int32_t line) {
    if (hi <= 0) {
        cjk_throw_message(&CJK_CLS_非法参数异常, "随机整数上限必须大于 0", line);
    }
    播种随机();
    return (int32_t)(rand() % hi) + 1;
}

int32_t cjk_random_range(int32_t lo, int32_t hi, int32_t line) {
    if (lo > hi) {
        cjk_throw_message(&CJK_CLS_非法参数异常, "随机整数下限不能大于上限", line);
    }
    uint32_t 宽 = (uint32_t)((int64_t)hi - (int64_t)lo) + 1u;
    播种随机();
    if (宽 == 0u) {
        /* 覆盖整个 32 位区间：拼接两次 rand 得到 32 位随机数。 */
        uint32_t 随机 = ((uint32_t)rand() << 16) ^ (uint32_t)rand();
        return (int32_t)(随机 + (uint32_t)lo);
    }
    return (int32_t)((uint32_t)rand() % 宽) + lo;
}

/* ================= 时间 ================= */

typedef struct {
    cjk_class *cls;
    int32_t y, m, d;
} 日期对象;

typedef struct {
    cjk_class *cls;
    int32_t y, m, d, H, M, S;
} 时刻对象;

static cjk_string *日期转文本(cjk_object *self);
static cjk_string *时刻转文本(cjk_object *self);

cjk_class CJK_CLS_本地日期 = {30, "本地日期", &CJK_CLS_对象, 0, NULL, NULL, 0, 日期转文本};
cjk_class CJK_CLS_本地日期时间 = {31, "本地日期时间", &CJK_CLS_对象, 0, NULL, NULL, 0, 时刻转文本};

static int64_t 天序号(int32_t y, int32_t m, int32_t d) {
    y -= m <= 2;
    int64_t era = (y >= 0 ? y : y - 399) / 400;
    uint32_t yoe = (uint32_t)(y - era * 400);
    uint32_t doy = (uint32_t)((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1);
    uint32_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + (int64_t)doe - 719468;
}

static void 从序号(int64_t z, int32_t *y, int32_t *m, int32_t *d) {
    z += 719468;
    int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    uint32_t doe = (uint32_t)(z - era * 146097);
    uint32_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    int32_t yy = (int32_t)(yoe + era * 400);
    uint32_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    uint32_t mp = (5 * doy + 2) / 153;
    *d = (int32_t)(doy - (153 * mp + 2) / 5 + 1);
    *m = (int32_t)(mp < 10 ? mp + 3 : mp - 9);
    *y = yy + (*m <= 2);
}

static void 取本地时刻(struct tm *out) {
    time_t 现在 = time(NULL);
    struct tm *本地 = localtime(&现在);
    if (本地 == NULL) {
        memset(out, 0, sizeof(*out));
        return;
    }
    *out = *本地;
}

int64_t cjk_now_millis(void) {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | (uint64_t)ft.dwLowDateTime;
    return (int64_t)(t / 10000u) - 11644473600000LL;
}

cjk_object *cjk_date_today(void) {
    struct tm 现在;
    取本地时刻(&现在);
    日期对象 *d = (日期对象 *)cjk_gc_alloc(sizeof(日期对象));
    d->cls = &CJK_CLS_本地日期;
    d->y = 现在.tm_year + 1900;
    d->m = 现在.tm_mon + 1;
    d->d = 现在.tm_mday;
    return (cjk_object *)d;
}

cjk_object *cjk_datetime_now(void) {
    struct tm 现在;
    取本地时刻(&现在);
    时刻对象 *t = (时刻对象 *)cjk_gc_alloc(sizeof(时刻对象));
    t->cls = &CJK_CLS_本地日期时间;
    t->y = 现在.tm_year + 1900;
    t->m = 现在.tm_mon + 1;
    t->d = 现在.tm_mday;
    t->H = 现在.tm_hour;
    t->M = 现在.tm_min;
    t->S = 现在.tm_sec;
    return (cjk_object *)t;
}

static cjk_string *日期转文本(cjk_object *self) {
    日期对象 *d = (日期对象 *)self;
    char 暂[32];
    snprintf(暂, sizeof(暂), "%04d-%02d-%02d", d->y, d->m, d->d);
    return cjk_str_from_utf8(暂);
}

static cjk_string *时刻转文本(cjk_object *self) {
    时刻对象 *t = (时刻对象 *)self;
    char 暂[48];
    if (t->S != 0) {
        snprintf(暂, sizeof(暂), "%04d-%02d-%02dT%02d:%02d:%02d",
                 t->y, t->m, t->d, t->H, t->M, t->S);
    } else {
        snprintf(暂, sizeof(暂), "%04d-%02d-%02dT%02d:%02d", t->y, t->m, t->d, t->H, t->M);
    }
    return cjk_str_from_utf8(暂);
}

int64_t cjk_date_between(cjk_object *a, cjk_object *b) {
    if (a == NULL || b == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "日期为空", 0);
    }
    日期对象 *甲 = (日期对象 *)a;
    日期对象 *乙 = (日期对象 *)b;
    return 天序号(乙->y, 乙->m, 乙->d) - 天序号(甲->y, 甲->m, 甲->d);
}

cjk_object *cjk_date_plus_days(cjk_object *d, int64_t days) {
    if (d == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "日期为空", 0);
    }
    日期对象 *源 = (日期对象 *)d;
    int64_t 序号 = 天序号(源->y, 源->m, 源->d) + days;
    日期对象 *结果 = (日期对象 *)cjk_gc_alloc(sizeof(日期对象));
    结果->cls = &CJK_CLS_本地日期;
    从序号(序号, &结果->y, &结果->m, &结果->d);
    return (cjk_object *)结果;
}

static void 追加补零(文本缓冲 *b, int32_t 值, int32_t 位数) {
    char 暂[16];
    if (位数 == 2) {
        snprintf(暂, sizeof(暂), "%02d", 值);
    } else if (位数 == 3) {
        snprintf(暂, sizeof(暂), "%03d", 值);
    } else {
        snprintf(暂, sizeof(暂), "%d", 值);
    }
    缓冲追加ASCII(b, 暂);
}

cjk_string *cjk_datetime_format(cjk_object *dt, cjk_string *pattern, int32_t line) {
    if (dt == NULL || pattern == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "时间为空", line);
    }
    int32_t y, m, d, H = 0, M = 0, S = 0;
    if (dt->cls == &CJK_CLS_本地日期) {
        日期对象 *x = (日期对象 *)dt;
        y = x->y;
        m = x->m;
        d = x->d;
    } else if (dt->cls == &CJK_CLS_本地日期时间) {
        时刻对象 *x = (时刻对象 *)dt;
        y = x->y;
        m = x->m;
        d = x->d;
        H = x->H;
        M = x->M;
        S = x->S;
    } else {
        cjk_throw_message(&CJK_CLS_非法参数异常, "时间格式化只支持本地日期或本地日期时间", line);
    }
    文本缓冲 b;
    缓冲初始化(&b);
    for (int32_t i = 0; i < pattern->len;) {
        uint16_t c = pattern->data[i];
        if (c == '\'') {
            i++;
            while (i < pattern->len) {
                if (pattern->data[i] == '\'') {
                    if (i + 1 < pattern->len && pattern->data[i + 1] == '\'') {
                        缓冲追加字符(&b, '\'');
                        i += 2;
                        continue;
                    }
                    i++;
                    break;
                }
                缓冲追加字符(&b, pattern->data[i++]);
            }
            continue;
        }
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
            缓冲追加字符(&b, (uint16_t)c);
            i++;
            continue;
        }
        int32_t 数 = 0;
        while (i < pattern->len && pattern->data[i] == c) {
            数++;
            i++;
        }
        switch (c) {
            case 'y':
                追加补零(&b, 数 == 2 ? y % 100 : y, 数 == 2 ? 2 : 0);
                break;
            case 'M':
                追加补零(&b, m, 数 >= 2 ? 2 : 0);
                break;
            case 'd':
                追加补零(&b, d, 数 >= 2 ? 2 : 0);
                break;
            case 'H':
                追加补零(&b, H, 数 >= 2 ? 2 : 0);
                break;
            case 'm':
                追加补零(&b, M, 数 >= 2 ? 2 : 0);
                break;
            case 's':
                追加补零(&b, S, 数 >= 2 ? 2 : 0);
                break;
            case 'S':
                追加补零(&b, 0, 数 >= 2 ? (数 >= 3 ? 3 : 2) : 0);
                break;
            default: {
                char 消息[96];
                snprintf(消息, sizeof(消息), "时间格式化模板暂不支持字母「%c」", (char)c);
                cjk_throw_message(&CJK_CLS_非法参数异常, 消息, line);
            }
        }
    }
    return 缓冲成串(&b);
}

/* ================= 并发 ================= */

typedef struct {
    cjk_class *cls;
    CRITICAL_SECTION 锁;
    CONDITION_VARIABLE 条件;
    int32_t 完成;
    cjk_value 结果;
} 未来对象;

typedef struct 任务节点 {
    struct 任务节点 *下一个;
    cjk_object *函数;
    cjk_callback 回调;
    未来对象 *未来;
} 任务节点;

typedef struct {
    cjk_class *cls;
    int32_t 线程数;
    int32_t 已关闭;
    HANDLE *线程;
    CRITICAL_SECTION 锁;
    CONDITION_VARIABLE 条件;
    任务节点 *头;
    任务节点 *尾;
    int32_t 序号;
} 线程池对象;

typedef struct 日志对象 {
    cjk_class *cls;
    cjk_string *名称;
    int32_t 级别;
    struct 日志对象 *下一日志;
} 日志对象;

typedef struct {
    cjk_class *cls;
    int32_t 状态;
    cjk_string *正文;
} 响应对象;

static cjk_string *池转文本(cjk_object *self);
static cjk_string *未来转文本(cjk_object *self);
static cjk_string *日志转文本(cjk_object *self);
static cjk_string *响应转文本(cjk_object *self);

cjk_class CJK_CLS_线程池 = {33, "线程池", &CJK_CLS_对象, 0, NULL, NULL, 0, 池转文本};
cjk_class CJK_CLS_未来 = {34, "未来", &CJK_CLS_对象, 0, NULL, NULL, 0, 未来转文本};
cjk_class CJK_CLS_日志记录器 = {35, "日志记录器", &CJK_CLS_对象, 0, NULL, NULL, 0, 日志转文本};
cjk_class CJK_CLS_响应 = {32, "响应", &CJK_CLS_对象, 0, NULL, NULL, 0, 响应转文本};

static cjk_string *池转文本(cjk_object *self) {
    线程池对象 *p = (线程池对象 *)self;
    char 暂[48];
    snprintf(暂, sizeof(暂), "线程池(%d)", p->线程数);
    return cjk_str_from_utf8(暂);
}

static cjk_string *未来转文本(cjk_object *self) {
    (void)self;
    return cjk_str_from_utf8("未来");
}

static cjk_string *日志转文本(cjk_object *self) {
    日志对象 *l = (日志对象 *)self;
    return l->名称 == NULL ? cjk_str_from_utf8("") : l->名称;
}

static cjk_string *响应转文本(cjk_object *self) {
    响应对象 *r = (响应对象 *)self;
    char 暂[32];
    snprintf(暂, sizeof(暂), "响应(%d)", r->状态);
    return cjk_str_from_utf8(暂);
}

static __thread const char *当前线程名 = "main";

static unsigned __stdcall 工作线程(void *参数) {
    线程池对象 *p = (线程池对象 *)参数;
    cjk_gc_enter_thread();
    char 名[48];
    snprintf(名, sizeof(名), "pool-1-thread-%d",
             (int)InterlockedIncrement((volatile LONG *)&p->序号));
    当前线程名 = 名;
    for (;;) {
        EnterCriticalSection(&p->锁);
        while (p->头 == NULL && !p->已关闭) {
            SleepConditionVariableCS(&p->条件, &p->锁, INFINITE);
        }
        if (p->头 == NULL && p->已关闭) {
            LeaveCriticalSection(&p->锁);
            break;
        }
        任务节点 *任务 = p->头;
        p->头 = 任务->下一个;
        if (p->头 == NULL) {
            p->尾 = NULL;
        }
        LeaveCriticalSection(&p->锁);

        cjk_value 结果 = 任务->回调(任务->函数, cjk_v_null(), cjk_v_null(), 0);
        if (任务->未来 != NULL) {
            未来对象 *f = 任务->未来;
            EnterCriticalSection(&f->锁);
            f->结果 = 结果;
            f->完成 = 1;
            WakeAllConditionVariable(&f->条件);
            LeaveCriticalSection(&f->锁);
        }
    }
    cjk_gc_leave_thread();
    return 0;
}

cjk_object *cjk_pool_new(int32_t size, int32_t line) {
    if (size <= 0) {
        cjk_throw_message(&CJK_CLS_非法参数异常, "线程数必须大于 0", line);
    }
    线程池对象 *p = (线程池对象 *)cjk_gc_alloc(sizeof(线程池对象));
    p->cls = &CJK_CLS_线程池;
    p->线程数 = size;
    p->已关闭 = 0;
    p->头 = NULL;
    p->尾 = NULL;
    p->序号 = 0;
    InitializeCriticalSection(&p->锁);
    InitializeConditionVariable(&p->条件);
    p->线程 = (HANDLE *)cjk_gc_alloc(sizeof(HANDLE) * (size_t)size);
    for (int32_t i = 0; i < size; i++) {
        p->线程[i] = (HANDLE)_beginthreadex(NULL, 0, 工作线程, p, 0, NULL);
    }
    return (cjk_object *)p;
}

static void 入队(线程池对象 *p, 任务节点 *任务) {
    EnterCriticalSection(&p->锁);
    任务->下一个 = NULL;
    if (p->尾 == NULL) {
        p->头 = 任务;
    } else {
        p->尾->下一个 = 任务;
    }
    p->尾 = 任务;
    WakeAllConditionVariable(&p->条件);
    LeaveCriticalSection(&p->锁);
}

void cjk_pool_execute(cjk_object *pool, cjk_object *fn, cjk_callback cb) {
    线程池对象 *p = (线程池对象 *)pool;
    任务节点 *任务 = (任务节点 *)cjk_gc_alloc(sizeof(任务节点));
    任务->函数 = fn;
    任务->回调 = cb;
    任务->未来 = NULL;
    入队(p, 任务);
}

cjk_object *cjk_pool_submit(cjk_object *pool, cjk_object *fn, cjk_callback cb) {
    线程池对象 *p = (线程池对象 *)pool;
    未来对象 *f = (未来对象 *)cjk_gc_alloc(sizeof(未来对象));
    f->cls = &CJK_CLS_未来;
    f->完成 = 0;
    f->结果 = cjk_v_null();
    InitializeCriticalSection(&f->锁);
    InitializeConditionVariable(&f->条件);
    任务节点 *任务 = (任务节点 *)cjk_gc_alloc(sizeof(任务节点));
    任务->函数 = fn;
    任务->回调 = cb;
    任务->未来 = f;
    入队(p, 任务);
    return (cjk_object *)f;
}

cjk_value cjk_future_get(cjk_object *future, int32_t line) {
    if (future == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "未来为空", line);
    }
    未来对象 *f = (未来对象 *)future;
    EnterCriticalSection(&f->锁);
    while (!f->完成) {
        SleepConditionVariableCS(&f->条件, &f->锁, INFINITE);
    }
    cjk_value 结果 = f->结果;
    LeaveCriticalSection(&f->锁);
    return 结果;
}

void cjk_pool_shutdown(cjk_object *pool) {
    if (pool == NULL) {
        return;
    }
    线程池对象 *p = (线程池对象 *)pool;
    EnterCriticalSection(&p->锁);
    p->已关闭 = 1;
    WakeAllConditionVariable(&p->条件);
    LeaveCriticalSection(&p->锁);
    for (int32_t i = 0; i < p->线程数; i++) {
        if (p->线程[i] != NULL) {
            WaitForSingleObject(p->线程[i], INFINITE);
            CloseHandle(p->线程[i]);
            p->线程[i] = NULL;
        }
    }
}

void cjk_sleep(int64_t millis) {
    if (millis <= 0) {
        return;
    }
    Sleep((DWORD)millis);
}

cjk_string *cjk_thread_name(void) {
    return cjk_str_from_utf8(当前线程名);
}

/* ================= 网络 ================= */

static wchar_t *转宽串(cjk_string *s) {
    int32_t 长 = s == NULL ? 0 : s->len;
    wchar_t *w = (wchar_t *)cjk_gc_alloc(sizeof(wchar_t) * (size_t)(长 + 1));
    for (int32_t i = 0; i < 长; i++) {
        w[i] = (wchar_t)s->data[i];
    }
    w[长] = 0;
    return w;
}

static void 网络失败(const char *原因, cjk_string *url, int32_t line) {
    文本缓冲 b;
    缓冲初始化(&b);
    缓冲追加ASCII(&b, 原因);
    缓冲追加文本(&b, url);
    cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 缓冲成串(&b)), line);
}

static void 解析网址(cjk_string *url, int32_t *安全, wchar_t *主机, size_t 主容量,
                     int32_t *端口, wchar_t *路径, size_t 路容量, int32_t line) {
    wchar_t *w = 转宽串(url);
    int32_t 起 = 0;
    *安全 = 0;
    if (wcsncmp(w, L"https://", 8) == 0) {
        *安全 = 1;
        起 = 8;
    } else if (wcsncmp(w, L"http://", 7) == 0) {
        起 = 7;
    }
    int32_t 终 = 起;
    while (w[终] != 0 && w[终] != L'/' && w[终] != L':') {
        终++;
    }
    size_t 主长 = (size_t)(终 - 起);
    if (主长 == 0 || 主长 + 1 >= 主容量) {
        网络失败("网址无效：", url, line);
    }
    wcsncpy(主机, w + 起, 主长);
    主机[主长] = 0;
    *端口 = *安全 ? 443 : 80;
    int32_t i = 终;
    if (w[i] == L':') {
        i++;
        int32_t 端口值 = 0;
        while (w[i] >= L'0' && w[i] <= L'9') {
            端口值 = 端口值 * 10 + (int32_t)(w[i] - L'0');
            i++;
        }
        if (端口值 <= 0 || 端口值 > 65535) {
            网络失败("网址端口无效：", url, line);
        }
        *端口 = 端口值;
    }
    size_t 路长 = wcslen(w + i);
    if (路长 == 0) {
        wcscpy(路径, L"/");
    } else if (路长 + 1 >= 路容量) {
        网络失败("网址路径过长：", url, line);
    } else {
        wcscpy(路径, w + i);
    }
}

static void HTTP请求(cjk_string *url, int32_t *状态, cjk_string **正文, int32_t line) {
    wchar_t 主机[512];
    wchar_t 路径[2048];
    int32_t 安全 = 0;
    int32_t 端口 = 80;
    解析网址(url, &安全, 主机, 512, &端口, 路径, 2048, line);

    HINTERNET 会话 = WinHttpOpen(L"cjc-native/1.0",
                                 WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                 WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (会话 == NULL) {
        网络失败("无法初始化网络会话：", url, line);
    }
    WinHttpSetTimeouts(会话, 5000, 5000, 10000, 10000);
    HINTERNET 连接 = WinHttpConnect(会话, 主机, (INTERNET_PORT)端口, 0);
    if (连接 == NULL) {
        WinHttpCloseHandle(会话);
        网络失败("无法连接主机：", url, line);
    }
    HINTERNET 请求 = WinHttpOpenRequest(连接, L"GET", 路径, NULL, WINHTTP_NO_REFERER,
                                        WINHTTP_DEFAULT_ACCEPT_TYPES,
                                        安全 ? WINHTTP_FLAG_SECURE : 0);
    if (请求 == NULL) {
        WinHttpCloseHandle(连接);
        WinHttpCloseHandle(会话);
        网络失败("无法创建请求：", url, line);
    }
    DWORD 重定向 = WINHTTP_OPTION_REDIRECT_POLICY_ALWAYS;
    WinHttpSetOption(请求, WINHTTP_OPTION_REDIRECT_POLICY, &重定向, sizeof(重定向));

    BOOL 成功 = WinHttpSendRequest(请求, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                   WINHTTP_NO_REQUEST_DATA, 0, 0, 0);
    if (成功) {
        成功 = WinHttpReceiveResponse(请求, NULL);
    }
    if (!成功) {
        WinHttpCloseHandle(请求);
        WinHttpCloseHandle(连接);
        WinHttpCloseHandle(会话);
        网络失败("网络请求失败：", url, line);
    }
    DWORD 状态码 = 0;
    DWORD 长 = sizeof(状态码);
    WinHttpQueryHeaders(请求, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &状态码, &长, WINHTTP_NO_HEADER_INDEX);

    size_t 容量 = 8192;
    size_t 已读 = 0;
    char *数据 = (char *)cjk_gc_alloc(容量);
    for (;;) {
        DWORD 本块 = 0;
        if (!WinHttpReadData(请求, 数据 + 已读, (DWORD)(容量 - 已读 - 1), &本块)) {
            break;
        }
        if (本块 == 0) {
            break;
        }
        已读 += 本块;
        if (已读 + 1 >= 容量) {
            容量 *= 2;
            char *新数据 = (char *)cjk_gc_alloc(容量);
            memcpy(新数据, 数据, 已读);
            数据 = 新数据;
        }
    }
    数据[已读] = 0;
    WinHttpCloseHandle(请求);
    WinHttpCloseHandle(连接);
    WinHttpCloseHandle(会话);
    *状态 = (int32_t)状态码;
    *正文 = cjk_str_from_utf8(数据);
}

cjk_string *cjk_http_get_text(cjk_string *url, int32_t line) {
    int32_t 状态 = 0;
    cjk_string *正文 = NULL;
    HTTP请求(url, &状态, &正文, line);
    return 正文 == NULL ? cjk_str_from_utf8("") : 正文;
}

int32_t cjk_http_status(cjk_string *url, int32_t line) {
    int32_t 状态 = 0;
    cjk_string *正文 = NULL;
    HTTP请求(url, &状态, &正文, line);
    return 状态;
}

cjk_object *cjk_http_get(cjk_string *url, int32_t line) {
    响应对象 *r = (响应对象 *)cjk_gc_alloc(sizeof(响应对象));
    r->cls = &CJK_CLS_响应;
    r->正文 = NULL;
    HTTP请求(url, &r->状态, &r->正文, line);
    if (r->正文 == NULL) {
        r->正文 = cjk_str_from_utf8("");
    }
    return (cjk_object *)r;
}

int32_t cjk_http_response_status(cjk_object *resp) {
    if (resp == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "响应为空", 0);
    }
    return ((响应对象 *)resp)->状态;
}

cjk_string *cjk_http_response_body(cjk_object *resp) {
    if (resp == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "响应为空", 0);
    }
    cjk_string *正文 = ((响应对象 *)resp)->正文;
    return 正文 == NULL ? cjk_str_from_utf8("") : 正文;
}

void cjk_http_download(cjk_string *url, cjk_object *path, int32_t line) {
    cjk_string *正文 = cjk_http_get_text(url, line);
    cjk_files_write_text_parents(path, 正文, line);
}

/* ================= 日志 ================= */

enum {
    级别_全部 = 0,
    级别_调试 = 1,
    级别_信息 = 2,
    级别_警告 = 3,
    级别_错误 = 4,
    级别_关闭 = 5
};

static cjk_object *新建日志器(cjk_string *name, int32_t level) {
    日志对象 *l = (日志对象 *)cjk_gc_alloc(sizeof(日志对象));
    l->cls = &CJK_CLS_日志记录器;
    l->名称 = name;
    l->级别 = level;
    return (cjk_object *)l;
}

/* 对齐 Logger.getLogger：同名日志器是同一实例。 */
static 日志对象 *日志表 = NULL;
static int32_t 日志表已登记 = 0;

static int32_t 文本相同(cjk_string *甲, cjk_string *乙) {
    if (甲 == 乙) {
        return 1;
    }
    if (甲 == NULL || 乙 == NULL || 甲->len != 乙->len) {
        return 0;
    }
    return memcmp(甲->data, 乙->data, sizeof(uint16_t) * (size_t)甲->len) == 0;
}

static 日志对象 *取或建日志器(cjk_string *name) {
    for (日志对象 *项 = 日志表; 项 != NULL; 项 = 项->下一日志) {
        if (文本相同(项->名称, name)) {
            return 项;
        }
    }
    if (!日志表已登记) {
        日志表已登记 = 1;
        cjk_gc_add_root(&日志表, sizeof(日志表));
    }
    日志对象 *新项 = (日志对象 *)新建日志器(name, 级别_信息);
    新项->下一日志 = 日志表;
    日志表 = 新项;
    return 新项;
}

cjk_object *cjk_logger_get(cjk_string *name) {
    return (cjk_object *)取或建日志器(name);
}

cjk_object *cjk_logger_console(cjk_string *name) {
    日志对象 *l = 取或建日志器(name);
    l->级别 = 级别_信息;
    return (cjk_object *)l;
}

static const char *级别名(int32_t level) {
    switch (level) {
        case 级别_调试: return "FINE";
        case 级别_信息: return "INFO";
        case 级别_警告: return "WARNING";
        case 级别_错误: return "SEVERE";
        default: return "INFO";
    }
}

void cjk_logger_log(cjk_object *logger, int32_t level, cjk_value msg, int32_t line) {
    if (logger == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "日志器为空", line);
    }
    日志对象 *l = (日志对象 *)logger;
    if (l->级别 == 级别_关闭 || level < l->级别) {
        return;
    }
    char 时间[32];
    time_t 现在 = time(NULL);
    struct tm *本地 = localtime(&现在);
    if (本地 == NULL) {
        strcpy(时间, "0000-00-00 00:00:00");
    } else {
        strftime(时间, sizeof(时间), "%Y-%m-%d %H:%M:%S", 本地);
    }
    fprintf(stderr, "%s %s ", 时间, 级别名(level));
    写UTF8(stderr, cjk_value_to_string(msg));
    fputc('\n', stderr);
}

void cjk_logger_set_level(cjk_object *logger, int32_t level) {
    if (logger == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "日志器为空", 0);
    }
    if (level < 级别_全部 || level > 级别_关闭) {
        cjk_throw_message(&CJK_CLS_非法参数异常, "日志级别超出范围", 0);
    }
    ((日志对象 *)logger)->级别 = level;
}

void cjk_logger_set_level_name(cjk_object *logger, cjk_string *name, int32_t line) {
    if (name == NULL) {
        cjk_throw_message(&CJK_CLS_空指针异常, "日志级别名称为空", line);
    }
    char 暂[32];
    int32_t 长 = name->len < 31 ? name->len : 31;
    for (int32_t i = 0; i < 长; i++) {
        暂[i] = (char)name->data[i];
    }
    暂[长] = 0;
    int32_t 级别;
    if (strcmp(暂, "ALL") == 0) {
        级别 = 级别_全部;
    } else if (strcmp(暂, "FINE") == 0) {
        级别 = 级别_调试;
    } else if (strcmp(暂, "INFO") == 0) {
        级别 = 级别_信息;
    } else if (strcmp(暂, "WARNING") == 0) {
        级别 = 级别_警告;
    } else if (strcmp(暂, "SEVERE") == 0) {
        级别 = 级别_错误;
    } else if (strcmp(暂, "OFF") == 0) {
        级别 = 级别_关闭;
    } else {
        cjk_throw_message(&CJK_CLS_非法参数异常, "未知的日志级别名称", line);
        return;
    }
    cjk_logger_set_level(logger, 级别);
}

/* ================= 数据（JSON） ================= */

static void JSON缩进(文本缓冲 *b, int32_t 深度) {
    缓冲追加字符(b, '\n');
    for (int32_t i = 0; i < 深度 * 2; i++) {
        缓冲追加字符(b, ' ');
    }
}

static void JSON字符串(文本缓冲 *b, cjk_string *s) {
    缓冲追加字符(b, '"');
    if (s != NULL) {
        for (int32_t i = 0; i < s->len; i++) {
            uint16_t c = s->data[i];
            switch (c) {
                case '"': 缓冲追加ASCII(b, "\\\""); break;
                case '\\': 缓冲追加ASCII(b, "\\\\"); break;
                case '\n': 缓冲追加ASCII(b, "\\n"); break;
                case '\r': 缓冲追加ASCII(b, "\\r"); break;
                case '\t': 缓冲追加ASCII(b, "\\t"); break;
                case '\b': 缓冲追加ASCII(b, "\\b"); break;
                case '\f': 缓冲追加ASCII(b, "\\f"); break;
                default:
                    if (c < 0x20) {
                        char 暂[8];
                        snprintf(暂, sizeof(暂), "\\u%04x", (unsigned)c);
                        缓冲追加ASCII(b, 暂);
                    } else {
                        缓冲追加字符(b, c);
                    }
            }
        }
    }
    缓冲追加字符(b, '"');
}

static void JSON值(文本缓冲 *b, cjk_value v, int32_t pretty, int32_t 深度);

static void JSON对象(文本缓冲 *b, cjk_object *obj, int32_t pretty, int32_t 深度) {
    if (obj == NULL) {
        缓冲追加ASCII(b, "null");
        return;
    }
    if (obj->cls == &CJK_CLS_清单 || obj->cls == &CJK_CLS_集 || obj->cls == &CJK_CLS_流) {
        int32_t 数 = cjk_list_size(obj);
        缓冲追加字符(b, '[');
        for (int32_t i = 0; i < 数; i++) {
            if (i > 0) {
                缓冲追加字符(b, ',');
            }
            if (pretty) {
                JSON缩进(b, 深度 + 1);
            }
            JSON值(b, cjk_list_get(obj, i, 0), pretty, 深度 + 1);
        }
        if (pretty && 数 > 0) {
            JSON缩进(b, 深度);
        }
        缓冲追加字符(b, ']');
        return;
    }
    if (obj->cls == &CJK_CLS_键值表) {
        int32_t 数 = cjk_map_size(obj);
        缓冲追加字符(b, '{');
        if (数 > 0) {
            cjk_object *键们 = cjk_map_keys(obj);
            for (int32_t i = 0; i < 数; i++) {
                if (i > 0) {
                    缓冲追加字符(b, ',');
                }
                if (pretty) {
                    JSON缩进(b, 深度 + 1);
                }
                cjk_value 键 = cjk_list_get(键们, i, 0);
                JSON字符串(b, cjk_value_to_string(键));
                缓冲追加字符(b, ':');
                if (pretty) {
                    缓冲追加字符(b, ' ');
                }
                JSON值(b, cjk_map_get(obj, 键), pretty, 深度 + 1);
            }
            if (pretty) {
                JSON缩进(b, 深度);
            }
        }
        缓冲追加字符(b, '}');
        return;
    }
    JSON字符串(b, cjk_object_to_string(obj));
}

static void JSON值(文本缓冲 *b, cjk_value v, int32_t pretty, int32_t 深度) {
    switch (v.kind) {
        case CJK_V_NULL:
            缓冲追加ASCII(b, "null");
            break;
        case CJK_V_I32:
        case CJK_V_I64:
            缓冲追加文本(b, cjk_value_to_string(v));
            break;
        case CJK_V_CHAR:
            JSON字符串(b, cjk_value_to_string(v));
            break;
        case CJK_V_BOOL:
            缓冲追加ASCII(b, v.u.i != 0 ? "true" : "false");
            break;
        case CJK_V_F32:
        case CJK_V_F64: {
            double 数 = v.u.d;
            if (isnan(数) || isinf(数)) {
                缓冲追加ASCII(b, "null");
            } else {
                缓冲追加文本(b, cjk_value_to_string(v));
            }
            break;
        }
        case CJK_V_STR:
            if (v.u.p == NULL) {
                缓冲追加ASCII(b, "null");
            } else {
                JSON字符串(b, (cjk_string *)v.u.p);
            }
            break;
        case CJK_V_REF:
            JSON对象(b, (cjk_object *)v.u.p, pretty, 深度);
            break;
        default:
            缓冲追加ASCII(b, "null");
    }
}

cjk_string *cjk_json_encode(cjk_value v, int32_t pretty, int32_t line) {
    (void)line;
    文本缓冲 b;
    缓冲初始化(&b);
    JSON值(&b, v, pretty, 0);
    return 缓冲成串(&b);
}

/*
 * 原生数组的 JSON 编码。C 生成器按静态元素类型传入 kind / elem_size；
 * text_ref 为 1 表示 REF 元素是 cjk_string*，否则是 cjk_object*。
 */
cjk_string *cjk_json_encode_array(cjk_array *a, int32_t elem_kind, int32_t elem_size,
                                  int32_t text_ref, int32_t pretty, int32_t line) {
    if (a == NULL) {
        return cjk_str_from_utf8("null");
    }
    文本缓冲 b;
    缓冲初始化(&b);
    缓冲追加字符(&b, '[');
    for (int32_t i = 0; i < a->len; i++) {
        if (i > 0) {
            缓冲追加字符(&b, ',');
        }
        if (pretty) {
            JSON缩进(&b, 1);
        }
        const unsigned char *槽 = (const unsigned char *)a->data + (size_t)i * (size_t)elem_size;
        cjk_value v;
        switch (elem_kind) {
            case CJK_KIND_I8:
                v = cjk_v_i32((int32_t)*(const int8_t *)槽);
                break;
            case CJK_KIND_I16:
                v = cjk_v_i32((int32_t)*(const int16_t *)槽);
                break;
            case CJK_KIND_I32:
                v = cjk_v_i32(*(const int32_t *)槽);
                break;
            case CJK_KIND_I64:
                v = cjk_v_i64(*(const int64_t *)槽);
                break;
            case CJK_KIND_F32:
                v = cjk_v_f32(*(const float *)槽);
                break;
            case CJK_KIND_F64:
                v = cjk_v_f64(*(const double *)槽);
                break;
            case CJK_KIND_U16:
                v = cjk_v_char(*(const uint16_t *)槽);
                break;
            case CJK_KIND_BOOL:
                v = cjk_v_bool(*(const int8_t *)槽 != 0);
                break;
            default: {
                void *指针 = *(void *const *)槽;
                v = text_ref ? cjk_v_str((cjk_string *)指针) : cjk_v_ref(指针);
                break;
            }
        }
        JSON值(&b, v, pretty, 1);
    }
    if (pretty && a->len > 0) {
        JSON缩进(&b, 0);
    }
    缓冲追加字符(&b, ']');
    return 缓冲成串(&b);
}
