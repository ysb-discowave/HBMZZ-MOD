#!/usr/bin/env python3
"""Static audit: everything the mod declares is actually registered.

Two bugs shipped in 2.3.0 that a compiler cannot see:

  * ITEM_BLOCK_VIDEO_BROADCASTER was declared but never handed to the item
    registry, so the block had no item - invisible in creative, unplaceable.
  * TileEntityMonitorCamera was never handed to GameRegistry.registerTileEntity.
    It syncs its aim through SPacketUpdateTileEntity, so the first setName() in
    onBlockPlacedBy called writeToNBT() -> "is missing a mapping!" -> the game
    died the instant the block was placed.

Both are pure declaration/registration mismatches inside this repository, which
means they can be caught without Minecraft. This script parses the source and
compares three sets: what is declared, and what actually reaches a registry.

    python tools/regcheck/check.py

Exit code 0 = every declared thing is registered. Nothing here starts a JVM.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONTENT = os.path.join(ROOT, "src/main/java/com/hbmzz/ModContent.java")
MAIN = os.path.join(ROOT, "src/main/java/com/hbmzz/HBMZZ.java")
SRC = os.path.join(ROOT, "src/main/java")

# Blocks that deliberately never get an item: the wall screen's filler part is
# placed by the monitor itself, and handing players one would only produce
# orphan blocks that break on the first tick.
ITEMLESS_BLOCKS = {"BLOCK_MONITOR_PART"}

# Tile entities that legitimately live outside GameRegistry.
TE_EXEMPT = set()

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


def body_of(source, method_name):
    """Text from `void <method_name>(` up to the line that closes it."""
    match = re.search(r"void\s+" + method_name + r"\s*\(", source)
    if not match:
        return ""
    end = source.find("\n    }", match.end())
    return source[match.end():end if end > 0 else len(source)]


def field_names(source, type_pattern):
    return set(re.findall(
        r"public\s+static\s+final\s+" + type_pattern + r"\s+([A-Z][A-Z_0-9]*)\s*=", source))


def registered_names(body):
    return set(re.findall(r"register\s*\(\s*([A-Z][A-Z_0-9]*)\s*\)", body))


def main():
    content = read(CONTENT)
    main_java = read(MAIN)

    blocks = field_names(content, r"[\w.]*Block")
    items = field_names(content, r"[\w.]*Item\w*")
    registered_blocks = registered_names(body_of(content, "registerBlocks"))
    registered_items_body = body_of(content, "registerItems")
    registered_items = registered_names(registered_items_body)

    # 1. Blocks declared but never registered.
    missing = sorted(blocks - registered_blocks)
    check(not missing, "every declared Block reaches the block registry",
          "declared but never registered: " + ", ".join(missing))

    # 2. Items declared but never registered.
    missing = sorted(items - registered_items)
    check(not missing, "every declared Item reaches the item registry",
          "declared but never registered: " + ", ".join(missing))

    # 3. Every placeable block has an item. Matching is by initializer, not by
    # name, because the console's item is ITEM_BLOCK_CTRL_CONSOLE.
    orphans = []
    for block in sorted(blocks):
        if block in ITEMLESS_BLOCKS:
            continue
        has_item = False
        for item in registered_items:
            decl = re.search(r"static\s+final\s+[\w.]+\s+" + item + r"\s*=\s*([^;]+);", content)
            if decl and re.search(r"\b" + block + r"\b", decl.group(1)):
                has_item = True
                break
        if not has_item:
            orphans.append(block)
    check(not orphans, "every placeable Block has a registered ItemBlock",
          "no item registered for: " + ", ".join(orphans))

    # 4. Every tile entity class has a registry mapping.
    te_classes = set()
    for dirpath, _dirnames, filenames in os.walk(SRC):
        if os.sep + "client" + os.sep in dirpath + os.sep:
            continue
        for filename in filenames:
            if not filename.endswith(".java"):
                continue
            text = read(os.path.join(dirpath, filename))
            if re.search(r"class\s+\w+\s+extends\s+(?:[\w.]+\.)?TileEntity\b", text):
                te_classes.add(filename[:-5])
    registered_tes = set(
        re.findall(r"registerTileEntity\s*\(\s*[\w.]*?(\w+)\.class\s*,", main_java))
    missing = sorted(te_classes - registered_tes - TE_EXEMPT)
    check(not missing, "every TileEntity class has a registry mapping",
          "would throw 'is missing a mapping!' on first sync: " + ", ".join(missing))

    # 5. ItemBlocks must carry their block's registry name, or they register
    # under null and disappear without an error.
    unnamed = []
    for item in sorted(registered_items):
        if not item.startswith("ITEM_BLOCK_"):
            continue
        if not re.search(re.escape(item) + r"\.setRegistryName\s*\(", registered_items_body):
            unnamed.append(item)
    check(not unnamed, "every registered ItemBlock sets its registry name",
          "no setRegistryName(...) before registering: " + ", ".join(unnamed))

    failed = [name for ok, name, _ in results if not ok]
    print()
    if failed:
        print("%d/%d checks FAILED" % (len(failed), len(results)))
        return 1
    print("all %d checks passed" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
