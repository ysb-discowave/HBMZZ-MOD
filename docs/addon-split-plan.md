# 控制台解耦方案（`com.hbmzz.cecp` → 附加包）

本文只讲**根 mod 侧**要做什么，以及拆完以后旧存档会怎样。
附加包仓库里的工程搭建不在这里。

- 拆分对象：`src/main/java/com/hbmzz/cecp`，153 个文件 / 23,090 行；配套资源在
  `assets/hbmzz/{blockstates,models,textures}/{control_panel,…}` 与 `lang`。
- 目标形态（已写在站点 `docs/addon.html` 里）：两枚 jar，**依赖只有一边** ——
  附加包 `after:hbmzz`，根 mod 不声明对附加包的依赖，单独装照常可用；
  单装附加包能启动但空跑。
- 本分支 `addon-split` 只做**不改变行为**的那部分（见 §7），`cecp` 一个文件都没删。

---

## 1. 现状：谁引用谁

### 1.1 根 mod → `cecp`（拆走后必须消失的边）

| 文件 | 行 | 引用什么 |
|---|---|---|
| `ModContent.java` | 60 | `new com.hbmzz.cecp.block.BlockCtrlConsole()`（字段 `BLOCK_CTRL_CONSOLE`） |
| `ModContent.java` | 69 / 132 / 156–157 / 227–236 | 由上面那个字段带出的 item、方块注册、item 注册名、配方 |
| `HBMZZ.java` | 68–69 | `GameRegistry.registerTileEntity(TileEntityCtrlConsole.class, hbmzz:ctrl_console)` |
| `HBMZZ.java` | 99 | `new com.hbmzz.cecp.CtrlConsoleGuiHandler()`（gui id 0） |
| `HBMZZ.java` | 104 | `com.hbmzz.cecp.ControlEvent.init()` |
| `HBMZZ.java` | 111 | `com.hbmzz.cecp.compat.CEEventTable.mirror()` |
| `HBMZZ.java` | 115 | `com.hbmzz.cecp.ControlRegistry.init()` |
| `HBMZZ.java` | 116 | `com.hbmzz.cecp.modular.StockNodesRegister.register()` |
| `network/NetworkHandler.java` | 55–58 | 两条包：`NBTControlPacket`(11→SERVER)、`ControlPanelUpdatePacket`(12→CLIENT) |
| `client/ClientProxy.java` | 62–63 | `bindTileEntitySpecialRenderer(TileEntityCtrlConsole, RenderCtrlConsole)` |
| `item/ItemMonitorLinker.java` | 71 / 144 | `instanceof BlockCtrlConsole`、`instanceof IControllable`（**本分支已去掉**） |
| `network/TerminalInputMessage.java` | 21 | `TileEntityCtrlConsole`（**本分支已去掉**） |
| `client/GuiCtrlTerminal.java` | 22–23 | `TerminalControl`、`TileEntityCtrlConsole`（**本分支已去掉**） |
| `ctrl/TerminalHost.java` | 32 | 只有 javadoc 里点了 `com.hbmzz.cecp.ControlPanel` 的名（**本分支已改写**） |

### 1.2 `cecp` → 根 mod（拆走后**方向正确**，是附加包依赖本体）

| `cecp` 侧 | 用到的根类 | 用途 |
|---|---|---|
| `compat/BridgedControllable`、`compat/CPMachines` | `ctrl.MachineBridge` | 读 NTM 机器的 key/event/变量，即"控制台能控任意机器"这件事 |
| `TerminalConsoleHost` | `ctrl.MachineBridge`、`ctrl.TerminalHost` | 终端宿主实现 |
| `tile/TileEntityCtrlConsole` | `ctrl.TerminalShell`、`ctrl.TerminalHost`、`ctrl.GroupedHost`、`ctrl.PartBroadcast` | 终端执行、按部件分组、批量广播 |
| `ControlEventSystem` | `ctrl.PartBroadcast` | 同上 |
| `compat/LinkerItems`、`block/BlockCtrlConsole` | `item.ItemMonitorLinker` | 认联动器 / 判定"配置工具" |
| `net/CPPackets` | `network.NetworkHandler` | 发/收上面那两条包 |
| `block/BlockCtrlConsole` | `HBMZZ.instance`、`HBMZZ.proxy.openTerminal` | 开 GUI |

**结论：`com.hbmzz.ctrl` 实际上是共享内核**（`TerminalHost` / `TerminalShell` /
`GroupedHost` / `PartBroadcast` / `MachineBridge`），不是"根 mod 私有的控制面板实现"。
这决定了拆分的形状：这些类留在根 mod，附加包依赖它们。

---

## 2. 逐处解耦方案

### 2.1 `ModContent.java`：方块、物品、配方

**现在**：字段初始化时 `new BlockCtrlConsole()`，注册期注册方块 + ItemBlock + 有序合成配方。

**拆走后**：**整块搬走**，根 mod 里删掉字段、注册调用、`ITEM_BLOCK_CTRL_CONSOLE` 的
`setRegistryName`、以及那条配方（`ModContent.java:227–236`）。

- 根 mod 不保留任何"控制台配方"或"占位物品"。留配方意味着配方表里有个
  `ItemStack(air)`，1.12.2 会在加载期直接崩；要留就必须改成按注册表查名 + 判空，
  而站点已经写明"单装本体照常可用"，不需要这条配方。
- **注意 `tools/regcheck`**：它解析 `ModContent.java` 源码，要求每个 `Block` 字段进
  `registerBlocks`、每个可放置方块有 item。删字段时这四处（字段 / 注册 / ItemBlock /
  配方）必须一起删，否则 `checkall.py --fast` 立刻红。

**旧存档**：方块和物品只是换了注册者，`hbmzz:ctrl_console` 这个名字不变就没事（§3）。
如果**不装**附加包，世界里已放置的控制台会变成未注册方块（Forge 替换为空气，方块与
TE 一并丢失）；这一点必须在站点写明，属于"卸载附加包 = 拆掉所有控制台"。

### 2.2 `HBMZZ.java`：TE、GUI handler、三张表

**现在**：`preInit` 里注册 TE、注册 GUI handler，并依次调
`ControlEvent.init()` → `CEEventTable.mirror()` → `ControlRegistry.init()` →
`StockNodesRegister.register()`。

**拆走后**：

| 项 | 根 mod | 附加包 |
|---|---|---|
| TE 注册 | 删除 | `GameRegistry.registerTileEntity(TileEntityCtrlConsole.class, new ResourceLocation("hbmzz", "ctrl_console"))` —— **必须仍是 `hbmzz:ctrl_console`** |
| GUI handler | 删除 | 在附加包的 `preInit` 用**自己的 mod 实例**注册（id 仍可用 0）；同时把 `BlockCtrlConsole` 里 `FMLNetworkHandler.openGui(player, com.hbmzz.HBMZZ.instance, 0, …)` 改成附加包自己的 instance，否则会去问根 mod 要 handler |
| 三张表 + `mirror()` | 删除 | 搬到附加包 `preInit`，**保持原顺序**：`ControlEvent.init` → `CEEventTable.mirror` → `ControlRegistry.init` → `StockNodesRegister.register` |

- 为什么顺序不能改：`mirror()` 读 NTM 现成的 `ControlEvent` 表（把 CE Addon / Leafia 混进去的
  事件名抄过来），必须在 NTM 初始化之后、`ControlRegistry.init()` 之前；`StockNodesRegister`
  读的是 `NTMControlPanelRegistry` 的分类表。
- 时机：附加包声明 `after:hbm` + `required-after:hbmzz`，拓扑顺序天然是 hbm → hbmzz → addon，
  比现在根 mod 的 `preInit` 更晚，不会更糟。
- 这三张表都是**运行期注册表**，不落盘，无存档影响。

### 2.3 `network/NetworkHandler.java`：两条包（序号 11、12）

**现在**：注册在根 mod 的通道 `hbmzz`（`NetworkRegistry.INSTANCE.newSimpleChannel(MODID)`）。

**拆走后**两条路，必须选一条（列入 §6）：

- **A. 附加包自带通道**（建议）：附加包 `newSimpleChannel("hbmzzaddon")`，序号从 0 起；
  根 mod 删掉 11、12。两枚 jar 各自独立，通道名不会撞。
- **B. 共用根 mod 通道**：根 mod 暴露 `NetworkHandler.channel()`，附加包往里注册 11、12。
  好处是一条通道；坏处是根 mod 要开一个只为附加包存在的口子，而且 11、12 从此不能重用。

**兼容性**：`SimpleNetworkWrapper` 没有版本协商，序号也不落盘。所以
"单 jar 老版本" ↔ "本体+附加包" 的切换必须两侧一起换，本来也不支持混装。
旧存档零影响。

### 2.4 `client/ClientProxy.java`：渲染器

**现在**：`bindTileEntitySpecialRenderer(TileEntityCtrlConsole.class, new RenderCtrlConsole())`。

**拆走后**：删两行；附加包在自己的 client proxy 的 `preInit` 里绑（客户端类必须在
`@SideOnly(CLIENT)` 的那一侧调用，别放进 common）。旧存档无影响。

### 2.5 `item/ItemMonitorLinker.java`：✅ 本分支已解耦

- 原来 `instanceof com.hbmzz.cecp.block.BlockCtrlConsole` 判"这一击是不是点在控制台上"，
  现在 `world.getTileEntity(pos) instanceof com.hbmzz.ctrl.LinkTarget`。
- 原来 `instanceof com.hbmzz.cecp.IControllable` 判"这东西是不是一块可被联动的板子"，
  现在同一个标记接口。`cecp.IControllable` 改为 `extends com.hbmzz.ctrl.LinkTarget`
  （用 `SnapshotHost` 的同一思路：接口在根 mod，实现跟实现类走）。
- 行为等价：当前只有 `TileEntityCtrlConsole` 同时是 `TileEntity` 和 `IControllable`；
  `BridgedControllable` 是包装器不是 TE，判定结果不变。
- **符号表不能动**：附加包侧的 `compat/LinkerItems`（`ItemMonitorLinker.getLocations`、
  `isLinker`）和 `block/BlockCtrlConsole.isConfigTool` 都直接 import 这个类。
  将来更彻底的做法是根 mod 提供一个 `LinkerItem` 接口（`int[][] locations()`），让附加包
  判接口 —— 那属于附加包侧的工作，不在本次范围。

### 2.6 `network/TerminalInputMessage.java`：✅ 本分支已解耦

- 原来 `te instanceof TileEntityCtrlConsole` + 强转，调 `runTerminal` / `terminalHost`。
- 现在 `te instanceof com.hbmzz.ctrl.TerminalTile`（新接口，根 mod 侧）。
- `KIND_CONSOLE`（=1）这个协议常量**留在根 mod**：它是"这个终端窗口说的是哪种板子"的
  线路层说法，不绑定实现；附加包复用。
- 拆走后这段分支在没装附加包时永远走不到（世界里没有控制台），属于无害死代码；
  错误串"控制台已经不在了。"也保持原样。
- 面板那一支仍用根 mod 的 `TileEntityCtrlPanel`，未改。

### 2.7 `client/GuiCtrlTerminal.java`：✅ 本分支已解耦

- 原来直连 `TileEntityCtrlConsole.panel.controls.get(i)`、`TerminalControl.tags()/getTarget()`。
- 现在只依赖 `TerminalTile` 的两个方法：`hasTerminalControl(index)`（窗口每帧的"收件人还在吗"）
  和 `seedTerminalSnapshot(index, snapshot)`（开窗首帧用本地面板副本填槽位条）。
  取标签的逻辑搬进 `TileEntityCtrlConsole`，因为它才知道一个控件怎么暴露自己的联动器。
- 行为等价：同一个索引越界判定、同一次 `snapshot.clear()`、同样的"不是终端就什么都不填"。

### 2.8 `ctrl/TerminalHost.java`：✅ 本分支已只改文档

接口本身与 `cecp` 无关（`CtrlPanel` 与 `SnapshotHost` 都实现它）。原来 javadoc 里点了
`com.hbmzz.cecp.ControlPanel` 的全名，改成"ported Cursed Edition console"，让根 mod 里
不再出现 `cecp` 字样。

### 2.9 `cecp/modular/categories/NCStockInput|Output` 与 `nodes/addon/NodeGraph*`：✅ 已改为接口

这几个是自写代码，但 `instanceof com.hbmzz.cecp.controls.addon.Graph` 绑在**具体控件类**上：

- 新增 `com.hbmzz.cecp.api.IValueChart`（`pushValue(double)`、`series()`），
  `Graph implements IValueChart`；
- `NodeGraphAdd` 用 `IValueChart.pushValue`，`NodeGraphBounds` 用 `IValueChart.series()`；
- 两个菜单类用 `instanceof IValueChart` 决定要不要显示"图表区间 / 写入图表"。

行为等价：`Graph` 是唯一实现。收益：`nodes/addon`（18 个类）与 `modular`（6 个类）不再
依赖 `controls/addon` 的具体类，将来若把这两层分到不同 jar 也不会断。

---

## 3. 一个字符都不能变的标识

| 类别 | 必须保持的值 | 落盘/依赖它的地方 |
|---|---|---|
| 方块 / 物品注册名 | `hbmzz:ctrl_console` | 区块里方块状态、玩家背包、配方产物 |
| 方块 TE id | `hbmzz:ctrl_console` | 区块 NBT 的 `id` 字段 —— **改了等于所有控制台的 TE 全部失踪** |
| 未本地化名 | `hbmzz.ctrl_console` | `lang` 里 `tile./item.hbmzz.ctrl_console.name` |
| 配方注册名 | `hbmzz:ctrl_console` | 配方表、JEI/REI 缓存 |
| 资源域 | `assets/hbmzz/**` | 即使文件搬进附加包 jar，目录也必须还是 `assets/hbmzz/`，否则 `hbmzz:ctrl_console` 找不到模型/贴图 |
| 控件注册名 | `button_push`、`switch_toggle`、`graph`、`terminal` … | `Control.writeToNBT` 写 `tag.setString("name", registryName)`；面板 NBT 里的字符串 |
| 节点类型串 | `leafia_graph`、`leafia_graph_bounds` … | `Node.writeToNBT` 的 `nodeType` |
| 节点加载器顺序 | `StockNodeLoader` 在前、`AddonNodeLoader` 在后 | `Node.nodeFromNBT` 按顺序让第一个认领者吃掉 tag，顺序换了会把节点读成另一种 |
| 面板 NBT 键 | `controls` / `control<i>` / `globalvars` / `N` / `V` / `in` / `out` / `X` / `Y` / `S` / `SS` | 方块 TE 的存档 NBT |
| 联动器物品 NBT 键 | `ctrl` / `ctrlName` / `pos` | 已发出去的联动器、控制台的 linker 页 |
| 包序号（若走共用通道） | 11 = `NBTControlPacket`，12 = `ControlPanelUpdatePacket` | 通道 `hbmzz` 的 discriminator |
| GUI id | 0 | `FMLNetworkHandler.openGui(...)` |

---

## 4. 旧存档会受什么影响

1. **TE id 改名 → 最严重**。区块 NBT 里的 `hbmzz:ctrl_console` 找不到注册者时，Forge 会把
   方块当作未知方块处理：控制台消失，面板里的一切（控件、节点图、变量、绑定的机器）随之丢失。
   → 附加包必须用 `new ResourceLocation("hbmzz", "ctrl_console")` 注册，不能用自己的 modid。
2. **控件注册名改名** → `Control.readFromNBT` 找不到名字会 `throw new IllegalStateException`
   （源码里是显式抛的），开控制台即崩存档。这条比"控件丢失"更糟，所以控件名一律不动。
3. **节点 `nodeType` 改名 / 加载器顺序变化** → 节点要么读不出来（丢掉整段逻辑），要么被
   另一个 loader 抢先认领（读到错误的结构）。两者都不崩，但玩家会觉得"我的图坏了"。
4. **联动器 NBT 键改名** → 背包里已有的联动器失去记录，需要重新点一遍。
5. **资源域变化** → 方块变紫黑 / 物品无图标。不崩。
6. **卸载附加包** → 世界上所有控制台消失（同第 1 条的机制）。反过来"单装本体"对旧存档
   的其余部分没有影响。
7. 网络序号、注册表初始化顺序、GUI handler —— 都不落盘，无影响。

---

## 5. 本条分支没有做的事

- 没删 `com.hbmzz.cecp` 下任何文件；没动 `ModContent` / `HBMZZ` / `NetworkHandler` /
  `ClientProxy` 里那 4 处注册级引用（它们只能在"附加包存在与否"确定之后才能改，见 §6）。
- 没动 `assets/` 与 `lang/`。
- 没碰 `master`，没 push。

---

## 6. 需要人决定

1. **附加包的 modid 到底是 `addon` 还是 `hbmzzaddon`**（站点上写的是 `hbmzz:addon`，但注册
   命名空间不能带冒号）。这决定了：资源域要不要另起、TE 要不要显式注册 `hbmzz:ctrl_console`、
   `assets/hbmzz/**` 是否原样搬过去。**建议：modid 自定，但方块/TE/资源域全部沿用 `hbmzz`。**
2. **网络通道**：附加包自带通道（§2.3 A）还是共用根 mod 的（B）。建议 A —— 根 mod 不需要
   为附加包开任何口子，符合"本体不依赖附加包"。
3. **`com.hbmzz.ctrl` 共享内核的归属**：`TerminalHost` / `TerminalShell` / `GroupedHost` /
   `PartBroadcast` / `MachineBridge` 现在服务两块板子。留在根 mod（附加包依赖它）是最省事的；
   若要彻底解耦，就得抽第三个公共库 —— 会引入第三条依赖边，不建议。
4. **`cecp/controls/addon`（14 类）与 `cecp/nodes/addon`（18 类）跟谁走**。建议跟附加包走：
   它们和 `StockNodesRegister` / `NMTControlPanelRegistry` 是同一套节点系统，拆开反而要在
   两个 jar 之间开注册接口。若决定留在根 mod，则 `NCStockInput/Output` 会反过来依赖附加包，
   与"依赖只有一边"冲突。
5. **配方归属**：搬进附加包（推荐，与方块同生共死），还是留在根 mod 用注册表查名 + 判空。
6. **`tools/` 里的三个构建档检查**：`cecpcheck` / `termcheck` / `linkercheck` 直接编译
   `cecp` 的实现类，拆完必须跟着搬。根 mod 的 `checkall.py --full` 会少三项，要不要在
   脚本里显式标"已迁出"而不是静默变少。
7. **卸载附加包的提示**：站点 `addon.html` 现在只说"装回两个包原来的控制台还在"，没说
   "拆掉附加包，控制台会连同面板内容一起消失"。要不要补一句。
8. **旧存档的兜底**：如果将来想让 `hbmzz:ctrl_console` 在附加包缺席时至少变成"占位方块"而不是
   空气，需要在根 mod 注册一个同名占位 TE —— 这会永久占用那个 id，且和"本体零依赖"没有冲突，
   但会多一份要维护的代码。默认不做。

---

## 7. 本分支实际改了什么（编译 + 检查都过）

新增（4 个，都是根侧接口/API）：

| 文件 | 作用 |
|---|---|
| `ctrl/TerminalTile.java` | 板子 TE 的终端接口：`runTerminal` / `terminalHost` / `hasTerminalControl` / `seedTerminalSnapshot` |
| `ctrl/LinkTarget.java` | 标记接口：可被联动器记录的板子 |
| `cecp/api/IValueChart.java` | 图表控件的取样接口：`pushValue` / `series` |

修改（9 个）：

| 文件 | 改动 |
|---|---|
| `cecp/IControllable.java` | `extends com.hbmzz.ctrl.LinkTarget`（加类注释说明为什么） |
| `cecp/controls/addon/Graph.java` | `implements IValueChart`；`pushValue` 加 `@Override`；新增 `series()` |
| `cecp/nodes/addon/NodeGraphAdd.java` | `instanceof Graph` → `instanceof IValueChart` |
| `cecp/nodes/addon/NodeGraphBounds.java` | 同上，`graph.values` → `chart.series()` |
| `cecp/modular/categories/NCStockInput.java` | 菜单判定改用 `IValueChart` |
| `cecp/modular/categories/NCStockOutput.java` | 同上 |
| `cecp/tile/TileEntityCtrlConsole.java` | `implements TerminalTile`；新增 `terminalAt` / `hasTerminalControl` / `seedTerminalSnapshot` |
| `network/TerminalInputMessage.java` | 控制台分支改用 `TerminalTile`，去掉 `cecp` import |
| `client/GuiCtrlTerminal.java` | 同上，去掉 `TileEntityCtrlConsole` / `TerminalControl` import，取标签逻辑下沉到 TE |
| `item/ItemMonitorLinker.java` | 两处 `cecp` 判定改成 `LinkTarget` |
| `ctrl/TerminalHost.java` | 仅 javadoc 措辞，去掉 `cecp` 全名 |

根 mod 里还剩 4 个文件提到 `com.hbmzz.cecp`（`ModContent`、`HBMZZ`、`NetworkHandler`、
`ClientProxy`），全部是**注册级**引用，只能在附加包工程就位后一次性搬走 —— 这是 §6 那几条
决定的下游。
