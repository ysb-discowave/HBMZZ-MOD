import io, os, re, sys

DOCS = os.path.join(os.path.dirname(os.path.abspath(__file__))) if False else r"E:\DSH\HBMZZ-MOD\docs"
CSS = os.path.join(DOCS, "assets", "site.css")
PAGES = ["index.html", "guide.html", "reference.html", "changelog.html", "license.html"]

def read(p):
    with io.open(p, "r", encoding="utf-8-sig") as f:
        return f.read()

css = read(CSS)
defined = set(re.findall(r"\.([a-zA-Z][\w-]*)", css))

problems = []
for name in PAGES:
    p = os.path.join(DOCS, name)
    if not os.path.exists(p):
        problems.append(("MISSING", name, ""))
        continue
    text = read(p)

    # 1) 用到的 class 是否都在 CSS 里定义
    used = set()
    for m in re.findall(r'class="([^"]*)"', text):
        for c in m.split():
            used.add(c)
    unknown = sorted(c for c in used if c not in defined)
    if unknown:
        problems.append(("UNKNOWN-CLASS", name, ", ".join(unknown)))

    # 2) 必需的骨架
    for must in ('<header class="nav">', '<footer class="foot">',
                 'assets/site.css', 'assets/data.js', 'assets/site.js',
                 '<meta name="viewport"', '<html lang="zh-CN">'):
        if must not in text:
            problems.append(("MISSING-MARKUP", name, must))

    # 3) 标签平衡（粗略：只数容器型标签）
    for tag in ("div", "section", "main", "header", "footer", "table", "ul", "ol", "pre", "p"):
        o = len(re.findall(r"<%s[\s>]" % tag, text))
        c = len(re.findall(r"</%s>" % tag, text))
        if o != c:
            problems.append(("TAG-IMBALANCE", name, "%s: %d open / %d close" % (tag, o, c)))

    # 4) 未处理的占位符 / 绝对路径
    if "{{" in text or "{%" in text:
        problems.append(("TEMPLATE-LEFTOVER", name, "还有未渲染的模板占位符"))
    for m in re.findall(r'(?:href|src)="(/[^/][^"]*)"', text):
        problems.append(("ABSOLUTE-PATH", name, m + "（GitHub Pages 项目页在子路径下，必须用相对路径）"))

    # 5) 外部资源（允许 https 链接跳转，但不允许加载）
    for m in re.findall(r'(?:src|link[^>]*href)="(https?://[^"]+)"', text):
        problems.append(("EXTERNAL-ASSET", name, m))

for lvl, page, detail in problems:
    print("%-16s %-16s %s" % (lvl, page, detail))

print("----")
print("PASS" if not problems else "FAIL (%d)" % len(problems))
sys.exit(0 if not problems else 1)
