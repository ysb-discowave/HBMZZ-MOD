# NOTICE — 版权与第三方来源

本文件列出 HBMZZ 的来源、许可证与修改说明。**发布二进制或源码时请一并保留本文件与
[`AUTHORIZATION.md`](AUTHORIZATION.md)**（后者是节枝授权的书面记录）。

---

## 1. 本项目

```
HBMZZ — HBM's Nuclear Tech 控制台 / 监控屏扩展
Copyright (C) 2026 Yang Sibo (GitHub: ysb-discowave)
```

**许可证：GNU General Public License, version 3（GPL-3.0-only）**
全文见仓库根目录的 [`LICENSE`](LICENSE)，或 <https://www.gnu.org/licenses/gpl-3.0.txt>。

选 GPL-3.0 不是偏好，是**义务**：`com.hbmzz.cecp` 整个包（含控件、节点系统、编辑器界面、
方块与方块实体）逐文件移植自 **NTM: 诅咒版** 的 `com.hbm.inventory.control_panel`，
而该项目的许可证是 GPL-3.0。GPL-3.0 的衍生作品必须继续以 GPL-3.0 分发，不能改成 MIT / Apache-2.0，
也不能附加"禁止商用""禁止再分发"之类的额外限制（GPL-3.0 §10）。

---

## 2. 第三方代码与素材

| 来源 | 许可证 | 本项目中使用的位置 |
|---|---|---|
| **HBM's Nuclear Tech — Community Edition**（NTM-CE）<br><https://github.com/Warfactory-Official/Hbm-s-Nuclear-Tech-CE><br>作者：HBMMods / Drillgon200 / TheOriginalGolem / Alcater / MrNorwood / Movblock / EOS 等，名单见其 `mcmod.info` | **GPL-3.0** | `com.hbmzz.cecp` 全部包（`Control`、`ControlType`、`controls/*`、`nodes/*`、`NodeSystem`、`modular/*`、`types/*`、`SubElement*`、`ControlPanel`、`ControlEvent(–System)`、`IControllable` …）；`BlockCtrlConsole` / `TileEntityCtrlConsole` / `RenderCtrlConsole` 对应的上游类；`assets/hbmzz/models/control_panel/*` 与 `assets/hbmzz/textures/models/control_panel/*` 的 OBJ 与贴图 |
| **NTM Cursed Addon（CE Addon / Leafia）**<br><https://github.com/ntmleafia/NTM-Cursed-Addon><br>作者：Leafia、movblock 等 | **GPL-3.0**（部分文件 **LGPL-3.0**，见其 `LICENSE` / `LICENSE.LESSER`） | `com.hbmzz.cecp.nodes.addon`（14 个节点）、`controls.addon`（11 个控件）、`controls.configs.addon`（7 个配置面板）、`modular.AddonNodeLoader`、`compat/AddonRes`、`compat/CEEventTable`、`compat/RBMKColumns`、`compat/ModSounds`；`assets/hbmzz/models/control_panel/addon/*`（6 个 `.obj`）、`assets/hbmzz/textures/models/control_panel/addon/*`（15 张 png）、`textures/gui/control_panel/{solid,solid_emissive,invisible}.png` |
| **DFC-Map-Creation-Tool / 节枝特效工具 JiezhiMod**<br><https://gitee.com/jiezhi-bililbli/DFC-Map-Creation-Tool><br>作者：树上的节枝（jiezhi） | 原为自定义「源码可见许可协议 v1.0」（非 OSI 认证）；**已取得作者书面授权，可按 GPL-3.0 使用**（2026-11-03，见 [`AUTHORIZATION.md`](AUTHORIZATION.md) 与第 5 节） | 监控屏的整机 TESR 渲染结构、控制器 + 占位格结构、吊装几何与变换、AWT 离屏画布 → 单纹理 quad 的上传管线。主要涉及 `client/RenderDFCMonitor`、`client/RenderFallenScreen`、`client/ConsoleModel`、`client/screen/TerminalCanvas`、`dfc/ConsoleRig`、`block/BlockDFCMonitor*`、`tile/TileEntityDFCMonitor`。逐项对应关系见 `README.md` 的「署名」一节 |

### 本项目自有

`com.hbmzz.ctrl`（反射桥 + 服务端轮次引擎 + 扁平规则模型）、`com.hbmzz.item`、`com.hbmzz.gun`、
`com.hbmzz.entity`、`com.hbmzz.network`、`com.hbmzz.dfc.DFCReader`、`com.hbmzz.panel`、
`client/screen` 中的界面绘制（AHBSSMFI-OS 各页面、双环量表、事件日志）、`HBMZZConfig`、
以及 `tools/` 下的全部验证脚本，均为本项目实现。

---

## 3. 本项目对上游代码做过的修改（GPL-3.0 §5(a)）

上游 `com.hbm.inventory.control_panel` → `com.hbmzz.cecp` 的移植做了以下改动，除此之外逻辑未动：

1. **包名**：`com.hbm.inventory.control_panel` → `com.hbmzz.cecp`（含子包）。
2. **语法降级**：Java 9+ 语法（`instanceof` 模式匹配、`switch` 表达式、`var`、`Optional.isEmpty()`、
   `String.isBlank()`）改写为 Java 8 等价写法，以便运行在 Minecraft 1.12.2 / Forge。
3. **依赖替换**：上游对 NTM 私有渲染 / 缓冲 / 网络类的调用（`NTMBufferBuilder`、`NTMImmediate`、
   `WaveFrontObjectVAO`、`MainRegistry` 等）替换为本项目的兼容层 `com.hbmzz.cecp.compat`
   （`CtrlBuf`、`CtrlImm`、`ObjModel`、`GuiFx`、`RenderUtil`、`CtrlRes`、`CPEnv`、`CPPackets`、
   `CPMachines`、`BridgedControllable`、`CEEventTable`）。
4. **跨模组适配**：新增 `CPMachines` / `BridgedControllable`，使 CE 机器（含多方块核心解析）
   能被面板识别；事件表改为运行时镜像 CE 的 `ControlEvent.REGISTRY`，不再抄写固定名单。
5. **界面文本**：显示文本英译中。**协议字符串一律未翻译**（事件名、变量名、NBT 键），
   原因见 README 的「汉化边界」。
6. **新增功能**：附属模组的 14 个节点 + 11 个控件 + 2 个图表节点；`NodeSounder` 的音效表改为
   按 `(类名, 字段名)` 反射解析；`NodeBulkQuery` 改走 `CPMachines` 以支持跨模组；
   `NodeSounder` 的 `doOutput` 由 `false` 改为 `true`（上游返回值在广播链路里会取消接收者，属缺陷）。

### 其他许可说明

- NTM-CE 与 CE Addon 的**名称、图标**归各自作者所有，本项目未使用其图标作为自己的图标。
- 本项目**不是** NTM / NTM-CE / CE Addon 的官方作品，也未获得其作者背书。装不装它们都能启动，
  但涉及机器交互的功能需要 NTM-CE 在运行时存在（反射软依赖，未链接其代码）。

---

## 4. 再分发者需要做的事

分发本项目的源码或 jar（包括改过的版本）时：

1. 保留 `LICENSE`、本 `NOTICE.md` 与 `AUTHORIZATION.md`，保留源码中的版权声明。
   `AUTHORIZATION.md` 是节枝授权的书面证据，删掉它，授权链就断了。
2. 以 GPL-3.0 分发，**不得追加**"禁止商用""禁止再分发""必须授权"等额外限制。
3. 修改过的话，在显眼位置说明改了什么、什么时候改的（§5(a)）。
4. 提供对应源码（§6）。从本仓库 fork 后发布二进制，指向你自己的仓库即可。
5. 保留 README「署名」一节里对**树上的节枝**的署名（这既是授权的条件，也是事实）。

---

## 5. 节枝协议：已取得授权（2026-11-03）

节枝特效工具 JiezhiMod 使用**自定义协议「源码可见 v1.0」**（非 OSI 认证），其中两条原本与
GPL-3.0 直接冲突：§4.5 禁止任何商业用途、§4.3 禁止直接二次分发 jar。

**该冲突已解决**：本项目作者于 **2026-11-03 20:34** 通过 B 站私信取得作者 **树上的节枝** 的
书面同意，其被引用的那部分代码可按 GPL-3.0 使用、修改与再分发。完整对话原文、时间、覆盖范围
与**明确不覆盖的部分**见仓库根目录的 [`AUTHORIZATION.md`](AUTHORIZATION.md)，截图存档于
`docs/authorization/2026-11-03-jiezhi-bilibili-pm.png`。

因此：

- 本仓库**可以**整体按 GPL-3.0 发布，`LICENSE` 的效力覆盖全部文件；
- 该授权只做**放宽**，未对下游追加任何限制，符合 GPL-3.0 §7；
- JiezhiMod 自身的「源码可见 v1.0」对其**自己的**代码仍然有效——任何人**直接**使用 JiezhiMod
  （而不是使用本项目）时，仍须遵守它的 §4.3 / §4.5；
- 从本项目 fork 后发布的人**不需要**再单独向节枝申请授权，因为 GPL-3.0 的授权链已经接上；
  但 `AUTHORIZATION.md` 与本节必须保留，否则授权链的书面证据就断了。

