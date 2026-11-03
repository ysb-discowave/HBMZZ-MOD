# 官网（docs/）

站点是纯静态 HTML，没有构建步骤、没有依赖、没有 CDN。GitHub Pages 直接从仓库的
`docs/` 目录发布（Settings → Pages → Source: `main` / `/docs`）。

```
docs/
├── index.html          首页：六个模块、快速开始、设计取舍
├── guide.html          使用指南：屏幕 / 终端 / 面板工坊 / 控制面板 / 节点图控制台
├── reference.html      参考手册：控件表、节点表、配方、配置、工具、构建
├── changelog.html      更新日志 + 路线图
├── license.html        许可与致谢（GPL-3.0、第三方来源、节枝授权、署名）
├── .nojekyll           让 GitHub Pages 跳过 Jekyll 处理
├── assets/
│   ├── site.css        设计系统（唯一一处样式）
│   ├── site.js         数据绑定 + 导航高亮 + 移动端菜单 + 复制按钮
│   └── data.js         **自动生成，勿手改**
└── authorization/      节枝书面授权截图（license.html 引用，勿删）
```

## 数据怎么随仓库状态更新

所有会变的东西（版本号、代码行数、控件/节点数量）都不许硬编码在 HTML 里：

- HTML 里写占位：`<span data-bind="auto.version">v?</span>`、
  `<span data-bind="auto.stats.javaLines|num">—</span>`
- `site.js` 在加载时从 `docs/assets/data.js` 取真实值填进去
- `data.js` 由 `tools/site/sync_site.py` 生成：
  - 版本号 ← `build.gradle` 的 `version`
  - MC / Forge ← `build.gradle` 的 `minecraft.version`
  - java 文件数 / 行数 / 资源数 / 控件类数 / 节点类数 ← 扫描 `src/main/java` 与 `src/main/resources`

**发版流程**：

```bash
# 1. 改 build.gradle 的 version
# 2. 同步数据层
python tools/site/sync_site.py
# 3. 校验（CI 里也可以跑，不一致就返回 1）
python tools/site/sync_site.py --check
python tools/site/audit_html.py
# 4. 提交
```

`--check` 会检查：

- `data.js` 与仓库状态是否一致
- 导航里指的页面是否都存在
- `content.json` 的 `timeline` 里每个版本是否都出现在 `changelog.html`
- 除更新日志外，页面里有没有硬编码旧的产物名 `hbmzz-<版本>.jar`
- 站内链接与 `#锚点` 是否都能落地

`audit_html.py` 会检查：有没有用到 CSS 里不存在的类名、骨架标签是否齐全、
容器标签是否配平、有没有绝对路径（项目页在 `/HBMZZ-MOD/` 子路径下，绝对路径会 404）、
有没有外部资源加载。

## 要改文案改哪里

- **首页六个模块、快速开始、时间线、路线图** → `tools/site/content.json`，然后重跑 `sync_site.py`
  （时间线改了以后，记得同步改 `docs/changelog.html` 里的静态条目，`--check` 会帮你发现漏改）
- **页面正文** → 直接改对应的 HTML
- **配色、间距、组件** → 只改 `docs/assets/site.css`，不要在页面里写 `<style>`

## 设计约束

- 视觉语言取自模组自己的 AHBSSMFI-OS：琥珀磷光 + 薄荷第二通道 + 状态色只用于状态词
- 等宽字承担所有数字、标签、控件名
- 不引外部字体/图标/JS，全部用 CSS 画（首页那块屏是纯 CSS + 一个内联 SVG）
- 所有页面共用一个 `<header class="nav">` 与 `<footer class="foot">`，改导航要五个页面一起改

## 本地预览

直接双击 `docs/index.html` 也能看（`data.js` 是 `<script>` 加载，不是 `fetch`，没有 CORS 问题）。
想更接近线上就起个静态服务：

```bash
python -m http.server 8080 --directory docs
```
