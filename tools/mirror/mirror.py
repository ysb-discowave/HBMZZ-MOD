#!/usr/bin/env python3
# HBMZZ - Minecraft 1.12.2 Forge mod
# Copyright (C) 2026 HBMZZ contributors
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See LICENSE.

"""主仓库 → 镜像仓库的同步。**默认只报告，不动手。**

镜像仓库（`HBMZZ-MOD-mirror`）是灾难备份：主仓库被删、被强推、被误操作
覆盖时，它还在。所以它必须只进不改——同步只走这一个脚本，而且：

  * 不带 `--yes` 时，脚本只打印「会推哪些提交、哪些标签」，绝不推；
  * 推送前必须由仓库所有者明确同意（这是使用约定，写在 tools/mirror/README.md）；
  * 同步方向永远是「主仓库 → 镜像」，没有反向。

    python tools/mirror/mirror.py              # 只看差异（默认）
    python tools/mirror/mirror.py --push --yes  # 真推（需所有者同意后手动执行）

推的是 `refs/remotes/origin/*`，不是本地分支：本机 master 落后于远端时，
镜像拿到的仍然是最新的远端状态，不会因为「忘了 pull」而备份出一份旧代码。
"""

import argparse
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MIRROR_URL = "https://github.com/ysb-discowave/HBMZZ-MOD-mirror.git"
MIRROR_REMOTE = "mirror"


def git(*args, check=True):
    p = subprocess.run(["git"] + list(args), cwd=ROOT, capture_output=True)
    out = p.stdout.decode("utf-8", "replace").strip()
    err = p.stderr.decode("utf-8", "replace").strip()
    if check and p.returncode != 0:
        raise SystemExit("git %s 失败：\n%s" % (" ".join(args), err or out))
    return out if p.returncode == 0 else ""


def ensure_remote():
    remotes = git("remote").split()
    if MIRROR_REMOTE not in remotes:
        git("remote", "add", MIRROR_REMOTE, MIRROR_URL)
        print("已添加远端 %s → %s" % (MIRROR_REMOTE, MIRROR_URL))
    else:
        url = git("remote", "get-url", MIRROR_REMOTE)
        if url != MIRROR_URL:
            raise SystemExit("远端 %s 指向 %s，与预期不符" % (MIRROR_REMOTE, url))


def fetch_all():
    print("拉取主仓库与镜像…")
    git("fetch", "--prune", "--tags", "origin")
    # 空仓库首次 fetch 会没有 refs，这不是错误
    git("fetch", "--prune", "--tags", MIRROR_REMOTE, check=False)


def ref(rev, required=False):
    """短 SHA。ref 不存在时返回空串（required=True 则直接退出）。"""
    p = subprocess.run(["git", "rev-parse", "--short", rev], cwd=ROOT, capture_output=True)
    if p.returncode != 0:
        if required:
            raise SystemExit("找不到 %s —— 远端是不是还没 fetch？" % rev)
        return ""
    return p.stdout.decode().strip()


def has_ref(rev):
    p = subprocess.run(["git", "rev-parse", "--verify", "--quiet", rev + "^{commit}"],
                       cwd=ROOT, capture_output=True)
    return p.returncode == 0


def main():
    ap = argparse.ArgumentParser(description="主仓库 → 镜像仓库，默认只报告")
    ap.add_argument("--push", action="store_true", help="真的推送（必须同时给 --yes）")
    ap.add_argument("--yes", action="store_true", help="确认已获得所有者同意")
    ap.add_argument("--prune", action="store_true", help="顺带删掉镜像上主仓库已没有的分支")
    args = ap.parse_args()

    if args.push and not args.yes:
        raise SystemExit(
            "拒绝对镜像动手：同步必须由仓库所有者明确同意后，再带 --yes 执行。\n"
            "先跑 python tools/mirror/mirror.py 看差异，把结果给所有者审阅。")

    ensure_remote()
    fetch_all()

    main_tip = ref("origin/master", required=True)
    mirror_tip = ref("%s/master" % MIRROR_REMOTE)

    print("")
    print("主仓库  origin/master  %s  %s" % (main_tip, git("log", "-1", "--format=%s", "origin/master")))
    print("镜像库  %s/master  %s" % (MIRROR_REMOTE, mirror_tip or "（空）"))
    print("")

    if mirror_tip and mirror_tip == main_tip:
        print("镜像与主仓库一致，没有需要同步的提交。")
        return 0

    if not mirror_tip:
        total = git("rev-list", "--count", "origin/master")
        # 分支数要数 refs/remotes/origin 下真正存在的，`git branch -r` 会把
        # `origin/HEAD -> origin/master` 这一行也算进去，报出来多一个。
        heads = [b for b in git("for-each-ref", "--format=%(refname:short)",
                                "refs/remotes/origin").split()
                 if not b.endswith("/HEAD")]
        print("镜像还是空的。首次同步会推 %s 个提交、%s 个标签、%s 个分支。"
              % (total, len(git("tag").split()), len(heads)))
        commits = git("log", "--oneline", "-12", "origin/master")
        print("\n最近 12 个提交：\n" + commits)
    else:
        commits = git("log", "--oneline", "%s/master..origin/master" % MIRROR_REMOTE)
        if commits:
            print("主仓库领先镜像的提交：\n" + commits)
        else:
            print("提交没有差异（可能只是标签或分支不同）。")

        # 标签差异：镜像上缺的
        main_tags = set(git("tag", "--list", "--merged", "origin/master").split())
        mirror_tags = set(git("tag", "--list", "--merged", "%s/master" % MIRROR_REMOTE, check=False).split())
        missing = sorted(main_tags - mirror_tags)
        if missing:
            print("\n镜像上缺这些标签：%s" % ", ".join(missing))

    # 变更摘要：按目录统计
    if mirror_tip:
        stat = git("diff", "--stat", "%s/master..origin/master" % MIRROR_REMOTE, check=False)
        names = git("diff", "--name-only", "%s/master..origin/master" % MIRROR_REMOTE, check=False)
    else:
        stat, names = "", ""
    if names:
        buckets = {}
        for path in names.splitlines():
            top = path.split("/")[0] if "/" in path else "(根目录)"
            buckets[top] = buckets.get(top, 0) + 1
        print("\n改动摘要（按顶层目录）：")
        for k in sorted(buckets, key=lambda k: -buckets[k]):
            print("  %-16s %d 个文件" % (k, buckets[k]))

    print("")
    if not args.push:
        print("以上只是报告，什么都没有推。")
        print("所有者同意后，执行：python tools/mirror/mirror.py --push --yes")
        return 0

    print("开始同步到镜像…")
    refspecs = ["refs/remotes/origin/*:refs/heads/*", "refs/tags/*:refs/tags/*"]
    cmd = ["push", MIRROR_REMOTE]
    if args.prune:
        cmd.append("--prune")
    cmd += refspecs
    print(git(*cmd, check=False) or "（git 没有输出）")

    # 复核：镜像的 master 必须等于主仓库的 master
    git("fetch", "--prune", "--tags", MIRROR_REMOTE, check=False)
    after = ref("%s/master" % MIRROR_REMOTE)
    print("")
    print("同步后镜像 master：%s" % (after or "（读不到）"))
    if after != main_tip:
        print("警告：同步后镜像 master 与主仓库不一致（%s ≠ %s），请检查上面的推送输出。"
              % (after, main_tip))
        return 1
    print("一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
