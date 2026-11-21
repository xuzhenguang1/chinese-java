"""M14 运行器：把 Alpine minirootfs + 待运行 ELF 打成 newc 格式 initramfs.gz。

newc cpio 格式简单，Windows 无 cpio 命令时用 Python 生成。
init 脚本挂载 proc/sys 后执行 /tests/ 下的程序，输出到串口（ttyS0），
qemu-system 用 -kernel + -initrd 启动，console=ttyS0 捕获输出。

用法: python 构建initramfs.py <rootfs.tar.gz> <输出initrd.gz> [测试文件...]
"""
import os
import sys
import gzip
import tarfile
import stat


def newc_header(name, size, mode, mtime=0, ino=0):
    name_b = name.encode("utf-8")
    fields = [
        "070701", "%08X" % (ino & 0xFFFFFFFF), "%08X" % (mode & 0xFFFFFFFF),
        "%08X" % 0, "%08X" % 0, "%08X" % 1, "%08X" % (mtime & 0xFFFFFFFF),
        "%08X" % (size & 0xFFFFFFFF), "%08X" % 0, "%08X" % 0,
        "%08X" % 0, "%08X" % 0, "%08X" % (len(name_b) + 1), "%08X" % 0,
    ]
    return "".join(fields).encode("ascii") + name_b + b"\x00"


def pad4(buf):
    while len(buf) % 4:
        buf += b"\x00"
    return buf


def add_file(out, name, data, mode=0o100644, ino=1):
    out += newc_header(name, len(data), mode, ino=ino)
    # newc：header+name 必须 pad 到 4 字节边界，然后放 data，再 pad 到 4 字节。
    out = pad4(out)
    out += pad4(data)
    return out


def add_dir(out, name, ino):
    out += newc_header(name, 0, 0o040755, ino=ino)
    out = pad4(out)
    return out


def build(rootfs_tgz, out_gz, test_files, init_pre=b""):
    # init_pre：可选的 init 前置 shell 片段（如网络冒烟时启动本地 httpd）。
    # 默认空，35 课对拍等既有调用行为零变化。
    init_script = (
        b"#!/bin/sh\n"
        b"mount -t proc proc /proc 2>/dev/null\n"
        b"mount -t sysfs sysfs /sys 2>/dev/null\n"
        + init_pre +
        b'echo "===CJK_ELF_START==="\n'
        b"for f in /tests/*; do\n"
        b'  [ -x "$f" ] || continue\n'
        b'  echo "===CJK_CASE:$(basename "$f")==="\n'
        b'  "$f"\n'
        b'  echo "===CJK_EXIT:$?==="\n'
        b"done\n"
        b'echo "===CJK_ELF_END==="\n'
        b"sync\n"
        # 跑完主动关机：qemu 检测到退出自动结束，对拍无需等满外部 timeout。
        # poweroff 失败时兜底挂起，避免 init 退出触发内核 panic。
        b"/sbin/poweroff -f 2>/dev/null\n"
        b"while true; do sleep 3600; done\n"
    )

    buf = b""
    ino = 1
    dirs = set()
    entries = []
    with tarfile.open(rootfs_tgz, "r:gz") as tf:
        for m in tf.getmembers():
            name = m.name.lstrip("./")
            if not name:
                continue
            entries.append((name, m))
            parts = name.split("/")
            for i in range(1, len(parts)):
                dirs.add("/".join(parts[:i]))
    dirs.add("tests")
    for d in sorted(dirs, key=lambda x: (x.count("/"), x)):
        buf = add_dir(buf, d, ino); ino += 1

    with tarfile.open(rootfs_tgz, "r:gz") as tf:
        for name, m in entries:
            if m.isdir():
                continue
            if m.issym():
                buf = add_file(buf, name, m.linkname.encode("utf-8"),
                               mode=0o120777, ino=ino); ino += 1
                continue
            if m.islnk():
                buf = add_file(buf, name, b"", mode=0o120777, ino=ino); ino += 1
                continue
            if not m.isfile():
                continue
            f = tf.extractfile(m)
            data = f.read() if f else b""
            mode = 0o100755 if (m.mode & stat.S_IXUSR) else 0o100644
            buf = add_file(buf, name, data, mode=mode, ino=ino); ino += 1

    buf = add_file(buf, "init", init_script, mode=0o100755, ino=ino); ino += 1
    for path in test_files:
        with open(path, "rb") as f:
            data = f.read()
        buf = add_file(buf, "tests/" + os.path.basename(path), data,
                       mode=0o100755, ino=ino); ino += 1

    buf += newc_header("TRAILER!!!", 0, 0, ino=0)
    buf = pad4(buf)
    with gzip.open(out_gz, "wb", compresslevel=6) as gz:
        gz.write(buf)
    print(f"initrd built: {out_gz} ({len(buf)} bytes, {len(test_files)} tests)")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("usage: python 构建initramfs.py <rootfs.tar.gz> <out.gz> [test files...]")
        sys.exit(2)
    init_pre = b""
    pre = os.environ.get("CJK_INIT_PRE", "")
    if pre:
        init_pre = pre.encode("utf-8")
        if not init_pre.endswith(b"\n"):
            init_pre += b"\n"
    build(sys.argv[1], sys.argv[2], sys.argv[3:], init_pre=init_pre)
