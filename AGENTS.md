# 项目约定（给维护者 / AI 助手）

## 发布更新时的固定动作

每次发布新版本（在 GitHub 创建 Release、上传 jar / 源码包）之后，**必须同步更新官网**，不能只发 GitHub：

1. **官网版本号**：改 `tools/site/content.json` 里的版本号字段，重建 `docs/assets/data.js`
   （`python tools/site/sync_site.py`，会把新版本号写进 data.js，并刷新各 HTML 的资源缓存键）。
2. **官网更新日志**：在 `tools/site/content.json` 的 `timeline` 顶部加一条新版本条目，
   并同步改写 `docs/changelog.html` 对应的 `tl-item` 块。
   - 文本按 GitHub Release 定稿来写，用「刚发布」口吻，只讲玩家摸得到的实际变化。
   - **HTML 样式不能变**：`tl-item / tl-head / tl-ver / tag / h3 / ul / li / strong / code / b` 原样保留，
     只换 `h3` 标题和 `li` 里的文字；写法模仿更早的条目（每条 `<li>` 以 `<strong>...：</strong>` 开头）。
   - 版本号按真实发布记录排，从新到旧倒序；没有对应 Release 的版本不要凭空编。
3. **校验**：`python tools/site/sync_site.py --check` 与 `python tools/site/audit_html.py` 都要 PASS。
4. **推送**：commit + push，`docs/` 由 GitHub Pages 自动上线；直连 `changelog.html` 核对版本号顺序
   （WebFetch 可能读到缓存，以 curl 直连为准）。

## 其它发布约定

- 公告草稿先落本地 `docs/release-3.8.0.md` 供用户核验，未经确认不发布、不发群、不传附件。
- 用户已进游戏实测：更新日志/公告里不要写「未进游戏实测」「需人工确认」这类话。
- 发群走剪贴板整段粘贴 + 一次回车/发送，不要逐字输入。
