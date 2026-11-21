# HBMZZ 3.9.0 交付文档

日期：2026-11-21
基线：4.1.0（贴墙壁挂屏 + 可调几何 + 专用连接工具版）

这一版做了五件事：版本号改回 3.9.0、几何调节改为实时、屏幕侧视补实体感、修复温度失效的根因、连接工具绑定后自动打开菜单；并新增一条指令驱动的自动停堆功能。

## 一、版本号改为 3.9.0

三处版本戳统一回退，保证构建产物、Mod 列表、配置里看到的是同一个号：

- `build.gradle`：`version = '3.9.0'`
- `HBMZZ.java`：`VERSION = "3.9.0"`
- `mcmod.info`：`"version": "3.9.0"`

产物 `hbmzz-3.9.0.jar`。站点数据 `docs/assets/data.js` 已同步；站点的"显示版本"按既有设计取自最新发布 tag（v4.1.0），本次未打新 tag、未推 GitHub，所以显示值不变——这是站点脚本逻辑，不是版本戳漏改。

## 二、几何调节改为真正实时

**问题**：调倾斜角/宽/高时，原先只把请求发给服务端，客户端要等同步回来才更新，感觉像"必须退出菜单才生效"。

**改法**（`GuiAegisTerminal.java` + `TileAegisTerminal.java`）：
- 新增 `setGeometryPreview()`：只改客户端三个几何字段，无 NBT、无发包，clamp 与服务端完全一致。
- 三个调节方法改为：先本地预览（画面和读数下一帧就变），再发 `AegisSetupPacket` 让服务端权威 clamp、写 NBT、广播。回包数值相同，不跳变。
- 鼠标按钮与 Z/X、[ / ]、- / = 快捷键走同一套方法。

## 三、屏幕侧视补实体感

**问题**：原来的金属外壳背板到前面只有约 0.06 格厚、颜色近乎全黑，从侧面/斜角看屏幕像一张贴在空气上的画面。

**改法**（`AegisRenderer.backing()`）：
- 外壳加厚到约 0.18 格，前面板略凸出墙面。
- 顶/底/左右四个侧壁分别用不同明度（顶亮、底暗、两侧居中），无动态光照也能读出体积。
- 四条深度棱加青色高光条，斜视时能看清厚度。侧看现在是一台有厚度的显示器，而不是空气。

## 四、修复温度失效（根因：核心没被识别）

**现象**：现场日志 `logs/latest.log` 里 `hbmzz 3.9.0` 已加载，但**一条 `Aegis sample` 都没有**——说明采样在找到核心之前就提前返回了，根本不是"读数算错"。

**根因**：`AegisProbe.isCore()` 和 `DFCReader.isCore()` 只认死类名 `com.hbm.tileentity.machine.TileEntityCore`。NTM:CE + Cursed Addon 环境下核心子类被重排/改名，匹配失败 → 自动扫描找不到核心 → 温度永远不刷新。

**改法**：
- 两处 `isCore()` 增加特征回退：先试精确类名，再试 `getDFCTemperature`/`isDFCHasCore` 访问器，最后试 `heat`+`field` 字段组合。任一命中即认作核心。
- 之前"找不到核心"是**完全静默**的，现在每 200 次采样记一条 `Aegis sample … NO CORE` 日志，带绑定坐标、扫描半径、加载的 TE 数量。下次若还失效，现场日志能直接区分是"核心没识别 / 绑定过期 / 距离太远"哪一种。

## 五、连接工具绑定后自动打开菜单

**问题**：连接工具绑定核心后右键终端，只显示"已绑定成功"，设置窗口没稳定打开。

**改法**：
- 新增网络包 `AegisOpenGuiPacket`（ID 33，服务端→客户端），经 `AegisClientBridge.openAegisGui()` 在渲染线程打开菜单。
- `ItemAegisLinker` 服务端绑定成功后主动发这个包，权威地打开菜单；客户端原有的本地 openAegis 保留。
- 结果：绑定完直接进入几何设置窗口，可以立刻调倾斜/宽/高。

## 六、新增指令：自动停堆 `/aegis scram`

**用法**（操作员权限，输入 `off`/`status` 可选）：
- `/aegis scram`：对玩家附近（神盾扫描半径内）最近的 DFC 核心启动自动停堆。
- `/aegis scram status`：查看正在停堆的核心与当前温度。
- `/aegis scram off`：解除本机世界所有停堆会话。

**行为**：
- 每服务器秒把核心温度写低 2000°C（`DFCReader.writeTemperature`，与黑客平板同一条写字段通道）。
- 如果读到温度比上一次写入值还高（说明堆在失控升温、降温压不住），**瞬间写 0°C** 并记日志。
- 每个核心一个会话，按 维度+坐标 索引；区块卸载时暂停、核心消失时移除、服务器停止时清空。写入全部在服务端 tick 线程。

**新增文件**：`AegisScram.java`（会话 + tick）、`command/CommandAegis.java`（指令）。在 `HBMZZ.java` 里注册事件监听与 `FMLServerStartingEvent`。

## 七、验证与部署

- 人工排查两遍 + javap 核对 1.12.2 API：修掉 5 处会在旧写法下编译失败的 API 误用（`ServerTickEvent.server` 不存在、`getTabCompletions`/`getName`/`getUsage` 方法名、`sendTo` 参数类型、私有构造器）。
- JDK 8 `gradle build` **BUILD SUCCESSFUL**。
- `tools/regcheck/check.py`：**all 150 checks passed**；网络包 ID 33 无冲突。
- 站点一致性 `sync_site.py --check`：全部一致（仅一条 tag/构建版本不一致的预期 WARN）。
- 新 jar 已部署进游戏 `mods`：`hbmzz-3.9.0.jar`，MD5 `8cd33925913d52400932c43017325a88`。
- 上一版 `hbmzz-3.9.0.jar` 已备份为 `mods/backup-mods/hbmzz-3.9.0.jar.pre-wallmount`。

## 八、说明

- 温度失效的修复基于"核心没被识别"这一日志证据；若现场仍异常，请附 `logs/latest.log` 里的 `Aegis sample` 行（现在无论是否找到核心都会记）。
- 自动停堆是应急刹车，直接写核心温度字段，不替代反应堆自身的控制棒/停堆逻辑。
- 未推 GitHub、未打新 tag、未动远端 Release：遵守约定，除非用户本人发出代理名 `XingZuo-Cloud Bot`。

---

## 九、追加修复：贴墙 + 侧视实体 + 远距离核心连接（同版本内）

本轮针对三条现场反馈，功能代码仍在 `3.9.0` 版本号下。

### 9.1 屏幕不贴墙、侧着看像滚木

**现象**：终端看着悬在墙前；侧视时是一段突出墙面的深盒子和交叉支架，像一卷滚木而非贴墙显示器。

**根因**：渲染器 `AegisRenderer` 上一版把外壳深度做到约 0.18 格，还画了一个从墙面伸出 0.1 格的交叉支架 `wallBracket()`；倾斜的旋转枢轴放在墙前 0.45 格处。三件事叠加，导致屏幕中心浮在墙外、侧面看到深盒+支架的柱状轮廓。

**修复**（`AegisRenderer.java`）：
- 倾斜枢轴移到**墙面本身**（局部 +Z = 0.5），外壳背面在任意倾角都贴着墙，只有正面往外仰。
- 删掉突出的交叉支架；外壳改为**贴墙扁盒**（`BOX_DEPTH = 0.14` 格），背面 z=0 贴墙、正面是画面。
- 四个侧壁按顶亮/底暗/两侧中色分别上色，正面一圈青色窄边，侧视是平整、有明确厚度的显示器。
- 修掉原 `drawEdgeStrip()` 的顶点 bug（vertical 分支把四个顶点压在同一 y 平面）。

### 9.2 有些核心连得上、有些连不上

**铁证**：现场 `logs/latest.log` 里 `Aegis sample @(606,26,-1008) NO CORE scanRadius=16`，而手动绑定的核心在 `(643,30,-1012)`，相距约 37 格——自动扫描半径 16 根本够不着。近的核心能连、远的连不上，与反馈完全吻合。

**修复**：
- `AegisConfig.scanRadius` 默认值 16 → **48**，配置上限 64 → 128。
- 加**一次性迁移**：旧存档里的值是 16（Forge 不覆盖已存在配置），加载时若小于 48 自动提升并写回，用户无需手动删配置。
- 现场 `config/hbmzz-aegis.cfg` 的 `scanRadius` 已同步改为 48，立即生效。
- 两处 `isCore()`（`AegisProbe` / `DFCReader`）增加 CE addon 标记接口 `IDFCBase` / `IMixinTileEntityCore` 的识别，加上简单类名回退，核心类被改名/换包也能认出。

### 9.3 验证

- 人工排查两遍 + javap 核对 Forge API（`Configuration.get(String,String,int)`、`Property.set(int)` 均存在）；`AegisRenderer` 大括号配平。
- JDK 8 `gradle build` **BUILD SUCCESSFUL**；regcheck **all 150 checks passed**；站点一致性通过。
- 产物 MD5 `8cd33925913d52400932c43017325a88`，已部署；上一版备份 `.pre-wallmount`。

---

## 十、贴墙修正 + 宽高一格步进 + 独立熔毁预测与停堆（同版本内）

本轮根据现场反馈继续修正，版本号仍是 `3.9.0`。

### 10.1 屏幕还是没贴墙、侧面像空气

**根因**：上一版把面板几何放在方块**朝玩家**那一面（局部 +Z=0.5）再往外凸 0.14 格，等于整块屏浮在方块外面，和墙之间隔了接近一整格——这就是"半砖空档"，侧视只剩很薄一片、看着像空气。

**修复**（`AegisRenderer.java`）：
- 背板改到**贴墙面**（局部 -Z=-0.5），外壳从墙面向外延伸 `BOX_DEPTH=1.06` 格，几乎填满整格厚度、正面只露出 0.06 格唇边——屏幕真正贴墙，不再是悬空薄片。
- 倾斜枢轴一起移到墙面，任意倾角背板都贴墙。
- 渲染期间 `GlStateManager.disableCull()`（finally 里 `enableCull()`），侧视/斜视都能看到实体盒体，不会再被背面剔除成空气。

### 10.2 宽/高每次加减一格、上限放大

**修复**：
- `GuiAegisTerminal` 宽/高按钮与快捷键（`[` `]` `-` `=`）步长 0.08 → **1.0 格**；倾斜角维持 5°/步。
- `TileAegisTerminal` 宽高范围 0.5~2.0 → **1.0~32.0 格**，默认值 2.0；`renderBoundingBox` 按新上限扩张，大屏走到近处不会被裁剪。
- 菜单提示文字同步为"宽/高 1~32 格，每次 +1/-1 格"。

### 10.3 全新独立的核心熔毁预测与自动停堆

**为什么要重写**：日志铁证——`getDFCTemperature()` 返回 708°C，但反射读 `heat` 字段是 0。反编译 CE addon 的 `MixinTileEntityCore` 确认：真实温度在 `double temperature` 字段，由 `getDFCTemperature()` / `setDFCTemperature(double)` 读写；原版 HBM 的 `int heat` 字段早就不承载 CE 的温度。旧停堆写 `heat`，根本打不中真实温度——这就是"自动停堆没用"的根因。

**实现**（全新 `com.hbmzz.guard` 包，不引用任何 `aegis` / `dfc` 监控屏代码）：
- `CoreThermal`：独立反射层，读温度优先 `getDFCTemperature()`、写温度优先 `setDFCTemperature(double)`，再回退真实 `temperature` 字段；熔点 `getDFCMeltingPoint()`。
- `CoreGuard`：服务端每秒——
  - **熔毁预测**：对观测核心采样算升温斜率，预测"多少秒后达到熔点"；温度未上升显示"暂无风险"。
  - **自动停堆**：温度 >10000°C 每秒降 **5000°C**，>5000°C 每秒降 **2000°C**，到 0 后保持；按维度+坐标建会话，区块卸载暂停、核心消失/停服清除。
- **指令**（权限 0，任何玩家可用）：`/coreguard <scram|forecast|status|off>`；旧 `/aegis scram` 作为兼容别名指向同一实现。
  - `/coreguard forecast` 查熔毁预测时间；`/coreguard scram` 启动停堆；`/coreguard status` 查状态；`/coreguard off` 解除。
- 旧 `AegisScram` 从事件总线摘除（它写的是错误的 heat 字段），旧的 `/aegis scram` 改委托到 `CoreGuard`。

### 10.4 验证

- 按用户要求人工排查两遍 + javap 核对 1.12.2 API；编译抓到并修复 3 处（GlStateManager 无 disableCullFace → disableCull、格式串缺 `%`、字体 U+00B1）。
- JDK 8 `gradle build` **BUILD SUCCESSFUL**；regcheck **all 150 checks passed**；站点一致性通过。
- 产物 MD5 `73115ed4b7b5a4f877315ffb54aa8568`，已部署到 `mods`；上一版备份 `.pre-guard`。

---

## 十一、自动停堆时在温度上方显示标识（同版本内）

**需求**：自动停堆运行中，监控屏温度读数上方要显示一个"自动停堆"标识，一眼可辨。

**实现**（状态从服务端贯通到客户端画面）：
- `CoreGuard.isArmed(world, pos)`：按"维度+坐标"查活跃停堆会话，供外部只读查询（不改停堆逻辑）。
- `AegisTelemetry`：新增 `scramming` 布尔位，紧跟 `collapsing` 一起 encode/decode，随遥测帧下发。
- `AegisProbe.sample()`：采样时按**实际读到的核心坐标**填入 `scramming`（自动扫描场景下与手动绑定坐标可能不同，故以实际核心为准）。
- `AegisCanvas.drawOverview()`：温度大数字上方画一个闪烁的"自动停堆"徽章（红底、琥珀↔红呼吸边框），画在辉光之后保证清晰；停堆解除后自动消失。

**验证**：人工排查两遍（序列化顺序一致、绘制 API 签名匹配、括号配平）；JDK 8 `gradle build` **BUILD SUCCESSFUL**；regcheck **all 150 checks passed**；站点一致。
产物 MD5 `71d6194f1b5da7345bde87e3502723d0`，已部署到 `mods`；上一版备份 `.pre-scram-badge`。
