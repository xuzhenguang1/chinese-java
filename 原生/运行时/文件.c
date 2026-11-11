/*
 * M5 文件运行时：文件路径、UTF-8 读写、目录、存在性、大小、删除、列举、复制、移动。
 *
 * 一期仅 Windows：内部用 UTF-16（wchar_t）调用 Win32 API。
 */
#include "cjkrt.h"

#include <direct.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <windows.h>

static cjk_string *串新建(int32_t len) {
    cjk_string *s = (cjk_string *)cjk_gc_alloc(sizeof(cjk_string));
    s->len = len;
    s->data = (uint16_t *)cjk_gc_alloc(sizeof(uint16_t) * (size_t)(len > 0 ? len : 1));
    return s;
}

static wchar_t *转宽(cjk_string *s) {
    if (s == NULL) {
        wchar_t *w = (wchar_t *)cjk_gc_alloc(sizeof(wchar_t));
        w[0] = 0;
        return w;
    }
    wchar_t *w = (wchar_t *)cjk_gc_alloc(sizeof(wchar_t) * (size_t)(s->len + 1));
    for (int32_t i = 0; i < s->len; i++) {
        w[i] = (wchar_t)s->data[i];
    }
    w[s->len] = 0;
    return w;
}

static cjk_string *从宽(const wchar_t *w) {
    int32_t len = (int32_t)wcslen(w);
    cjk_string *s = 串新建(len);
    for (int32_t i = 0; i < len; i++) {
        s->data[i] = (uint16_t)w[i];
    }
    return s;
}

/* ---------------- 路径 ---------------- */

typedef struct cjk_path {
    cjk_class *cls;
    cjk_string *s;
} cjk_path;

static cjk_string *路径转文本(cjk_object *self);

cjk_class CJK_CLS_文件路径 = {28, "文件路径", &CJK_CLS_对象, 0, NULL, NULL, 0, 路径转文本};
cjk_class CJK_CLS_输入输出异常 = {9, "输入输出异常", &CJK_CLS_异常, 0, NULL, NULL, 0, NULL};

static cjk_path *路径新建(cjk_string *s) {
    cjk_path *p = (cjk_path *)cjk_gc_alloc(sizeof(cjk_path));
    p->cls = &CJK_CLS_文件路径;
    p->s = s;
    return p;
}

static cjk_string *规范化(cjk_string *s) {
    wchar_t *w = 转宽(s);
    size_t 长 = wcslen(w);
    wchar_t *r = (wchar_t *)cjk_gc_alloc(sizeof(wchar_t) * (长 + 2));
    size_t 写 = 0;
    for (size_t i = 0; i < 长; i++) {
        wchar_t c = w[i];
        if (c == '/') {
            c = L'\\';
        }
        if (c == L'\\' && 写 > 0 && r[写 - 1] == L'\\') {
            continue;
        }
        r[写++] = c;
    }
    /* 去掉末尾分隔符，保留「X:\」与「\」根。 */
    while (写 > 1 && r[写 - 1] == L'\\' && !(写 == 3 && r[1] == L':')) {
        写--;
    }
    r[写] = 0;
    return 从宽(r);
}

static int32_t 是绝对路径(wchar_t *w) {
    if (w[0] == L'\\') {
        return 1;
    }
    return wcslen(w) >= 3 && w[1] == L':' && w[2] == L'\\';
}

cjk_object *cjk_path_of(int32_t n, cjk_string **parts) {
    if (n <= 0) {
        return (cjk_object *)路径新建(cjk_str_from_utf8(""));
    }
    wchar_t *结果 = (wchar_t *)cjk_gc_alloc(sizeof(wchar_t) * 4096);
    结果[0] = 0;
    for (int32_t i = 0; i < n; i++) {
        wchar_t *段 = 转宽(parts[i]);
        if (i == 0 || 是绝对路径(段)) {
            wcscpy(结果, 段);
        } else {
            size_t 长 = wcslen(结果);
            if (长 > 0 && 结果[长 - 1] != L'\\' && 结果[长 - 1] != L'/') {
                wcscat(结果, L"\\");
            }
            wcscat(结果, 段);
        }
    }
    return (cjk_object *)路径新建(规范化(从宽(结果)));
}

cjk_string *cjk_path_to_string(cjk_object *path) {
    return ((cjk_path *)path)->s;
}

static cjk_string *路径转文本(cjk_object *self) {
    return ((cjk_path *)self)->s;
}

cjk_object *cjk_path_parent(cjk_object *path) {
    wchar_t *w = 转宽(((cjk_path *)path)->s);
    size_t 长 = wcslen(w);
    size_t 位 = 长;
    while (位 > 0 && w[位 - 1] == L'\\') {
        位--;
    }
    while (位 > 0 && w[位 - 1] != L'\\') {
        位--;
    }
    if (位 == 0) {
        return NULL;
    }
    while (位 > 1 && w[位 - 1] == L'\\') {
        位--;
    }
    if (位 == 2 && w[1] == L':') {
        return (cjk_object *)路径新建(从宽(L"\\"));
    }
    wchar_t *父 = (wchar_t *)cjk_gc_alloc(sizeof(wchar_t) * (位 + 1));
    for (size_t i = 0; i < 位; i++) {
        父[i] = w[i];
    }
    父[位] = 0;
    return (cjk_object *)路径新建(从宽(父));
}

cjk_object *cjk_path_absolute(cjk_object *path) {
    wchar_t *w = 转宽(((cjk_path *)path)->s);
    if (是绝对路径(w)) {
        return (cjk_object *)路径新建(规范化(从宽(w)));
    }
    wchar_t 当前[4096];
    if (_wgetcwd(当前, 4096) == NULL) {
        return (cjk_object *)路径新建(规范化(从宽(w)));
    }
    wchar_t *结果 = (wchar_t *)cjk_gc_alloc(sizeof(wchar_t) * 8192);
    wcscpy(结果, 当前);
    size_t 长 = wcslen(结果);
    if (长 > 0 && 结果[长 - 1] != L'\\') {
        wcscat(结果, L"\\");
    }
    wcscat(结果, w);
    return (cjk_object *)路径新建(规范化(从宽(结果)));
}

cjk_string *cjk_path_file_name(cjk_object *path) {
    wchar_t *w = 转宽(((cjk_path *)path)->s);
    size_t 长 = wcslen(w);
    while (长 > 0 && w[长 - 1] == L'\\') {
        长--;
    }
    size_t 位 = 长;
    while (位 > 0 && w[位 - 1] != L'\\') {
        位--;
    }
    if (位 == 长) {
        return NULL;
    }
    return 从宽(w + 位);
}

/* ---------------- UTF-8 转换 ---------------- */

static char *宽转UTF8(const wchar_t *w, size_t *输出长) {
    size_t 长 = wcslen(w);
    char *r = (char *)cjk_gc_alloc(长 * 4 + 1);
    size_t 写 = 0;
    for (size_t i = 0; i < 长; i++) {
        uint32_t c = (uint16_t)w[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < 长) {
            uint32_t 低 = (uint16_t)w[i + 1];
            if (低 >= 0xDC00 && 低 <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (低 - 0xDC00);
                i++;
            }
        }
        if (c < 0x80) {
            r[写++] = (char)c;
        } else if (c < 0x800) {
            r[写++] = (char)(0xC0 | (c >> 6));
            r[写++] = (char)(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            r[写++] = (char)(0xE0 | (c >> 12));
            r[写++] = (char)(0x80 | ((c >> 6) & 0x3F));
            r[写++] = (char)(0x80 | (c & 0x3F));
        } else {
            r[写++] = (char)(0xF0 | (c >> 18));
            r[写++] = (char)(0x80 | ((c >> 12) & 0x3F));
            r[写++] = (char)(0x80 | ((c >> 6) & 0x3F));
            r[写++] = (char)(0x80 | (c & 0x3F));
        }
    }
    r[写] = 0;
    if (输出长 != NULL) {
        *输出长 = 写;
    }
    return r;
}

static void 读失败(cjk_object *path, int32_t line) {
    cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法读取文件："),
                                     cjk_path_to_string(path));
    cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
}

static cjk_string *读取全部(cjk_object *path, int32_t line) {
    wchar_t *w = 转宽(cjk_path_to_string(path));
    FILE *f = _wfopen(w, L"rb");
    if (f == NULL) {
        读失败(path, line);
    }
    fseek(f, 0, SEEK_END);
    long 长 = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (长 < 0) {
        长 = 0;
    }
    char *缓冲 = (char *)cjk_gc_alloc((size_t)长 + 1);
    size_t 读 = fread(缓冲, 1, (size_t)长, f);
    fclose(f);
    缓冲[读] = 0;
    /* 去掉 UTF-8 BOM。 */
    if (读 >= 3 && (unsigned char)缓冲[0] == 0xEF
            && (unsigned char)缓冲[1] == 0xBB && (unsigned char)缓冲[2] == 0xBF) {
        memmove(缓冲, 缓冲 + 3, 读 - 3);
        缓冲[读 - 3] = 0;
    }
    return cjk_str_from_utf8(缓冲);
}

cjk_string *cjk_files_read_text(cjk_object *path, int32_t line) {
    return 读取全部(path, line);
}

cjk_object *cjk_files_write_text(cjk_object *path, cjk_string *text, int32_t line) {
    wchar_t *w = 转宽(cjk_path_to_string(path));
    FILE *f = _wfopen(w, L"wb");
    if (f == NULL) {
        cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法写入文件："),
                                          cjk_path_to_string(path));
        cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
    }
    size_t 长 = 0;
    char *数据 = 宽转UTF8(text == NULL ? L"" : 转宽(text), &长);
    if (长 > 0) {
        fwrite(数据, 1, 长, f);
    }
    fclose(f);
    return NULL;
}

static void 建目录递归(wchar_t *w) {
    size_t 长 = wcslen(w);
    for (size_t i = 1; i <= 长; i++) {
        if (w[i] == L'\\' || w[i] == 0) {
            wchar_t 暂 = w[i];
            w[i] = 0;
            if (wcslen(w) > 0 && !(wcslen(w) == 2 && w[1] == L':')) {
                _wmkdir(w);
            }
            w[i] = 暂;
        }
    }
}

cjk_object *cjk_files_create_directories(cjk_object *path, int32_t line) {
    (void)line;
    wchar_t *w = 转宽(cjk_path_to_string(path));
    建目录递归(w);
    return NULL;
}

void cjk_files_write_text_parents(cjk_object *path, cjk_string *text, int32_t line) {
    wchar_t *w = 转宽(cjk_path_to_string(path));
    wchar_t *末 = wcsrchr(w, L'\\');
    if (末 != NULL) {
        *末 = 0;
        if (wcslen(w) > 0) {
            建目录递归(w);
        }
    }
    cjk_files_write_text(path, text, line);
}

cjk_object *cjk_files_read_all_lines(cjk_object *path, int32_t line) {
    cjk_string *全部 = 读取全部(path, line);
    /* 逐行切分；去掉行尾 \r，忽略文件末尾换行产生的空行。 */
    cjk_value *行 = (cjk_value *)cjk_gc_alloc(sizeof(cjk_value) * (size_t)(全部->len + 1));
    int32_t 数 = 0;
    int32_t 起 = 0;
    for (int32_t i = 0; i <= 全部->len; i++) {
        if (i == 全部->len) {
            if (i > 起) {
                行[数++] = cjk_v_str(cjk_str_substring(全部, 起, i, line));
            }
            break;
        }
        if (全部->data[i] == '\n') {
            int32_t 止 = i;
            if (止 > 起 && 全部->data[止 - 1] == '\r') {
                止--;
            }
            行[数++] = cjk_v_str(cjk_str_substring(全部, 起, 止, line));
            起 = i + 1;
        }
    }
    return cjk_list_from(数, 行);
}

int32_t cjk_files_exists(cjk_object *path) {
    wchar_t *w = 转宽(cjk_path_to_string(path));
    struct _stat64 st;
    return _wstat64(w, &st) == 0;
}

int64_t cjk_files_size(cjk_object *path, int32_t line) {
    wchar_t *w = 转宽(cjk_path_to_string(path));
    struct _stat64 st;
    if (_wstat64(w, &st) != 0) {
        cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法获取文件大小："),
                                          cjk_path_to_string(path));
        cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
    }
    return (int64_t)st.st_size;
}

int32_t cjk_files_delete(cjk_object *path, int32_t line) {
    wchar_t *w = 转宽(cjk_path_to_string(path));
    if (_wremove(w) != 0) {
        cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法删除文件："),
                                          cjk_path_to_string(path));
        cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
    }
    return 1;
}

/* 对齐 Files.deleteIfExists：文件不存在时静默返回。 */
int32_t cjk_files_delete_if_exists(cjk_object *path, int32_t line) {
    wchar_t *w = 转宽(cjk_path_to_string(path));
    struct _stat64 st;
    if (_wstat64(w, &st) != 0) {
        return 0;
    }
    if (_wremove(w) != 0) {
        cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法删除文件："),
                                          cjk_path_to_string(path));
        cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
    }
    return 1;
}

cjk_object *cjk_files_list(cjk_object *path, int32_t line) {
    wchar_t *基 = 转宽(cjk_path_to_string(path));
    wchar_t 模式[8192];
    wcscpy(模式, 基);
    size_t 长 = wcslen(模式);
    if (长 > 0 && 模式[长 - 1] != L'\\') {
        wcscat(模式, L"\\");
    }
    wcscat(模式, L"*");

    WIN32_FIND_DATAW 数据;
    HANDLE 句柄 = FindFirstFileW(模式, &数据);
    cjk_object *清单 = cjk_list_new();
    if (句柄 == INVALID_HANDLE_VALUE) {
        cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法列举目录："),
                                          cjk_path_to_string(path));
        cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
    }
    do {
        if (wcscmp(数据.cFileName, L".") == 0 || wcscmp(数据.cFileName, L"..") == 0) {
            continue;
        }
        wchar_t 全名[8192];
        wcscpy(全名, 基);
        长 = wcslen(全名);
        if (长 > 0 && 全名[长 - 1] != L'\\') {
            wcscat(全名, L"\\");
        }
        wcscat(全名, 数据.cFileName);
        cjk_list_add(清单, cjk_v_ref(路径新建(从宽(全名))));
    } while (FindNextFileW(句柄, &数据));
    FindClose(句柄);
    return 清单;
}

cjk_object *cjk_files_copy(cjk_object *from, cjk_object *to, int32_t line) {
    wchar_t *甲 = 转宽(cjk_path_to_string(from));
    wchar_t *乙 = 转宽(cjk_path_to_string(to));
    if (!CopyFileW(甲, 乙, FALSE)) {
        cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法复制文件："),
                                          cjk_path_to_string(from));
        cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
    }
    return NULL;
}

cjk_object *cjk_files_move(cjk_object *from, cjk_object *to, int32_t line) {
    wchar_t *甲 = 转宽(cjk_path_to_string(from));
    wchar_t *乙 = 转宽(cjk_path_to_string(to));
    if (!MoveFileExW(甲, 乙, MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED)) {
        cjk_string *消息 = cjk_str_concat(cjk_str_from_utf8("无法移动文件："),
                                          cjk_path_to_string(from));
        cjk_throw(cjk_new_exception(&CJK_CLS_输入输出异常, 消息), line);
    }
    return NULL;
}
