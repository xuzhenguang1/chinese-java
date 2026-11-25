/*
 * 中文 Java 原生运行时（M20 G5）
 *
 * 异常控制流统一走 Itanium C++ ABI：抛出点是本文件的 cjk_throw，
 * 捕获点由两个后端各自生成 landing（LLVM landingpad / C++ try-catch）。
 * PE 用平台 personality（__gxx_personality_seh0），ELF 用 DWARF
 * personality（__gxx_personality_v0），均通过编译器默认 ABI 选择，
 * 生成代码只需按平台声明对应 personality。
 *
 * 抛出的“对象”是一个 cjk_exception* 值：catch 侧直接拿到指针，
 * 继续沿用 cjk_is_instance 做父类型/接口匹配，不改异常类层级语义。
 */
#include <typeinfo>

#include "cjkrt.h"

extern "C" {
void *__cxa_allocate_exception(unsigned long);
void __cxa_throw(void *, void *, void (*)(void *));
void *__cxa_begin_catch(void *);
void __cxa_end_catch(void);
void __cxa_rethrow(void);
}

/* LLVM 后端若需要按类型 catch，可加载这个全局取得 cjk_exception* 的 typeinfo。 */
extern "C" const void *cjk_exception_typeinfo = &typeid(cjk_exception *);

extern "C" void cjk_throw(cjk_exception *e, int32_t line) {
    cjk_throw_异常 = e;
    cjk_throw_行 = line;
    void *对象 = __cxa_allocate_exception(sizeof(cjk_exception *));
    *(cjk_exception **)对象 = e;
    __cxa_throw(对象, (void *)&typeid(cjk_exception *), nullptr);
}

/* LLVM landingpad 拿到的是 ABI 头指针，必须经 begin_catch 换回抛出的对象。 */
extern "C" void *cjk_catch_object(void *着陆值) {
    return __cxa_begin_catch(着陆值);
}

extern "C" void cjk_end_catch(void) {
    __cxa_end_catch();
}

extern "C" void cjk_rethrow(void) {
    __cxa_rethrow();
}
