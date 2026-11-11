/*
 * 中文 Java 原生运行时：保守标记-清除 GC（M4）
 *
 * 所有对象、字符串、数组都经 cjk_gc_alloc 分配；根包括 C 栈、setjmp
 * 保存的寄存器和显式登记的静态字段。保守扫描允许块内指针。
 */
#include "cjkrt.h"

#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <windows.h>

typedef struct cjk_块 {
    size_t 大小;
    int 已标记;
    struct cjk_块 *下一块;
} cjk_块;

/* 载荷按 16 字节对齐，满足 double / 指针要求。 */
#define CJK_块头 ((sizeof(cjk_块) + 15u) & ~(size_t)15u)

typedef struct cjk_根 {
    void *地址;
    size_t 大小;
    struct cjk_根 *下一根;
} cjk_根;

static cjk_块 *块表 = NULL;
static cjk_根 *根表 = NULL;
static size_t 堆字节 = 0;
static size_t 阈值 = 4u * 1024u * 1024u;
static char *栈底 = NULL;

/* 线程安全：块表与根表的增删都走同一把锁；多线程存活期间暂停自动回收。 */
static INIT_ONCE 锁初始化一次 = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION 堆锁;
static volatile LONG 存活线程数 = 1;

static BOOL CALLBACK 初始化堆锁(PINIT_ONCE 一次, PVOID 参数, PVOID *上下文) {
    (void)一次;
    (void)参数;
    (void)上下文;
    InitializeCriticalSection(&堆锁);
    return TRUE;
}

static void 确保堆锁(void) {
    InitOnceExecuteOnce(&锁初始化一次, 初始化堆锁, NULL, NULL);
}

void cjk_gc_enter_thread(void) {
    InterlockedIncrement(&存活线程数);
}

void cjk_gc_leave_thread(void) {
    InterlockedDecrement(&存活线程数);
}

static void *载荷(cjk_块 *b) {
    return (char *)b + CJK_块头;
}

void cjk_gc_init(void) {
    if (栈底 == NULL) {
        char 局部;
        栈底 = &局部;
    }
}

void cjk_gc_add_root(void *addr, size_t bytes) {
    if (addr == NULL || bytes == 0) {
        return;
    }
    cjk_根 *根 = (cjk_根 *)malloc(sizeof(cjk_根));
    if (根 == NULL) {
        fprintf(stderr, "GC 根登记失败：内存不足\n");
        exit(1);
    }
    根->地址 = addr;
    根->大小 = bytes;
    确保堆锁();
    EnterCriticalSection(&堆锁);
    根->下一根 = 根表;
    根表 = 根;
    LeaveCriticalSection(&堆锁);
}

void *cjk_gc_alloc(size_t bytes) {
    if (bytes == 0) {
        bytes = 1;
    }
    确保堆锁();
    EnterCriticalSection(&堆锁);
    /* 多线程存活时不做回收：保守扫描只看当前线程栈，会误回收其他线程的活跃对象。 */
    if (存活线程数 <= 1 && 堆字节 + CJK_块头 + bytes > 阈值) {
        cjk_gc_collect();
    }
    cjk_块 *b = (cjk_块 *)malloc(CJK_块头 + bytes);
    if (b == NULL) {
        cjk_gc_collect();
        b = (cjk_块 *)malloc(CJK_块头 + bytes);
        if (b == NULL) {
            LeaveCriticalSection(&堆锁);
            fprintf(stderr, "内存不足\n");
            exit(1);
        }
    }
    b->大小 = bytes;
    b->已标记 = 0;
    b->下一块 = 块表;
    块表 = b;
    堆字节 += CJK_块头 + bytes;
    void *结果 = 载荷(b);
    memset(结果, 0, bytes);
    LeaveCriticalSection(&堆锁);
    return 结果;
}

static int 比较块(const void *甲, const void *乙) {
    const cjk_块 *a = *(const cjk_块 *const *)甲;
    const cjk_块 *b = *(const cjk_块 *const *)乙;
    char *pa = (char *)载荷((cjk_块 *)a);
    char *pb = (char *)载荷((cjk_块 *)b);
    return pa < pb ? -1 : (pa > pb ? 1 : 0);
}

static cjk_块 **排序表 = NULL;
static size_t 排序数 = 0;

/* 在排序表里找包含候选地址的块；允许指向块内部。 */
static cjk_块 *定位(void *候选) {
    char *p = (char *)候选;
    size_t 低 = 0;
    size_t 高 = 排序数;
    while (低 < 高) {
        size_t 中 = (低 + 高) / 2;
        char *起 = (char *)载荷(排序表[中]);
        if (起 <= p) {
            低 = 中 + 1;
        } else {
            高 = 中;
        }
    }
    if (低 == 0) {
        return NULL;
    }
    cjk_块 *b = 排序表[低 - 1];
    char *起 = (char *)载荷(b);
    if (p >= 起 && p < 起 + b->大小) {
        return b;
    }
    return NULL;
}

static cjk_块 **待扫表 = NULL;
static size_t 待扫数 = 0;
static size_t 待扫容量 = 0;

static void 标记(cjk_块 *b) {
    if (b == NULL || b->已标记) {
        return;
    }
    b->已标记 = 1;
    if (待扫数 == 待扫容量) {
        待扫容量 = 待扫容量 == 0 ? 1024 : 待扫容量 * 2;
        待扫表 = (cjk_块 **)realloc(待扫表, 待扫容量 * sizeof(cjk_块 *));
        if (待扫表 == NULL) {
            fprintf(stderr, "GC 标记栈扩容失败\n");
            exit(1);
        }
    }
    待扫表[待扫数++] = b;
}

static void 扫描区间(unsigned char *起, unsigned char *止) {
    if (起 == NULL || 止 == NULL || 起 >= 止) {
        return;
    }
    uintptr_t 对齐 = (uintptr_t)起;
    对齐 = (对齐 + sizeof(void *) - 1) & ~(uintptr_t)(sizeof(void *) - 1);
    for (unsigned char *p = (unsigned char *)对齐; p + sizeof(void *) <= 止;
         p += sizeof(void *)) {
        void *候选;
        memcpy(&候选, p, sizeof(void *));
        if (候选 == NULL) {
            continue;
        }
        标记(定位(候选));
    }
}

void cjk_gc_collect(void) {
    if (块表 == NULL) {
        return;
    }
    确保堆锁();
    EnterCriticalSection(&堆锁);

    排序数 = 0;
    for (cjk_块 *b = 块表; b != NULL; b = b->下一块) {
        b->已标记 = 0;
        排序数++;
    }
    if (排序表 != NULL) {
        free(排序表);
    }
    排序表 = (cjk_块 **)malloc(排序数 * sizeof(cjk_块 *));
    if (排序表 == NULL) {
        fprintf(stderr, "GC 排序表分配失败\n");
        exit(1);
    }
    size_t i = 0;
    for (cjk_块 *b = 块表; b != NULL; b = b->下一块) {
        排序表[i++] = b;
    }
    qsort(排序表, 排序数, sizeof(cjk_块 *), 比较块);

    待扫数 = 0;

    /* 显式登记的全局根（静态字段、枚举常量数组）。 */
    for (cjk_根 *根 = 根表; 根 != NULL; 根 = 根->下一根) {
        扫描区间((unsigned char *)根->地址,
                 (unsigned char *)根->地址 + 根->大小);
    }

    /* setjmp 保存的寄存器。 */
    {
        jmp_buf 寄存器;
        memset(&寄存器, 0, sizeof(寄存器));
        if (setjmp(寄存器) == 0) {
            /* 只为把寄存器内容落到内存。 */
        }
        扫描区间((unsigned char *)&寄存器,
                 (unsigned char *)&寄存器 + sizeof(寄存器));
    }

    /* C 栈：从当前帧到 cjk_gc_init 记录的栈底。 */
    {
        char 局部;
        unsigned char *甲 = (unsigned char *)&局部;
        unsigned char *乙 = (unsigned char *)栈底;
        if (甲 > 乙) {
            unsigned char *临时 = 甲;
            甲 = 乙;
            乙 = 临时;
        }
        扫描区间(甲, 乙);
    }

    while (待扫数 > 0) {
        cjk_块 *b = 待扫表[--待扫数];
        扫描区间((unsigned char *)载荷(b),
                 (unsigned char *)载荷(b) + b->大小);
    }

    /* 清除未标记块。 */
    size_t 活动字节 = 0;
    cjk_块 **链接 = &块表;
    while (*链接 != NULL) {
        cjk_块 *b = *链接;
        if (b->已标记) {
            活动字节 += CJK_块头 + b->大小;
            链接 = &b->下一块;
        } else {
            *链接 = b->下一块;
            free(b);
        }
    }
    堆字节 = 活动字节;
    阈值 = 活动字节 * 2 + 1024u * 1024u;
    if (阈值 < 1024u * 1024u) {
        阈值 = 1024u * 1024u;
    }
    LeaveCriticalSection(&堆锁);
}
