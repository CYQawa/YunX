#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""云析 YunX - 从 Gopeed 的 AAR 重新生成桥接依赖 app/libs/gopeed-classes.jar

背景（详见 Agent.md §3.27 与 §3.28）：
  gomobile 生成的 go.Seq 静态初始化里写死了 System.loadLibrary("gojni")：

      31: ldc "gojni"
      33: invokestatic java/lang/System.loadLibrary(String)
      36: invokestatic go/Seq.init()V
      39: invokestatic go/Universe.touch()V

  而 Gopeed 官方编出来的 libgojni.so 没有 DT_SONAME（只有 DT_NEEDED），
  Android linker 只按 soname 认「已经加载过的库」。于是即使我们先
  System.load("/data/user/0/<包名>/files/gopeed/lib/libgojni.so") 成功了，
  这行 loadLibrary 也找不到任何东西，必然抛：

      dalvik.system.PathClassLoader[DexPathList[[zip file ".../base.apk"],
      nativeLibraryDirectories=[...]]] couldn't find "libgojni.so"

  并且 go.Seq 类初始化失败会被 JVM 永久记住，第二次点击引擎就变成
  NoClassDefFoundError: com.gopeed.libgopeed.Libgopeed（必须杀进程重开）。

修法：把上面那两条指令（ldc 2 字节 + invokestatic 3 字节 = 共 5 字节）
  原地替换成 5 个 nop（0x00）。指令总长度不变，所有偏移、异常表、
  StackMapTable 都不受影响，clinit 的 max_stack 只会变小（仍然合法）。
  引擎库改由 GopeedEngine 在触碰任何 go.* / com.gopeed.* 类之前，
  先用 System.load(绝对路径) 加载（.so 打包进 APK 时回退 loadLibrary）。

  脚本只改这一处，其余字节一律原样拷贝，并强制校验「恰好命中一次」。

用法：
  python3 tools/patch-gopeed-classes.py <libgopeed-<abi>.aar> app/libs/gopeed-classes.jar
  python3 tools/patch-gopeed-classes.py <classes.jar>        app/libs/gopeed-classes.jar

  第一个参数也可以是 AAR（会自动取其中的 classes.jar）。
"""

import io
import struct
import sys
import zipfile

TARGET_CLASS = "go/Seq.class"
LOAD_LIBRARY = "java/lang/System.loadLibrary"
NOP = 0x00


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def u1(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def u2(self):
        v = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def u4(self):
        v = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def take(self, n):
        v = self.data[self.pos:self.pos + n]
        self.pos += n
        return v

    def skip(self, n):
        self.pos += n


def parse_pool(r):
    """返回常量池列表；下标即常量池索引（long/double 占两个槽位）。"""
    count = r.u2()
    pool = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            pool[i] = ("Utf8", r.take(r.u2()).decode("utf-8", "replace"))
        elif tag in (3, 4):
            pool[i] = ("Int", r.u4())
        elif tag in (5, 6):
            pool[i] = ("Long", r.take(8))
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag, r.u2(), r.u2())
        elif tag == 15:
            pool[i] = (15, r.u1(), r.u2())
        else:
            raise SystemExit("常量池出现未知 tag=%d（索引 %d）" % (tag, i))
        i += 1
    return pool


def utf(pool, idx):
    e = pool[idx]
    if not e or e[0] != "Utf8":
        raise SystemExit("常量池 %d 不是 Utf8" % idx)
    return e[1]


def skip_attributes(r):
    for _ in range(r.u2()):
        r.u2()
        r.skip(r.u4())


def find_clinit_code(data):
    """定位 <clinit> 的 Code 属性：返回 (code 起始绝对偏移, code 长度, 常量池)。"""
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise SystemExit("不是 class 文件（magic 不对）")
    r.u2()
    r.u2()
    pool = parse_pool(r)
    r.u2()                      # access_flags
    r.u2()                      # this_class
    r.u2()                      # super_class
    r.skip(r.u2() * 2)          # interfaces
    for _ in range(r.u2()):     # fields
        r.u2()
        r.u2()
        r.u2()
        skip_attributes(r)
    found = None
    for _ in range(r.u2()):     # methods
        r.u2()
        name = utf(pool, r.u2())
        r.u2()
        for _ in range(r.u2()):
            attr_name = utf(pool, r.u2())
            length = r.u4()
            if attr_name == "Code":
                body_start = r.pos
                r.u2()          # max_stack
                r.u2()          # max_locals
                code_len = r.u4()
                code_start = r.pos
                if name == "<clinit>":
                    found = (code_start, code_len, pool)
                r.pos = body_start + length
            else:
                r.skip(length)
    if found is None:
        raise SystemExit("没有找到 <clinit> 方法")
    return found


def scan_load_library(data):
    """扫描 <clinit>，返回 (code 起始偏移, 常量池, [(指令偏移, ldc 长度)])。"""
    code_start, code_len, pool = find_clinit_code(data)
    code = data[code_start:code_start + code_len]
    hits = []
    i = 0
    while i < len(code) - 4:
        # ldc（1 字节索引，2 字节）或 ldc_w（2 字节索引，3 字节）后面紧跟 invokestatic（3 字节）
        op = code[i]
        if op == 0x12:
            ld_len = 2
            str_idx = code[i + 1]
        elif op == 0x13:
            ld_len = 3
            str_idx = struct.unpack_from(">H", code, i + 1)[0]
        else:
            i += 1
            continue
        if code[i + ld_len] == 0xB8:
            method_idx = struct.unpack_from(">H", code, i + ld_len + 1)[0]
            entry = pool[str_idx] if str_idx < len(pool) else None
            if entry and entry[0] == 8:      # CONSTANT_String → 再取 Utf8
                entry = pool[entry[1]]
            ref = pool[method_idx] if method_idx < len(pool) else None
            if entry and entry[0] == "Utf8" and entry[1] == "gojni" and ref and ref[0] == 10:
                klass = utf(pool, pool[ref[1]][1])
                member = utf(pool, pool[ref[2]][1])
                if klass == "java/lang/System" and member == "loadLibrary":
                    hits.append((i, ld_len))
        i += 1
    return code_start, pool, hits


def patch_seq_class(data):
    """把 <clinit> 里 loadLibrary 的那条指令（2/3 字节 + 3 字节）换成 nop。"""
    code_start, pool, hits = scan_load_library(data)
    if len(hits) != 1:
        raise SystemExit("预期恰好命中 1 处 System.loadLibrary(\"gojni\")，实际 %d 处，已放弃修改" % len(hits))
    at = code_start + hits[0][0]
    span = hits[0][1] + 3
    original = bytes(data[at:at + span])
    patched = bytearray(data)
    for k in range(span):
        patched[at + k] = NOP
    # 复核：补丁后该 class 里不应再有任何 loadLibrary("gojni") 调用
    leftover = scan_load_library(bytes(patched))[2]
    if leftover:
        raise SystemExit("补丁后仍能扫到 %d 处 loadLibrary 调用，已放弃修改" % len(leftover))
    return bytes(patched), at, original, span


def load_jar_bytes(path):
    with open(path, "rb") as f:
        head = f.read(4)
    if head[:2] == b"PK" and zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            if "classes.jar" in names:
                sys.stderr.write("输入是 AAR，取其中的 classes.jar\n")
                return z.read("classes.jar")
            return open(path, "rb").read()
    raise SystemExit("无法识别的输入：%s" % path)


def main():
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    jar_bytes = load_jar_bytes(src)
    zin = zipfile.ZipFile(io.BytesIO(jar_bytes))
    if TARGET_CLASS not in zin.namelist():
        raise SystemExit("输入的 jar 里没有 %s" % TARGET_CLASS)

    patched_bytes = None
    patch_at = 0
    original = b""
    span = 0
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            payload = zin.read(item.filename)
            if item.filename == TARGET_CLASS:
                payload, patch_at, original, span = patch_seq_class(payload)
                patched_bytes = payload
            info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            info.compress_type = item.compress_type
            info.external_attr = item.external_attr
            zout.writestr(info, payload)

    before = zin.read(TARGET_CLASS)
    diff = [k for k in range(len(before)) if before[k] != patched_bytes[k]]
    outside = [k for k in diff if not (patch_at <= k < patch_at + span)]
    if outside:
        raise SystemExit("内部校验失败：改动溢出到补丁区间之外（%s）" % outside[:8])
    if not diff:
        raise SystemExit("内部校验失败：字节码没有任何改动")
    sys.stderr.write("已生成 %s\n" % dst)
    sys.stderr.write("  %s 的 <clinit> 里 System.loadLibrary(\"gojni\") 已替换为 nop\n" % TARGET_CLASS)
    sys.stderr.write("  位置：class 文件偏移 %d，原字节 %s\n"
                     % (patch_at, " ".join("%02X" % b for b in original)))
    sys.stderr.write("  实际变化 %d 个字节（区间共 %d 字节，其余本就是 0x00）\n" % (len(diff), span))
    return 0


if __name__ == "__main__":
    sys.exit(main())
