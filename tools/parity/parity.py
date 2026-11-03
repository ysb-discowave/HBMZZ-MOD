"""Structural parity check: CE's control panel vs. the port in src/main/java.

Usage:
    python tools/parity/parity.py <NTM-CE.jar> [--work DIR] [--src DIR]

Three independent views, because each one catches a different kind of drift:

1. class coverage   - every class in the upstream package has a counterpart
2. member coverage  - every upstream method/field name exists locally
3. source diff      - line diff of the (normalised) decompiled upstream code
                      against the port, so "same shape, emptied body" shows up

The source-diff pass needs a decompiler. Vineflower ships inside ForgeGradle's
cache; pass it with --vineflower, or the pass is skipped and the script still
reports coverage.
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

SRG_MAP_DIR = r"C:/Users/Yang Sibo/.gradle/caches/minecraft/de/oceanlabs/mcp/mcp_snapshot/20171003"
DEFAULT_VINEFLOWER = (r"C:/Users/Yang Sibo/.gradle/caches/minecraftforge/forgegradle/mavenizer/"
                      r"caches/maven/mcp-tools/org/vineflower/vineflower/1.12.0/vineflower-1.12.0.jar")

HDR = re.compile(r'\b(?:class|interface|enum)\s+([\w\.\$]+)')
MEMBER = re.compile(r'^(?:[\w\.\[\]$<>?,\s]+)\s+(\w+)\((.*)\);?$')

# Upstream helpers the port replaces with its own shims.
REPL = [
    ("com.hbm.inventory.control_panel", "com.hbmzz.cecp"),
    ("com.hbm.render.NTMRenderHelper", "com.hbmzz.cecp.compat.GuiFx"),
    ("NTMRenderHelper", "GuiFx"),
    ("com.hbm.render.util.NTMImmediate", "com.hbmzz.cecp.compat.CtrlImm"),
    ("NTMImmediate", "CtrlImm"),
    ("field_71466_p", "fontRenderer"),
    ("field_71441_e", "world"),
    ("func_82737_E", "getTotalWorldTime"),
    ("func_71410_x()", "getMinecraft()"),
    ("func_110434_K()", "getTextureManager()"),
    ("func_110577_a(", "bindTexture("),
    ("func_74782_a(", "setTag("),
    ("func_74775_l(", "getCompoundTag("),
    ("func_74776_a(", "setFloat("),
    ("func_74768_a(", "setInteger("),
    ("func_74778_a(", "setString("),
    ("func_74760_g(", "getFloat("),
    ("func_74762_e(", "getInteger("),
    ("func_74779_i(", "getString("),
    ("func_150296_c()", "getKeySet()"),
    ("func_74764_b(", "hasKey("),
    ("func_74769_h(", "getDouble("),
    ("func_150287_d()", "getInt()"),
    ("func_150291_c()", "getString()"),
]


def extract(jar, work):
    out = os.path.join(work, "classes")
    if os.path.isdir(out):
        shutil.rmtree(out)
    count = 0
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith(UPSTREAM_PKG + "/") and name.endswith(".class"):
                path = os.path.join(out, name)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as fh:
                    fh.write(z.read(name))
                count += 1
    return out, count


def javap_dump(javap, classpath, classes):
    out = subprocess.run([javap, "-p", "-cp", classpath] + classes,
                         capture_output=True, text=True, errors="ignore").stdout
    res, cur = {}, None
    for raw in out.splitlines():
        line = raw.strip()
        if not line or line.startswith("Compiled from"):
            continue
        h = HDR.search(line)
        if h and line.endswith("{"):
            cur = h.group(1)
            res.setdefault(cur, set())
            continue
        m = MEMBER.match(line)
        if not m or cur is None:
            continue
        name = m.group(1)
        if name.startswith("lambda$") or name == "<clinit>":
            continue
        res[cur].add(name + "(" + m.group(2) + ")")
    return res


def decompile(java, vineflower, classes, work):
    out = os.path.join(work, "src")
    if os.path.isdir(out):
        shutil.rmtree(out)
    subprocess.run([java, "-jar", vineflower,
                    "-dgs=1", "-hdc=0", "-asc=1", "-rsy=1", "-jvs=1",
                    os.path.join(classes, UPSTREAM_PKG), out],
                   capture_output=True)
    return out


def srg_table():
    table = {}
    for fname in ("methods.csv", "fields.csv"):
        path = os.path.join(SRG_MAP_DIR, fname)
        if not os.path.isfile(path):
            continue
        with open(path, newline="", encoding="utf-8") as fh:
            for row in csv.reader(fh):
                if len(row) >= 2 and row[0].startswith(("func_", "field_")):
                    table[row[0]] = row[1]
    return table


def normalise(text, table):
    for a, b in REPL:
        text = text.replace(a, b)
    return re.sub(r'\b(func_\d+_\w+|field_\d+_\w+)\b',
                  lambda m: table.get(m.group(1), m.group(1)), text)


def squash(path):
    out = []
    for line in open(path, encoding="utf-8", errors="replace").read().splitlines():
        s = re.sub(r'\s+', ' ', line.strip()).replace("\t", "")
        if not s or s in ("}", "{", "};"):
            continue
        out.append(s.replace("float[] {", "float[]{"))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("--work", default="tmp/parity")
    ap.add_argument("--src", default="src/main/java/com/hbmzz/cecp")
    ap.add_argument("--javap", default=shutil.which("javap") or "javap")
    ap.add_argument("--java", default="java")
    ap.add_argument("--vineflower", default=DEFAULT_VINEFLOWER)
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    classes, nclasses = extract(args.jar, args.work)
    print("upstream classes: %d" % nclasses)

    # ---- 1/2. coverage -------------------------------------------------
    names = []
    for root, _, files in os.walk(classes):
        for f in files:
            if f.endswith(".class") and "$" not in f:
                rel = os.path.relpath(os.path.join(root, f), classes).replace("\\", "/")
                names.append(rel[:-6].replace("/", "."))
    names.sort()
    local_names = [n.replace("com.hbm.inventory.control_panel", "com.hbmzz.cecp") for n in names]

    ce = javap_dump(args.javap, classes, names)
    loc = javap_dump(args.javap, os.path.join("build", "classes", "java", "main"), local_names)

    missing_cls, missing_mem, empty = [], [], []
    for ce_cls, lcls in zip(names, local_names):
        ce_m = set(s.replace("com.hbm.inventory.control_panel", "com.hbmzz.cecp")
                   for s in ce.get(ce_cls, set()))
        lo_m = loc.get(lcls, set())
        if not os.path.isfile(os.path.join(args.src, lcls.replace("com.hbmzz.cecp.", "").replace(".", "/") + ".java")):
            missing_cls.append(ce_cls)
            continue
        if not lo_m:
            empty.append(lcls)
            continue
        gap = sorted(ce_m - lo_m)
        if gap:
            missing_mem.append((lcls, gap))

    print("ported classes  : %d/%d" % (len(names) - len(missing_cls), len(names)))
    for c in missing_cls:
        print("  MISSING CLASS", c)
    for c in empty:
        print("  NO METHODS   ", c)
    for lcls, gap in missing_mem:
        print("  MEMBER GAP   ", lcls)
        for g in gap:
            print("      -", g)

    # ---- 3. source diff ------------------------------------------------
    if not os.path.isfile(args.vineflower):
        print("vineflower not found, skipping source diff")
        return
    src = decompile(args.java, args.vineflower, classes, args.work)
    table = srg_table()
    diffs = []
    for root, _, files in os.walk(src):
        for f in files:
            if not f.endswith(".java"):
                continue
            rel = os.path.relpath(os.path.join(root, f), src).replace("\\", "/")
            lp = os.path.join(args.src, rel.replace("com/hbm/inventory/control_panel", "").lstrip("/"))
            if not os.path.isfile(lp):
                continue
            text = normalise(open(os.path.join(root, f), encoding="utf-8", errors="replace").read(), table)
            tmp = os.path.join(args.work, "norm_" + f)
            with open(tmp, "w", encoding="utf-8") as fh:
                fh.write(text)
            a, b = squash(tmp), squash(lp)
            if a == b:
                continue
            d = [l for l in difflib.unified_diff(a, b, lineterm="", n=0)]
            diffs.append((len(d), rel, d))
    diffs.sort(reverse=True)
    print("\nsource diff (formatting/naming noise included):")
    for n, rel, _ in diffs[:15]:
        print("  %-40s %4d lines" % (rel, n))


if __name__ == "__main__":
    main()
