#!/usr/bin/env python3
"""把仓库状态同步进官网的数据层。

官网里所有会随版本变化的东西（版本号、代码规模、控件/节点数量）都不硬编码在 HTML 里，
而是由本脚本从 build.gradle 与源码扫描出来，写进 docs/assets/data.js；页面用
data-bind="auto.xxx" 占位，由 docs/assets/site.js 在加载时填进去。

用法：
    python tools/site/sync_site.py            # 重新生成 docs/assets/data.js
    python tools/site/sync_site.py --check    # 只校验，不一致则退出码 1（给 CI / 发版前用）
    python tools/site/sync_site.py --print    # 把统计打到 stdout，不写文件
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_JAVA = os.path.join(ROOT, "src", "main", "java")
SRC_RES = os.path.join(ROOT, "src", "main", "resources")
BUILD_GRADLE = os.path.join(ROOT, "build.gradle")
CONTENT_JSON = os.path.join(ROOT, "tools", "site", "content.json")
OUT_JS = os.path.join(ROOT, "docs", "assets", "data.js")

# 站点是纯静态的，Pages 前面还挂着一层 CDN：CSS/JS 的 Cache-Control 是
# max-age=14400，HTML 只有 600。于是刚推上去的那十几分钟里，浏览器拿到的往往是
# 「新的 HTML + 四小时前缓存的旧 JS」——按钮还在，点了什么也不发生。这不是脚本
# 写错了，是两份文件不同龄。解法只有一个：让 URL 跟着内容走，内容一变 URL 就变。
PAGES = ("index.html", "guide.html", "reference.html", "addon.html", "changelog.html",
         "ost.html", "tools.html", "thanks.html", "license.html")
STAMPED = ("assets/site.css", "assets/site.js", "assets/data.js")

# 这些不是可用的控件类型：注册表枚举、基类
CONTROL_SKIP = {"ControlType.java", "AddonControlType.java", "LeverBase.java"}
# 这些不是可用的节点类型：节点基类、附属节点基类、音效定义辅助类
NODE_SKIP = {"Node.java", "NodeRetarded.java", "SIPfx.java"}


def read(path: str) -> str:
    with io.open(path, "r", encoding="utf-8-sig") as f:
        return f.read()


def latest_tag():
    """最新（按创建时间）的发布 tag，去掉 v 前缀；没有 tag 时返回 None。

    「站点显示的版本」以 tag 为准：tag 是发布这件事本身，build.gradle 只是构建时的
    意图。两者不一致时，玩家看到的应该是**已经发出去的那个**，而不是工作区里改了一半
    的下一个。遍历顺序按 creatordate 倒序——V3.5.1 这种大写前缀的老 tag 也一并认。
    """
    try:
        out = subprocess.run(
            ["git", "for-each-ref", "--sort=-creatordate", "--format=%(refname:short)", "refs/tags"],
            cwd=ROOT, capture_output=True, text=True)
    except Exception:
        return None
    if out.returncode != 0:
        return None
    for line in out.stdout.splitlines():
        name = line.strip()
        m = re.match(r"^[vV]?(\d+\.\d+\.\d+)$", name)
        if m:
            return m.group(1)
    return None


def scan_gradle() -> dict:
    text = read(BUILD_GRADLE)
    version = re.search(r"^version\s*=\s*'([^']+)'", text, re.M)
    mc = re.search(r"version\s*=\s*'(\d+\.\d+\.\d+)-([\d.]+)'", text)
    if not version or not mc:
        raise SystemExit("build.gradle 里找不到 version / minecraft.version")
    tagged = latest_tag()
    return {
        # 站点版本以最新 tag 为准；没有 tag 的仓库（比如刚 clone 的）退回 build.gradle
        "version": tagged or version.group(1),
        "gradleVersion": version.group(1),
        "mcVersion": mc.group(1),
        "forgeVersion": mc.group(2),
    }


def walk(base: str, suffix: str) -> list:
    out = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if name.endswith(suffix):
                out.append(os.path.join(dirpath, name))
    return out


def count_lines(paths: list) -> int:
    total = 0
    for p in paths:
        try:
            total += read(p).count("\n") + 1
        except UnicodeDecodeError:
            with io.open(p, "r", encoding="utf-8", errors="replace") as f:
                total += f.read().count("\n") + 1
    return total


def scan_sources() -> dict:
    java = walk(SRC_JAVA, ".java")
    resources = walk(SRC_RES, "")

    nodes_dir = os.path.join(SRC_JAVA, "com", "hbmzz", "cecp", "nodes")
    controls_dir = os.path.join(SRC_JAVA, "com", "hbmzz", "cecp", "controls")

    node_files = [p for p in walk(nodes_dir, ".java") if os.path.basename(p) not in NODE_SKIP]
    # controls/ 下的 configs/ 是配置面板，不算控件类型
    control_files = [
        p
        for p in walk(controls_dir, ".java")
        if os.path.basename(p) not in CONTROL_SKIP
        and os.path.basename(os.path.dirname(p)) != "configs"
        and "configs" not in p.split(os.sep)
    ]

    return {
        "javaFiles": len(java),
        "javaLines": count_lines(java),
        "resourceFiles": len(resources),
        "nodeTypes": len(node_files),
        "controlTypes": len(control_files),
    }


def build_payload() -> dict:
    content = json.loads(read(CONTENT_JSON))
    content.pop("_comment", None)
    gradle = scan_gradle()
    stats = scan_sources()
    payload = dict(content)
    payload["auto"] = {
        **gradle,
        "stats": stats,
        "generatedAt": datetime.datetime.now().strftime("%Y-%m-%d"),
    }
    # 站点顶部显示的版本可被 content.json 的 siteVersion 覆盖；它只管页面显示，
    # 不动 build.gradle / jar 命名 / git tag。留空则维持原来的“跟随最新 tag”行为。
    override = content.get("siteVersion")
    if override:
        payload["auto"]["version"] = override
    return payload


def render_js(payload: dict) -> str:
    body = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False)
    return (
        "/* 本文件由 tools/site/sync_site.py 自动生成，请勿手工编辑。\n"
        " * 改内容去 tools/site/content.json；改版本号去 build.gradle。\n"
        " */\n"
        "window.HBMZZ_DATA = " + body + ";\n"
    )


def check_pages(payload: dict) -> list:
    """一致性检查：页面里该有的东西在不在，不该有的东西有没有。

    返回 [(级别, 说明)]，级别为 "FAIL" 或 "WARN"。
    """
    problems = []
    docs = os.path.join(ROOT, "docs")
    nav = payload.get("nav", [])
    timeline = payload.get("timeline", [])
    current = payload["auto"]["version"]

    # 1) 导航里的每个页面都得真实存在
    for item in nav:
        target = item["href"].split("#")[0]
        if target and not os.path.exists(os.path.join(docs, target)):
            problems.append(("FAIL", "导航指向的页面不存在：docs/%s" % target))

    # 2) 更新日志必须收录 content.json 里的每个版本
    cl = os.path.join(docs, "changelog.html")
    if os.path.exists(cl):
        text = read(cl)
        for entry in timeline:
            if ("v" + entry["version"]) not in text:
                problems.append(
                    ("FAIL", "changelog.html 缺版本 v%s（content.json 的 timeline 有）" % entry["version"])
                )
    else:
        problems.append(("FAIL", "docs/changelog.html 不存在"))

    # 3) 除更新日志外，页面里不该硬编码别的版本号当"当前版本"
    for name in ("index.html", "guide.html", "reference.html", "addon.html", "ost.html",
                 "tools.html", "thanks.html", "license.html"):
        path = os.path.join(docs, name)
        if not os.path.exists(path):
            problems.append(("FAIL", "docs/%s 不存在" % name))
            continue
        text = read(path)
        for hit in re.findall(r"hbmzz-(\d+\.\d+\.\d+)\.jar", text):
            if hit != current:
                problems.append(("FAIL", "%s 里写死了旧产物名 hbmzz-%s.jar" % (name, hit)))

    # 4) 站内链接（相对路径 + #锚点）必须都能落地
    anchor_cache = {}
    for name in ("index.html", "guide.html", "reference.html", "addon.html", "changelog.html",
                 "ost.html", "tools.html", "thanks.html", "license.html"):
        path = os.path.join(docs, name)
        if not os.path.exists(path):
            continue
        text = read(path)
        for href in re.findall(r'href="([^"#:]+\.html)(#[^"]*)?"', text):
            target, frag = href[0], href[1].lstrip("#")
            tpath = os.path.join(docs, target)
            if not os.path.exists(tpath):
                problems.append(("FAIL", "%s 链到不存在的 %s" % (name, target)))
                continue
            if frag:
                if tpath not in anchor_cache:
                    anchor_cache[tpath] = read(tpath)
                if ('id="%s"' % frag) not in anchor_cache[tpath]:
                    problems.append(("FAIL", "%s 链到 %s#%s，但目标页没有这个锚点" % (name, target, frag)))

    return problems


def digest(rel: str) -> str:
    """docs 下某个资源的短指纹：内容一改，页面里的查询串就跟着改。"""
    path = os.path.join(ROOT, "docs", rel)
    if not os.path.exists(path):
        return ""
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()[:10]


def stamp_assets(write: bool) -> list:
    """把 ?v=<内容指纹> 写进各页面引用 CSS/JS/data.js 的地方。

    没有这一步，改完脚本要等 CDN 那四小时过期才生效，而在这期间 HTML 是新的、
    JS 是旧的——页面上的播放按钮就是「点了没反应」的样子。写盘时顺手维护，
    --check 时只比对，发现过期就 FAIL，避免带着旧键发版。
    """
    digests = {rel: digest(rel) for rel in STAMPED}
    problems = []
    for name in PAGES:
        path = os.path.join(ROOT, "docs", name)
        if not os.path.exists(path):
            continue
        text = read(path)
        new = text
        for rel, dig in digests.items():
            if not dig:
                continue
            pattern = re.compile(r'((?:href|src)="%s)(?:\?v=[0-9a-f]+)?"' % re.escape(rel))
            new = pattern.sub(lambda m, dig=dig: m.group(1) + "?v=" + dig + '"', new)
        if new == text:
            continue
        if write:
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(new)
        else:
            problems.append(("FAIL", "%s 里的资源缓存键过期——跑一次 sync_site.py" % name))
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description="同步 HBMZZ 官网数据层")
    ap.add_argument("--check", action="store_true", help="只校验，不写文件")
    ap.add_argument("--print", dest="do_print", action="store_true", help="只打印统计")
    args = ap.parse_args()

    payload = build_payload()
    js = render_js(payload)

    if args.do_print:
        print(json.dumps(payload["auto"], ensure_ascii=False, indent=2))
        return 0

    if args.check:
        failed = False
        if not os.path.exists(OUT_JS):
            print("MISSING  docs/assets/data.js — 先跑一次 sync_site.py")
            return 1
        current = read(OUT_JS)
        # 生成时间允许不同，比对时抹掉
        strip = lambda s: re.sub(r'"generatedAt": "[^"]*"', "", s)
        if strip(current) != strip(js):
            print("STALE    docs/assets/data.js 与仓库状态不一致 — 跑一次 sync_site.py")
            failed = True
        else:
            print("OK       docs/assets/data.js 与仓库状态一致")

        problems = check_pages(payload)
        problems += stamp_assets(False)
        # mcmod.info 里的 version 是游戏内模组列表显示的那个，它和 build.gradle
        # 各写一份，最容易悄悄漂掉（jar 名 2.3.0、列表里 2.0.0）。
        info = os.path.join(ROOT, "src", "main", "resources", "mcmod.info")
        if os.path.exists(info):
            m = re.search(r'"version"\s*:\s*"([^"]+)"', read(info))
            if not m:
                problems.append(("FAIL", "mcmod.info 里找不到 version 字段"))
            elif m.group(1) != payload["auto"].get("gradleVersion", payload["auto"]["version"]):
                problems.append(("FAIL", "mcmod.info 的 version 是 %s，build.gradle 是 %s"
                                 % (m.group(1), payload["auto"].get("gradleVersion"))))
        # 站点版本与构建版本不一致本身不是错（发完版后工作区会往前走，
        # 或 content.json 的 siteVersion 有意覆盖显示版本），说一声免得以为没更新。
        gv = payload["auto"].get("gradleVersion")
        overridden = bool(json.loads(read(CONTENT_JSON)).get("siteVersion"))
        if gv and gv != payload["auto"]["version"]:
            desc = "content.json 的 siteVersion 覆盖" if overridden else "tag"
            problems.append(("WARN", "站点版本 %s（%s），build.gradle 是 %s"
                             % (payload["auto"]["version"], desc, gv)))

        seen = set()
        for level, msg in problems:
            if (level, msg) in seen:
                continue
            seen.add((level, msg))
            print("%-8s %s" % (level, msg))
        # WARN 只是提醒（例如 tag 与 build.gradle 不一致），不阻断
        if any(level == "FAIL" for level, _ in problems):
            failed = True
        else:
            print("OK       页面结构、版本号、站内链接与锚点全部一致")

        return 1 if failed else 0

    os.makedirs(os.path.dirname(OUT_JS), exist_ok=True)
    with io.open(OUT_JS, "w", encoding="utf-8", newline="\n") as f:
        f.write(js)

    # data.js 刚落盘，指纹要取它写完之后的；页面里的 ?v= 跟着一起刷新
    stamp_assets(True)

    a = payload["auto"]
    s = a["stats"]
    print("wrote docs/assets/data.js")
    print("  version      %s  (MC %s / Forge %s)" % (a["version"], a["mcVersion"], a["forgeVersion"]))
    print("  java         %d 文件 / %d 行" % (s["javaFiles"], s["javaLines"]))
    print("  resources    %d 文件" % s["resourceFiles"])
    print("  nodeTypes    %d" % s["nodeTypes"])
    print("  controlTypes %d" % s["controlTypes"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
