/*
 * 中文 Java 原生运行时（M3 最小集）
 *
 * 只提供教程 01–08 需要的字符串、打印、数组与错误退出。
 * 对象模型、异常与 GC 在 M4 接入。
 */
#ifndef CJKRT_H
#define CJKRT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cjk_string {
    int32_t len;      /* UTF-16 码元数 */
    uint16_t *data;   /* UTF-16 码元 */
} cjk_string;

typedef struct cjk_array {
    int32_t len;      /* 元素个数 */
    int32_t kind;     /* 元素种类，见下方 CJK_KIND_* */
    void *data;       /* 连续数据 */
} cjk_array;

enum {
    CJK_KIND_I8 = 1,
    CJK_KIND_I16 = 2,
    CJK_KIND_I32 = 3,
    CJK_KIND_I64 = 4,
    CJK_KIND_F32 = 5,
    CJK_KIND_F64 = 6,
    CJK_KIND_U16 = 7,
    CJK_KIND_BOOL = 8,
    CJK_KIND_REF = 9
};

/* 启动：Windows 控制台切 UTF-8 代码页 */
void cjk_rt_init(void);

/* 字符串 */
cjk_string *cjk_str_from_utf8(const char *s);
cjk_string *cjk_str_from_i32(int32_t v);
cjk_string *cjk_str_from_i64(int64_t v);
cjk_string *cjk_str_from_f32(float v);
cjk_string *cjk_str_from_f64(double v);
cjk_string *cjk_str_from_bool(int32_t v);
cjk_string *cjk_str_from_char(uint16_t v);
cjk_string *cjk_str_concat(cjk_string *a, cjk_string *b);
cjk_string *cjk_str_trim(cjk_string *s);
int32_t cjk_str_len(cjk_string *s, int32_t line);
int32_t cjk_str_equal(cjk_string *a, cjk_string *b);

/* 打印 */
void cjk_print_str(cjk_string *s);
void cjk_print_i32(int32_t v);
void cjk_print_i64(int64_t v);
void cjk_print_f32(float v);
void cjk_print_f64(double v);
void cjk_print_bool(int32_t v);
void cjk_print_char(uint16_t v);
void cjk_newline(void);

/* 数组 */
cjk_array *cjk_array_new(int32_t len, int32_t kind, int32_t elem_size, int32_t line);
cjk_array *cjk_array_from(int32_t kind, int32_t elem_size, int32_t len, const void *data);
int32_t cjk_array_len(cjk_array *a, int32_t line);
void *cjk_array_at(cjk_array *a, int32_t index, int32_t elem_size, int32_t line);
void cjk_array_copy(cjk_array *src, int32_t src_pos, cjk_array *dst, int32_t dst_pos,
                    int32_t len, int32_t elem_size, int32_t line);

/* 整数除法与取余：除零检查，INT_MIN / -1 回绕 */
int32_t cjk_div_i32(int32_t a, int32_t b, int32_t line);
int32_t cjk_rem_i32(int32_t a, int32_t b, int32_t line);
int64_t cjk_div_i64(int64_t a, int64_t b, int32_t line);
int64_t cjk_rem_i64(int64_t a, int64_t b, int32_t line);

/* 错误：打印中文消息到 stderr，退出码 1 */
void cjk_fatal(int32_t line, const char *message);

#ifdef __cplusplus
}
#endif

#endif /* CJKRT_H */
