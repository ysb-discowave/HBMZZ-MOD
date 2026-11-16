#!/usr/bin/env python3
"""Static audit of blockstates: they must be *findable*, and they must fit meta.

Three bugs that no compiler and no offline registry audit could see, all three
shipped at least once:

  * A blockstate whose variant keys are not in alphabetical property order.
    ``BlockStateContainer`` keeps its properties in an ``ImmutableSortedMap``
    keyed by property *name*, so the string Forge builds to look a state up is
    always alphabetical. A file written in declaration order (``up,down,facing``)
    matches nothing and every variant of that block renders as the purple-black
    missing model - with no error anywhere, because "no variant matched" is not
    an exception until the model baker asks for one.
  * A block with more than sixteen states. ``Block.getStateId`` is
    ``blockId + (getMetaFromState(state) << 12)`` and ``getStateById`` reads the
    meta back with ``& 15``: meta is four bits, full stop. A block that returns
    0..63 from ``getMetaFromState`` writes state ids far outside the 16-bit slot
    the chunk stores, and reads back a different block entirely.
  * A variant that points at a model file that does not exist.

All three are checkable from the resources plus the source, which is what this
script does. It never starts a JVM.

    python tools/statecheck/check.py

Exit code 0 = every blockstate is reachable and every block fits in meta.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets")
SRC = os.path.join(ROOT, "src", "main", "java")

# 1.12.2: meta is the top four bits of the state id. See Block.getStateById.
META_LIMIT = 16

results = []


def check(ok, name, detail=""):
    results.append((bool(ok), name, detail))
    line = ("PASS  " if ok else "FAIL  ") + name
    if not ok and detail:
        line += "  -- " + detail
    print(line)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def model_exists(namespace, ref):
    """`ref` is `ns:path`, `ns:block/path`, or a bare name resolved in `namespace`.

    A bare name from a blockstate lives in ``models/block``; the full form
    (``hbmzz:block/x``) is used as written. Both spellings appear in this repo.
    """
    if ":" in ref:
        ns, _, path = ref.partition(":")
    else:
        ns, path = namespace, ref
    candidates = []
    if "/" in path:
        candidates.append(path)
    else:
        candidates.append("block/" + path)
        candidates.append("item/" + path)
    for candidate in candidates:
        target = os.path.join(ASSETS, ns, "models", *candidate.split("/")) + ".json"
        if os.path.isfile(target):
            return True
    return False


# ------------------------------------------------------------------ blockstates
def audit_blockstates():
    bad_order = []
    bad_set = []
    missing_model = []
    files = 0

    for namespace in sorted(os.listdir(ASSETS)):
        states_dir = os.path.join(ASSETS, namespace, "blockstates")
        if not os.path.isdir(states_dir):
            continue
        for name in sorted(os.listdir(states_dir)):
            if not name.endswith(".json"):
                continue
            files += 1
            path = os.path.join(states_dir, name)
            label = "%s:%s" % (namespace, name[:-5])
            try:
                data = load(path)
            except ValueError as exc:
                bad_set.append((label, "JSON 解析失败：%s" % exc))
                continue

            variants = data.get("variants")
            if not isinstance(variants, dict) or not variants:
                # multipart 或空文件：没有变体键可查，交给模型检查
                for ref in _collect_models(data):
                    if not model_exists(namespace, ref):
                        missing_model.append((label, ref))
                continue

            expected = None
            for key, value in variants.items():
                props = [kv.split("=")[0].strip() for kv in key.split(",") if kv.strip()]
                if props != sorted(props):
                    bad_order.append((label, key, ", ".join(sorted(props))))
                if expected is None:
                    expected = set(props)
                elif set(props) != expected:
                    bad_set.append((label, "属性集不一致：%s vs %s"
                                    % (sorted(expected), sorted(props))))
                for ref in _collect_models(value):
                    if not model_exists(namespace, ref):
                        missing_model.append((label, ref))

    if bad_order:
        files_hit = sorted(set(a for a, _b, _c in bad_order))
        check(False, "变体键按属性名字母序（否则整块查不到模型）",
              "%d 个文件、%d 条键；例如 %s"
              % (len(files_hit), len(bad_order),
                 "; ".join("%s 的 %r 应为 %r" % (a, b, c) for a, b, c in bad_order[:2])))
    else:
        check(True, "变体键按属性名字母序（否则整块查不到模型）")
    check(not bad_set, "同一文件内所有变体的属性集一致",
          "; ".join("%s: %s" % (a, b) for a, b in bad_set[:3]))
    check(not missing_model, "每个变体指向的模型文件都存在",
          "; ".join("%s -> %s" % (a, b) for a, b in missing_model[:3]))
    print("      （扫了 %d 个 blockstate）" % files)


def _collect_models(node):
    """Every `model` reference anywhere in a variant / multipart value."""
    found = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "model" and isinstance(value, str):
                found.append(value)
            else:
                found.extend(_collect_models(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(_collect_models(item))
    return found


# ------------------------------------------------------------------ state count
PROP_BOOL = "PropertyBool"
PROP_DIR = "PropertyDirection"
PROP_INT = "PropertyInteger"
PROP_ENUM = "PropertyEnum"


def _scan_classes():
    """class -> (superclass, source). 属性可能声明在父类上，得能往上找。"""
    classes = {}
    for base, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            text = read(os.path.join(base, name))
            match = re.search(r"\b(?:class|enum)\s+(\w+)[^{]*?extends\s+([\w.<>]+)", text)
            superclass = match.group(2).split("<")[0] if match else None
            classes[name[:-5]] = (superclass, text)
    return classes


def _prop_expr(classes, class_name, field, depth=0):
    """Find `PropertyX field = <expr>;` in the class or any ancestor."""
    if class_name not in classes or depth > 8:
        return None
    superclass, text = classes[class_name]
    decl = re.search(
        r"Property\w*(?:<[^>]+>)?\s+%s\s*=\s*([^;]+);" % re.escape(field), text)
    if decl:
        return decl.group(1)
    if superclass is None or superclass not in classes:
        # 父类在原版里：只有少数几个常量能确定取值
        if superclass in ("BlockHorizontal", "BlockDirectional") and field == "FACING":
            return "BlockHorizontal.FACING"
        if superclass == "BlockRotatedPillar" and field == "AXIS":
            return "PropertyEnum.AXIS"      # 3 个值，见下面 EnumFacing.Axis 特判
        return None
    return _prop_expr(classes, superclass, field, depth + 1)


def _cardinality(classes, class_name, arg):
    """Allowed values of the property `arg`, or None if it cannot be told."""
    field = arg.split(".")[-1]
    expr = _prop_expr(classes, class_name, field)
    if expr is None:
        return None

    if expr == "PropertyEnum.AXIS":
        return 3                            # EnumFacing.Axis: X / Y / Z
    if "BlockHorizontal.FACING" in expr or "BlockDirectional.FACING" in expr:
        return 4
    if PROP_BOOL in expr:
        return 2
    if PROP_DIR in expr:
        if "Plane.HORIZONTAL" in expr:
            return 4
        if "Plane.VERTICAL" in expr:
            return 2
        # 一参数的 create() 默认 Plane.ALL
        return 6
    if PROP_INT in expr:
        # create("level", 0, 15) —— 名字里没有数字，取到的是 [min, max]
        nums = re.findall(r"-?\d+", expr)
        if len(nums) >= 2 and int(nums[1]) >= int(nums[0]):
            return int(nums[1]) - int(nums[0]) + 1
        return None
    if PROP_ENUM in expr:
        match = re.search(r"PropertyEnum\.create\(\s*\"[^\"]*\"\s*,\s*(\w+)\.class", expr)
        if not match:
            return None
        enum_name = match.group(1)
        if enum_name == "EnumFacing":
            # Plane.HORIZONTAL 只看四向；否则六向
            return 4 if "Plane.HORIZONTAL" in expr else 6
        # 枚举可能嵌套在别的类里，不能只按文件名找
        pattern = re.compile(r"\benum\s+%s\b([^{]*)\{" % enum_name)
        for _base, _dirs, files in os.walk(SRC):
            for name in files:
                if not name.endswith(".java"):
                    continue
                body = read(os.path.join(_base, name))
                match2 = pattern.search(body)
                if not match2:
                    continue
                head = body[match2.end():].split(";", 1)[0]
                return len(re.findall(r"^\s*[A-Z][A-Z0-9_]*\s*(?:\(|,|$)", head, re.M))
        return None
    return None


def _meta_bound(classes, class_name, text, props):
    """Upper bound of what ``getMetaFromState`` can return, or None if unknown.

    A block may hold more than sixteen *states* as long as the part that is
    persisted fits four bits - vanilla ``BlockStairs`` is the reference case
    (24 states, 3 bits of meta, the shape is recomputed from neighbours). So
    the rule is not "at most sixteen states", it is "at most sixteen possible
    return values".
    """
    body_match = re.search(r"getMetaFromState\s*\([^)]*\)\s*\{(.*?)\n    \}", text, re.S)
    if not body_match:
        return None
    body = body_match.group(1)

    # x & 63 / x & MASK_MAX —— 掩码直接写明上界
    masks = []
    for literal in re.findall(r"&\s*(\d+)", body):
        masks.append(int(literal))
    for name in re.findall(r"&\s*([A-Z][A-Z0-9_]*)", body):
        const = re.search(r"\bint\s+%s\s*=\s*(\d+)\s*;" % name, text)
        if const:
            masks.append(int(const.group(1)))
        else:
            return None
    if masks:
        return max(masks)

    # 否则按它读到的属性算：返回值最多是这些属性的组合数
    names = set(re.findall(r"getValue\(\s*([A-Z][A-Z0-9_]*)\s*\)", body))
    if not names:
        # 常量返回（含 return 0）
        return 0 if re.search(r"return\s+\d+\s*;", body) else None
    total = 1
    for name in names:
        count = _cardinality(classes, class_name, name)
        if count is None:
            return None
        total *= count
    return total - 1 if total else 0


def audit_state_counts():
    """Persisted metadata must fit four bits, whatever the state count is."""
    over = []
    narrowed = []
    unresolved = []
    scanned = 0
    classes = _scan_classes()

    for class_name, (_superclass, text) in classes.items():
        match = re.search(r"new\s+BlockStateContainer\s*\(\s*this\s*,(.*?)\)\s*;", text, re.S)
        if not match:
            continue
        scanned += 1
        args = [a.strip() for a in match.group(1).split(",") if a.strip()]
        total = 1
        unknown = []
        for arg in args:
            count = _cardinality(classes, class_name, arg)
            if count is None:
                unknown.append(arg)
            else:
                total *= count
        if unknown:
            unresolved.append((class_name, ", ".join(unknown)))
            continue
        if total <= META_LIMIT:
            continue
        bound = _meta_bound(classes, class_name, text, args)
        if bound is None:
            over.append((class_name, "%d 态，且看不出 meta 上界" % total))
        elif bound > META_LIMIT - 1:
            over.append((class_name, "%d 态，meta 可达 %d" % (total, bound)))
        else:
            narrowed.append((class_name, total, bound))

    check(not over, "meta 不超过 4 位（状态多可以，进存档的必须窄）",
          "; ".join("%s: %s" % (n, d) for n, d in over))
    if narrowed:
        print("      这些方块状态超过 16，但 meta 收窄了（和原版楼梯同一路子）：")
        for name, total, bound in narrowed:
            print("        %s：%d 态 / meta 0..%d" % (name, total, bound))
    if unresolved:
        print("      跳过 %d 个无法静态数清的方块：%s"
              % (len(unresolved), ", ".join("%s(%s)" % (n, p) for n, p in unresolved[:4])))
    print("      （数了 %d 个方块的状态数）" % scanned)


def main():
    print("== blockstates ==")
    audit_blockstates()
    print("== 状态数 ==")
    audit_state_counts()

    failed = [name for ok, name, _ in results if not ok]
    print()
    if failed:
        print("%d/%d checks FAILED" % (len(failed), len(results)))
        return 1
    print("all %d checks passed" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
