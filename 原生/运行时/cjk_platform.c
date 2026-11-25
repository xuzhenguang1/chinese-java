/*
 * 中文 Java 原生运行时：平台抽象实现（M13 / ADR-017）
 *
 * Windows 分支：逐字节复用迁移前的 Win32 实现（含 值.c 宽参数样板、gc.c once 锁、
 *   标准库.c 时钟/线程入口）。唯一变化是代码位置，不做语义改写。
 * POSIX 分支（Linux/musl 与 macOS/Darwin 共用 M21）：pthread / clock_gettime 实现；
 *   文件与网络 IO 的中文诊断桩在 文件.c / 标准库.c 的平台分区中给出，本文件只负责六类公共接口。
 *   Darwin 沿用 Linux 的 pthread/clock 路径，不复制第二份实现（ADR-024 决策 2）。
 *
 * 注意：跨平台一致性由调用点保证，本文件不引入任何链接期新依赖
 *   （Win32 侧沿用 kernel32/shell32；POSIX 侧仅 pthread / librt）。
 */
#include "cjk_platform.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ================= POSIX 分支（Linux/Darwin）的 pthread 包装 ================= */
#if !CJK_PLATFORM_WINDOWS
void cjk_lock_init(cjk_lock_t *m) {
    /* GC 的 alloc 持锁期间会递归调用 collect，需可重入锁（对齐 Windows CRITICAL_SECTION）。 */
    pthread_mutexattr_t 属性;
    pthread_mutexattr_init(&属性);
    pthread_mutexattr_settype(&属性, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(m, &属性);
    pthread_mutexattr_destroy(&属性);
}
void cjk_lock_enter(cjk_lock_t *m)  { pthread_mutex_lock(m); }
void cjk_lock_leave(cjk_lock_t *m)  { pthread_mutex_unlock(m); }
void cjk_cond_init(cjk_cond_t *c)   { pthread_cond_init(c, NULL); }
void cjk_cond_wait(cjk_cond_t *c, cjk_lock_t *m) { pthread_cond_wait(c, m); }
void cjk_cond_wake_all(cjk_cond_t *c) { pthread_cond_broadcast(c); }
int32_t cjk_atomic_inc32(volatile int32_t *p) {
    return __atomic_add_fetch(p, 1, __ATOMIC_SEQ_CST);
}
int32_t cjk_atomic_dec32(volatile int32_t *p) {
    return __atomic_sub_fetch(p, 1, __ATOMIC_SEQ_CST);
}
int cjk_once_call(cjk_once_t *o, cjk_once_cb cb) {
    return pthread_once(o, cb);
}
#endif

/* ---------------- 全局锁（一次性初始化） ----------------
 * Windows：INIT_ONCE 回调里 InitializeCriticalSection，与迁移前 gc.c 同构。
 * Linux：once 回调里 pthread_mutex_init。锁为文件级 static，无参回调可直接访问。 */
static cjk_once_t 全局锁一次 = CJK_ONCE_INIT;
static cjk_lock_t 全局锁;

#if CJK_PLATFORM_WINDOWS
static BOOL CALLBACK 初始化全局锁(cjk_once_t *一次, void *参数, void **上下文) {
    (void)一次;
    (void)参数;
    (void)上下文;
    cjk_lock_init(&全局锁);
    return TRUE;
}
#else
static void 初始化全局锁(void) {
    cjk_lock_init(&全局锁);
}
#endif

cjk_lock_t *cjk_global_mutex(void) {
    cjk_once_call(&全局锁一次, 初始化全局锁);
    return &全局锁;
}

/* ---------------- 线程创建 / 等待 ----------------
 * 统一入口签名：unsigned (CJK_THREAD_CALL *)(void *)。
 * Windows 直接交给 _beginthreadex；pthread 需要 void* 返回，做一次薄适配。 */
#if CJK_PLATFORM_WINDOWS
#  include <process.h>
#  include <shellapi.h>
cjk_thread_t cjk_thread_start(unsigned (CJK_THREAD_CALL *入口)(void *), void *参数) {
    return (cjk_thread_t)_beginthreadex(NULL, 0, 入口, 参数, 0, NULL);
}
void cjk_thread_join(cjk_thread_t 线程) {
    WaitForSingleObject(线程, INFINITE);
    CloseHandle(线程);
}
#else
typedef struct {
    unsigned (CJK_THREAD_CALL *入口)(void *);
    void *参数;
} cjk_线程适配;

static void *线程跳板(void *p) {
    cjk_线程适配 *a = (cjk_线程适配 *)p;
    (void)a->入口(a->参数);
    free(a);
    return NULL;
}

cjk_thread_t cjk_thread_start(unsigned (CJK_THREAD_CALL *入口)(void *), void *参数) {
    cjk_线程适配 *a = (cjk_线程适配 *)malloc(sizeof(cjk_线程适配));
    if (a == NULL) {
        return (cjk_thread_t)0;
    }
    a->入口 = 入口;
    a->参数 = 参数;
    pthread_t t;
    if (pthread_create(&t, NULL, 线程跳板, a) != 0) {
        free(a);
        return (cjk_thread_t)0;
    }
    return t;
}
void cjk_thread_join(cjk_thread_t 线程) {
    pthread_join(线程, NULL);
}
#endif

/* ---------------- 线程栈顶（保守 GC 扫栈上界） ----------------
 * Win64：TEB.StackBase = gs:0x08；Win32：fs:0x04。
 * Linux：pthread_getattr_np 取栈基址+大小，上界 = 基址+大小（不返回 NULL）。 */
unsigned char *cjk_thread_stack_top(void) {
#if CJK_PLATFORM_WINDOWS
#  if defined(_WIN64)
    return (unsigned char *)__readgsqword(0x08);
#  else
    return (unsigned char *)__readfsdword(0x04);
#  endif
#else
    void *基址 = NULL;
    size_t 大小 = 0;
    pthread_attr_t 属性;
    if (pthread_getattr_np(pthread_self(), &属性) == 0) {
        pthread_attr_getstack(&属性, &基址, &大小);
        pthread_attr_destroy(&属性);
    }
    if (基址 == NULL || 大小 == 0) {
        /* T4.3：取栈信息失败时返回 NULL，由 gc.c 跳过该线程栈扫描（保守但安全）。
         * 旧实现返回一个栈上哨兵地址+64K，可能越过真实栈顶读到野指针甚至无效页，
         * 且编译器警告 -Wreturn-stack-address；返回 NULL 让调用方显式放弃。 */
        return NULL;
    }
    return (unsigned char *)基址 + 大小;
#endif
}

/* ---------------- 墙上时钟毫秒 ---------------- */
int64_t cjk_wall_millis(void) {
#if CJK_PLATFORM_WINDOWS
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | (uint64_t)ft.dwLowDateTime;
    return (int64_t)(t / 10000u) - 11644473600000LL;
#else
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000 + (int64_t)(ts.tv_nsec / 1000000);
#endif
}

/* ---------------- 控制台 UTF-8 初始化 ---------------- */
void cjk_console_init_utf8(void) {
#if CJK_PLATFORM_WINDOWS
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
}

/* ---------------- 命令行参数 ----------------
 * Windows：CommandLineToArgvW 读宽命令行转 UTF-8（值.c 样板原样迁入），不含 argv[0]。
 * Linux：直接用 argv[i+1] 的 UTF-8 字节。 */
#if CJK_PLATFORM_WINDOWS
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

cjk_array *cjk_platform_program_args(int argc, char **argv) {
#if CJK_PLATFORM_WINDOWS
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
    return cjk_array_from(CJK_KIND_REF, (int32_t)sizeof(cjk_string *), 数, 项);
#else
    int 数 = argc > 0 ? argc - 1 : 0;
    cjk_string **项 = (cjk_string **)cjk_gc_alloc(sizeof(cjk_string *) * (size_t)(数 > 0 ? 数 : 1));
    for (int i = 0; i < 数; i++) {
        项[i] = cjk_str_from_utf8(argv[i + 1]);
    }
    return cjk_array_from(CJK_KIND_REF, (int32_t)sizeof(cjk_string *), 数, 项);
#endif
}

/* ---------------- 睡眠 ---------------- */
void cjk_sleep_ms(int64_t millis) {
    if (millis <= 0) {
        return;
    }
#if CJK_PLATFORM_WINDOWS
    Sleep((DWORD)millis);
#else
    struct timespec 请求;
    请求.tv_sec = (time_t)(millis / 1000);
    请求.tv_nsec = (long)((millis % 1000) * 1000000);
    while (nanosleep(&请求, &请求) != 0) {
        /* EINTR 时 nanosleep 已把剩余时间写回请求，继续睡。 */
    }
#endif
}
