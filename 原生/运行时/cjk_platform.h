/*
 * 中文 Java 原生运行时：平台抽象层（M13 / ADR-017）
 *
 * 把运行时对 Windows API 的裸露依赖收口到统一接口。契约：
 *   - Windows 分支 typedef / 宏直接映射到现有 Win32 原语，编译产物与原实现等价
 *     （Windows 行为是唯一硬验收口径，逐字节复用，不做重写）。
 *   - Linux 分支映射到 pthread / clock_gettime，文件与网络 IO 给出明确中文诊断桩，
 *     不静默返回空值。本机无 Linux sysroot，运行验收顺延 M14（见 ADR-017）。
 *
 * 平台选择：默认跟随编译器（_WIN32）。交叉编译时可用 -DCJK_PLATFORM_LINUX 强制 Linux 骨架。
 */
#ifndef CJK_PLATFORM_H
#define CJK_PLATFORM_H

#include <stddef.h>
#include <stdint.h>
#include <wchar.h>
#include "cjkrt.h"

#if defined(_WIN32) && !defined(CJK_PLATFORM_LINUX)
#  define CJK_PLATFORM_WINDOWS 1
#else
#  define CJK_PLATFORM_WINDOWS 0
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ---------------- 六类接口之一：路径分隔符常量 ---------------- */
#if CJK_PLATFORM_WINDOWS
#  define CJK_PATH_SEP    '\\'
#  define CJK_PATH_SEP_W  L'\\'
#else
#  define CJK_PATH_SEP    '/'
#  define CJK_PATH_SEP_W  L'/'
#endif

/* ---------------- 六类接口之一：线程局部存储关键字 ----------------
 * 两端都用 clang（MinGW-w64 clang / Linux clang），均支持 GNU __thread；
 * 迁移前 标准库.c 即用 __thread，保持逐字节一致（勿改 __declspec(thread)，
 * MinGW clang 在 -std=c11 下会忽略它并报警告）。 */
#define CJK_THREAD_LOCAL __thread

/* ---------------- 六类接口之一：锁 / once / 原子增减 ----------------
 * 类型按值嵌入结构体，故必须是完整类型。操作用宏，Windows 分支直接展开成
 * 原 Win32 调用（逐字节复用），Linux 分支展开成对 cjk_platform.c 的包装。 */
#if CJK_PLATFORM_WINDOWS
#  define WIN32_LEAN_AND_MEAN
#  include <windows.h>
   typedef CRITICAL_SECTION   cjk_lock_t;
   typedef CONDITION_VARIABLE cjk_cond_t;
   typedef HANDLE             cjk_thread_t;
   typedef INIT_ONCE          cjk_once_t;
#  define CJK_ONCE_INIT       INIT_ONCE_STATIC_INIT
   typedef BOOL (CALLBACK *cjk_once_cb)(cjk_once_t *, void *, void **);
#  define CJK_THREAD_CALL    __stdcall
#  define cjk_lock_init(m)        InitializeCriticalSection(m)
#  define cjk_lock_enter(m)       EnterCriticalSection(m)
#  define cjk_lock_leave(m)       LeaveCriticalSection(m)
#  define cjk_cond_init(c)        InitializeConditionVariable(c)
#  define cjk_cond_wait(c, m)     SleepConditionVariableCS((c), (m), INFINITE)
#  define cjk_cond_wake_all(c)    WakeAllConditionVariable(c)
   typedef LONG               cjk_atomic_i32_t;
#  define cjk_atomic_inc32(p)     InterlockedIncrement((volatile LONG *)(p))
#  define cjk_atomic_dec32(p)     InterlockedDecrement((volatile LONG *)(p))
#  define cjk_once_call(o, cb)    InitOnceExecuteOnce((o), (cb), NULL, NULL)
#else
#  include <pthread.h>
   typedef pthread_mutex_t    cjk_lock_t;
   typedef pthread_cond_t     cjk_cond_t;
   typedef pthread_t          cjk_thread_t;
   typedef pthread_once_t     cjk_once_t;
   typedef int32_t            cjk_atomic_i32_t;
#  define CJK_ONCE_INIT       PTHREAD_ONCE_INIT
   typedef void (*cjk_once_cb)(void);
#  define CJK_THREAD_CALL
   void cjk_lock_init(cjk_lock_t *m);
   void cjk_lock_enter(cjk_lock_t *m);
   void cjk_lock_leave(cjk_lock_t *m);
   void cjk_cond_init(cjk_cond_t *c);
   void cjk_cond_wait(cjk_cond_t *c, cjk_lock_t *m);
   void cjk_cond_wake_all(cjk_cond_t *c);
   int32_t cjk_atomic_inc32(volatile int32_t *p);
   int32_t cjk_atomic_dec32(volatile int32_t *p);
   int  cjk_once_call(cjk_once_t *o, cjk_once_cb cb);
#endif

/* 一次性初始化的进程全局锁：调用方无需关心 once 状态。 */
cjk_lock_t *cjk_global_mutex(void);

/* 线程创建 / 等待：统一签名，入口由 cjk_platform.c 适配 Win32 / pthread。 */
cjk_thread_t cjk_thread_start(unsigned (CJK_THREAD_CALL *入口)(void *), void *参数);
void cjk_thread_join(cjk_thread_t 线程);

/* ---------------- 六类接口之一：线程栈顶（保守 GC 扫栈上界） ----------------
 * 必须返回非 NULL 的栈高地址端；Linux 用 pthread 栈属性取上界，不静默返回 NULL。 */
unsigned char *cjk_thread_stack_top(void);

/* ---------------- 六类接口之一：墙上时钟毫秒（UTC 纪元，对齐 Java） ---------------- */
int64_t cjk_wall_millis(void);

/* ---------------- 六类接口之一：控制台 UTF-8 初始化 ----------------
 * Windows 切 CP_UTF8；Linux 为空操作（终端默认 UTF-8）。 */
void cjk_console_init_utf8(void);

/* ---------------- 六类接口之一：命令行参数 ----------------
 * 返回"程序参数"数组（不含 argv[0]，与 Java 对齐）。值.c 的 cjk_set_args
 * 原样委托本接口：Windows 走 CommandLineToArgvW 保留中文路径，Linux 用 argv。 */
cjk_array *cjk_platform_program_args(int argc, char **argv);

/* 辅助：睡眠指定毫秒（供标准库 cjk_sleep 使用）。 */
void cjk_sleep_ms(int64_t millis);

#ifdef __cplusplus
}
#endif

#endif /* CJK_PLATFORM_H */
