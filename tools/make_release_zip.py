#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make_release_zip.py — 便携版发布包打包脚本（v0.0.2.3 起替代 bsdtar）。

为什么换掉 `tar -a -cf`（bsdtar）：
  bsdtar 打出的 zip 对非 ASCII（中文）文件名**不设 UTF-8 flag**（按本地代码页写入），
  Java 的 ZipFile 默认按 UTF-8 严格解析会直接报 "invalid CEN header"——游戏内自动更新
  （引擎 P-14 auto-update）需要 Java 解压发布包，因此发布包必须用标准 UTF-8 文件名。
  Python zipfile 默认对文件名设 UTF-8 flag，Java / Windows 资源管理器 / WinRAR 全部兼容。

用法：
  python tools/make_release_zip.py <staging目录> <输出.zip>
例：
  python D:/forge-analysis/tools/make_release_zip.py D:/forge-analysis/gbf-portable/staging \\
      D:/forge-analysis/gbf-portable/Card-Forge-GBF-Portable-v0.0.2.3.zip

注意：
  - 打包的是 staging 根下的全部内容（含中文名文件），条目路径不带 "./" 前缀；
  - 压缩 deflate level 6，约 2.3 万个文件，耗时数分钟（可用 -0 换存储模式更快但更大）；
  - 发布前确认 staging 已 robocopy /MIR 同步（res + jar + README + 更新信息 + version.txt +
    使用说明.txt 版本头已升）。
"""
import os
import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src = os.path.abspath(sys.argv[1])
    dst = os.path.abspath(sys.argv[2])
    if not os.path.isdir(src):
        print("ERROR: staging dir not found:", src)
        return 1
    if os.path.exists(dst):
        print("ERROR: output already exists:", dst)
        return 1

    print("packing", src, "->", dst)
    n = 0
    total = 0
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, dirs, files in os.walk(src):
            dirs.sort()
            files.sort()
            for f in files:
                p = os.path.join(root, f)
                arc = os.path.relpath(p, src)  # UTF-8 flag 由 zipfile 自动设置
                z.write(p, arc)
                n += 1
                total += os.path.getsize(p)
                if n % 2000 == 0:
                    print("  %d files, %d MB raw..." % (n, total // (1024 * 1024)))
    size_mb = os.path.getsize(dst) / (1024 * 1024)
    print("done: %d files, zip %.1f MB" % (n, size_mb))
    return 0


if __name__ == "__main__":
    sys.exit(main())
