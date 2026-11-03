# 第三方授权记录

本文件记录本项目取得的外部代码授权。**它是 `LICENSE`（GPL-3.0）能覆盖全仓库的前提条件之一，
请与 `NOTICE.md` 一同保留。**

---

## 1. 树上的节枝 — DFC-Map-Creation-Tool（JiezhiMod）

| 项 | 内容 |
|---|---|
| 授权人 | **树上的节枝**（B 站 <https://space.bilibili.com/3494363348863838>，Gitee <https://gitee.com/yi-sichen-jiezhi/DFC-Map-Creation-Tool>） |
| 被授权方 | Yang Sibo（GitHub: ysb-discowave），HBMZZ 项目 |
| 授权时间 | **2026-11-03 20:34**（B 站私信） |
| 授权范围 | 本项目从 JiezhiMod 取用的部分（见下表）可按 **GPL-3.0** 使用、修改与再分发 |
| 截图 | [`docs/authorization/2026-11-03-jiezhi-bilibili-pm.png`](docs/authorization/2026-11-03-jiezhi-bilibili-pm.png) |

### 对话原文

> **Tiny·全能（本项目作者）** 20:34
>
> @树上的节枝 我看你的 dfc 模组的显示器挺有意思的，所以我在我的 HBM-ZZ 模组里的显示器抄了你的
> 部分代码、思路，现在我打算发布 HBM-ZZ 的第一个外部版本，打算使用 GPL-3.0 开源协议开放源代码，
> 但这和你所使用的开源协议有所冲突，希望你能给我一份书面授权，已让 HBM-ZZ 合法发行

> **树上的节枝**
>
> （引用上条消息）
>
> 知道了，可以了

> **树上的节枝**
>
> 去吧

> **树上的节枝**
>
> 才回家

### 授权覆盖的具体内容

即 `NOTICE.md` 第 2 节「JiezhiMod」一行所列，全部为**渲染结构与管线思路**，不含屏上界面：

| 取用部分 | 文件 |
|---|---|
| 整机 TESR 渲染结构（方块 `INVISIBLE` + 空模型，外壳/前框/吊架/屏幕全由 `TileEntitySpecialRenderer` 绘制） | `client/RenderDFCMonitor`、`client/ConsoleModel` |
| 控制器 + 占位格结构（仅控制器格有方块实体，其余为无实体占位块） | `block/BlockDFCMonitor`、`block/BlockDFCMonitorPart`、`tile/TileEntityDFCMonitor`、`dfc/ConsoleRig` |
| 吊装几何与变换（绕顶边下倾、`push = (rows/2)·sin(tilt)`、吊架不随面板旋转） | `client/RenderDFCMonitor`、`dfc/ConsoleRig` |
| 坠落/摆动动画 | `client/RenderFallenScreen` |
| AWT 离屏画布 → 单纹理 quad 上传管线（`glTexSubImage2D(GL_BGRA)`、`CLAMP_TO_EDGE`、尺寸变化重新 `glTexImage2D`） | `client/screen/TerminalCanvas` |

### 明确不在此授权内的部分

- JiezhiMod 的**界面设计、配色、页面布局**：本项目未取用，屏上 AHBSSMFI-OS 的页面、双环量表、
  七路灯柱、MIMIC 结构图、事件日志全部为本项目自写（详见 `README.md`「署名」一节）。
- JiezhiMod 的**素材文件**（贴图、模型、音效）：本项目未取用。
- JiezhiMod 的其余代码：本项目未取用。

### 备注

- 本节记录的是**一次性、不可撤销的许可**，其效力不依赖节枝后续是否变更 JiezhiMod 的协议。
- 原「源码可见许可协议 v1.0」的 §4.3（禁止二次分发 jar）、§4.5（禁止商业用途）对**本项目**
  不再适用；它们对 JiezhiMod 自身仍然有效，任何人直接使用 JiezhiMod 时仍须遵守。
- 依 GPL-3.0 §7 的精神，此处不存在「附加限制」：授权只做**放宽**，未对下游施加任何额外约束。
