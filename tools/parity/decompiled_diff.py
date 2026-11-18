"""Diff the port against upstream at token level, both sides decompiled.

`parity.py` decompiles only the upstream class files and compares against the
hand-written sources, so it drowns in formatting noise. Here **both** sides are
decompiled with the same vineflower and the same options, then every line has its
whitespace stripped (string literals aside). Wrapping style, `this.` prefixes and
`<>` inference all vanish, and what is left is a real token difference.

    python tools/parity/decompiled_diff.py <NTM-CE.jar>
        [--classes build/classes/java/main] [--work tmp/parity2] [--out tmp/parity2/diff]

Needs a JDK 11+ `java` for vineflower. Pass --java on Windows.
"""
import argparse
import csv
import difflib
import os
import re
import shutil
import subprocess
import sys
import zipfile

UPSTREAM_PKG = "com/hbm/inventory/control_panel"
LOCAL_PKG = "com/hbmzz/cecp"
DEFAULT_VINEFLOWER = (r"C:/Users/Yang Sibo/.gradle/caches/minecraftforge/forgegradle/mavenizer/"
                      r"caches/maven/mcp-tools/org/vineflower/vineflower/1.12.0/vineflower-1.12.0.jar")
DEFAULT_MAPPINGS = (r"C:/Users/Yang Sibo/.gradle/caches/forge_gradle/minecraft_repo/"
                    r"versions/1.12.2/mcp_mappings.tsrg")
DEFAULT_SRG_DIR = r"C:/Users/Yang Sibo/.gradle/caches/minecraft/de/oceanlabs/mcp/mcp_snapshot/20171003"

# compat shims stand in for CE helpers; map them back so lines line up
NORM = [
    ("com.hbmzz.cecp.compat.GuiFx", "NTMRenderHelper"),
    ("com.hbmzz.cecp.compat.CtrlImm", "NTMImmediate"),
    ("com.hbmzz.cecp.compat.CtrlBuf", "NTMBufferBuilder"),
]

STR = re.compile(r'"(?:[^"\\]|\\.)*"')


def extract(jar, work, pkg):
    out = os.path.join(work, "classes")
    if os.path.isdir(out):
        shutil.rmtree(out)
    count = 0
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith(pkg + "/") and name.endswith(".class"):
                path = os.path.join(out, name)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as fh:
                    fh.write(z.read(name))
                count += 1
    return out, count


def decompile(java, vineflower, mappings, classes, out):
    if os.path.isdir(out):
        shutil.rmtree(out)
    cmd = [java, "-jar", vineflower, "-dgs=1", "-hdc=0", "-asc=1", "-rsy=1", "-jvs=1"]
    if mappings and os.path.isfile(mappings):
        cmd += ["-mapping", mappings]
    cmd += [classes, out]
    subprocess.run(cmd, capture_output=True)
    return out


def squash(text):
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s in ("}", "{", "};") or s.startswith(("import ", "package ")):
            continue
        parts, last = [], 0
        for m in STR.finditer(s):
            parts.append(re.sub(r"\s+", "", s[last:m.start()]))
            parts.append(m.group(0))
            last = m.end()
        parts.append(re.sub(r"\s+", "", s[last:]))
        t = "".join(parts)
        if t:
            out.append(t)
    return out


def srg_table(srg_dir):
    """func_123_a -> getString, field_456_b -> height, from the MCP csv dumps."""
    table = {}
    if not srg_dir:
        return table
    for fname in ("methods.csv", "fields.csv"):
        path = os.path.join(srg_dir, fname)
        if not os.path.isfile(path):
            continue
        with open(path, newline="", encoding="utf-8") as fh:
            for row in csv.reader(fh):
                if len(row) >= 2 and row[0].startswith(("func_", "field_")):
                    table[row[0]] = row[1]
    return table


def norm(text, table):
    for a, b in NORM:
        text = text.replace(a, b)
    text = text.replace("com.hbm.inventory.control_panel", LOCAL_PKG.replace("/", "."))
    if table:
        text = re.sub(r"\b(func_\d+_\w+|field_\d+_\w+)\b",
                      lambda m: table.get(m.group(1), m.group(1)), text)
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("--classes", default=os.path.join("build", "classes", "java", "main"))
    ap.add_argument("--work", default="tmp/parity2")
    ap.add_argument("--out", default="")
    ap.add_argument("--java", default="java")
    ap.add_argument("--vineflower", default=DEFAULT_VINEFLOWER)
    ap.add_argument("--mappings", default=DEFAULT_MAPPINGS)
    ap.add_argument("--srg-dir", default=DEFAULT_SRG_DIR)
    args = ap.parse_args()

    if not os.path.isfile(args.vineflower):
        sys.exit("vineflower not found: " + args.vineflower)

    os.makedirs(args.work, exist_ok=True)
    up_classes, n = extract(args.jar, args.work, UPSTREAM_PKG)
    print("upstream classes: %d" % n)

    up_src = decompile(args.java, args.vineflower, args.mappings,
                       os.path.join(up_classes, UPSTREAM_PKG), os.path.join(args.work, "up"))
    loc_src = decompile(args.java, args.vineflower, None,
                        os.path.join(args.classes, LOCAL_PKG), os.path.join(args.work, "local"))

    out_dir = args.out or os.path.join(args.work, "diff")
    os.makedirs(out_dir, exist_ok=True)

    table = srg_table(args.srg_dir)
    rows = []
    for root, _, files in os.walk(up_src):
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            rel = os.path.relpath(os.path.join(root, f), up_src).replace("\\", "/")
            lp = os.path.join(loc_src, rel)
            if not os.path.isfile(lp):
                rows.append((9999, rel, "MISSING LOCAL"))
                continue
            a = squash(norm(open(os.path.join(root, f), encoding="utf-8", errors="replace").read(), table))
            b = squash(norm(open(lp, encoding="utf-8", errors="replace").read(), table))
            d = list(difflib.unified_diff(a, b, "CE/" + rel, "LOCAL/" + rel, lineterm="", n=1))
            rows.append((len(d), rel, "\n".join(d)))

    rows.sort(reverse=True)
    differing = [r for r in rows if r[0]]
    for n, rel, d in differing:
        with open(os.path.join(out_dir, rel.replace("/", "__") + ".diff"), "w", encoding="utf-8") as fh:
            fh.write(d)
    for n, rel, _ in differing:
        print("%5d  %s" % (n, rel))
    print("\nidentical: %d / %d, diffs in %s" % (len(rows) - len(differing), len(rows), out_dir))


if __name__ == "__main__":
    main()
