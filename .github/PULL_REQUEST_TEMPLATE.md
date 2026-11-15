<!-- 提 PR 前请先跑一遍 python tools/checkall.py。下面三节别删，写清楚了评审才快。 -->

## 改了什么

<!-- 原来怎样 / 现在怎样。涉及游戏内行为的，写清玩家会看到什么差别。 -->

## 为什么这么改

<!-- 设计取舍、被否掉的方案、与上游（NTM CE / Leafia）的关系。 -->

## 怎么验的

<!-- 贴 checkall 的结果；进游戏验过的，写操作步骤与截图。
     红石、爆炸、渲染这类改动只有真跑过才算验过。 -->

```
python tools/checkall.py
```

- [ ] `checkall` 全过（或在下面说明哪项没过、为什么）
- [ ] 涉及游戏内行为的，进游戏实际验过
- [ ] 改站点的，`sync_site.py --check` 与 `audit_html.py` 都过

## 清单

- [ ] 新增 `.java` 都带了 GPL 头
- [ ] 没有从非 GPL 来源抄代码（思路参考不算，抄实现算）
- [ ] 新增方块/物品走完了 README「加东西的清单」，`regcheck` 通过
- [ ] 提交带 `Signed-off-by`（`git commit -s`）
- [ ] 动了 `AUTHORIZATION.md` / `NOTICE.md` / `LICENSE` 的，已在上面说明原因
