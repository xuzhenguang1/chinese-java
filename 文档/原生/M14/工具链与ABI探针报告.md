# M14 工具链与 ABI 探针报告

> T1 产出。本文数值是 LLVM 平台配置与 `cjkrt.c` 静态断言的**唯一数据来源**。
> 记录时间：2026-11-20。

## 1. 工具链来源与校验

### musl 静态交叉工具链（xyg）

- musl.cc 预编译包下载极慢且反复 SSL 超时，**弃用**；改用 Alpine CDN 的 `musl-dev` / `musl` apk 作为 sysroot 来源（17 秒完成）。
- 来源：
  - `https://dl-cdn.alpinelinux.org/alpine/v3.20/main/x86_64/musl-dev-1.2.5-r4.apk`（3437930 字节）—— 提供头文件 + `crt1.o`/`crti.o`/`crtn.o` + `libc.a`。
  - `https://dl-cdn.alpinelinux.org/alpine/v3.20/main/x86_64/musl-1.2.5-r4.apk`（408411 字节）—— 提供动态加载器 `ld-musl-x86_64.so.1`（静态链接不需要）。
- 版本：musl 1.2.5-r4（Alpine v3.20）。
- 落地路径：`构建/工具链/alpine/sysroot/`（解压 musl-dev.apk）。
- 用途：提供 `x86_64-linux-musl` sysroot；**实际用本机 clang 23.1.5 + ld.lld 配该 sysroot 交叉编译**（gcc 是 Linux 二进制、Windows 不能直接跑）。
- 链接实测：`-nostdlib -static` + 手动 `crt1.o/crti.o/crtn.o` + `-lc` 即可静态链接并跑通；musl 1.2.5 已把 pthread/dl/rt/mcrypt 等并入 `libc.a`（对应 `libpthread.a` 等为 8 字节空归档），**无需 -lpthread/-lrt/-ldl**。

### qemu 运行器（方案偏差）

- 原方案假设"Windows 版 qemu-user（`qemu-x86_64.exe`）"经实测**不存在**：
  - 官方 Stefan Weil Windows 安装包 `qemu-w64-setup-20260811.exe`（206615928 字节）SHA512 校验一致：`5bcf9eed634e8575a37b74f445af41a2fe4106da512d0c30c368301d4c105037fdfab40a5287367a28a957624cddebbc8c07e16c88ab6634f554cdf3d16bf543`。
  - 安装后 65 个 exe 全是 `qemu-system-*`，无任何 linux-user 模式运行器；MSYS2 的 qemu 包同源。
  - WSL 被工具层安全策略禁止（`wsl.exe` 命中 Program Blacklist）。
- **采用替代路径**（用户 2026-11-20 确认）：`qemu-system-x86_64` + Alpine `linux-virt` 内核 + `minirootfs` 构造最小 Linux VM，用 `-kernel`/`-initrd` 启动、串口捕获输出验证 ELF。
  - Alpine 内核：`https://dl-cdn.alpinelinux.org/alpine/v3.20/main/x86_64/linux-virt-6.6.142-r0.apk`，提取 `boot/vmlinuz-virt`（10867712 字节）。
  - Alpine rootfs：`https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-minirootfs-3.20.3-x86_64.tar.gz`（3490290 字节）。
  - 中文路径会导致 qemu 打不开 BIOS/内核，需用 ASCII junction：`E:\cjk-qemu` → `构建\工具链\qemu`、`E:\cjk-alpine` → `构建\工具链\alpine`。
  - initramfs 用 `原生/测试/工具/构建initramfs.py` 生成（newc cpio，Python 打包，Windows 无 cpio 命令）。已冒烟验证：`===CJK_ELF_START===`/`===CJK_ELF_END===` 串口输出成功。

## 2. ABI 探针实测值（唯一数据来源）

> 2026-11-20 实测。编译：`clang 23.1.5 --target=x86_64-linux-musl --sysroot=.../sysroot -nostdlib -static -O2 -std=c11 -D_POSIX_C_SOURCE=200809L -fuse-ld=lld`，程序 `构建/工具链/abi_probe.c`，经 Alpine VM（qemu-system-x86_64 + serial）运行输出。**这些值是 LLVM 平台配置与 cjkrt.c 静态断言的唯一来源，禁止照搬推测。**

| 探针项 | 实测值 | Windows 对照 | 用途 |
| --- | --- | --- | --- |
| `sizeof(jmp_buf)` | **200** | 256 | `%cjk_handler` 元素类型 `[25 x i64]`（200/8=25） |
| `_Alignof(jmp_buf)` | **8** | 16 | `alloca %cjk_handler` align = 8 |
| `sizeof(cjk_handler)` | **216** | 272 | 布局核对（200 + 8 + 8） |
| `_Alignof(cjk_handler)` | **8** | 16 | 结构对齐核对 |
| `offsetof(cjk_handler, prev)` | **200** | 256 | `_Static_assert` + handler helper GEP |
| `offsetof(cjk_handler, exception)` | **208** | 264 | `_Static_assert` + handler helper GEP |
| `sizeof(cjk_value)` / `_Alignof` | **16 / 8** | 16 / 8 | SysV 两个 eightbyte 按值走寄存器 |
| `sizeof(void*)` / `sizeof(long)` | **8 / 8** | 8 / 8 | LP64 |
| `setjmp` 签名 | **单参 `setjmp(jmp_buf*)`** | 双参 `_setjmp(buf, frame)` | IR 声明 `i32 @setjmp(ptr) returns_twice`，无需 `llvm.frameaddress` |
| setjmp/longjmp 展开 | **通过**（code=42，exception 字段保留 ok） | 同 | handler 布局正确性端到端验证 |
| pthread_create/join | **通过**（ret=42） | Win32 线程 | 线程栈扫描依赖 |
| clock_gettime(CLOCK_REALTIME) | **通过** | — | 链接参数 |
| pthread/clock 链接库 | **仅 `-lc`**（musl 1.2.5 合并入 libc） | Win32 系统库 | 链接参数 |

## 3. 本机交叉编译能力（M13 已实测）

- clang 23.1.5 + ld.lld：能产合法 ELF64 对象与可执行文件（`-nostdlib -static`，仅缺 `_start`）。
- 缺的只是 libc/sysroot，由 musl 工具链补齐；无需从零搭工具链。

## 4. 实施期发现与修正（2026-11-21）

### 4.1 运行器偏差：Windows 无 qemu-user

- 方案原定 `qemu-x86_64.exe`（user-mode）直接跑 ELF；实测官方 Windows qemu 包（11.1.0，SHA512 校验通过）只有 `qemu-system-*`，无 user-mode；MSYS2 同源，WSL 被安全策略禁用。
- 改用 `qemu-system-x86_64` + Alpine v3.20 `linux-virt` 内核 + `minirootfs` + 自建 newc initramfs，串口 `ttyS0` 捕获输出。qemu 打不开中文路径，统一经 ASCII junction `E:/cjk-qemu` / `E:/cjk-alpine`。
- init 脚本跑完 `/sbin/poweroff -f` 自动关机，批量 35 课一次 VM 约 5 秒。
- newc cpio 打包器关键修正：目录项 header+name 必须 pad4，否则内核报 `no cpio magic`。

### 4.2 musl sysroot 来源变更

- musl.cc 预编译包分片下载反复 SSL 超时，改用 Alpine CDN `musl-dev-1.2.5-r4.apk` + `musl-1.2.5-r4.apk`（17 秒完成），解压得到 `sysroot/usr/lib/{crt1.o,crti.o,crtn.o,libc.a}` 与头文件。
- 交叉编译命令：`clang --target=x86_64-linux-musl --sysroot=<sysroot> -nostdlib -fuse-ld=lld -static -D_GNU_SOURCE <src> crt1.o crti.o -o out.elf -lc crtn.o`。
- `-D_GNU_SOURCE` 必需：`pthread_getattr_np` 是 GNU 扩展（同时涵盖 `clock_gettime`）；仅 `-D_POSIX_C_SOURCE` 不够。

### 4.3 中文异常消息乱码修复（网络冒烟中发现）

- 现象：Linux ELF 的 `网络失败()` 抛异常时，中文原因显示为 `æ æ³•...`（UTF-8 字节被当 Latin-1）。
- 根因：`标准库.c` Linux 分支用 `缓冲追加ASCII(&b, 原因)` 追加中文 UTF-8 字面量——该函数逐字节转 `uint16_t`，多字节中文被拆成 Latin-1 字符。
- 修复：改为 `cjk_str_from_utf8(原因)` + `缓冲追加文本`。修复后正确显示 `无法连接主机：http://127.0.0.1/hello`。Windows 分支未改动。

### 4.4 handler helper 决策（T2.3）

- 实测两侧访问天然类型安全：LLVM 用 GEP 类型索引（i32 1=prev / 2=exception）配合 IR `[25 x i64]`，C 侧用真实 struct 由编译器算偏移，均无硬编码字节偏移。
- 故不引入额外 handler helper，避免改 C 后端引入 Windows 回退风险；静态断言按 `_WIN64` / `__linux__` 分派，取值全部来自本节实测。
