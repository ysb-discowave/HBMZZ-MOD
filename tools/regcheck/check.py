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


def member_body(source, method_name):
    """Text of a method with any return type, up to the line that closes it."""
    match = re.search(r"\b[\w<>\[\]]+\s+" + method_name + r"\s*\(", source)
    if not match:
        return ""
    end = source.find("\n    }", match.end())
    return source[match.end():end if end > 0 else len(source)]


def field_names(source, type_pattern):    return set(re.findall(
        r"public\s+static\s+final\s+" + type_pattern + r"\s+([A-Z][A-Z_0-9]*)\s*=", source))


def registered_names(body):
    return set(re.findall(r"register\s*\(\s*([A-Z][A-Z_0-9]*)\s*\)", body))


def code_lines(source):
    """Lines with comments blanked out, keeping the line count.

    Some checks are about what the code *does*; a comment explaining the rule
    ("an empty stack means updateScreen dies on getFirst()") must not trip the
    rule it documents. Nothing here needs to understand strings: the files
    these checks read have no comment markers inside literals.
    """
    out = []
    in_block = False
    for line in source.splitlines():
        buf = []
        i = 0
        while i < len(line):
            if in_block:
                end = line.find("*/", i)
                if end < 0:
                    i = len(line)
                else:
                    in_block = False
                    i = end + 2
            elif line.startswith("//", i):
                i = len(line)
            elif line.startswith("/*", i):
                in_block = True
                i += 2
            else:
                buf.append(line[i])
                i += 1
        out.append("".join(buf))
    return out


def string_literals(source):
    """(line, text) for every string literal that is not inside a comment."""
    out = []
    i = 0
    line = 1
    n = len(source)
    while i < n:
        ch = source[i]
        if ch == "\n":
            line += 1
            i += 1
        elif source.startswith("//", i):
            j = source.find("\n", i)
            i = n if j < 0 else j
        elif source.startswith("/*", i):
            j = source.find("*/", i + 2)
            end = n if j < 0 else j
            line += source.count("\n", i, end)
            i = n if j < 0 else j + 2
        elif ch == '"':
            j = i + 1
            buf = []
            text = line
            while j < n:
                c = source[j]
                if c == "\\" and j + 1 < n:
                    nxt = source[j + 1]
                    buf.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
                    j += 2
                    continue
                if c == '"' or c == "\n":
                    break
                buf.append(c)
                j += 1
            out.append((text, "".join(buf)))
            line += "".join(buf).count("\n")
            i = j + 1
        elif ch == "'":
            j = i + 1
            while j < n and source[j] != "'":
                j += 2 if source[j] == "\\" else 1
            i = j + 1
        else:
            i += 1
    return out


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

    # 6. Every client-only window the blocks ask the proxy to open must have an
    # implementation on the client. A name that exists only on CommonProxy is a
    # no-op that compiles perfectly: 2.3.1 shipped a broadcaster whose
    # right-click called openVideoBroadcaster() into an empty base method, so
    # the block returned true, the hand swung, and no window ever appeared.
    common = read(os.path.join(SRC, "com/hbmzz/common/CommonProxy.java"))
    client = read(os.path.join(SRC, "com/hbmzz/client/ClientProxy.java"))
    wanted = set()
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for filename in filenames:
            if not filename.endswith(".java"):
                continue
            text = read(os.path.join(dirpath, filename))
            wanted |= set(re.findall(r"proxy\s*\.\s*(open\w+)\s*\(", text))
    missing_common = sorted(w for w in wanted if not re.search(r"void\s+" + w + r"\s*\(", common))
    missing_client = sorted(w for w in wanted if not re.search(r"void\s+" + w + r"\s*\(", client))
    check(not missing_common, "every proxy.openX(...) call exists on CommonProxy",
          "called but not declared: " + ", ".join(missing_common))
    check(not missing_client, "every proxy.openX(...) call is implemented on ClientProxy",
          "no-op on the client (the window never opens): " + ", ".join(missing_client))

    # 7. Every translation key the code asks for must exist in both languages.
    # A missing key shows the raw "hbmzz.msg.camera_bound" to the player, which
    # reads like a crash to anyone who is not the author.
    keys = set()
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for filename in filenames:
            if not filename.endswith(".java"):
                continue
            text = read(os.path.join(dirpath, filename))
            keys |= set(re.findall(
                r'(?:TextComponentTranslation|translateToLocal(?:Formatted)?)\s*\(\s*"(hbmzz\.[^"]+)"',
                text))

    def lang_keys(name):
        path = os.path.join(ROOT, "src/main/resources/assets/hbmzz/lang", name)
        if not os.path.exists(path):
            return set()
        return set(re.findall(r"^(hbmzz\.[^=\s]+)\s*=", read(path), re.MULTILINE))

    zh = lang_keys("zh_cn.lang")
    en = lang_keys("en_us.lang")
    for label, have in (("zh_cn.lang", zh), ("en_us.lang", en)):
        absent = sorted(k for k in keys if k not in have)
        check(not absent, "every %s key used in code exists in %s" % ("hbmzz.*", label),
              "missing: " + ", ".join(absent))

    # 8. The link cable binds by running Item.onItemUse, and 1.12.2 only calls
    # that after the block has declined the click. A block that claims every
    # right-click therefore makes its own linking tool unreachable - which is
    # exactly what shipped: a monitor that opened its terminal on every click,
    # so no camera, core or meter could ever be connected to it. The console is
    # exempt because it owns its editor and the cable only records it while
    # sneaking.
    LINK_TARGETS = ["BlockDFCMonitor", "BlockMonitorCamera", "BlockCtrlPanel"]
    LINK_EXEMPT = {"BlockCtrlConsole"}
    offenders = []
    for name in LINK_TARGETS:
        if name in LINK_EXEMPT:
            continue
        path = os.path.join(SRC, "com/hbmzz/block", name + ".java")
        if not os.path.exists(path):
            continue
        text = read(path)
        if "onBlockActivated" not in text:
            continue
        if "ItemMonitorLinker" not in member_body(text, "onBlockActivated"):
            offenders.append(name)
    check(not offenders, "every block the cable binds into declines the click for it",
          "claims the right-click, so ItemMonitorLinker.onItemUse never runs: "
          + ", ".join(offenders))

    # 9. Every control type must have a painter. A missing case does not fail to
    # compile - it silently falls through to `default: paintPlate`, which is a
    # blank grey square where a control should be. That is exactly how a new
    # type ships looking like a placeholder.
    type_source = read(os.path.join(SRC, "com/hbmzz/ctrl/CtrlType.java"))
    types = set(re.findall(r"^    ([A-Z][A-Z_0-9]*)\(", type_source, re.MULTILINE))
    painter = read(os.path.join(SRC, "com/hbmzz/client/screen/CtrlPainter.java"))
    switch_at = painter.find("switch (widget.type)")
    painted = set()
    if switch_at >= 0:
        body = painter[switch_at:painter.find("default:", switch_at)]
        painted = set(re.findall(r"case\s+([A-Z][A-Z_0-9]*)\s*:", body))
    unpainted = sorted(types - painted)
    check(not unpainted, "every CtrlType has a case in CtrlPainter.paint",
          "falls through to a blank plate: " + ", ".join(unpainted))

    # 10. Every control type must explain itself in the palette tooltip list.
    hints = set(re.findall(r"case\s+([A-Z][A-Z_0-9]*)\s*:\s*\n\s*return\s+\"", type_source))
    unhinted = sorted(types - hints)
    check(not unhinted, "every CtrlType has a non-empty hint()",
          "palette shows an empty tooltip: " + ", ".join(unhinted))

    # 11. The terminal's command table and its help text must agree: a verb that
    # completes but has no usage line is a command the player can run and cannot
    # look up.
    shell = read(os.path.join(SRC, "com/hbmzz/ctrl/TerminalShell.java"))
    start = shell.find("String[] COMMANDS")
    block = shell[start:shell.find("};", start)] if start >= 0 else ""
    declared = set(re.findall(r'"([a-z]+)"', block))
    documented = set(re.findall(r'USAGE\.put\("([a-z]+)"', shell))
    undoc = sorted(declared - documented)
    check(declared and not undoc, "every terminal verb has a usage line",
          "completes but 'help <verb>' says nothing: " + ", ".join(undoc))

    # 12. Every decorative glyph in a user-visible string must exist in the
    # fonts the AWT windows actually use.
    #
    # 2.4.0 shipped a terminal whose "current target" marker was U+25B8: present
    # in Consolas, absent from Microsoft YaHei. TermCanvas picks a font per
    # line, and a line carrying a machine name goes to the CJK face - so the
    # marker rendered as a tofu box on exactly the rows that mattered, while
    # looking perfectly fine in the source and in the Latin-only rows.
    #
    # The safe set below was measured with Font.canDisplay at 9-10 px on
    # Consolas, Microsoft YaHei, SimHei and Dialog. A glyph missing from any one
    # of them is one machine away from a box, so the set is the intersection.
    # CJK ideographs and full-width punctuation are allowed wholesale: those are
    # what the CJK faces exist for.
    safe_glyphs = set("\u00b0\u00b7\u00d7\u2013\u2014\u201c\u201d\u2026"
                      "\u2191\u2192\u2193\u25cb")
    offenders = []
    for base, _, files in sorted(os.walk(SRC)):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            for lineno, literal in string_literals(read(path)):
                for ch in literal:
                    cp = ord(ch)
                    if cp < 128 or ch in safe_glyphs:
                        continue
                    if 0x4E00 <= cp <= 0x9FFF or 0x3000 <= cp <= 0x303F or 0xFF00 <= cp <= 0xFFEF:
                        continue
                    offenders.append("%s:%d U+%04X" % (
                        os.path.relpath(path, ROOT).replace("\\", "/"), lineno, cp))
    check(not offenders, "no string uses a glyph the UI fonts cannot draw",
          "renders as a box: " + ", ".join(sorted(set(offenders))[:6]))

    # 15. The console editor must never take the top of its sub-element stack
    # without asking whether there is one.
    #
    # The stack is filled in initGui, so an exception inside initGui leaves it
    # empty - and then updateScreen() died on getFirst() every tick, turning a
    # broken menu into "the editor crashes on open". The empty case is only
    # reachable through another bug, which is exactly why it has to be handled:
    # one bug should not be reported as a different, unrecoverable one.
    editor = read(os.path.join(SRC, "com/hbmzz/cecp/GuiControlEdit.java"))
    unguarded = []
    for lineno, line in enumerate(code_lines(editor), 1):
        if "getFirst()" in line and "isEmpty()" not in line:
            unguarded.append("GuiControlEdit.java:%d" % lineno)
    check(not unguarded, "the editor never reads an empty sub-element stack",
          "unguarded getFirst(): " + ", ".join(unguarded))

    # 16. ControlType's table is filled by static initialisers, including ones
    # that only run on first use (the addon's three types). Any code that
    # iterates the live list is therefore iterating something that can grow
    # under it - the ConcurrentModificationException that took the editor down.
    # The list is private and all() hands out a copy, so this is about nobody
    # undoing that: one public field would put the trap back.
    type_src = read(os.path.join(SRC, "com/hbmzz/cecp/controls/ControlType.java"))
    leaks = []
    for base, _, files in sorted(os.walk(SRC)):
        for name in sorted(files):
            if not name.endswith(".java") or name == "ControlType.java":
                continue
            path = os.path.join(base, name)
            for lineno, line in enumerate(code_lines(read(path)), 1):
                if "ALL_VALUES" in line:
                    leaks.append("%s:%d" % (os.path.relpath(path, ROOT).replace("\\", "/"), lineno))
    exposed = "public static final List<ControlType> ALL_VALUES" in type_src
    check(not leaks and not exposed, "the control-type table is only read through all()",
          "reads the live list: " + (", ".join(leaks) if leaks else "field is public again"))

    failed = [name for ok, name, _ in results if not ok]
    print()
    if failed:
        print("%d/%d checks FAILED" % (len(failed), len(results)))
        return 1
    print("all %d checks passed" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
