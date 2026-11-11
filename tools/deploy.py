#!/usr/bin/env python3
# HBMZZ - Minecraft 1.12.2 Forge mod
# Copyright (C) 2026 HBMZZ contributors
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See LICENSE.

"""把 build/libs 里当前版本的 jar 装进游戏的 mods 目录。

装进去这件事每次发版都要做一遍，手点资源管理器容易装错版本、也容易把上一版
留在目录里让游戏同时加载两个 hbmzz。这个脚本只做一件事：拿 build/libs 里
那个由 build.gradle 版本号算出来的 jar，校验和比对后放进 mods，旧的那份改名
`.jar.bak`（和一直以来手工部署的规矩一致，不删任何东西）。

    python tools/deploy.py                 # 自动找游戏目录
    python tools/deploy.py --mods "D:/mc/versions/mp3mod/mods"
    python tools/deploy.py --dry-run       # 只看会做什么

mods 目录的解析顺序：

    1. --mods 参数
    2. 环境变量 HBMZZ_MODS
    3. 仓库根的 deploy.local.json（已 gitignore，格式 {"mods": "..."}）
    4. 自动探测：~/Desktop/.minecraft/versions/*/mods 与 ~/.minecraft/mods 里，
       含 NTM 本体或旧版 hbmzz 的那个（取最近改动的一个）

退出码 0 = 已经是目标状态或装好了；1 = 出错（没构建、目录找不到、jar 被占用）。
"""

import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_LIBS = os.path.join(ROOT, "build", "libs")
LOCAL_CONFIG = os.path.join(ROOT, "deploy.local.json")
HOME = os.path.expanduser("~")


def die(message):
    print("错误：" + message)
    sys.exit(1)


def version_of(build_gradle):
    with open(build_gradle, encoding="utf-8") as handle:
        text = handle.read()
    match = re.search(r"^\s*version\s*=\s*['\"]([^'\"]+)['\"]", text, re.M)
    if not match:
        die("build.gradle 里找不到 version")
    return match.group(1)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def looks_like_game_mods(path):
    """这个目录是不是那个装了 NTM 的游戏 —— 不是就算探测失败。"""
    if not os.path.isdir(path):
        return False
    for name in os.listdir(path):
        lower = name.lower()
        if lower.startswith("hbmzz-"):
            return True
        if "ntm" in lower and "ce" in lower and lower.endswith(".jar"):
            return True
    return False


def auto_detect():
    candidates = []
    patterns = [
        os.path.join(HOME, "Desktop", ".minecraft", "versions", "*", "mods"),
        os.path.join(HOME, ".minecraft", "versions", "*", "mods"),
        os.path.join(HOME, ".minecraft", "mods"),
    ]
    for pattern in patterns:
        candidates.extend(glob.glob(pattern))
    hits = [path for path in candidates if looks_like_game_mods(path)]
    if not hits:
        return None
    if len(hits) == 1:
        return hits[0]

    def newest(path):
        times = [os.path.getmtime(os.path.join(path, name)) for name in os.listdir(path)]
        return max(times) if times else 0

    hits.sort(key=newest, reverse=True)
    print("找到多个候选目录，按最近改动选了第一个：")
    for path in hits:
        print("  " + path)
    return hits[0]


def resolve_mods(explicit):
    if explicit:
        if not os.path.isdir(explicit):
            die("指定的 mods 目录不存在：" + explicit)
        return os.path.abspath(explicit)
    if os.environ.get("HBMZZ_MODS"):
        path = os.environ["HBMZZ_MODS"]
        if not os.path.isdir(path):
            die("HBMZZ_MODS 指向的目录不存在：" + path)
        return os.path.abspath(path)
    if os.path.exists(LOCAL_CONFIG):
        with open(LOCAL_CONFIG, encoding="utf-8") as handle:
            path = json.load(handle).get("mods", "")
        if path and not os.path.isdir(path):
            die("deploy.local.json 里的 mods 目录不存在：" + path)
        if path:
            return os.path.abspath(path)
    found = auto_detect()
    if not found:
        die("找不到游戏 mods 目录，用 --mods 指定一次，或写进 deploy.local.json")
    return os.path.abspath(found)


def installed_jars(mods):
    return sorted(
        os.path.join(mods, name)
        for name in os.listdir(mods)
        if name.lower().startswith("hbmzz-") and name.lower().endswith(".jar")
    )


def standalone_rsaudio(mods):
    """独立的 rsaudio jar。

    2.9.0 起 rsaudio 并进了 hbmzz 这一个 jar：一个 jar 里装两个模组容器
    （hbmzz + rsaudio），所以 rsaudio 的方块、贴图和存档 id 都没变。代价是
    独立的 rsaudio-*.jar 现在会声明同一个容器 id —— 两份同时在 mods 里就是
    duplicate modid，FML 在加载阶段直接崩，而且报错文字完全指不到
    "你装了两份"这件事上。所以部署时顺手把它停用掉。
    """
    return sorted(
        os.path.join(mods, name)
        for name in os.listdir(mods)
        if name.lower().startswith("rsaudio-") and name.lower().endswith(".jar")
    )


def main():
    parser = argparse.ArgumentParser(description="把当前版本装进游戏 mods 目录")
    parser.add_argument("--mods", help="游戏 mods 目录")
    parser.add_argument("--dry-run", action="store_true", help="只报告，不落盘")
    args = parser.parse_args()

    version = version_of(os.path.join(ROOT, "build.gradle"))
    jar = os.path.join(BUILD_LIBS, "hbmzz-%s.jar" % version)
    if not os.path.exists(jar):
        die("没有 %s，先 gradlew build" % os.path.relpath(jar, ROOT))

    mods = resolve_mods(args.mods)
    digest = sha256(jar)
    print("版本 %s" % version)
    print("源   %s" % jar)
    print("目标 %s" % mods)

    target = os.path.join(mods, os.path.basename(jar))
    if os.path.exists(target) and sha256(target) == digest:
        print("已是最新：目标里那份校验和与构建产物一致，什么都没改。")
        return 0

    stale = [path for path in installed_jars(mods) if os.path.abspath(path) != os.path.abspath(target)]
    duplicates = standalone_rsaudio(mods)
    plan = [("改名 .bak", path) for path in stale] + [("复制", jar)]

    if args.dry_run:
        for action, path in plan:
            print("[dry-run] %s %s" % (action, path))
        for path in duplicates:
            print("[dry-run] 停用独立 rsaudio %s" % path)
        return 0

    for path in duplicates:
        # 后缀不用 .bak：目录里本来就有一堆同名 .bak，会撞上
        backup = path + ".merged"
        try:
            os.replace(path, backup)
        except PermissionError:
            die("改不动 %s —— 游戏还开着？先退出游戏再跑一次" % os.path.basename(path))
        print("独立 rsaudio 已停用 %s（已并进 hbmzz，两份同时装会 duplicate modid）"
              % os.path.basename(backup))

    for path in stale:
        backup = path + ".bak"
        try:
            os.replace(path, backup)
        except PermissionError:
            die("改不动 %s —— 游戏还开着？先退出游戏再跑一次" % os.path.basename(path))
        print("旧版改名 %s" % os.path.basename(backup))

    try:
        shutil.copy2(jar, target)
    except PermissionError:
        die("写不进 %s —— 游戏还开着？先退出游戏再跑一次" % os.path.basename(target))

    # 复制完必须再算一次：以前踩过「拷过去的是另一个版本」的坑。
    if sha256(target) != digest:
        die("复制后校验和不一致，别进游戏，先查磁盘：" + target)

    print("装好了 %s（%d 字节，sha256 %s…）" % (os.path.basename(target), os.path.getsize(target), digest[:12]))
    backups = glob.glob(os.path.join(mods, "hbmzz-*.jar.bak"))
    if backups:
        total = sum(os.path.getsize(path) for path in backups)
        print("目录里还留着 %d 份历史 .bak（共 %.1f MB），要清自己删，本脚本不动它们。"
              % (len(backups), total / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
