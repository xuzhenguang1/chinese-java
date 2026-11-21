r"""M14 运行器 VM 启动辅助：在 Alpine VM 里批量运行 ELF，输出串口文本到 stdout。

用 subprocess 启动 qemu-system-x86_64，捕获合并输出（stdout+stderr），
VM 跑完 poweroff 自动退出；超时则 kill。输出原样写 stdout（UTF-8 字节）。

用法: python 运行VM.py <initrd.gz> <输出文件> [超时秒=180]
依赖 ASCII junction: E:\cjk-qemu, E:\cjk-alpine（qemu 打不开中文路径）。
"""
import os
import sys
import subprocess

QEMU = r"E:\cjk-qemu\qemu-system-x86_64.exe"
KERNEL = r"E:\cjk-alpine\boot\vmlinuz-virt"
ALPINE = r"E:\cjk-alpine"


def main():
    if len(sys.argv) < 3:
        print("usage: python 运行VM.py <initrd.gz> <out.txt> [timeout_sec]")
        sys.exit(2)
    initrd = sys.argv[1]
    out_path = sys.argv[2]
    timeout = int(sys.argv[3]) if len(sys.argv) > 3 else 180

    if not os.path.isfile(QEMU) or not os.path.isfile(KERNEL):
        print("VM 运行器：qemu/kernel 不存在（检查 ASCII junction）", file=sys.stderr)
        sys.exit(2)

    # 把 initrd 拷到 ASCII 路径（qemu 无法打开中文路径）。
    ascii_initrd = os.path.join(ALPINE, "runner-initrd.gz")
    import shutil
    shutil.copyfile(initrd, ascii_initrd)

    args = [
        QEMU,
        "-kernel", KERNEL,
        "-initrd", ascii_initrd,
        "-append", "console=ttyS0 rdinit=/init quiet",
        "-nographic", "-no-reboot", "-m", "256",
    ]
    try:
        proc = subprocess.run(args, capture_output=True, timeout=timeout)
        # qemu -nographic 把内核与程序输出都写 stdout；字节级保留，UTF-8。
        with open(out_path, "wb") as f:
            f.write(proc.stdout)
            f.write(b"\n")
        try:
            os.remove(ascii_initrd)
        except OSError:
            pass
        sys.exit(proc.returncode)
    except subprocess.TimeoutExpired as e:
        with open(out_path, "wb") as f:
            f.write(e.stdout or b"")
        try:
            os.remove(ascii_initrd)
        except OSError:
            pass
        print("VM 运行器：超时 %d 秒" % timeout, file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
