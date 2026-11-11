/*
 * 中文 Java 原生运行时（M4）
 *
 * 字符串、打印、数组、对象头、类描述符、异常与 GC 接口。
 * 所有堆分配都经 cjk_gc_alloc，GC 实现在 gc.c。
 */
#ifndef CJKRT_H
#define CJKRT_H

#include <setjmp.h>
#include <stddef.h>
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

typedef struct cjk_class cjk_class;

typedef struct cjk_object {
    cjk_class *cls;
} cjk_object;

struct cjk_class {
    int32_t id;
    const char *name;
    cjk_class *parent;
    int32_t slot_count;
    void **methods;
    cjk_class **interfaces;
    int32_t interface_count;
    /* M5：cjk_object_to_string 的转换钩子；NULL 表示用默认「类名@地址」。 */
    cjk_string *(*to_string)(cjk_object *self);
};

typedef struct cjk_exception {
    cjk_class *cls;
    cjk_string *message;
} cjk_exception;

/* 运行时内置的类描述符（源码未声明的异常类）。 */
extern cjk_class CJK_CLS_对象;
extern cjk_class CJK_CLS_异常;
extern cjk_class CJK_CLS_运行时异常;
extern cjk_class CJK_CLS_算术异常;
extern cjk_class CJK_CLS_空指针异常;
extern cjk_class CJK_CLS_数组越界异常;
extern cjk_class CJK_CLS_非法参数异常;
extern cjk_class CJK_CLS_数字格式异常;
extern cjk_class CJK_CLS_类转换异常;

/* 异常处理器栈：生成代码的「尝试」直接操作它。 */
typedef struct cjk_handler {
    jmp_buf buf;
    struct cjk_handler *prev;
    cjk_exception *exception;
} cjk_handler;

extern cjk_handler *cjk_handler_top;

/* 启动与全局设置 */
void cjk_rt_init(void);
void cjk_set_source(const char *path);

/* GC */
void cjk_gc_init(void);
void *cjk_gc_alloc(size_t bytes);
void cjk_gc_add_root(void *addr, size_t bytes);
void cjk_gc_collect(void);
/* 工作线程进出：多于一个线程时暂停自动回收，避免扫描到其他线程的活跃栈。 */
void cjk_gc_enter_thread(void);
void cjk_gc_leave_thread(void);

/* 对象 */
cjk_object *cjk_new_object(cjk_class *cls, size_t size);
int32_t cjk_is_instance(cjk_object *obj, cjk_class *target);
cjk_object *cjk_null_check(cjk_object *obj, int32_t line);
cjk_string *cjk_object_to_string(cjk_object *obj);
cjk_string *cjk_virtual_to_string(cjk_object *obj, int32_t slot);

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
int32_t cjk_str_is_empty(cjk_string *s, int32_t line);
int32_t cjk_parse_int(cjk_string *s, int32_t line);

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

/* 整数除法与取余：除零抛算术异常，INT_MIN / -1 回绕 */
int32_t cjk_div_i32(int32_t a, int32_t b, int32_t line);
int32_t cjk_rem_i32(int32_t a, int32_t b, int32_t line);
int64_t cjk_div_i64(int64_t a, int64_t b, int32_t line);
int64_t cjk_rem_i64(int64_t a, int64_t b, int32_t line);

/* 异常 */
cjk_exception *cjk_new_exception(cjk_class *cls, cjk_string *message);
cjk_exception *cjk_new_exception_utf8(cjk_class *cls, const char *message);
void cjk_throw(cjk_exception *e, int32_t line);
void cjk_throw_message(cjk_class *cls, const char *message, int32_t line);

/* ================= M5：值模型 ================= */

enum {
    CJK_V_NULL = 0,
    CJK_V_I32 = 1,
    CJK_V_I64 = 2,
    CJK_V_F32 = 3,
    CJK_V_F64 = 4,
    CJK_V_BOOL = 5,
    CJK_V_CHAR = 6,
    CJK_V_STR = 7,
    CJK_V_REF = 8
};

typedef struct cjk_value {
    int32_t kind;
    union {
        int64_t i;
        double d;
        void *p;
    } u;
} cjk_value;

/* 运行时的函数对象回调：返回装箱后的值。 */
typedef cjk_value (*cjk_callback)(cjk_object *self, cjk_value a, cjk_value b, int32_t line);

cjk_value cjk_v_null(void);
cjk_value cjk_v_i32(int32_t v);
cjk_value cjk_v_i64(int64_t v);
cjk_value cjk_v_f32(float v);
cjk_value cjk_v_f64(double v);
cjk_value cjk_v_bool(int32_t v);
cjk_value cjk_v_char(uint16_t v);
cjk_value cjk_v_str(cjk_string *v);
cjk_value cjk_v_ref(void *v);

int32_t cjk_v_as_i32(cjk_value v, int32_t line);
int64_t cjk_v_as_i64(cjk_value v, int32_t line);
float cjk_v_as_f32(cjk_value v, int32_t line);
double cjk_v_as_f64(cjk_value v, int32_t line);
int32_t cjk_v_as_bool(cjk_value v, int32_t line);
uint16_t cjk_v_as_char(cjk_value v, int32_t line);
cjk_string *cjk_v_as_str(cjk_value v, int32_t line);
void *cjk_v_as_ref(cjk_value v, int32_t line);

/* 泛型擦除值（__值）用堆上的值盒承载；引用类型直接传对象。 */
cjk_object *cjk_box_value(cjk_value v);
cjk_value cjk_unbox_value(cjk_object *obj, int32_t line);

cjk_string *cjk_value_to_string(cjk_value v);
int32_t cjk_value_equal(cjk_value a, cjk_value b);
int32_t cjk_value_compare(cjk_value a, cjk_value b);
int32_t cjk_value_hash(cjk_value v);

/* 程序参数 */
void cjk_set_args(int argc, char **argv);
cjk_array *cjk_program_args(void);

/* ================= M5：字符串扩展 ================= */

cjk_string *cjk_str_substring(cjk_string *s, int32_t begin, int32_t end, int32_t line);
cjk_string *cjk_str_replace(cjk_string *s, cjk_string *old, cjk_string *new_s);
cjk_array *cjk_str_split(cjk_string *s, cjk_string *sep, int32_t line);
cjk_string *cjk_str_repeat(cjk_string *s, int32_t n, int32_t line);
int32_t cjk_str_compare(cjk_string *a, cjk_string *b);
int32_t cjk_str_contains(cjk_string *s, cjk_string *sub);
int32_t cjk_str_starts_with(cjk_string *s, cjk_string *prefix);
int32_t cjk_str_ends_with(cjk_string *s, cjk_string *suffix);
int32_t cjk_str_is_blank(cjk_string *s, int32_t line);
cjk_string *cjk_str_upper(cjk_string *s);
cjk_string *cjk_str_lower(cjk_string *s);
uint16_t cjk_str_char_at(cjk_string *s, int32_t index, int32_t line);
cjk_string *cjk_str_join(cjk_string *sep, cjk_array *parts);
int64_t cjk_parse_i64(cjk_string *s, int32_t line);
double cjk_parse_f64(cjk_string *s, int32_t line);
cjk_string *cjk_str_from_cstr_raw(const char *s, int32_t len);

/* ================= M5：集合与流 ================= */

extern cjk_class CJK_CLS_清单;
extern cjk_class CJK_CLS_键值表;
extern cjk_class CJK_CLS_集;
extern cjk_class CJK_CLS_文本构建器;
extern cjk_class CJK_CLS_文件路径;
extern cjk_class CJK_CLS_可选值;
extern cjk_class CJK_CLS_流;
extern cjk_class CJK_CLS_比较器;
extern cjk_class CJK_CLS_收集器;
extern cjk_class CJK_CLS_可比较;

cjk_object *cjk_list_new(void);
cjk_object *cjk_list_from(int32_t n, const cjk_value *items);
int32_t cjk_list_add(cjk_object *list, cjk_value v);
cjk_value cjk_list_get(cjk_object *list, int32_t index, int32_t line);
cjk_value cjk_list_set(cjk_object *list, int32_t index, cjk_value v, int32_t line);
cjk_value cjk_list_remove_at(cjk_object *list, int32_t index, int32_t line);
int32_t cjk_list_remove_value(cjk_object *list, cjk_value v);
int32_t cjk_list_index_of(cjk_object *list, cjk_value v);
int32_t cjk_list_size(cjk_object *list);
int32_t cjk_list_is_empty(cjk_object *list);
int32_t cjk_list_contains(cjk_object *list, cjk_value v);
void cjk_list_clear(cjk_object *list);
void cjk_list_sort(cjk_object *list);
void cjk_list_sort_by(cjk_object *list, cjk_object *cmp);
cjk_object *cjk_list_stream(cjk_object *list);
cjk_object *cjk_list_to_set(cjk_object *list);
cjk_value cjk_list_max(cjk_object *list, int32_t line);
cjk_value cjk_list_min(cjk_object *list, int32_t line);
void cjk_list_reverse(cjk_object *list);
void cjk_list_shuffle(cjk_object *list);

cjk_object *cjk_set_new(void);
cjk_object *cjk_set_from(int32_t n, const cjk_value *items);
int32_t cjk_set_add(cjk_object *set, cjk_value v);
int32_t cjk_set_remove(cjk_object *set, cjk_value v);
int32_t cjk_set_contains(cjk_object *set, cjk_value v);
int32_t cjk_set_size(cjk_object *set);
int32_t cjk_set_is_empty(cjk_object *set);
void cjk_set_clear(cjk_object *set);
cjk_value cjk_set_get(cjk_object *set, int32_t index, int32_t line);
cjk_object *cjk_set_stream(cjk_object *set);

cjk_object *cjk_map_new(void);
cjk_object *cjk_map_from(int32_t n, const cjk_value *flat_pairs);
cjk_value cjk_map_put(cjk_object *map, cjk_value key, cjk_value value);
cjk_value cjk_map_get(cjk_object *map, cjk_value key);
cjk_value cjk_map_get_or(cjk_object *map, cjk_value key, cjk_value fallback);
cjk_value cjk_map_remove(cjk_object *map, cjk_value key);
cjk_object *cjk_map_keys(cjk_object *map);
cjk_object *cjk_map_values(cjk_object *map);
int32_t cjk_map_contains_key(cjk_object *map, cjk_value key);
int32_t cjk_map_contains_value(cjk_object *map, cjk_value value);
int32_t cjk_map_size(cjk_object *map);
int32_t cjk_map_is_empty(cjk_object *map);
void cjk_map_clear(cjk_object *map);
cjk_object *cjk_map_stream(cjk_object *map);
void cjk_map_for_each(cjk_object *map, cjk_object *fn, cjk_callback cb);

cjk_object *cjk_builder_new(void);
cjk_object *cjk_builder_append(cjk_object *builder, cjk_value v);
cjk_string *cjk_builder_to_string(cjk_object *builder);
int32_t cjk_builder_len(cjk_object *builder);
void cjk_builder_clear(cjk_object *builder);

cjk_object *cjk_optional_empty(void);
cjk_object *cjk_optional_of(cjk_value v);
cjk_object *cjk_optional_of_nullable(cjk_value v);
cjk_value cjk_optional_or(cjk_object *opt, cjk_value fallback);
int32_t cjk_optional_is_empty(cjk_object *opt);
int32_t cjk_optional_is_present(cjk_object *opt);
cjk_value cjk_optional_get(cjk_object *opt, int32_t line);
void cjk_optional_if_present(cjk_object *opt, cjk_object *fn, cjk_callback cb);

cjk_object *cjk_stream_filter(cjk_object *stream, cjk_object *fn, cjk_callback cb);
cjk_object *cjk_stream_map(cjk_object *stream, cjk_object *fn, cjk_callback cb);
void cjk_stream_for_each(cjk_object *stream, cjk_object *fn, cjk_callback cb);
cjk_object *cjk_stream_collect_list(cjk_object *stream);
cjk_object *cjk_stream_collect_set(cjk_object *stream);
cjk_string *cjk_stream_collect_join(cjk_object *stream, cjk_object *collector);
cjk_object *cjk_stream_find_first(cjk_object *stream);
int32_t cjk_stream_count(cjk_object *stream);
cjk_object *cjk_stream_distinct(cjk_object *stream);
cjk_object *cjk_stream_sorted(cjk_object *stream);

cjk_object *cjk_collector_list(void);
cjk_object *cjk_collector_set(void);
cjk_object *cjk_collector_join(cjk_string *sep);
cjk_string *cjk_collector_separator(cjk_object *collector);

cjk_object *cjk_comparator_by_int(cjk_object *fn, cjk_callback cb);
cjk_object *cjk_comparator_reversed(cjk_object *cmp);
int32_t cjk_comparator_compare(cjk_object *cmp, cjk_value a, cjk_value b);

/* ================= M5：文件与路径 ================= */

extern cjk_class CJK_CLS_输入输出异常;

cjk_object *cjk_path_of(int32_t n, cjk_string **parts);
cjk_object *cjk_path_parent(cjk_object *path);
cjk_object *cjk_path_absolute(cjk_object *path);
cjk_string *cjk_path_file_name(cjk_object *path);
cjk_string *cjk_path_to_string(cjk_object *path);
cjk_string *cjk_files_read_text(cjk_object *path, int32_t line);
cjk_object *cjk_files_write_text(cjk_object *path, cjk_string *text, int32_t line);
cjk_object *cjk_files_create_directories(cjk_object *path, int32_t line);
cjk_object *cjk_files_read_all_lines(cjk_object *path, int32_t line);
int32_t cjk_files_exists(cjk_object *path);
int64_t cjk_files_size(cjk_object *path, int32_t line);
int32_t cjk_files_delete(cjk_object *path, int32_t line);
int32_t cjk_files_delete_if_exists(cjk_object *path, int32_t line);
cjk_object *cjk_files_list(cjk_object *path, int32_t line);
cjk_object *cjk_files_copy(cjk_object *from, cjk_object *to, int32_t line);
cjk_object *cjk_files_move(cjk_object *from, cjk_object *to, int32_t line);

/* ================= M5：中文.标准库 ================= */

extern cjk_class CJK_CLS_本地日期;
extern cjk_class CJK_CLS_本地日期时间;
extern cjk_class CJK_CLS_响应;
extern cjk_class CJK_CLS_线程池;
extern cjk_class CJK_CLS_未来;
extern cjk_class CJK_CLS_日志记录器;

/* 输出 */
void cjk_print_values(int32_t n, cjk_value *values);
void cjk_println_values(int32_t n, cjk_value *values);
void cjk_print_newline(void);
void cjk_eprintln_values(int32_t n, cjk_value *values);
void cjk_output_format(cjk_string *tpl, int32_t n, cjk_value *values, int32_t line);

/* 文本 / 数值 / 集合工具 */
int32_t cjk_str_is_null_or_empty(cjk_string *s);
int32_t cjk_str_is_null_or_blank(cjk_string *s);
cjk_string *cjk_str_strip(cjk_string *s, int32_t line);
cjk_string *cjk_str_join_values(cjk_string *sep, int32_t n, cjk_value *values);
int32_t cjk_str_is_integer(cjk_string *s);
int32_t cjk_random_int(int32_t hi, int32_t line);
int32_t cjk_random_range(int32_t lo, int32_t hi, int32_t line);
void cjk_list_fill(cjk_object *list, cjk_value v);

/* 时间 */
cjk_object *cjk_date_today(void);
cjk_object *cjk_datetime_now(void);
int64_t cjk_now_millis(void);
cjk_string *cjk_datetime_format(cjk_object *dt, cjk_string *pattern, int32_t line);
int64_t cjk_date_between(cjk_object *a, cjk_object *b);
cjk_object *cjk_date_plus_days(cjk_object *d, int64_t days);

/* 文件（标准库语义：写文本前自动建父目录） */
void cjk_files_write_text_parents(cjk_object *path, cjk_string *text, int32_t line);

/* 并发 */
cjk_object *cjk_pool_new(int32_t size, int32_t line);
void cjk_pool_execute(cjk_object *pool, cjk_object *fn, cjk_callback cb);
cjk_object *cjk_pool_submit(cjk_object *pool, cjk_object *fn, cjk_callback cb);
cjk_value cjk_future_get(cjk_object *future, int32_t line);
void cjk_pool_shutdown(cjk_object *pool);
void cjk_sleep(int64_t millis);
cjk_string *cjk_thread_name(void);

/* 网络 */
cjk_string *cjk_http_get_text(cjk_string *url, int32_t line);
int32_t cjk_http_status(cjk_string *url, int32_t line);
cjk_object *cjk_http_get(cjk_string *url, int32_t line);
int32_t cjk_http_response_status(cjk_object *resp);
cjk_string *cjk_http_response_body(cjk_object *resp);
void cjk_http_download(cjk_string *url, cjk_object *path, int32_t line);

/* 日志 */
cjk_object *cjk_logger_get(cjk_string *name);
cjk_object *cjk_logger_console(cjk_string *name);
void cjk_logger_log(cjk_object *logger, int32_t level, cjk_value msg, int32_t line);
void cjk_logger_set_level(cjk_object *logger, int32_t level);
void cjk_logger_set_level_name(cjk_object *logger, cjk_string *name, int32_t line);

/* 数据 */
cjk_string *cjk_json_encode(cjk_value v, int32_t pretty, int32_t line);
cjk_string *cjk_json_encode_array(cjk_array *a, int32_t elem_kind, int32_t elem_size,
                                  int32_t text_ref, int32_t pretty, int32_t line);

#ifdef __cplusplus
}
#endif

#endif /* CJKRT_H */
