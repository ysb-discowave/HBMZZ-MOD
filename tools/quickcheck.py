#!/usr/bin/env python3
"""One-pass javac over src/main/java, against the Gradle cache.

Gradle's compileJava takes ten minutes on this machine, and almost all of it
is ForgeGradle's setup tasks, not javac. When the only question is "does this
still compile", paying that per iteration is silly: the same 249 files go
through javac in about forty seconds against the same jars Gradle would put on
the classpath.

This is a smoke test, not a replacement for `gradlew build` - it does not
produce the shaded jar, run the resource pipeline, or check the Forge metadata.
What it does check is the thing that actually breaks: symbols, signatures and
packages.

    python tools/quickcheck.py                 # everything
    python tools/quickcheck.py TerminalShell   # only errors in matching files

One caveat makes the unfiltered run noisy: the Forge jar in the Gradle cache is
SRG-named in this checkout, so API calls that carry an MCP name report as
"cannot find symbol" (mcServer, lengthVector, hasNoTags…). They compile fine
under Gradle, which remaps first. So the useful way to read a red run is to
pass the files you just changed - a genuine mistake in your own code shows up
there, and the MCP-name noise does not.

Exit code 0 = compiles. Nothing is written outside tmp/quickcheck.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOME = os.path.expanduser("~")
JAVA_HOME = os.environ.get("JAVA_HOME", r"C:/Program Files/Eclipse Adoptium/jdk-8.0.502.7-hotspot")
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe" if os.name == "nt" else "javac")

FORGE = os.path.join(
    HOME, ".gradle", "caches", "minecraft", "net", "minecraftforge", "forge",
    "1.12.2-14.23.5.2847", "stable", "39", "forgeSrc-1.12.2-14.23.5.2847.jar")

OUT = os.path.join(ROOT, "tmp", "quickcheck")
ARGS = os.path.join(ROOT, "tmp", "javac.args")


def find_jars():
    base = os.path.join(HOME, ".gradle", "caches", "modules-2")
    jars = []
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if name.endswith(".jar") and not name.endswith("-sources.jar"):
                jars.append(os.path.join(dirpath, name))
    return jars


def find_sources():
    base = os.path.join(ROOT, "src", "main", "java")
    out = []
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if name.endswith(".java"):
                out.append(os.path.join(dirpath, name))
    return out


def main():
    if not os.path.exists(JAVAC):
        print("no javac at " + JAVAC, file=sys.stderr)
        return 2
    if not os.path.exists(FORGE):
        print("no deobfuscated Forge jar at " + FORGE, file=sys.stderr)
        return 2

    classpath = ";".join([FORGE] + find_jars())
    sources = find_sources()

    os.makedirs(os.path.dirname(ARGS), exist_ok=True)
    with open(ARGS, "w", encoding="utf-8") as fh:
        fh.write("-nowarn\n-encoding\nUTF-8\n-proc:none\n")
        fh.write('-d\n"%s"\n' % OUT.replace("\\", "/"))
        fh.write('-cp\n"%s"\n' % classpath.replace("\\", "/"))
        for path in sources:
            fh.write('"%s"\n' % path.replace("\\", "/"))

    proc = subprocess.run([JAVAC, "@" + ARGS], capture_output=True)
    text = (proc.stdout + proc.stderr).decode("utf-8", "replace")

    # javac reports "<path>:<line>: error: <text>" then the offending line.
    # Group the report by file so a filter can drop a whole error, not just its
    # headline - half an error is worse than none.
    blocks = []
    for line in text.splitlines():
        is_head = re.match(r"^(.*?\.java):(\d+): (error|warning):", line)
        if is_head and is_head.group(3) == "error":
            blocks.append([line])
        elif blocks:
            blocks[-1].append(line)
        else:
            blocks.append([line])

    wanted = sys.argv[1:]
    shown = [b for b in blocks if re.search(r"\.java:\d+: error:", b[0])]
    if wanted:
        shown = [b for b in shown if any(name in b[0] for name in wanted)]

    if proc.returncode == 0:
        print("compiles: %d files" % len(sources))
        return 0

    for block in shown:
        print("\n".join(block).rstrip())
    print()
    print("%d errors%s" % (len(shown),
                           "" if not wanted else " in " + ", ".join(wanted)))
    if not wanted:
        print("(some may be MCP-name artefacts of the SRG-named cache jar; "
              "re-run with file names to filter)")
    return 1 if shown else 0


if __name__ == "__main__":
    sys.exit(main())
