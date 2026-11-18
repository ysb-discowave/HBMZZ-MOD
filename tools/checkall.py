#!/usr/bin/env python3
# HBMZZ - Minecraft 1.12.2 Forge mod
# Copyright (C) 2026 HBMZZ contributors
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See LICENSE.

"""一条命令跑完所有离线检查，替代「记得去跑那七八个目录」。

检查分两档，因为它们的成本差两个数量级：

  快速档  几秒，不读构建产物。改完一行代码就能跑，CI 每次 PR 也跑这一档。
          regcheck（注册/配方/模型表，99 条）+ 内置 OST 资源 + 站点 HTML 审计
          + 站点数据一致性。

  构建档  需要先 `gradle build`，因为它们编译并运行真正的实现类。
          rbmkcheck / cecpcheck / termcheck / videocheck / linkercheck（既有
          run.sh）+ reasimcheck（直接 javac 编译后跑 main）。

    python tools/checkall.py            # 有构建产物就跑两档，没有就只跑快速档
    python tools/checkall.py --fast     # 只跑快速档（CI 用，永远不因未构建而失败）
    python tools/checkall.py --full     # 构建档缺失算失败，用在本机发版前
    python tools/checkall.py --verbose  # 把每个检查的输出也打出来

退出码：0 全过；1 有失败或（--full 下）构建档缺失。
"""

import argparse
import io
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLASSES = os.path.join(ROOT, "build", "classes", "java", "main")
TMP = os.path.join(ROOT, "tmp")

# 每个 java 检查的默认 JDK。这里只作为兜底：先看环境变量 JAVA_HOME，
# 找不到才用这个——贡献者的 JDK 装在哪由他自己决定。
FALLBACK_JDK = r"C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot"


def java_home():
    env = os.environ.get("JAVA_HOME", "").strip()
    if env and os.path.isdir(env):
        return env
    if os.path.isdir(FALLBACK_JDK):
        return FALLBACK_JDK
    return ""


def run(cmd, cwd=ROOT, env=None):
    """跑一条命令，返回 (ok, 输出)。输出统一成文本，解码失败用替换字符。"""
    full_env = os.environ.copy()
    if env:
        full_env.update(env)
    try:
        p = subprocess.run(cmd, cwd=cwd, env=full_env, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=1800)
    except FileNotFoundError:
        return False, "找不到命令：%s" % cmd[0]
    except subprocess.TimeoutExpired:
        return False, "超时（30 分钟）：%s" % " ".join(cmd)
    text = p.stdout.decode("utf-8", "replace") if p.stdout else ""
    return p.returncode == 0, text


def py(*args):
    return [sys.executable] + list(args)


# ---------------------------------------------------------------- 快速档

# 四个不能少的文件。删掉它们不会让任何代码编不过，只会让这个仓库变成一份
# 没有授权的分发——所以放进 CI：少了、被清空了，红。
REQUIRED = (
    ("LICENSE", ("GNU GENERAL PUBLIC LICENSE",)),
    ("NOTICE.md", ("第三方",)),
    ("AUTHORIZATION.md", ("树上的节枝", "2026-11-03")),
    ("CONTRIBUTING.md", ("GPL", "PR")),
)


def check_files():
    """许可与协作文件在不在、内容是不是被清了。"""
    missing = []
    wrong = []
    for name, needles in REQUIRED:
        path = os.path.join(ROOT, name)
        if not os.path.isfile(path):
            missing.append(name)
            continue
        text = io.open(path, encoding="utf-8", errors="replace").read()
        for needle in needles:
            if needle not in text:
                wrong.append("%s 里找不到 %r（被清空了？）" % (name, needle))
    if missing or wrong:
        return False, "\n".join(
            (["缺少：%s" % ", ".join(missing)] if missing else []) + wrong)
    return True, ""


def check_regcheck():
    return run(py(os.path.join("tools", "regcheck", "check.py")))


# 内置 OST 的曲目表。改了曲目忘了把 ogg 放进 jar，或者转码时写了个空文件，
# 编译、打包、启动全都不会报错 —— 播放器列表里就是少几项，玩家只会觉得
# "这模组不是说自己带音乐吗"。所以这里盯三件事：文件在不在、是不是真 ogg、
# 落地名会不会在资源包里撞车。
BUILTIN_MUSIC = os.path.join("src", "main", "java", "com", "rsaudio", "client", "BuiltinMusic.java")
RESOURCES = os.path.join(ROOT, "src", "main", "resources")


def ost_sound_name(file_name):
    """复刻 AudioLibrary.sanitize：只留 a-z 0-9 _ -，纯数字加 track_ 前缀。"""
    base = file_name[:-4] if file_name.lower().endswith(".ogg") else file_name
    out = "".join(c if (c.isdigit() or "a" <= c <= "z" or c in "_-") else "_"
                  for c in base.lower())
    while "__" in out:
        out = out.replace("__", "_")
    out = out.strip("_")
    if not out:
        return "track"
    return "track_" + out if out.isdigit() else out


def check_builtin_music():
    """内置 OST：资源真的在、是合法 Ogg、落地名不重复。"""
    path = os.path.join(ROOT, BUILTIN_MUSIC)
    if not os.path.isfile(path):
        return False, "找不到 %s（曲目表已迁移？更新这条检查）" % BUILTIN_MUSIC
    text = io.open(path, encoding="utf-8", errors="replace").read()

    seed = re.search(r"SEED_VERSION\s*=\s*(\d+)", text)
    if not seed:
        return False, "BuiltinMusic.SEED_VERSION 读不出来（改曲目后必须 +1，否则老玩家不会更新）"

    pairs = re.findall(r'\{\s*"(assets/[^"]+\.ogg)"\s*,\s*"([^"]+\.ogg)"\s*\}', text)
    if not pairs:
        return False, "曲目表 TRACKS 里没解析出任何 {jar 路径, 落地文件名}"

    problems = []
    names = {}
    for resource, landed in pairs:
        target = os.path.join(RESOURCES, resource.replace("/", os.sep))
        if not os.path.isfile(target):
            problems.append("jar 里没有 %s（%s 会缺这一项）" % (resource, landed))
        elif os.path.getsize(target) == 0:
            problems.append("%s 是 0 字节" % resource)
        else:
            with open(target, "rb") as fh:
                if fh.read(4) != b"OggS":
                    problems.append("%s 不是 Ogg（1.12.2 只认 Vorbis，别的格式放出来是静音）" % resource)
        sound = ost_sound_name(landed)
        if sound in names:
            problems.append("落地名 %r 与 %r 规范化后都变成 %r，资源包里会互相覆盖"
                            % (landed, names[sound], sound))
        names[sound] = landed

    if problems:
        return False, "\n".join(problems)
    return True, "%d 首，seed=%s" % (len(pairs), seed.group(1))


def check_site_audit():
    return run(py(os.path.join("tools", "site", "audit_html.py")))


def check_site_data():
    return run(py(os.path.join("tools", "site", "sync_site.py"), "--check"))


# ---------------------------------------------------------------- 构建档

def check_script(name):
    """tools/<name>/run.sh：五个既有检查都是这个形状。"""
    script = os.path.join("tools", name, "run.sh")
    if not shutil.which("bash"):
        return False, "没有 bash，跑不了 %s（Windows 上用 Git Bash）" % script
    jh = java_home()
    env = {"JAVA_HOME": jh} if jh else {}
    return run(["bash", script], env=env)


def check_reasim():
    """没有 run.sh：编译 DspCheck 再跑它，类都在 build/classes 里。"""
    jh = java_home()
    if not jh:
        return False, "没有 JDK 8：设 JAVA_HOME 或装一个"
    javac = os.path.join(jh, "bin", "javac")
    java = os.path.join(jh, "bin", "java")
    out = os.path.join(TMP, "reasim-out")
    os.makedirs(out, exist_ok=True)
    ok, text = run([javac, "-encoding", "UTF-8", "-cp", CLASSES, "-d", out,
                    os.path.join("tools", "reasimcheck", "DspCheck.java")])
    if not ok:
        return False, text
    return run([java, "-cp", out + os.pathsep + CLASSES, "DspCheck"])


# FML 的状态事件（FMLPreInitializationEvent / FMLServerStoppedEvent …）继承的是
# FMLStateEvent，不是 Event。它们只能走 @Mod.EventHandler；写进 @SubscribeEvent
# 会让 EventBus.register 在参数类型校验时抛 IllegalArgumentException，而那条校验
# 发生在 preInit 的注册调用里——整个模组加载失败，报错还指向注册那一行。
FML_EVENT = __import__("re").compile(r"\bFML\w*Event\b")


def _strip_comments(text):
    """去掉 // 与 /* */ 之后的内容。

    注释里提到 @SubscribeEvent（本节自己在解释这个坑）时，正则会把后面随便
    一个 `foo();` 当成方法签名配对，于是报出一条不存在的错。
    """
    import re as _re
    text = _re.sub(r"/\*.*?\*/", " ", text, flags=_re.S)
    text = _re.sub(r"//[^\n]*", " ", text)
    return text


def check_events():
    """@SubscribeEvent 的参数必须是 Event 子类，且只能有一个。"""
    import re as _re
    bad = []
    for base, _dirs, files in os.walk(os.path.join(ROOT, "src", "main", "java")):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            text = _strip_comments(io.open(path, encoding="utf-8").read())
            if "@SubscribeEvent" not in text:
                continue
            for m in _re.finditer(r"@SubscribeEvent\b[^{;]*?\b(\w+)\s*\(([^)]*)\)", text, _re.S):
                params = m.group(2).strip()
                parts = [x.strip() for x in params.split(",") if x.strip()]
                rel = os.path.relpath(path, ROOT)
                if not parts:
                    bad.append("%s %s() 没有参数" % (rel, m.group(1)))
                elif len(parts) > 1:
                    bad.append("%s %s 收了 %d 个参数" % (rel, m.group(1), len(parts)))
                else:
                    ptype = parts[0].rsplit(" ", 1)[0].strip()
                    if FML_EVENT.search(ptype):
                        bad.append("%s %s(%s) 是 FML 状态事件，应走 @Mod.EventHandler"
                                   % (rel, m.group(1), ptype))
    if bad:
        return False, "\n".join(bad)
    return True, ""


def check_statecheck():
    return run([sys.executable, os.path.join("tools", "statecheck", "check.py")])


def check_gate():
    """红石门的真值表。GateType 不 import Minecraft，可以直接编来跑。"""
    jh = java_home()
    if not jh:
        return False, "没有 JDK 8：设 JAVA_HOME 或装一个"
    javac = os.path.join(jh, "bin", "javac")
    java = os.path.join(jh, "bin", "java")
    out = os.path.join(TMP, "gatecheck-out")
    os.makedirs(out, exist_ok=True)
    ok, text = run([javac, "-encoding", "UTF-8", "-d", out,
                    os.path.join("src", "main", "java", "com", "hbmzz", "redstone", "GateType.java"),
                    os.path.join("tools", "gatecheck", "GateCheck.java")])
    if not ok:
        return False, text
    return run([java, "-cp", out, "GateCheck"])


def check_dfc():
    """自动停机时序。DfcShutdownPlan 不 import Minecraft，可以直接编来跑。"""
    jh = java_home()
    if not jh:
        return False, "没有 JDK 8：设 JAVA_HOME 或装一个"
    javac = os.path.join(jh, "bin", "javac")
    java = os.path.join(jh, "bin", "java")
    out = os.path.join(TMP, "dfccheck-out")
    os.makedirs(out, exist_ok=True)
    ok, text = run([javac, "-encoding", "UTF-8", "-d", out,
                    os.path.join("src", "main", "java", "com", "hbmzz", "dfc", "DfcShutdownPlan.java"),
                    os.path.join("tools", "dfccheck", "DfcCheck.java")])
    if not ok:
        return False, text
    return run([java, "-cp", out, "DfcCheck"])


FAST = [
    ("许可与协作文件（LICENSE / NOTICE / AUTHORIZATION / CONTRIBUTING）", check_files),
    ("statecheck（blockstate 键序 / 状态数与 meta 上限）", check_statecheck),
    ("事件注解（@SubscribeEvent 的参数必须是 Event 子类）", check_events),
    ("regcheck（注册 / 配方 / 模型表 99 条）", check_regcheck),
    ("内置 OST（jar 资源 / Ogg 格式 / 落地名不撞车）", check_builtin_music),
    ("站点 HTML 审计", check_site_audit),
    ("站点数据一致性", check_site_data),
]

BUILD = [
    ("rbmkcheck（熔毁时序 50 条）", lambda: check_script("rbmkcheck")),
    ("cecpcheck（控件表）", lambda: check_script("cecpcheck")),
    ("termcheck（终端语法）", lambda: check_script("termcheck")),
    ("videocheck（视频库）", lambda: check_script("videocheck")),
    ("linkercheck（连线与事件）", lambda: check_script("linkercheck")),
    ("reasimcheck（reasim 信号链）", check_reasim),
    ("gatecheck（四种红石门的真值表）", check_gate),
    ("dfccheck（DFC 自动停机的时序）", check_dfc),
]

# 需要外部 jar 或会写图片，故意不进自动档：
#   tools/parity      要一份上游 NTM-CE.jar
#   tools/alertcheck  渲染两块屏的预览图，不是断言
MANUAL = ["tools/parity（要上游 jar）", "tools/alertcheck（出预览图）",
          "tools/quickcheck.py（40 秒 javac 体检，SRG 假阳性多，只当参考）"]


def main():
    ap = argparse.ArgumentParser(description="跑完 HBMZZ 的离线检查")
    ap.add_argument("--fast", action="store_true", help="只跑快速档")
    ap.add_argument("--full", action="store_true", help="构建档缺失算失败")
    ap.add_argument("--verbose", action="store_true", help="打印每个检查的输出")
    args = ap.parse_args()

    has_build = os.path.isdir(CLASSES) and os.listdir(CLASSES)
    # 每项带上档位：构建档在没有产物时要整段跳过，而不是挨个报「build first」
    steps = [("fast", name, fn) for name, fn in FAST]
    if not args.fast:
        steps += [("build", name, fn) for name, fn in BUILD]

    failed = []
    skipped = []

    print("HBMZZ 离线检查 —— %s" % ROOT)
    print("构建产物：%s" % ("有（%s）" % CLASSES if has_build else "没有（先跑一次 gradle build）"))
    print("")

    for tier, name, fn in steps:
        # 构建档没产物时整段跳过，否则每个都会以「build first」失败一遍，
        # 真正的问题会被淹没在五行噪声里。
        if tier == "build" and not has_build:
            reason = "没有 build/classes/java/main"
            if args.full:
                failed.append((name, reason))
                print("FAIL  %-32s %s" % (name, reason))
            else:
                skipped.append((name, reason))
                print("SKIP  %-32s %s" % (name, reason))
            continue

        ok, text = fn()
        if args.verbose and text:
            print(text.rstrip())
        print("%s  %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            failed.append((name, text))

    print("")
    if skipped:
        print("跳过 %d 项（没有构建产物）" % len(skipped))
    if failed:
        print("=" * 72)
        for name, text in failed:
            print("\n### %s\n" % name)
            lines = (text or "").rstrip().splitlines()
            print("\n".join(lines[-40:]) if lines else "(没有输出)")
        print("=" * 72)
        print("FAIL  %d 项没过" % len(failed))
        return 1

    print("ALL PASS  %d 项" % (len(steps) - len(skipped)))
    if not args.fast:
        print("不自动跑：%s" % "；".join(MANUAL))
    return 0


if __name__ == "__main__":
    sys.exit(main())
