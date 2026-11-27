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

# The jar carries a second mod container: com/rsaudio, the redstone audio
# player ported in 2.9.0, which keeps its own block/item/tile registry, its own
# proxy and its own screen fonts. The declaration->registration rules below are
# about *this* mod's tables, so they read com/hbmzz only. Walking all of SRC
# flags every rsaudio tile entity as "missing a mapping" and every rsaudio
# window as "no-op on the client" - 23 + 18 false positives, which is exactly
# how a real regression gets lost in the noise.
AUDITED = os.path.join(SRC, "com/hbmzz")

# Blocks that deliberately never get an item: the wall screen's filler part is
# placed by the monitor itself, and handing players one would only produce
# orphan blocks that break on the first tick.
ITEMLESS_BLOCKS = {"BLOCK_MONITOR_PART", "BLOCK_GATE_PART", "BLOCK_VAULT_PART"}

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


def tab_body(source, method_name):
    """Same, for the ported files, which close their methods with a tab.

    body_of() looks for four spaces because the files written for this mod are
    indented that way; the ported Cursed Edition sources indent with tabs, so
    a four-space search runs past the end of the method and swallows whatever
    comes next - which for a "does the registry list this control" check means
    reading half the file and passing on anything.
    """
    match = re.search(r"\b[\w<>\[\]]+\s+" + method_name + r"\s*\(", source)
    if not match:
        return ""
    ends = [source.find(mark, match.end()) for mark in ("\n\t}", "\n    }")]
    ends = [end for end in ends if end >= 0]
    end = min(ends) if ends else len(source)
    return source[match.end():end]


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
    for dirpath, _dirnames, filenames in os.walk(AUDITED):
        if os.sep + "client" + os.sep in dirpath + os.sep:
            continue
        for filename in filenames:
            if not filename.endswith(".java"):
                continue
            text = read(os.path.join(dirpath, filename))
            # An abstract base never needs a mapping: it is never instantiated,
            # so "is missing a mapping!" cannot happen to it. Flagging it would
            # push the next person to give a base class a fake registration.
            match = re.search(r"(abstract\s+)?class\s+\w+\s+extends\s+(?:[\w.]+\.)?TileEntity\b", text)
            if match and not match.group(1):
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
    for dirpath, _dirnames, filenames in os.walk(AUDITED):
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

    # 8b. 大屏这一对走的是另一件工具：大屏遥控器先对准播放器，再右键大屏。
    # 同样的道理——方块只要把右键吞掉，遥控器的 onItemUse 就永远不会跑，
    # 大屏也就永远接不上任何播放器。这条以前没单独写，是因为大屏曾经借用
    # 监控数据线；现在两套工具分开，检查也得跟着分开。
    REMOTE_TARGETS = ["BlockVideoBroadcaster", "BlockVideoScreen"]
    remote_offenders = []
    for name in REMOTE_TARGETS:
        path = os.path.join(SRC, "com/hbmzz/block", name + ".java")
        if not os.path.exists(path):
            continue
        text = read(path)
        if "onBlockActivated" not in text:
            continue
        if "ItemVideoRemote" not in member_body(text, "onBlockActivated"):
            remote_offenders.append(name)
    check(not remote_offenders, "every block the remote connects declines the click for it",
          "claims the right-click, so ItemVideoRemote.onItemUse never runs: "
          + ", ".join(remote_offenders))

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
    for base, _, files in sorted(os.walk(AUDITED)):
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

    # 14b. The terminal window must pick its face from the string, not from the
    # call site.
    #
    # 2.5.1's prompt read "反应堆>" - the current slot's tag, which on the ported
    # console is an operator's own Chinese link tag - and it was drawn with the
    # monospaced face, so it came out as three tofu boxes. Nothing threw: AWT
    # draws the notdef glyph and moves on.
    #
    # TermCanvas now has one entry point per primitive (text/width/clip all take
    # size+bold and derive the face from the value), which makes measure and
    # draw physically incapable of disagreeing. A direct ScreenFonts.mono() in
    # that file is the only way back to the old behaviour, so it is banned.
    term_canvas = read(os.path.join(SRC, "com/hbmzz/client/screen/TermCanvas.java"))
    bypass = []
    for lineno, line in enumerate(code_lines(term_canvas), 1):
        if "ScreenFonts.mono" in line:
            bypass.append("TermCanvas.java:%d %s" % (lineno, line.strip()[:60]))
    check(not bypass, "the terminal window never picks a face by hand",
          "a Chinese tag drawn as monospaced becomes boxes: " + "; ".join(bypass[:3]))

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

    # 17. A control type the addon registers must have a Chinese name. The
    # editor's "pick a type" menu is drawn from getDisplayName(), and the
    # fallback in localized() is the key itself - so a forgotten case does not
    # fail, it just puts "Terminal" in a menu where every other row is Chinese.
    addon_types = read(os.path.join(SRC, "com/hbmzz/cecp/controls/addon/AddonControlType.java"))
    names = set(re.findall(r'registerOrGet\s*\(\s*"([^"]+)"\s*\)', "\n".join(code_lines(addon_types))))
    translated = set(re.findall(r'case\s+"([^"]+)"\s*:\s*return\s+"([^"]*)"', type_src))
    have = set(name for name, label in translated if label and label != name)
    untranslated = sorted(names - have)
    check(names and not untranslated, "every addon control type has a Chinese display name",
          "menu shows the English key: " + ", ".join(untranslated))

    # 18. Every control class the addon ships must actually be handed to the
    # registry. A class nobody adds is dead code that still compiles - which is
    # how 2.3.0 shipped a block with no item and a tile entity with no mapping.
    # The registry silently drops duplicates, so a control that is written but
    # unlisted looks exactly like one that works, right up until you open the
    # menu and cannot find it.
    addon_dir = os.path.join(SRC, "com/hbmzz/cecp/controls/addon")
    concrete = set()
    for name in sorted(os.listdir(addon_dir)):
        if not name.endswith(".java") or name in ("AddonControlType.java",):
            continue
        text = read(os.path.join(addon_dir, name))
        if re.search(r"\babstract\s+class\b", text):
            continue
        if not re.search(r"\bclass\s+\w+\s+extends\s+Control\b", text):
            continue
        concrete.add(name[:-5])
    registry = read(os.path.join(SRC, "com/hbmzz/cecp/ControlRegistry.java"))
    # Comments blanked, not just skipped: "//addonControls.add(new TerminalControl(..."
    # is exactly what a half-finished port looks like, and it is not registered.
    addon_body = "\n".join(code_lines(tab_body(registry, "initAddonControls")))
    listed = set(re.findall(r"new\s+(?:[\w.]+\.)?(\w+)\s*\(", addon_body))
    unlisted = sorted(concrete - listed)
    check(not unlisted, "every addon control class is in the control registry",
          "never instantiated in initAddonControls: " + ", ".join(unlisted))

    # 19. Registry names have to be unique: ControlRegistry.put() overwrites,
    # so a collision removes one control from every saved panel without an
    # error - the panel loads, one control is simply the other one now.
    entries = re.findall(
        r"new\s+(?:[\w.]+\.)?\w+\s*\(\s*\"[^\"]*\"\s*,\s*(\"[^\"]*\"|[^,]+?)\s*,\s*null\s*\)",
        addon_body)
    seen = {}
    clashes = []
    for name in entries:
        token = name.strip()
        if token.startswith('"'):
            key = token.strip('"')
        else:
            # A constant: follow it into the class that declares it. The
            # constant is qualified (TerminalControl.REGISTRY), so the owner is
            # the segment before it, not the first one.
            parts = token.split(".")
            constant = parts[-1]
            owner = parts[-2] if len(parts) > 1 else parts[0]
            path = os.path.join(addon_dir, owner + ".java")
            found = re.search(r'\b' + constant + r'\s*=\s*"([^"]*)"', read(path)) \
                if os.path.exists(path) else None
            key = found.group(1) if found else "<" + token + ">"
        if key in seen:
            clashes.append(key)
        seen[key] = token
    check(entries and not clashes, "every addon control has its own registry name",
          "silently overwrites another control: " + ", ".join(sorted(set(clashes))))

    # 20. Three places spell the version and only one of them is bumped by hand
    # reliably. HBMZZ.VERSION is what FML prints in "Client attempting to join
    # with N mods: ... hbmzz@<ver>", so a stale literal means the log lies about
    # which build is loaded - it said 2.0.0 for five releases. The annotation
    # needs a compile-time constant, so this cannot be read from mcmod.info at
    # runtime; comparing the three is the next best thing.
    gradle_version = re.search(r"^version\s*=\s*'([^']+)'",
                               read(os.path.join(ROOT, "build.gradle")), re.M)
    mod_info = re.search(r'"version"\s*:\s*"([^"]+)"',
                         read(os.path.join(SRC, "..", "resources", "mcmod.info")))
    java_version = re.search(r'VERSION\s*=\s*"([^"]+)"',
                             read(os.path.join(SRC, "com/hbmzz/HBMZZ.java")))
    stamps = [m.group(1) if m else None for m in (gradle_version, mod_info, java_version)]
    check(len(set(stamps)) == 1 and stamps[0],
          "the three version stamps agree (build.gradle / mcmod.info / HBMZZ.VERSION)",
          "got " + " / ".join(str(s) for s in stamps))

    # 21. The same-name broadcast hangs off MachineBridge.fireAll, because that
    # is the one method every write in this mod passes through - a knob, a rule,
    # the node graph, a terminal. If the call goes away, nothing fails to
    # compile: every caller still sends to one machine, and the reactor just
    # drifts apart one rod at a time.
    bridge = read(os.path.join(SRC, "com/hbmzz/ctrl/MachineBridge.java"))
    # Anchored on the definition: fire() calls fireAll(), so a regex that just
    # looks for the name would read the wrong method and pass on a file where
    # the fan-out is gone.
    fire_all_at = bridge.find("public static boolean fireAll(")
    fire_all_body = bridge[fire_all_at:bridge.find("\n    }", fire_all_at)] if fire_all_at >= 0 else ""
    check("PartBroadcast.fanOut(" in fire_all_body,
          "every machine write fans out to its same-name parts",
          "MachineBridge.fireAll no longer calls PartBroadcast.fanOut")

    # 22. ...and a fan-out that already covers the group must not fan out again.
    # Two writers do their own multi-machine loop: PartGroup (one slot, many
    # parts) and ControlEventSystem (one node, many positions). Both have to set
    # the scope guard, or a four-rod write delivers sixteen events and a
    # momentary door_toggle closes the door it just opened.
    groups = read(os.path.join(SRC, "com/hbmzz/ctrl/PartGroup.java"))
    events_sys = read(os.path.join(SRC, "com/hbmzz/cecp/ControlEventSystem.java"))
    check("PartBroadcast.beginScope(" in member_body(groups, "fire"),
          "a group write scopes its own fan-out",
          "PartGroup.fire would deliver each part N times")
    # The file has two broadcastEvent overloads, so the body extractor cannot
    # be pointed at one of them by name; the guard is asserted per file.
    check("PartBroadcast.beginScope(" in events_sys and "PartBroadcast.endScope(" in events_sys,
          "a multi-target broadcast scopes its own fan-out",
          "ControlEventSystem.broadcastEvent would deliver each position N times")

    # 23. The terminal must be handed the grouped host. Building the raw host at
    # the call site still compiles and still works - it just quietly goes back to
    # one tab per linker tag, which is the state this feature exists to end.
    raw_hosts = []
    for base, _dirs, files in sorted(os.walk(SRC)):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            for lineno, line in enumerate(code_lines(read(path)), 1):
                if re.search(r"TerminalShell\.execute\(\s*new\s", line):
                    raw_hosts.append("%s:%d" % (
                        os.path.relpath(path, ROOT).replace("\\", "/"), lineno))
    check(not raw_hosts, "the terminal is always given a grouped host",
          "TerminalShell.execute gets a raw host: " + ", ".join(raw_hosts))

    # 24. The family table has to know the two reactors the feature was asked
    # for. An empty or renamed table still compiles - the window would simply
    # stop folding, one family at a time.
    types = read(os.path.join(SRC, "com/hbmzz/ctrl/PartTypes.java"))
    families = re.findall(r"\{\"([^\"]+)\",\s*(\w+),", types)
    missing = [name for name, key in (("RBMK", "RBMK"), ("DFC", "DFC"))
               if name not in [entry[0] for entry in families]]
    check(not missing, "the part-family table covers RBMK and DFC",
          "no family rule for: " + ", ".join(missing))

    # 25. Grouping must stay a family test, never a distance test. A radius looks
    # harmless and then quietly refuses to fold a 30-block DFC, or merges two
    # reactors that happen to be built flush - and the player has no way to
    # argue with it. What separates two reactors is two terminals.
    # Math.abs is the canary: no distance test can be written without it.
    grouped = read(os.path.join(SRC, "com/hbmzz/ctrl/GroupedHost.java"))
    check("byFamily" in types and "Math.abs(" not in types and "PartTypes.byFamily(" in grouped,
          "part grouping is by family, not by distance",
          "PartTypes is back to measuring coordinates, or GroupedHost stopped using byFamily")

    # 26. Broadcast scope comes from the terminals that link the source, not from
    # geometry either. If peersOf() ever starts grouping by position again, two
    # reactors on two panels would cross-talk exactly as before.
    peers = member_body(read(os.path.join(SRC, "com/hbmzz/ctrl/PartBroadcast.java")), "peersOf")
    check("membersAround(" in peers and "byFamily(" not in peers,
          "broadcast scope is the terminal's own binding list",
          "PartBroadcast.peersOf derives peers from something other than the panel links")

    # ------------------------------------------------------------------ RBMK
    # The staged blow-up talks to somebody else's reactor. Every rule below is
    # about keeping that borrowing honest: no compile dependency, no second
    # reflection layer, no second meltdown line, and nothing that turns the
    # flying lid back into a block.

    # 27. The base mod is not a build dependency and must not become one. The
    # build has no NTM on its classpath at all, so an import would not even
    # compile - but a `compileOnly files('libs/...')` added "just for one field"
    # would, and then the jar stops loading on a pack without that exact version.
    base_imports = []
    for base, _dirs, files in sorted(os.walk(SRC)):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            for lineno, line in enumerate(read(path).splitlines(), 1):
                if re.match(r"\s*import\s+com\.hbm[.;]", line):
                    base_imports.append("%s:%d" % (
                        os.path.relpath(path, ROOT).replace("\\", "/"), lineno))
    check(not base_imports, "nothing imports the base mod",
          "compile dependency on NTM crept in: " + ", ".join(base_imports))

    # 28. Reflection lives in one file. MachineBridge already carries the
    # machine half; the reactor half belongs in RbmkParts and nowhere else, or
    # the next base-mod rename has to be chased through five call sites.
    rbmk_dir = os.path.join(SRC, "com/hbmzz/rbmk")
    stray = []
    for name in sorted(os.listdir(rbmk_dir)):
        if not name.endswith(".java") or name == "RbmkParts.java":
            continue
        source = read(os.path.join(rbmk_dir, name))
        for marker in ("Class.forName(", ".getField(", ".getDeclaredField("):
            if marker in source:
                stray.append(name + " uses " + marker)
    check(not stray, "reactor reflection stays in RbmkParts",
          "; ".join(stray))

    # 29. The jump is asked for in the addon's own damage units, and 1200 of
    # them is where that addon melts the reactor down itself. One tick at or
    # above that line and the base meltdown wins the race: the lid never moves
    # and the whole sequence is dead code nobody notices until a reactor blows.
    timing = read(os.path.join(rbmk_dir, "RbmkTiming.java"))
    cap = re.search(r"DAMAGE_END\s*=\s*(\d+)", timing)
    check(cap is not None and int(cap.group(1)) < 1200,
          "the requested jump stays under the addon's meltdown",
          "DAMAGE_END is " + (cap.group(1) if cap else "missing"))

    # 30. The plate is an entity, never a block. A lid that lands as a block
    # plants a live RBMK column wherever it stops - a new reactor, with a tile
    # entity, born from rubble. Dropping debris is fine; placing is not.
    lid = read(os.path.join(SRC, "com/hbmzz/entity/EntityRbmkLid.java"))
    check("setBlockState(" not in lid,
          "the flying lid never places a block",
          "EntityRbmkLid writes a block state; that is how a lid becomes a reactor")

    # 31. The decision layer has to stay runnable without Minecraft, or
    # tools/rbmkcheck cannot run it and the trip condition goes untested - which
    # is the one thing that must not be discovered by melting a real save.
    check("import net.minecraft" not in timing,
          "the trip logic has no Minecraft in it",
          "RbmkTiming imports the game; tools/rbmkcheck can no longer run it")

    # 32. The film camera is a second entity, never a teleported player. Moving
    # the player would be corrected by the server within a second (and would
    # drag the hitbox through walls on the way); the one thing that must not
    # appear here is any call that writes the player's position.
    cam = read(os.path.join(SRC, "com/hbmzz/client/ClientCinematic.java"))
    moved = [marker for marker in ("player.setPosition(", "player.setPositionAndUpdate(",
                                   "player.setLocationAndAngles(", "player.posX =")
             if marker in cam]
    check(not moved, "the zd_tx camera never moves the player",
          "ClientCinematic writes the player's position (" + ", ".join(moved)
          + "); an orbit is built by swapping the view entity, not by teleporting")

    # 33. addGameRule overwrites whatever value it finds. Registering zd_tx
    # unconditionally therefore resets it to false on every world load, which
    # looks exactly like "the gamerule does not work" to a player who set it.
    director = read(os.path.join(SRC, "com/hbmzz/cinematic/CinematicDirector.java"))
    check("getString(RULE).isEmpty()" in director,
          "the zd_tx rule is only created when it is absent",
          "CinematicDirector adds the rule without checking; addGameRule will "
          "reset an existing value to false on every world load")

    # 34. reasim's arithmetic has to stay runnable without Minecraft for the
    # same reason the trip logic does: tools/reasimcheck/DspCheck.java runs the
    # whole chain on a bare JVM, and the moment ReasimDsp or ReasimParams grows
    # an import from net.minecraft, that check stops being a check. The
    # environment sampler (ReasimEnvironment) is exempt by design - it is the
    # one file that has to look at the world.
    dsp_layer = []
    for name in ("ReasimDsp.java", "ReasimParams.java"):
        path = os.path.join(SRC, "com/rsaudio/audio", name)
        if not os.path.exists(path):
            dsp_layer.append(name + " missing")
            continue
        if "import net.minecraft" in read(path):
            dsp_layer.append(name + " imports the game")
    check(not dsp_layer, "the reasim DSP has no Minecraft in it",
          "; ".join(dsp_layer) + " - tools/reasimcheck can no longer run it")

    # ---- 36..42: the rsaudio container (com/rsaudio) ---------------------
    # This script used to audit com/hbmzz only. The exclusion was deliberate -
    # walking all of SRC flags every rsaudio tile entity as "missing a mapping"
    # because that module keeps its own tables - but the side effect was that a
    # 199-file module with 39 blocks, 36 items and 21 tile entities had no audit
    # at all. These checks read the module's own tables instead of excluding it.
    #
    # The three blocks below are the "only the machine may place this" ones
    # (see their class comments): no item, on purpose, and that list is frozen.
    RS_NO_ITEM = {"BLAST_DOOR_PANEL", "BLAST_CURTAIN_PANEL", "TARTARUS_HATCH", "ELEVATOR_CAR"}
    RS_NO_ITEM_NAMES = {"blast_door_panel", "blast_curtain_panel", "tartarus_hatch", "elevator_car"}

    rs = os.path.join(SRC, "com/rsaudio")
    rs_modblocks_path = os.path.join(rs, "ModBlocks.java")
    rs_main_path = os.path.join(rs, "RsAudio.java")
    if not os.path.exists(rs_modblocks_path) or not os.path.exists(rs_main_path):
        check(False, "the rsaudio container is where the jar expects it",
              "com/rsaudio/ModBlocks.java or RsAudio.java missing")
        modblocks = rs_main = ""
        rs_block_fields = set()
        rs_block_classes = []
        rs_blocks_body = rs_items_body = ""
    else:
        modblocks = read(rs_modblocks_path)
        rs_main = read(rs_main_path)
        rs_blocks_body = member_body(modblocks, "registerBlocks")
        rs_items_body = member_body(modblocks, "registerItems")

        # The registration entry point has to be explicit. @Mod.EventBusSubscriber
        # would also work, but a miss in FML's class scan for the *second* mod
        # container in this jar fails silently: no crash, no log line, empty
        # creative tab, blocks that place as air.
        check("EVENT_BUS.register(ModBlocks.class)" in rs_main,
              "the rsaudio block/item tables are registered explicitly",
              "RsAudio.preInit must call MinecraftForge.EVENT_BUS.register(ModBlocks.class); "
              "relying on @Mod.EventBusSubscriber for a second container fails silently")

        # 36. Every Block subclass in the module is actually instantiated.
        block_dir = os.path.join(rs, "block")
        rs_block_classes = []
        for name in sorted(os.listdir(block_dir)):
            if not name.endswith(".java"):
                continue
            src = read(os.path.join(block_dir, name))
            # ★ 也算上"变体"：防爆窗帘 / 布帘格是 BlockBlastDoor(±Panel) 的子类，
            #   它们自己就是带注册名的方块类；只认 "extends Block" 会让它们的
            #   blockstate / 物品模型两条守卫直接失明。
            match = re.search(
                r"class\s+(\w+)\s+extends\s+(?:Block|BlockBlastDoor|BlockBlastDoorPanel)\b", src)
            if match:
                rs_block_classes.append(match.group(1))
        orphans = [c for c in rs_block_classes if ("new " + c + "(") not in modblocks]
        check(not orphans, "every rsaudio block class reaches ModBlocks",
              ", ".join(orphans) + " - a block class nobody news never reaches the registry")

        # 37. Every declared block field is handed to the block registry, either
        #     by its own line or through the loop that walks its array.
        # Non-capturing group: field_names() returns findall() results, and a
        # capturing group here would turn every field name into a tuple.
        rs_block_fields = field_names(modblocks, r"Block(?:\[\])?")
        unregistered = []
        for field in sorted(rs_block_fields):
            if ("register(" + field + ")") in rs_blocks_body or (": " + field + ")") in rs_blocks_body:
                continue
            unregistered.append(field)
        check(not unregistered, "every rsaudio block field is registered",
              ", ".join(unregistered) + " - declared in ModBlocks but never passed to the registry")

        # 38. Every placeable block gets an ItemBlock; the itemless three stay itemless.
        itemless_ok = [f for f in sorted(rs_block_fields) if f in RS_NO_ITEM]
        missing_items = []
        for field in sorted(rs_block_fields):
            if field in RS_NO_ITEM:
                continue
            if ("makeItemBlock(" + field + ")") in rs_items_body or (": " + field + ")") in rs_items_body:
                continue
            missing_items.append(field)
        check(not missing_items, "every placeable rsaudio block has an item",
              ", ".join(missing_items) + " - block is registered, item is not, so it cannot be held")
        check(len(itemless_ok) == len(RS_NO_ITEM),
              "the three generated-only rsaudio blocks still exist under those names",
              "expected " + ", ".join(sorted(RS_NO_ITEM)) + ", found " + (", ".join(itemless_ok) or "none"))

        # 39. Every tile entity class is registered. Missing here is not "no
        #     ticking" - it is a crash the first time the chunk is saved.
        rs_tes = []
        for root, _dirs, files in os.walk(rs):
            for name in files:
                if not name.endswith(".java"):
                    continue
                src = read(os.path.join(root, name))
                match = re.search(r"class\s+(\w+)\s+extends\s+(?:TileEntity|TileEntityLockable)\b", src)
                if match:
                    rs_tes.append(match.group(1))
        unregistered_tes = [t for t in sorted(set(rs_tes)) if (t + ".class") not in rs_main]
        check(not unregistered_tes, "every rsaudio tile entity is registered",
              ", ".join(unregistered_tes) + " - writeToNBT throws 'is missing a mapping!' on save")

        # Registry names, read from the block classes themselves. Two of them are
        # built from a NAMES array (six alarm colours, three elevator types), so
        # those are expanded against the array in the same file.
        rs_names = set()
        for cls in rs_block_classes:
            src = read(os.path.join(block_dir, cls + ".java"))
            for literal in re.findall(r'setRegistryName\(RsAudio\.MODID,\s*"([a-z0-9_]+)"\s*\)', src):
                rs_names.add(literal)
            for prefix in re.findall(r'setRegistryName\(RsAudio\.MODID,\s*"([a-z0-9_]+)"\s*\+', src):
                names_array = re.search(r"String\[\]\s+\w*NAMES\s*=\s*\{([^}]*)\}", src)
                for suffix in re.findall(r'"([a-z0-9_]+)"', names_array.group(1) if names_array else ""):
                    rs_names.add(prefix + suffix)

        # 40. Every registered block has a blockstate file, and no blockstate file
        #     is orphaned (an orphan means a registry name was renamed and half the
        #     pair was missed - the block then renders as the purple/black cube).
        assets = os.path.join(ROOT, "src/main/resources/assets/rsaudio")
        states = set(n[:-5] for n in os.listdir(os.path.join(assets, "blockstates")) if n.endswith(".json"))
        check(not (rs_names - states), "every rsaudio block has a blockstate",
              ", ".join(sorted(rs_names - states)) + " - places fine, renders as a missing model")
        check(not (states - rs_names), "no orphan rsaudio blockstate",
              ", ".join(sorted(states - rs_names)) + " - file exists but no block registers that name")

        # 41. Every block that has an item has an item model AND a model
        #     registration on the client. Both halves are needed: the json alone
        #     is never looked up, and the lookup alone has nothing to load.
        want_items = rs_names - RS_NO_ITEM_NAMES
        item_models = set(n[:-5] for n in
                          os.listdir(os.path.join(assets, "models/item")) if n.endswith(".json"))
        check(not (want_items - item_models), "every rsaudio item has an item model",
              ", ".join(sorted(want_items - item_models)) + " - held in hand as a purple/black cube")

        cm_path = os.path.join(rs, "client/ClientModelRegistry.java")
        cm = read(cm_path) if os.path.exists(cm_path) else ""
        cm_names = set(re.findall(r'registerItem\(\s*ModBlocks\.[A-Z_0-9]+,\s*"([a-z0-9_]+)"\s*\)', cm))
        alarm = re.search(r"COLOR_NAMES\s*=\s*\{([^}]*)\}",
                          read(os.path.join(block_dir, "BlockAlarmLight.java")))
        for colour in re.findall(r'"([a-z0-9_]+)"', alarm.group(1) if alarm else ""):
            cm_names.add("alarm_" + colour)
        lifts = re.search(r"TYPE_NAMES\s*=\s*\{([^}]*)\}",
                          read(os.path.join(block_dir, "BlockElevatorController.java")))
        for kind in re.findall(r'"([a-z0-9_]+)"', lifts.group(1) if lifts else ""):
            cm_names.add("elevator_controller_" + kind)
        check(not (want_items - cm_names), "every rsaudio item model is registered on the client",
              ", ".join(sorted(want_items - cm_names)) + " - ClientModelRegistry is missing the line")

        # 42. Display names. An ItemBlock looks up "tile.<block unlocalized
        #     name>.name" - not its registry name, and not without the suffix.
        #     A key written without .name is never read: the tooltip prints the
        #     raw key, which reads exactly like "this item was never registered".
        lang = ""
        for locale in ("en_us", "zh_cn"):
            lang += read(os.path.join(assets, "lang", locale + ".lang"))
        no_unlocalized = []
        for cls in rs_block_classes:
            src = read(os.path.join(block_dir, cls + ".java"))
            if "setUnlocalizedName(" not in src:
                no_unlocalized.append(cls)
        check(not no_unlocalized, "every rsaudio block class sets an unlocalized name",
              ", ".join(no_unlocalized) + " - shows up as tile.null.name")

        expected = set()
        for cls in rs_block_classes:
            src = read(os.path.join(block_dir, cls + ".java"))
            for suffix in re.findall(r'setUnlocalizedName\(RsAudio\.MODID\s*\+\s*"([^"]+)"\s*\)', src):
                expected.add("tile.rsaudio" + suffix + ".name")
            for prefix in re.findall(r'setUnlocalizedName\(RsAudio\.MODID\s*\+\s*"([^"]+)"\s*\+', src):
                names_array = re.search(r"String\[\]\s+\w*NAMES\s*=\s*\{([^}]*)\}", src)
                for value in re.findall(r'"([a-z0-9_]+)"', names_array.group(1) if names_array else ""):
                    expected.add("tile.rsaudio" + prefix + value + ".name")
        unnamed = [key for key in sorted(expected) if (key + "=") not in lang]
        check(not unnamed, "every rsaudio block has a display name",
              ", ".join(unnamed) + " - the tooltip shows the raw translation key")

        # The exact shape of the bug above: a key that should end in .name spelled
        # without it, so nothing ever looks it up.
        truncated = [key[:-5] for key in sorted(expected) if (key[:-5] + "=") in lang]
        check(not truncated, "no rsaudio name key is missing its .name suffix",
              ", ".join(truncated) + " - an ItemBlock looks up tile.<name>.name, never tile.<name>")

    # ---------------------------------------------------------------- hacker
    #
    # The tablet is the first thing in this mod that writes into another mod's
    # tile entities without going through that mod's event contract. Every
    # failure mode it has is silent: an unregistered item is a purple square, a
    # packet discriminator collision drops the reply, a missing lang key prints
    # the raw key, and a monitor that computes an intrusion state without a
    # branch to draw it looks exactly like a monitor that never noticed. So the
    # checks below are about the wiring, not the values.

    hk_dir = os.path.join(ROOT, "src/main/java/com/hbmzz/hack")
    hk_files = set(os.listdir(hk_dir)) if os.path.isdir(hk_dir) else set()
    hk_want = {"HackState.java", "HackRegistry.java", "HackService.java"}
    check(hk_want <= hk_files, "the hacker package carries state, registry and service",
          "missing: " + ", ".join(sorted(hk_want - hk_files)))

    hk_content = read(CONTENT)
    check("ITEM_HACKER_TABLET" in hk_content
          and re.search(r"register\(ITEM_HACKER_TABLET\)", hk_content) is not None,
          "the hacker tablet is declared and registered",
          "declared in ModContent but never handed to the item registry")

    hk_lang = ""
    for hk_locale in ("en_us", "zh_cn"):
        hk_lang += read(os.path.join(ROOT, "src/main/resources/assets/hbmzz/lang",
                                     hk_locale + ".lang"))
    check("item.hbmzz.hacker_tablet.name=" in hk_lang,
          "the hacker tablet has a name in both languages",
          "the tooltip would show item.hbmzz.hacker_tablet.name")

    hk_assets = os.path.join(ROOT, "src/main/resources/assets/hbmzz")
    check(os.path.exists(os.path.join(hk_assets, "models/item/hacker_tablet.json")),
          "the hacker tablet has an item model", "it would render as a purple square")
    check(os.path.exists(os.path.join(hk_assets, "textures/items/hacker_tablet.png")),
          "the hacker tablet has a texture", "the model points at a missing layer0")

    hk_net = read(os.path.join(ROOT, "src/main/java/com/hbmzz/network/NetworkHandler.java"))
    hk_ids = [int(n) for n in re.findall(r"registerMessage\([^;]*?,\s*(\d+)\s*,\s*Side\.", hk_net)]
    hk_dupes = sorted({i for i in hk_ids if hk_ids.count(i) > 1})
    check(not hk_dupes, "every packet discriminator is unique",
          "duplicates: " + ", ".join(str(i) for i in hk_dupes))
    check("HackerCommandMessage" in hk_net and "HackerReplyMessage" in hk_net,
          "the hacker packets are on the channel",
          "the tablet would send into nothing and hang")

    hk_screen = read(os.path.join(ROOT, "src/main/java/com/hbmzz/client/screen/DfcScreen.java"))
    hk_missing = [s for s in ("CUT", "DISCONNECT", "DEGRADED")
                  if ("HackState." + s) not in hk_screen]
    check(not hk_missing, "the monitor paints all three intrusion states",
          "computed but never drawn: " + ", ".join(hk_missing))

    hk_snapshot = read(os.path.join(ROOT, "src/main/java/com/hbmzz/dfc/DFCSnapshot.java"))
    check(hk_snapshot.count("hackState") >= 3 and "hackTicksLeft" in hk_snapshot,
          "the intrusion state travels on the sample",
          "the field, its write and its read are all required or the client never sees it")

    hk_service = read(os.path.join(hk_dir, "HackService.java"))
    check(all(token in hk_service for token in
              ("Modifier.isStatic", "Modifier.isFinal", "ownerAllowed", "isInfinite")),
          "reflective writes are fenced by owner, static, final and range",
          "an unfenced reflective write is a griefing primitive against every mod")

    hk_monitor = read(os.path.join(ROOT, "src/main/java/com/hbmzz/tile/TileEntityDFCMonitor.java"))
    check("HackRegistry.stateFor" in hk_monitor and "attachIntrusion" in hk_monitor,
          "the monitor reads the intrusion state off the world",
          "the screen would keep showing the last good numbers after a write")

    # ---- no two shaped recipes may resolve to the same grid ---------------
    # 1.12.2 answers a crafting query with the first matching recipe, so an
    # identical pattern makes the later registration unreachable: the player
    # crafts a different block and is never told why. Nothing else catches this -
    # it compiles, the item exists, the recipe "is registered", and the only
    # symptom is that one of the two is unobtainable. This is exactly how the
    # nuke monitor shipped with the DFC monitor's grid and could not be crafted.
    content = read(os.path.join(ROOT, "src/main/java/com/hbmzz/ModContent.java"))
    grids = {}
    for recipe_name, body in re.findall(
            r'new ShapedOreRecipe\(\s*new ResourceLocation\(HBMZZ\.MODID, "([a-z_0-9]+)"\),'
            r'(.*?)\.setRegistryName',
            content, re.S):
        rows = re.findall(r'"([A-Z ]{1,3})"', body)
        keys = dict(re.findall(r"'([A-Z])',\s*([^,\n]+)", body))
        if len(rows) < 3:
            continue
        canon = tuple("".join(keys.get(c, c).strip() for c in row) for row in rows[:3])
        grids.setdefault(canon, []).append(recipe_name)
    clashes = [" vs ".join(v) for v in grids.values() if len(v) > 1]
    check(not clashes, "no two shaped recipes share the same crafting grid",
          "the later registration can never be crafted: " + "; ".join(clashes))

    # 62. The three client model tables must stay aligned with the item registry.
    #
    # ClientProxy keeps item models, items and metadata in three parallel lists.
    # Every mismatch is silent. The registration loop runs to the length of the
    # model list, so a short items() is an IndexOutOfBounds on startup, a long one
    # just drops the tail, and an item that is missing from items() altogether
    # gets no explicit model location at all - which only shows up in game as a
    # magenta square, or, worse, as the correct icon by coincidence because
    # vanilla falls back to models/item/<registry name>.json. The hacker tablet
    # was missing for exactly that reason and looked fine.
    proxy = read(os.path.join(ROOT, "src/main/java/com/hbmzz/client/ClientProxy.java"))
    model_names = re.findall(r'loc\("([a-z_0-9]+)"\)', member_body(proxy, "itemModels"))
    item_tokens = []
    for ref in re.findall(r"ModContent\.(\w+)", member_body(proxy, "items")):
        # Item.getItemFromBlock(BLOCK_X) is the item form of BLOCK_X, and that
        # item is called ITEM_BLOCK_X; normalise so the coverage test compares
        # like with like.
        item_tokens.append("ITEM_" + ref if ref.startswith("BLOCK_") else ref)
    metas_block = proxy[proxy.index("int[] METAS"):]
    metas_block = metas_block[:metas_block.index("};")]
    metas_block = re.sub(r"//[^\n]*", "", metas_block)
    meta_count = len(re.findall(r"\d+", metas_block))
    check(len(model_names) == len(item_tokens) == meta_count,
          "the three item-model tables are the same length",
          "models=%d items=%d metas=%d (any mismatch shifts every icon after it)"
          % (len(model_names), len(item_tokens), meta_count))
    uncovered = sorted(name for name in registered_items if name not in set(item_tokens))
    check(not uncovered, "every registered item has an explicit model location",
          "icon depends on a filename coincidence: " + ", ".join(uncovered))

    # 65. setRegistryName() writes the field once and throws the second time
    # ("Attempted to set registry name with existing registry name!"), and it
    # throws from a static field initializer - so the client dies during
    # "Initializing game" with a stacktrace that names the constructor, not the
    # block that caused it. The 3.8.0 build shipped exactly that: a literal
    # setRegistryName() added to BlockBlastDoor's no-arg constructor on top of
    # the delegated one that already set the same name, and blast_door took the
    # whole game down before the main menu. The rule that keeps it from coming
    # back: one call per file. A concrete class sets its literal once (this
    # script reads that line), and a shared parent sets none - if the parent
    # sets it from a parameter, every subclass that adds its own literal is
    # double-setting too, which is why the check counts per file and not per
    # inheritance chain.
    doubled = []
    for base, _, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            calls = [line for line in read(path).splitlines()
                     if re.match(r"\s*setRegistryName\s*\(", line)]
            if len(calls) > 1:
                doubled.append(os.path.relpath(path, ROOT).replace(os.sep, "/"))
    check(not doubled, "no class sets its registry name twice (crashes at startup)",
          "second call throws in a static initializer, game dies at 'Initializing game': "
          + ", ".join(sorted(doubled)))

    # 66. 大屏的播放定位：客户端发上来的是一个整数秒数，服务器必须先夹再存。
    # 这条规则守的是"谁来定边界"——拖动进度条时窗口已经夹过一次，但窗口是
    # 便利路径不是边界；一条 -5 秒或者 10 小时的报文只要漏过一处，墙面就会
    # 停在一个永远到不了的时间上，而且看起来像画面坏了。所以 seekTo() 里
    # 必须自己夹，且夹进 0..MAX_PROGRESS_MS。
    screen_path = os.path.join(SRC, "com/hbmzz/tile/TileEntityVideoScreen.java")
    screen_text = read(screen_path)
    seek_body = member_body(screen_text, "seekTo")
    check("clampMs(" in seek_body and "MAX_PROGRESS_MS" in screen_text,
          "the wall clamps a seek before it stores it",
          "TileEntityVideoScreen.seekTo must go through clampMs(0..MAX_PROGRESS_MS): "
          "an unclamped packet parks the wall on a time that never arrives")

    # 66b. 客户端拿到的位置要按"本机这一段影片"再夹一次：服务器只知道一个
    # 上限，真正的长度只有客户端手里那份文件知道。少了这一夹，片尾之后的
    # 位置会一直被当成"跑偏了"而反复 seek，解码器每秒被清空好几次。
    playback = read(os.path.join(SRC, "com/hbmzz/client/video/VideoScreenPlayback.java"))
    check("within(player, player.filmSeconds())" in playback
          and "durationSeconds()" in playback,
          "the client clamps the playhead into the clip it actually has",
          "VideoScreenPlayback must clamp the LOCAL side too, or an ended film "
          "looks like drift and gets seeked every frame")

    # 67. 强制限制返回是"设置"，不是"画面上的控件"。它可以拦 ESC，但绝不
    # 能出现在播放界面里：一块盖住整个屏幕的窗口上多一个按钮，就等于给了
    # 观众一个中途退场的入口，而这正是这个功能存在的理由。所以那条界面里
    # 连 buttonList 都不该被碰。
    takeover = read(os.path.join(SRC, "com/hbmzz/client/GuiVideoScreen.java"))
    # 界面本身能有鼠标处理（底部那条进度条要能拖），但不能有"点一下就退场"的
    # 控件：既不许有按钮，也不许在处理左键的路径里调 displayGuiScreen(null)。
    intrusive = [token for token in ("buttonList.add", "GuiButton") if token in takeover]
    click_body = member_body(takeover, "mouseClicked")
    check(not intrusive and "displayGuiScreen(null)" not in click_body,
          "the takeover window offers no button that could dismiss it",
          "GuiVideoScreen grows a control: " + ", ".join(intrusive))

    # 68. 这条限制必须在"没有大屏"时才成立，而"有没有大屏"只能问方块实体。
    # 曾经把它当成打开窗口那一刻的布尔快照存下来，结果遥控器中途接上大屏，
    # 画面还在拦人；大屏被拆掉，画面又已经放行。所以这条规则守两件事：
    # 服务端的判定要读连接状态，客户端要每一帧重新问，而不是记着旧答案。
    broadcaster = read(os.path.join(SRC, "com/hbmzz/tile/TileEntityVideoBroadcaster.java"))
    applies_body = member_body(broadcaster, "restrictBackApplies")
    check("screenPos == null" in applies_body and "restrictBack" in applies_body,
          "强制限制返回 only holds while no wall is connected",
          "TileEntityVideoBroadcaster.restrictBackApplies must read the live link state")
    check("hasScreen()" in takeover and "restricted()" in takeover
          and "screenLinked()" in takeover,
          "the takeover asks the wall again instead of caching the answer",
          "GuiVideoScreen must re-read hasScreen() through screenLinked() and gate on restricted()")

    # 69. 客户端要知道"这台播放器接没接大屏"，就得知道它在哪。报文里少一个
    # 坐标，播放界面就只能相信打开时那一刻的结论，68 那条也就守不住了。
    broadcast_message = read(os.path.join(SRC, "com/hbmzz/network/VideoBroadcastMessage.java"))
    check("pos.toLong()" in broadcast_message or "this.pos.toLong()" in broadcast_message,
          "the broadcast packet carries the player's position",
          "VideoBroadcastMessage without pos cannot look the wall up on the client")

    # 70. 播放器的两种右键各有各的活：站着是"看片"，蹲下是"改片"。
    # 这一对写反过，后果不是打不开窗口，而是打开错的窗口——玩家想放片却看见
    # 选择器，想改设置却开始播。所以直接按分支顺序读源码，不看注释。
    block_body = member_body(read(os.path.join(SRC, "com/hbmzz/block/BlockVideoBroadcaster.java")),
                             "onBlockActivated")
    split = re.search(r"isSneaking\(\)[\s\S]*?openVideoBroadcaster\(pos\)[\s\S]*?else\s*\{[\s\S]*?"
                      r"openVideoPlayback\(pos\)", block_body)
    check(split is not None,
          "sneak opens the editor and a plain click starts the film",
          "BlockVideoBroadcaster must call openVideoBroadcaster() when sneaking and "
          "openVideoPlayback() otherwise")

    # 71. F 是播放窗口唯一的"看得见"的键。窗口盖住整个桌面，玩家没有别的地方
    # 可以确认自己按的是什么，所以全屏切换必须真的调 Minecraft 的开关，而不是
    # 只把某个布尔翻一下；退出时还要把进入前的显示模式还回去。
    check("Keyboard.KEY_F" in takeover and "toggleFullscreen()" in takeover,
          "the playback window toggles real fullscreen on F",
          "GuiVideoScreen must handle Keyboard.KEY_F with Minecraft.toggleFullscreen()")
    # 71 补：退出时要还回去的不只是"全屏/窗口"这个开关，还有窗口大小 —— 原来直接
    # 调 Minecraft.toggleFullscreen()，退全屏会回到启动尺寸（854x480），玩家看到的是
    # "按了 F 之后游戏框反而变小了"。现在由 VideoFullscreen 记账并按进来的尺寸还原
    # （#92 那条守着实现本身）。
    check("VideoFullscreen.restore(" in takeover and "addScheduledTask" in takeover,
          "leaving the film restores the display mode and window size it found",
          "GuiVideoScreen.onGuiClosed must schedule VideoFullscreen.restore() "
          "(scheduled, not inside displayGuiScreen)")

    # 72. 刷新必须说出被跳过的文件。video_screen 读的是图片帧序列，不是视频容器；
    # 一个 .mp4 丢进目录永远不会出现在列表里，于是"按了刷新没反应"看起来像 bug。
    # 这条守的是那句话本身：库要记账，窗口和 F7 都要把它念出来。
    check("public static List<String> skipped()" in read(os.path.join(SRC, "com/hbmzz/video/VideoLibrary.java")),
          "the library remembers files it could not use",
          "VideoLibrary needs skipped() so a dropped .mp4 is named instead of ignored")
    gui_broadcaster = read(os.path.join(SRC, "com/hbmzz/client/GuiVideoBroadcaster.java"))
    events = read(os.path.join(SRC, "com/hbmzz/client/ClientEvents.java"))
    check("ignoredNote" in gui_broadcaster and "skipped()" in gui_broadcaster
          and "skipped()" in events,
          "refresh says why a file did not appear",
          "both the window's 刷新 and the F7 key must report VideoLibrary.skipped()")

    # 73. 没有 ffmpeg 的时候必须自己去下，而不是安静地降级。这一段丢过一次：
    # 源码回到 3.7.1 基线时，BundledFfmpeg 只剩下"从 jar 里释放资源"，而包里根本
    # 没有那个资源，于是每次都是 null，播放器就一直说"没有 ffmpeg"。所以这里守
    # 两件事：下载器还在（HTTP + 分段），以及引导阶段真的会在缺 ffmpeg 时开下载页。
    bundled = read(os.path.join(SRC, "com/rsaudio/formats/convert/BundledFfmpeg.java"))
    check("HttpURLConnection" in bundled and "DEFAULT_PROXY" in bundled
          and "SEGMENTS" in bundled,
          "the ffmpeg downloader is present (HTTP + segmented)",
          "BundledFfmpeg lost its download path and only extracts a bundled resource")
    bootstrap = read(os.path.join(SRC, "com/rsaudio/formats/FormatsBootstrap.java"))
    check("BundledFfmpeg.isReady" in bootstrap and "GuiFfmpegDownload" in bootstrap,
          "startup downloads ffmpeg when none is detected",
          "FormatsBootstrap must ask BundledFfmpeg.isReady() and open GuiFfmpegDownload when false")

    # 74. 下载不能挂在主线程上。190 MB 走主线程就是白屏到下载结束，而且进度页
    # 也没机会画。进度页因此必须自己起线程调 ensure()，主线程只读 progress()。
    gui_download = read(os.path.join(SRC, "com/rsaudio/client/GuiFfmpegDownload.java"))
    check("new Thread" in gui_download and "BundledFfmpeg.ensure" in gui_download
          and "BundledFfmpeg.progress" in gui_download,
          "the download page runs the transfer off the render thread",
          "GuiFfmpegDownload must call ensure() on a worker thread and only read progress() while drawing")

    # 75. "支持所有视频格式"落在导入器上：模组自己不解码，交给 ffmpeg 拆帧。
    # 这条守的是那条命令真的存在 —— 少了 fps/scale 滤镜，转出来的包要么帧率
    # 不对，要么宽度还是原片，播放端就得每帧缩一次。
    importer = read(os.path.join(SRC, "com/hbmzz/video/VideoImportQueue.java"))
    check("FfmpegFinder.path()" in importer and "fps=" in importer and "scale=" in importer,
          "the importer shells out to ffmpeg for frames",
          "VideoImportQueue must call FfmpegFinder.path() and use the fps/scale filters")

    # 76. 转出来的东西必须是 VideoClip 认得的那三种文件。少一样就是"导进去了
    # 却播不了"，而这正是最容易在重构里丢的一环：meta.json 决定帧率，audio.wav
    # 决定有没有声音，帧文件名决定播放顺序。
    check("meta.json" in importer and "audio.wav" in importer and "f%06d" in importer,
          "the importer writes the package VideoClip reads",
          "VideoImportQueue must emit meta.json + f%06d.jpg + audio.wav")

    # 77. 入口在广播器窗口里，而且待导入文件要和"真不支持"分开记：mp4 不该再
    # 被当成坏文件跳过，它只是还没被拆成帧。
    broadcaster = read(os.path.join(SRC, "com/hbmzz/client/GuiVideoBroadcaster.java"))
    library = read(os.path.join(SRC, "com/hbmzz/video/VideoLibrary.java"))
    check("new GuiVideoImport(" in broadcaster,
          "the broadcaster window offers an import entry",
          "GuiVideoBroadcaster must open GuiVideoImport from a button")
    check("List<File> importable()" in library and "VideoFormats.isImportable" in library,
          "video containers are listed as pending imports, not as junk",
          "VideoLibrary needs importable() fed by VideoFormats.isImportable")

    # 78. 站立右键开的是"大屏展示"，不是第二个播放器。玩家反映过：右键进去看见
    # 一个带进度条的视频播放器窗口，而世界里的屏还在放、还在响。所以客户端必须先问
    # 这台机器接没接屏：接了就把窗口做成屏的放大镜，没接（或屏不在已加载区，这边
    # 根本没人发声）才退回本机全屏播放。
    proxy = read(os.path.join(SRC, "com/hbmzz/client/ClientProxy.java"))
    playback_body = member_body(proxy, "openVideoPlayback")
    linked_block = re.search(r"if \(wall != null && mc\.world\.isBlockLoaded\(wall\)\) \{([\s\S]*?)\n        \}",
                             playback_body)
    linked = linked_block.group(1) if linked_block else ""
    check("return;" in linked and "displayGuiScreen" not in linked,
          "接了大屏以后右键播放器什么都不开：画面归那块屏，播放器不再抢窗口",
          "ClientProxy.openVideoPlayback must return without opening a window while a wall is loaded")
    check("new GuiVideoScreen(" in playback_body,
          "没接屏（或屏不在本机已加载区）才退回本机播放窗口",
          "openVideoPlayback must still open the local player when there is no wall to watch")
    check("isBlockLoaded(wall)" in playback_body,
          "the fallback happens when the wall is not loaded on this client",
          "a mirror window for an unloaded chunk can only show an error")
    # 一次"点了没反应"是玩家无法自查的故障：拿不到方块实体（刚放下 / 刚重连）
    # 时也要开菜单。
    check(playback_body.count("new GuiVideoBroadcaster(pos)") >= 2,
          "a click never ends in silence when the block entity is not there yet",
          "openVideoPlayback must fall back to the broadcaster window instead of returning")

    # 79. 展示窗口不持有播放实例：它每帧向那块屏要帧，退出时只放下指针。
    # 结构上排除"屏在放、窗口也在放"的双份音源。
    mirror_body = member_body(takeover, "watchMirror")
    closed_body = member_body(takeover, "onGuiClosed")
    check("VideoScreenPlayback.active(" in mirror_body,
          "大屏展示 borrows the wall's decoder instead of opening another one",
          "GuiVideoScreen.watchMirror must call VideoScreenPlayback.active(wall)")
    check("wallPlayer.close" not in takeover and "this.wallPlayer = null" in closed_body,
          "closing 大屏展示 never closes the wall's decoder",
          "onGuiClosed must drop the reference only; the wall still owns that player")

    # 80. 一个房间里四块屏放同一部片子，曾经是四份音轨错开几毫秒一起响。
    # 现在只有最近的那一块说话，其余继续解码、继续走时钟，只是静音。
    wall_playback = read(os.path.join(SRC, "com/hbmzz/client/video/VideoScreenPlayback.java"))
    active_body = member_body(wall_playback, "active")
    check("public static void setAudible(" in wall_playback and "setMuted(te != audible)" in active_body,
          "only one wall in the world is allowed a soundtrack",
          "VideoScreenPlayback needs setAudible() and must push setMuted(te != audible) per frame")

    # 81. 声音由 VideoAudioPolicy 每 tick 统一判，不是每个窗口各关各的（各有各的
    # 必然漏一个）。但"开着窗口就全场静音"是错的：片子在世界里放着，去开个箱子、
    # 看一眼机器，不该把声音一起关掉。现在只有两种情况算"别人在说话"——接管窗口
    # 有自己的解码器，以及"只留一块屏"这条。其余（任何窗口 / 没窗口）都是最近的屏。
    policy = read(os.path.join(SRC, "com/hbmzz/client/video/VideoAudioPolicy.java"))
    check("GuiVideoScreen.reap(mc)" in policy and "VideoScreenPlayback.setAudible(target)" in policy
          and "else if (screen == null)" not in policy
          and "target = nearest(mc);" in policy,
          "开个窗口不再把片子弄哑（只有接管窗口才独占声音）",
          "VideoAudioPolicy must keep the nearest wall audible for any non-takeover screen")
    check("VideoAudioPolicy.tick(" in events and "VideoAudioPolicy.reset()" in events,
          "the audio policy is ticked once per client tick and reset with the world",
          "ClientEvents must tick VideoAudioPolicy and reset it on world unload")

    # 82. 音轨静音不能顺带把画面停住：四块屏里三块静音，三块的时钟还得走。
    audio = read(os.path.join(SRC, "com/hbmzz/client/video/VideoAudio.java"))
    check("setMuted" in audio and "this.paused || this.muted" in audio,
          "muting is not pausing",
          "VideoAudio.apply() must stop the clip when paused OR muted, and seek must respect both")

    # 83. 窗口没了，音源必须跟着没。换世界、被另一个界面顶掉、某条路径忘了
    # onGuiClosed —— 三种都只能由一句"当前界面不是我了"来兜。
    check("public static void reap(" in takeover and "mc.currentScreen != screen" in takeover,
          "a window that is no longer on screen loses its decoder",
          "GuiVideoScreen.reap must abandon a screen that is not mc.currentScreen any more")

    # 84. 蹲下右键必须真的到得了方块。原版只有在"没蹲下"或"手里的东西声明
    # doesSneakBypassUse"时才把点击交给方块 —— 空手能开、拿着镐子就开不了，
    # 这就是"有时候点了没反应"。修法只有一条：RightClickBlock 事件里 ALLOW。
    click_handler = read(os.path.join(SRC, "com/hbmzz/common/VideoClickHandler.java"))
    hbmzz = read(os.path.join(SRC, "com/hbmzz/HBMZZ.java"))
    check("setUseBlock(Event.Result.ALLOW)" in click_handler
          and "BlockVideoBroadcaster" in click_handler and "BlockVideoScreen" in click_handler,
          "sneak-clicking reaches the block even with something in hand",
          "VideoClickHandler must ALLOW the use of the two video blocks while sneaking")
    check("new com.hbmzz.common.VideoClickHandler()" in hbmzz,
          "the sneak-click fix is registered on both sides",
          "HBMZZ.preInit must register VideoClickHandler on the common event bus")

    # 85. 右键开始展示要落到服务端：屏是世界状态，客户端说了不算。
    broadcaster_tile = read(os.path.join(SRC, "com/hbmzz/tile/TileEntityVideoBroadcaster.java"))
    check("public boolean startDisplay()" in broadcaster_tile and "startDisplay()" in block_body,
          "a plain click starts the wall from the server side",
          "BlockVideoBroadcaster must call TileEntityVideoBroadcaster.startDisplay() off-thread-free")

    # 86. 导入窗口必须永远居中。以前是写死的 (width - 360) / 2，GUI 缩放调大后可用
    # 宽度缩到 320，left 变负数，面板左边被切在屏幕外——玩家看到的就是"歪在一边"。
    # 现在宽度跟着视口算、左边距夹住，拉窗口时还要重排按钮。
    import_gui = read(os.path.join(SRC, "com/hbmzz/client/GuiVideoImport.java"))
    check("Math.min(MAX_W, this.width - 16)" in import_gui
          and "Math.max(4, (this.width - this.W) / 2)" in import_gui,
          "导入窗口按视口居中，窄窗口里缩小而不是歪掉",
          "GuiVideoImport must clamp its panel width to the viewport and centre it with a floor")
    check("laidOutWidth" in import_gui and "private void layout()" in import_gui,
          "拉窗口/改全屏之后导入窗口会重排，按钮不留在旧位置",
          "GuiVideoImport.updateScreen must call layout() when the viewport size changed")

    # 87. 帧率上限 120，而且读取端不能偷偷压回 60 —— 否则导出来的 120fps 包
    # 会以半速播放，看上去像"导入时丢帧了"。
    clip = read(os.path.join(SRC, "com/hbmzz/video/VideoClip.java"))
    check("120" in importer.split("FPS_STEPS")[1][:80] and "Math.min(120, fps)" in clip
          and "Math.min(120, fps)" in importer,
          "导入与读取两端都认到 120fps",
          "FPS_STEPS must reach 120 and both clamps must be 120, not 60")

    # 88. 导入窗口"歪在一边"的真因是字段遮蔽：那个类自己有一个 width（导出宽度，
    # 默认 480），和 GuiScreen.width（视口宽）同名，于是 layout() 和整块暗底都按
    # 480 算 —— 1280 宽的界面上面板落在 x=60，左边还露着没盖住的地面。改名之后
    # this.width 才是视口。这条守住"别再有人把导出宽度叫 width"。
    check("private int videoWidth" in import_gui and "private int width" not in import_gui,
          "导入窗口不再用 width 当导出宽度的字段名（否则会遮蔽视口宽）",
          "GuiVideoImport must keep the export width in videoWidth, not width")
    check("Mouse.getEventX() * this.width / this.mc.displayWidth" in import_gui,
          "导入窗口的滚轮命中测试用的是视口宽度",
          "handleMouseInput must map the mouse with GuiScreen.width, not the export width")

    # 89. 进度条不能"点了就弹回去"。窗口画的是本机那份方块实体，服务端确认要一个
    # 来回；这段时间里只画旧值，手柄就会自己回原位。所以跳转要先把播放头写进本地
    # 方块实体，再把包发出去。
    seek_gui = read(os.path.join(SRC, "com/hbmzz/client/GuiVideoSeek.java"))
    apply_body = member_body(seek_gui, "applySeek")
    check("te.seekTo(clamped)" in apply_body and "sendSeekOnly(" in apply_body,
          "拖动进度条先写本地播放头再发包，手柄不会弹回原位",
          "GuiVideoSeek.applySeek must call te.seekTo(...) before sending the packet")
    release_body = member_body(seek_gui, "mouseReleased")
    click_body = member_body(seek_gui, "mouseClicked")
    check("applySeek(msAt(mouseX))" in release_body and "applySeek(this.dragMs)" in click_body,
          "松手那一刻的落点也会被写进去（不是只发一个包）",
          "mouseClicked / mouseReleased must go through applySeek so the local playhead moves too")

    # 90. 服务端改完播放头要把权威值直接回给发起者。区块更新也会发，但它是按
    # "谁在看这个区块"路由的，晚一两 tick 到；那段空窗正是"弹回原位"看起来发生
    # 的地方。回一发是最省事的收口。
    seek_msg = read(os.path.join(SRC, "com/hbmzz/network/VideoSeekMessage.java"))
    check("sender.connection.sendPacket(screen.getUpdatePacket())" in seek_msg,
          "服务端把跳转后的权威播放头直接回给发起者",
          "VideoSeekMessage.Handler must echo screen.getUpdatePacket() to the sender")

    # 91. 没有本机副本时进度条不该接受拖动：没有长度就没有比例，点哪儿都只能跳到
    # 一个猜出来的时间，手柄永远画在最左边 —— 那看上去就是"拖了又弹回去"。
    over_body = member_body(seek_gui, "overBar")
    check("if (!this.haveClip)" in over_body,
          "没有本机副本时进度条不接受拖动（只能用 +/- 按钮）",
          "GuiVideoSeek.overBar must refuse the bar when the clip length is unknown")

    # 92. 全屏：方向以桌面真实状态为准，退出时回到"进来之前的窗口尺寸"。
    # 直接调 Minecraft.toggleFullscreen() 会按它自己的字段翻面（字段与桌面不同步时
    # 就是"按 F 反而变小"），而且退全屏会回到启动尺寸（854x480）。
    fullscreen = read(os.path.join(SRC, "com/hbmzz/client/video/VideoFullscreen.java"))
    check("Display.isFullscreen()" in fullscreen and "mc.toggleFullscreen()" in fullscreen
          and "Display.setDisplayMode(new DisplayMode(windowWidth, windowHeight))" in fullscreen,
          "全屏开关按桌面真实状态决定方向，退出来回到原窗口尺寸",
          "VideoFullscreen must read Display.isFullscreen() and restore the captured window size")
    check("VideoFullscreen.capture()" in takeover and "VideoFullscreen.restore(generation, client)" in takeover,
          "播放窗口记下进来之前的显示状态，关闭时还回去",
          "GuiVideoScreen must capture() on open and restore(generation, ...) when it closes")
    key_body = member_body(takeover, "keyTyped")
    check("this.toggleFullscreen()" in key_body and "mc.toggleFullscreen()" not in key_body,
          "F 键走统一的全屏开关，不再直接翻 Minecraft 的字段",
          "GuiVideoScreen.keyTyped must call its own toggleFullscreen() helper")
    mouse_body = member_body(takeover, "handleMouseInput")
    check("getEventButton() == 1" in mouse_body and "this.toggleFullscreen()" in mouse_body,
          "窗口里右键 = 全屏开关（大屏展示时是大屏，本机播放时是播放器）",
          "GuiVideoScreen.handleMouseInput must toggle fullscreen on the right button")

    # 93. 播放窗口底部那条进度条也得能拖。以前它只是画出来的一个百分比，
    # 点上去什么都不发生、画面照旧往前走——玩家的描述是"拖动之后又弹回原位"。
    check("private boolean overBar(" in takeover and "player.seekTo(secondsAt(mouseX))" in takeover,
          "本机播放窗口的进度条可以点、可以拖，松手就跳过去",
          "GuiVideoScreen must accept a click/drag on its bottom bar and seekTo on release")
    check("while (org.lwjgl.input.Mouse.next())" not in takeover,
          "鼠标事件按原版的节奏一个个分派（不会把拖动事件吃掉）",
          "GuiVideoScreen.handleMouseInput must not loop over Mouse.next(); that starves the drag")

    # 94. 长片不能被悄悄截断。VideoClip 曾经把帧数硬切在 20000：4 分 23 秒的片子
    # 按 120fps 导入是 31618 帧，切完只剩 20000 帧，于是同一部片在游戏里报 2:46，
    # 音轨却还在放到 4:23 —— 玩家看到的就是"只剩两分钟，而且有声音没画面"。
    # 上限必须高到任何正常导入都碰不到，它只该挡住坏包。
    video_clip = read(os.path.join(SRC, "com/hbmzz/video/VideoClip.java"))
    clip_limit = re.search(r"MAX_FRAMES = (\d+)", video_clip)
    check(clip_limit is not None and int(clip_limit.group(1)) >= 100000,
          "长片不会被帧数上限悄悄截断（120fps 的四分钟片子仍是四分钟）",
          "VideoClip.MAX_FRAMES is a bad-pack guard, not a length limit: " + (
              clip_limit.group(1) if clip_limit else "missing"))

    # 95. 播放头跑在解码前面时必须丢帧追上去。120fps 的片子配一台每秒只解得出
    # 四五十帧的机器，缓存里永远没有播放头要的那一帧，画面一帧都不出来，声音却
    # 照放 —— 这就是"有声音没画面"。追帧只在落后一整个缓冲时才触发，并且限频，
    # 否则每帧都 seek 会把缓存清空得更彻底。
    player_src = read(os.path.join(SRC, "com/hbmzz/client/video/VideoPlayer.java"))
    show_body = member_body(player_src, "showFrame")
    loop_body = member_body(player_src, "decodeLoop")
    check("this.wanted = index;" in show_body and "catchUp(" not in show_body,
          "渲染线程只公布播放头，不由它发追帧请求（发一次就清一次缓存）",
          "VideoPlayer.showFrame must publish `wanted` and not seek itself")
    check("this.wanted - index > this.cacheFrames" in loop_body and "index = this.wanted;" in loop_body,
          "解码器落后整个缓冲时自己丢帧追上播放头",
          "VideoPlayer.decodeLoop must skip ahead when the playhead left the buffer")

    # 96. 音轨按帧包自己的长度截断：帧包可能是 frames/fps 秒，源音轨却是整段视频
    # 的长度，不截就会在画面已经放完之后继续响（同一个"有声音没画面"）。
    import_src = read(os.path.join(SRC, "com/hbmzz/video/VideoImportQueue.java"))
    audio_body = member_body(import_src, "runAudio")
    check('cmd.add("-t")' in audio_body and "seconds" in audio_body,
          "音轨截到与帧包一样长，声音不会比画面多放一截",
          "VideoImportQueue.runAudio must bound the track with -t <pack seconds>")
    check("frames.size() / (double) job.fps" in import_src,
          "截断长度取自实际解出的帧数，而不是源视频时长",
          "convert() must pass the pack's own duration to runAudio")

    # 97. ffmpeg 的输出必须有人读。管道缓冲 64KB，长片 / 高帧率的告警足够填满它；
    # 一旦填满，ffmpeg 卡在 write 上，进度条停在半路 —— 看起来像"转到一半停了"。
    check("drainOutput(process)" in import_src and "hbmzz-ffmpeg-output" in import_src,
          "一路读走 ffmpeg 的输出，长片转换不会卡在管道上",
          "VideoImportQueue.start must drain the child's stdout")

    # 98. 全屏的归属：接了屏归大屏，没接屏归播放器。所以"能不能按全屏"必须是一个
    # 现算的判定，而不是打开窗口时记下来的布尔 —— 遥控器中途接上大屏，下一次按键
    # 就该改口。接屏时右键播放器开出来的展示窗口不提供全屏入口。
    check("private boolean canFullscreen()" in takeover and "return this.wallOwned;" in takeover
          and "return !screenLinked() || !wallReachable();" in takeover,
          "全屏权限按'有没有接大屏 / 这窗口是不是从大屏进来的'现算",
          "GuiVideoScreen must gate fullscreen through canFullscreen()")
    key_body = member_body(takeover, "keyTyped")
    check("canFullscreen()" in key_body,
          "接了大屏以后 F 不再改桌面模式，只提示去右键大屏",
          "GuiVideoScreen.keyTyped must ask canFullscreen() before toggling")
    check("canFullscreen()" in mouse_body,
          "接了大屏以后右键播放器不会把桌面切进全屏",
          "GuiVideoScreen.handleMouseInput must ask canFullscreen() before toggling")
    check("public static GuiVideoScreen watch(" in takeover
          and "public static GuiVideoScreen mirror(" not in takeover,
          "看大屏只有一个入口（watch）：接屏以后右键播放器不再开窗口",
          "GuiVideoScreen.mirror() must be gone; watch() is the only wall window")

    # 99. 全屏要在大屏那边按：右键大屏 → 大屏定位窗口 →「全屏观看」。点一下就该是
    # 满屏，不是先给个窗口再让玩家自己找 F。
    check("全屏观看" in seek_gui and "openVideoFullscreen(this.pos)" in seek_gui,
          "大屏菜单里点「全屏观看」直接进全屏",
          "GuiVideoSeek must offer 全屏观看 and route it through openVideoFullscreen()")
    full_body = member_body(proxy, "openVideoFullscreen")
    check("GuiVideoScreen.watch(" in full_body and "enterFullscreen()" in full_body,
          "「普通右键大屏」和「全屏观看」走同一条路：watch(...) + enterFullscreen()",
          "ClientProxy.openVideoFullscreen must open GuiVideoScreen.watch and enter fullscreen")
    check("public void enterFullscreen()" in takeover,
          "进入全屏只有一条路（enterFullscreen），仍然走统一的显示状态记账",
          "GuiVideoScreen must expose enterFullscreen() rather than poking Display directly")

    # 100. 接上大屏以后「强制限制返回」的入口要从设置窗口里整个消失：那个开关只在
    # 没接屏时成立，接屏后留着它只会让人以为按了有用。窗口开着时接上/拆掉大屏也要
    # 立刻重排，不能等关窗重开。
    config_gui = read(os.path.join(SRC, "com/hbmzz/client/GuiVideoPlayerConfig.java"))
    rebuild_body = member_body(config_gui, "rebuildButtons")
    check("if (!this.screenLinked)" in rebuild_body and "new GuiButton(0" in rebuild_body,
          "接上大屏后「强制限制返回」的入口不再画出来",
          "GuiVideoPlayerConfig.rebuildButtons must only add the lock button while no wall is linked")
    check("rebuildButtons()" in config_gui.split("public void initGui()")[1][:400]
          and config_gui.count("rebuildButtons()") >= 3,
          "连上/拆掉大屏时设置窗口就地重排",
          "GuiVideoPlayerConfig.drawScreen must rebuild when the link state flips")

    # 101. 大屏的两种右键：普通右键开电视窗（屏正中一台电视，F 全屏 / 再按 F 回到
    # 窗口），Shift + 右键才开菜单。普通右键不再直接满屏——那会把"看片"变成
    # "整块显示器被占"，也没法在小窗里干别的。
    screen_body = member_body(read(os.path.join(SRC, "com/hbmzz/block/BlockVideoScreen.java")),
                              "onBlockActivated")
    check("openVideoWatch(origin)" in screen_body and "openVideoSeek(origin)" in screen_body
          and "openVideoScreenConfig" not in screen_body,
          "普通右键大屏 = 开电视窗；Shift + 右键 = 唯一的菜单",
          "BlockVideoScreen must open the television window on a plain click and the menu on sneak")
    check(screen_body.index("openVideoSeek(origin)") < screen_body.index("openVideoWatch(origin)"),
          "Shift + 右键走菜单那一条分支（不是反过来）",
          "sneaking must map to openVideoSeek, plain click to openVideoWatch")
    client_proxy = read(os.path.join(SRC, "com/hbmzz/client/ClientProxy.java"))
    watch_body = member_body(client_proxy, "openVideoWatch")
    check("GuiVideoScreen.watch(" in watch_body and "enterFullscreen" not in watch_body,
          "电视窗打开时是小窗（全屏是 F 的事，不是打开的事）",
          "ClientProxy.openVideoWatch must not switch to fullscreen on open")
    full_body = member_body(client_proxy, "openVideoFullscreen")
    check("enterFullscreen" in full_body,
          "菜单里的「全屏观看」仍然是进去就满屏",
          "ClientProxy.openVideoFullscreen must enter fullscreen")

    # 102. 尺寸收进菜单，不再占第三个组合键。
    check('"尺寸"' in seek_gui and "new GuiVideoScreenConfig(this.pos)" in seek_gui,
          "尺寸是菜单里的一个按钮",
          "GuiVideoSeek must offer a 尺寸 button that opens GuiVideoScreenConfig")

    # 103. 倍速整个删掉。0.25x–3x 只搬时钟不搬音轨（Clip 只能按源速率放），所以非
    # 1x 时只能把声音扣住——"能加速但没声音"是半成品，与其留着不如删干净：UI、消息、
    # 方块实体字段、播放器的速率、音轨的 speedMuted 一起走，片子永远按自己的帧率放。
    tile_src = read(os.path.join(SRC, "com/hbmzz/tile/TileEntityVideoScreen.java"))
    config_msg = read(os.path.join(SRC, "com/hbmzz/network/VideoScreenConfigMessage.java"))
    audio_src = read(os.path.join(SRC, "com/hbmzz/client/video/VideoAudio.java"))
    for token in ("MIN_SPEED", "MAX_SPEED", "applySpeed", "formatSpeed", "setSpeedMuted",
                  "speedMuted", "setSpeed(", "getSpeed("):
        check(token not in tile_src and token not in config_msg and token not in player_src
              and token not in audio_src and token not in seek_gui,
              "倍速的「%s」已经不存在（不是一个藏起来的入口）" % token,
              "speed feature must be gone from the tile entity, the packet, the player and the audio")
    check('"倍速"' not in read(os.path.join(SRC, "com/hbmzz/client/GuiVideoScreenConfig.java")),
          "配置窗口里没有倍速这一行",
          "GuiVideoScreenConfig must not draw a 倍速 row")

    # 104. 缓冲里没有播放头要的那一帧时，不能让渲染线程去清缓存：那是"卡一下、
    # 跳一格"的来源。解码线程按 wanted 跳过一段即可，缓存里还在播放头前面的帧
    # 仍然有用，不该一起清掉。
    check("removeIf(key -> key < from)" in loop_body,
          "丢帧追播放头时只清掉过期帧，不整张清空",
          "decodeLoop must prune frames behind the playhead instead of clearing the ring")

    # 105. 大屏的画面不能被房间照亮。TESR 跑在世界渲染阶段，unit 1 还挂着光照
    # 贴图，没有自带光照坐标的四边形会乘上"上一个画过的东西的亮度"——这就是
    # "同一部片在播放器里正常、贴到墙上发暗"的来源。两个玻璃面绘制器都必须把
    # 光照钉在 240（满亮）并在画完后还原。
    painter = read(os.path.join(SRC, "com/hbmzz/client/PanelPainter.java"))
    glass_body = member_body(painter, "glass")
    check("setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F)" in glass_body,
          "监视器画面钉满亮（世界里的屏不随房间变暗）",
          "PanelPainter.glass must pin the lightmap to 240/240 before drawing the picture")
    check("lightX" in glass_body and "setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY)" in glass_body,
          "画完把光照还原回去（不留副作用给下一个 TESR）",
          "PanelPainter.glass must restore the previous lightmap coordinates")
    panel_render = read(os.path.join(SRC, "com/hbmzz/client/PanelRender.java"))
    pr_body = member_body(panel_render, "glass")
    check("240.0F, 240.0F" in pr_body and "lightX" in pr_body,
          "另一个玻璃面绘制器同样钉满亮并还原",
          "PanelRender.glass must pin and restore the lightmap the same way")

    # 106. 电视窗：小窗状态要画的是"一台电视"，不是一块漂浮的画面。外壳、边框、
    # 底座、电源灯都在 drawTelevision 里；全屏时只画画面。比例按片子自己的宽高算，
    # 4:3 的片子里屏幕就是 4:3。
    tv_body = member_body(takeover, "drawTelevision")
    check("drawTelevision(this.wallPlayer)" in takeover and "drawRect(" in tv_body,
          "小窗状态画的是一台电视（外壳 / 边框 / 底座）",
          "GuiVideoScreen.drawMirror must call drawTelevision while not fullscreen")
    check("film.frameWidth()" in tv_body and "film.frameHeight()" in tv_body,
          "电视屏幕的比例跟着片子走（4:3 的片不会套 16:9 的壳）",
          "drawTelevision must size the screen from the frame's own aspect")
    check("drawFilm(this.wallPlayer)" in takeover and "boolean fullscreen = this.tvFullscreen;" in takeover,
          "全屏时才铺满整屏",
          "GuiVideoScreen must draw the picture edge-to-edge only while its own fullscreen flag is set")
    check("this.tvFullscreen = false;" in takeover
          and "VideoFullscreen.active()" not in takeover.split("public void initGui()")[1][:600],
          "一进来是电视窗，不是满屏（本来就是全屏游戏的也一样）",
          "GuiVideoScreen.initGui must start windowed even when the desktop is already fullscreen")
    fs = read(os.path.join(SRC, "com/hbmzz/client/video/VideoFullscreen.java"))
    check("enterForPicture" in fs and "leaveForPicture" in fs and "weEntered" in fs,
          "只为画面切全屏，退出只还自己动过的那次",
          "VideoFullscreen must only change the desktop mode when it was not already fullscreen")

    failed = [name for ok, name, _ in results if not ok]
    print()
    if failed:
        print("%d/%d checks FAILED" % (len(failed), len(results)))
        return 1
    print("all %d checks passed" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
