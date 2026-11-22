r"""M17 CI：下载并校验 ELF 运行资产，构造与本机一致的工具链目录。

用途：在 ubuntu-latest runner 上构建以下目录（与本机 M14 口径一致）：
  构建/工具链/alpine/sysroot/usr/lib/{crt1.o,crti.o,crtn.o,libc.a}  ← musl-dev.apk
  构建/工具链/alpine/boot/vmlinuz-virt                              ← linux-virt.apk
  构建/工具链/alpine/minirootfs.tar.gz                              ← 直接下载

所有下载按 M14 报告的 SHA256 校验，失败即退出 2（防资产漂移）。
已存在且校验通过的资产会跳过（配合 Actions cache）。

用法: python 工具/CI/准备ELF资产.py
"""
import os
import sys
import hashlib
import urllib.request
import tarfile
import shutil

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ALPINE = os.path.join(ROOT, "构建", "工具链", "alpine")
CDN = "https://dl-cdn.alpinelinux.org/alpine/v3.20"

# (相对落地路径, URL, SHA256, 解包动作)
# 解包动作：None=原样落地；"musl-dev"=解 apk 到 sysroot；"kernel"=解 vmlinuz-virt 到 boot/
ASSETS = [
    ("apk/musl-dev.apk", f"{CDN}/main/x86_64/musl-dev-1.2.5-r4.apk",
     "e9cca1709f3a740976f22cc27b825261de062d97e7de2d08c41243017c389338", "musl-dev"),
    ("apk/musl.apk", f"{CDN}/main/x86_64/musl-1.2.5-r4.apk",
     "b255cdcf4f64eb301f4dbd40d1fbdc0fcb021613b3f0d124a41eeff62e2bc7d8", None),
    ("apk/linux-virt.apk", f"{CDN}/main/x86_64/linux-virt-6.6.142-r0.apk",
     "0d84e30c04c52426b0bf10b01616c45f7d0c5384a59552e4c4a6caeb13a3f6e7", "kernel"),
    ("minirootfs.tar.gz", f"{CDN}/releases/x86_64/alpine-minirootfs-3.20.3-x86_64.tar.gz",
     "d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c", None),
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def download(url, dest, expect_sha):
    if os.path.isfile(dest) and sha256(dest) == expect_sha:
        print("命中缓存：%s" % dest)
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print("下载：%s" % url)
    with urllib.request.urlopen(url, timeout=120) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    got = sha256(dest)
    if got != expect_sha:
        print("SHA256 不匹配：%s 期望 %s 实际 %s" % (dest, expect_sha, got), file=sys.stderr)
        sys.exit(2)


def unpack(action, apk_path):
    if action == "musl-dev":
        sysroot = os.path.join(ALPINE, "sysroot")
        os.makedirs(sysroot, exist_ok=True)
        # musl-dev.apk 内容以 usr/ 开头，直接解到 sysroot/usr
        with tarfile.open(apk_path, "r:gz") as tf:
            for member in tf.getmembers():
                if member.name == "usr" or member.name.startswith("usr/"):
                    out = os.path.join(sysroot, member.name)
                    if member.isdir():
                        os.makedirs(out, exist_ok=True)
                    elif member.isfile():
                        os.makedirs(os.path.dirname(out), exist_ok=True)
                        src = tf.extractfile(member)
                        with src, open(out, "wb") as f:
                            shutil.copyfileobj(src, f)
        print("解包 musl sysroot → %s" % sysroot)
    elif action == "kernel":
        boot = os.path.join(ALPINE, "boot")
        os.makedirs(boot, exist_ok=True)
        out = os.path.join(boot, "vmlinuz-virt")
        with tarfile.open(apk_path, "r:gz") as tf:
            for member in tf.getmembers():
                if member.name == "boot/vmlinuz-virt":
                    src = tf.extractfile(member)
                    with src, open(out, "wb") as f:
                        shutil.copyfileobj(src, f)
                    break
        got = sha256(out)
        expect = "d341acf38aca9f9af67641e3241ef1075c0368a59774485fa076f271182e8c00"
        if got != expect:
            print("vmlinuz-virt SHA256 不匹配：期望 %s 实际 %s" % (expect, got), file=sys.stderr)
            sys.exit(2)
        print("解包内核 → %s" % out)


def main():
    os.makedirs(ALPINE, exist_ok=True)
    for rel, url, expect, action in ASSETS:
        dest = os.path.join(ALPINE, rel.replace("/", os.sep))
        download(url, dest, expect)
        if action:
            unpack(action, dest)
    # 关键文件存在性校验（CI 早失败）
    required = [
        os.path.join(ALPINE, "sysroot", "usr", "lib", "crt1.o"),
        os.path.join(ALPINE, "sysroot", "usr", "lib", "crti.o"),
        os.path.join(ALPINE, "sysroot", "usr", "lib", "crtn.o"),
        os.path.join(ALPINE, "sysroot", "usr", "lib", "libc.a"),
        os.path.join(ALPINE, "boot", "vmlinuz-virt"),
        os.path.join(ALPINE, "minirootfs.tar.gz"),
    ]
    missing = [p for p in required if not os.path.isfile(p)]
    if missing:
        print("资产构造不完整，缺少：\n  " + "\n  ".join(missing), file=sys.stderr)
        sys.exit(2)
    print("ELF 资产构造完成，%d 个关键文件就位。" % len(required))


if __name__ == "__main__":
    main()
