#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发布包发布前校验（便携 zip + 外部安装程序）。

用法:
    python tools/verify_release_assets.py <zip路径> [<exe路径> ...] --version 0.1.0.0

检查项（每一项都是历史上踩过的坑）：
  1. zip 可打开、全部条目 CRC 通过（testzip）；
  2. **中文名条目带 UTF-8 flag**——bsdtar 打的对非 ASCII 名不设该 flag，
     Java ZipFile 会报 "invalid CEN header"，游戏内自动更新（P-14）就解不开；
  3. 版本三处一致：包内 `version.txt` / `更新信息.txt` 首行 / `使用说明.txt` 首行；
  4. 关键文件存在：forge.exe、主 jar、README、更新信息、使用说明；
  5. 卡图在 `res/gbf-pics/GBF/` 且在包内（**不得**出现在 `res/pics/`——会触发启动迁移弹窗）；
  6. GBF 卡脚本抽样内容（Katalina 的 Optional/ConditionPlayerTurn、Sword 的 PT）。
"""
import argparse
import os
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REQUIRED = ["forge.exe", "version.txt", "更新信息.txt", "使用说明.txt", "README.md",
            "forge-gui-desktop-2.0.13-jar-with-dependencies.jar"]

fails, warns = [], []


def ok(msg):
    print(f"  [OK]   {msg}")


def fail(msg):
    fails.append(msg)
    print(f"  [FAIL] {msg}")


def warn(msg):
    warns.append(msg)
    print(f"  [WARN] {msg}")


def check_zip(path, version):
    print(f"\n--- zip: {os.path.basename(path)} ---")
    if not os.path.isfile(path):
        fail(f"文件不存在: {path}")
        return
    print(f"  大小: {os.path.getsize(path) / 1024 / 1024:.1f} MB")
    try:
        z = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        fail(f"无法作为 zip 打开: {e}")
        return
    with z:
        bad = z.testzip()
        if bad:
            fail(f"CRC 校验失败，首个损坏条目: {bad}")
        else:
            ok("全部条目 CRC 通过")

        infos = z.infolist()
        names = z.namelist()
        cn = [i for i in infos if any(ord(c) > 127 for c in i.filename)]
        noflag = [i.filename for i in cn if not (i.flag_bits & 0x800)]
        if noflag:
            fail(f"{len(noflag)} 个中文名条目缺 UTF-8 flag（Java 解压会报 invalid CEN header），"
                 f"例: {noflag[:3]}")
        else:
            ok(f"中文名条目 {len(cn)} 个，全部带 UTF-8 flag")
        print(f"  条目数: {len(names)}")

        for k in REQUIRED:
            if k in names:
                ok(f"存在 {k}")
            else:
                fail(f"缺少必需文件 {k}")

        # 版本三处
        if "version.txt" in names:
            v = z.read("version.txt").decode("utf-8-sig").strip()
            if v == version:
                ok(f"包内 version.txt = {v}")
            else:
                fail(f"包内 version.txt = {v}，期望 {version}")
        for f, tag in (("更新信息.txt", "当前版本"), ("使用说明.txt", "使用说明")):
            if f in names:
                head = z.read(f).decode("utf-8-sig").splitlines()[0]
                if version in head:
                    ok(f"包内 {f} 首行含 {version}: {head.strip()[:60]}")
                else:
                    fail(f"包内 {f} 首行不含 {version}（可能漏升版本头）: {head.strip()[:60]}")

        # 卡图目录
        gbf_pics = [n for n in names if n.startswith("res/gbf-pics/GBF/") and n.lower().endswith((".jpg", ".png"))]
        bad_pics = [n for n in names if n.startswith("res/pics/") and not n.endswith("/")]
        if gbf_pics:
            ok(f"res/gbf-pics/GBF/ 下 {len(gbf_pics)} 张卡图")
        else:
            fail("包内没有 res/gbf-pics/GBF/ 卡图")
        if bad_pics:
            fail(f"包内有 {len(bad_pics)} 个文件在 res/pics/ —— 会触发 Forge 启动迁移弹窗！例: {bad_pics[:2]}")
        else:
            ok("res/pics/ 干净（无迁移弹窗风险）")

        # 卡脚本抽样
        def card(name):
            hits = [n for n in names if n.endswith(name)]
            return z.read(hits[0]).decode("utf-8-sig") if hits else None

        t = card("katalina_guardian_knight_of_the_blue_sky.txt")
        if t is None:
            fail("包内缺 Katalina 脚本")
        else:
            checks = [("Discard 带 Optional$ True", "Optional$ True" in t and "DB$ Discard" in t),
                      ("含 ConditionPlayerTurn$ False（对手回合找地）", "ConditionPlayerTurn$ False" in t),
                      ("含 SVar:HandBefore:Number$0 初值", "SVar:HandBefore:Number$0" in t)]
            for label, cond in checks:
                (ok if cond else fail)(f"Katalina: {label}")

        t = card("sword_of_sorrow_and_wrath.txt")
        if t is None:
            fail("包内缺 Sword of Sorrow and Wrath 脚本")
        else:
            pt = next((l for l in t.splitlines() if l.startswith("PT:")), "?")
            (ok if pt == "PT:4/5" else fail)(f"Sword: {pt}（期望 PT:4/5）")


def check_exe(path, version):
    print(f"\n--- exe: {os.path.basename(path)} ---")
    if not os.path.isfile(path):
        fail(f"文件不存在: {path}")
        return
    size = os.path.getsize(path)
    print(f"  大小: {size / 1024 / 1024:.1f} MB")
    with open(path, "rb") as f:
        magic = f.read(2)
    if magic == b"MZ":
        ok("PE 可执行文件头正常")
    else:
        fail(f"不是有效 PE 文件（头 {magic!r}）")
    if size < 1024 * 1024:
        warn("安装程序小于 1 MB，可能不是完整包")
    print(f"  修改时间: {__import__('datetime').datetime.fromtimestamp(os.path.getmtime(path)):%Y-%m-%d %H:%M:%S}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+", help="待校验的发布资产（.zip / .exe）")
    ap.add_argument("--version", required=True, help="期望版本号，如 0.1.0.0")
    a = ap.parse_args()
    for p in a.paths:
        if p.lower().endswith(".zip"):
            check_zip(p, a.version)
        elif p.lower().endswith(".exe"):
            check_exe(p, a.version)
        else:
            warn(f"跳过未知类型: {p}")

    print("\n" + "=" * 60)
    print(f"结论：FAIL {len(fails)} / WARN {len(warns)}")
    for f in fails:
        print(f"  - {f}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
