#!/usr/bin/env python3
"""Start-of-task check: has anybody else pushed since we last looked?

This repository has two lines of work: ours, and the collaborator's. He pushes
straight to ``master`` (no branch, no PR), so a "pull" is not a merge - it is a
comparison, and then a decision about what of his is worth taking. Doing that
by hand every time is exactly the step that gets skipped at 2am.

What it prints, in order:

  1. both tips, and whether we are ahead / behind;
  2. every commit we do not have, with author, date and subject;
  3. what those commits touched, grouped by top-level directory;
  4. the *conflict surface*: files that both sides changed - those are the only
     ones where a merge can actually go wrong;
  5. the four licence/collaboration files, because deleting those is the one
     change that turns the repository into an unauthorised distribution;
  6. the handover file (``交接.md``), which the tester/developer maintains with
     the result of every build he actually launched the game for. It is the only
     record of what has been verified *in Minecraft* rather than by a static
     check, so it decides what is safe to touch next;
  7. releases published after our HEAD commit, because a collaborator's real
     code may exist only as a jar + sources zip. On 2026-11-15 that is exactly
     where it was: his three commits only deleted and restored two markdown
     files, while three real fixes sat in the v3.3.0 source archive. A git-only
     comparison would have concluded "nothing to merge" and been wrong.

Exit code 0 = nothing incoming, go ahead. 2 = there is something to integrate.
1 = the fetch itself failed (network, credentials).

    python tools/sync/preflight.py
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

PROTECTED = ("LICENSE", "NOTICE.md", "AUTHORIZATION.md", "CONTRIBUTING.md")

# 测试与开发那一侧维护的交接记录（每次真的开了游戏之后写什么、什么没过）。
HANDOVER = "交接.md"

# Files that are generated from something else; a diff here is usually noise
# from a rebuild, not an edit somebody made on purpose.
GENERATED = ("docs/assets/data.js",)


def git(*args):
    proc = subprocess.run(["git"] + list(args), cwd=ROOT, capture_output=True)
    out = proc.stdout.decode("utf-8", "replace")
    err = proc.stderr.decode("utf-8", "replace")
    return proc.returncode, out, err


def handover(incoming_files):
    """交接记录：有就读它的最后更新，没有就明说没有。

    这份文件由测试那一侧维护，记的是"真的开过游戏之后"的结果——静态检查看不见的
    东西（模组能不能加载、方块是不是紫黑、方块实体有没有写 NBT 崩溃）都在里面。
    所以它不是参考资料之一，而是排在最前面的依据。
    """
    print("== 交接 ==")
    path = os.path.join(ROOT, HANDOVER)
    if not os.path.isfile(path):
        print("  仓库里没有 %s。" % HANDOVER)
        print("  测试记录无从参考：这一轮只能按代码与离线检查来判断，别把"
              "「没报错」当成「进游戏没问题」。")
        return

    _c, info, _e = git("log", "-1", "--format=%h\t%an\t%ad\t%s", "--date=short",
                       "--", HANDOVER)
    info = info.strip()
    if info:
        sha, author, date, subject = (info.split("\t") + ["", "", ""])[:4]
        print("  最后更新  %s  %s  %s  %s" % (sha, author, date, subject))
    print("  当前 %s 字节" % format(os.path.getsize(path), ","))

    if HANDOVER in incoming_files:
        print("  ！！对方在待并入的提交里改过它，下面是差异：")
        _c, diff, _e = git("diff", "--unified=1", "HEAD..origin/master", "--", HANDOVER)
        for line in diff.splitlines()[:40]:
            print("    " + line)


def releases():
    """Tags/Release 也要看：代码可能只以 jar + sources 的形式存在。

    返回 True 表示有比本地 HEAD 更新的发布——那种情况下同样不该直接开工，
    因为「别人发了一版」和「别人推了提交」一样需要先看一眼。
    """
    _c, head_date, _e = git("log", "-1", "--format=%cI", "HEAD")
    head_date = head_date.strip()
    token = _token()
    if not token:
        print("== 发布 ==")
        print("  没有可用的 GitHub 凭据，跳过（这次没查 Release 资产）")
        return False

    url = ("https://api.github.com/repos/ysb-discowave/HBMZZ-MOD/releases?per_page=10")
    payload = _curl(url, token)
    if payload is None:
        print("== 发布 ==")
        print("  查不到（网络或凭据问题），请手工看 Releases 页")
        return False

    try:
        items = __import__("json").loads(payload)
    except ValueError:
        print("== 发布 ==")
        print("  返回的不是 JSON，跳过")
        return False

    newer = False
    print("== 发布（最近 %d 条）==" % len(items))
    for rel in items:
        tag = rel.get("tag_name") or "?"
        published = (rel.get("published_at") or "")[:16].replace("T", " ")
        assets = rel.get("assets") or []
        newest = published > head_date[:16] if published else False
        mark = "  ← 比本地 HEAD(%.10s) 新" % head_date if newest else ""
        newer = newer or newest
        print("  %-10s %s  %d 个资产%s" % (tag, published or "?", len(assets), mark))
        for asset in assets:
            print("      %s  %s B" % (asset.get("name"), format(asset.get("size", 0), ",")))
    return newer


def _token():
    proc = subprocess.run(["git", "credential", "fill"], cwd=ROOT,
                          input=b"protocol=https\nhost=github.com\n\n",
                          capture_output=True)
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    return ""


def _curl(url, token):
    proc = subprocess.run(
        ["curl", "-sS", "--ssl-no-revoke", "-m", "30", "-H", "Authorization: token " + token,
         "-H", "Accept: application/vnd.github+json", url],
        cwd=ROOT, capture_output=True)
    if proc.returncode != 0:
        return None
    return proc.stdout.decode("utf-8", "replace")


def main():
    code, _out, err = git("fetch", "origin", "--tags", "--prune")
    if code != 0:
        print("git fetch 失败：\n%s" % (err.strip() or "（没有输出）"))
        return 1

    _c, remote, _e = git("rev-parse", "--short", "origin/master")
    _c, local, _e = git("rev-parse", "--short", "HEAD")
    remote, local = remote.strip(), local.strip()

    _c, counts, _e = git("rev-list", "--left-right", "--count", "origin/master...HEAD")
    behind, ahead = (counts.split() + ["0", "0"])[:2]

    print("主仓库  %s   本地 %s   落后 %s / 领先 %s" % (remote, local, behind, ahead))
    print()

    incoming = []
    if behind != "0":
        _c, log, _e = git("log", "--reverse",
                          "--format=%h\t%an\t%ad\t%s", "--date=short",
                          "HEAD..origin/master")
        incoming = [line for line in log.splitlines() if line.strip()]
        print("== 我们没有的提交（%d 条）==" % len(incoming))
        for line in incoming:
            sha, author, date, subject = (line.split("\t") + ["", "", ""])[:4]
            print("  %s  %-10s %s  %s" % (sha, author[:10], date, subject))
        print()

        _c, names, _e = git("diff", "--name-only", "HEAD..origin/master")
        files = [n for n in names.splitlines() if n.strip()]
        groups = {}
        for name in files:
            top = name.split("/")[0] if "/" in name else "(根目录)"
            groups[top] = groups.get(top, 0) + 1
        print("== 改了哪些地方（%d 个文件）==" % len(files))
        for top, count in sorted(groups.items(), key=lambda kv: -kv[1]):
            print("  %-14s %d" % (top, count))
        skipped = [n for n in files if n in GENERATED]
        if skipped:
            print("  （其中 %s 是生成物，差异通常是重建噪音）" % ", ".join(skipped))
        print()

        # 真正的冲突面：两边都改过的文件
        _c, ours, _e = git("diff", "--name-only", "origin/master...HEAD")
        our_files = set(n for n in ours.splitlines() if n.strip())
        both = sorted(our_files & set(files))
        print("== 冲突面（两边都改过）==")
        if both:
            for name in both:
                print("  ! %s" % name)
            print("  → 这些文件必须逐个人工合并，不能直接取一边")
        else:
            print("  无。我们这边改的和对方改的不重叠，可以直接接上。")
        print()

        deleted = [n for n in files if n in PROTECTED]
        _c, statuses, _e = git("diff", "--name-status", "HEAD..origin/master")
        removed = [line.split("\t", 1)[1] for line in statuses.splitlines()
                   if line.startswith("D\t") and line.split("\t", 1)[1] in PROTECTED]
        if removed:
            print("!! 对方删除了受保护文件：%s" % ", ".join(removed))
            print("   AUTHORIZATION.md 不在树上，任何一次 clone 都是没有授权的分发。")
            print("   先恢复再谈别的（checkall 的 check_files 会红）。")
            print()

    handover(files if incoming else [])
    print()

    newer_release = releases()
    print()

    if newer_release:
        print("== 结论 ==")
        print("  有比本地 HEAD 更新的发布。先看清那一版改了什么、资产多大，再动手。")
        return 2

    if not incoming:
        print("== 结论 ==")
        print("  远端没有新提交，可以直接开始。")
        return 0

    print("== 结论 ==")
    print("  有 %d 条新提交要先看。逐条看 diff，再决定并入哪些。" % len(incoming))
    return 2


if __name__ == "__main__":
    sys.exit(main())
