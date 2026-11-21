# HBMZZ 4.0.0 交付文档

> 交付时间：2026-11-20
> 基准版本：4.0.0（Forge 1.12.2，ForgeGradle 2.3.4，Java 8）
> 本次主题：**Aegis 遥测终端（DFC 监控屏从零重写）** + 首次真实编译验证

## 发布公告（同步 QQ 群「HBM-ZZ开发群」）

【HBMZZ 4.0.0 刚发布】Aegis 遥测终端 + 首次全量编译通过

任务：DFC 监控屏从零重写 + 交付部署
状态：已完成，jar 已装入游戏 mods

主要结果
- Aegis 遥测终端（神盾）：独立包 `com.hbmzz.aegis`，17 个新类，不复用旧监控屏一行代码，界面与配色全新设计
- 新增 `aegis_terminal` 主屏 + `aegis_cell` 边框，三页界面：概览（温度/稳定/能量/趋势）、组件（部件/燃料罐）、诊断（催化剂/功率/信号）；右键切页，潜行右键重置绑定
- 首次真实 `gradle build` 全量编译通过（JDK 8u512 + Gradle 4.10.3），BUILD SUCCESSFUL，Aegis 全部类已编入 jar
- 交付四件套一次做全：部署 mods、交付文档 `delivery-4.0.0.md`、发布页 changelog + 站点数据、群公告

关键变更
- 修复 24 个编译错误：Aegis 参数顺序/类型/import 4 组，4.0.0 原有代码 1.12.2 不兼容 API 4 组
- 网络消息 ID 29/30/31，独立配置 `hbmzz-aegis.cfg`，客户端走反射桥（专用服务端不崩）
- 打 v4.0.0 tag，站点版本已同步 4.0.0

注意
- 编译告警均为历史遗留（老 mapping / ForgeGradle），不影响构建
- 尚未推 GitHub（本地与远端历史分叉，待定）
- regcheck 150/150 全过

## 一、交付状态

已完成并部署。jar 已装入游戏 `mods` 目录，MD5 与构建产物一致。

| 项目 | 内容 |
| --- | --- |
| 模组包 | `hbmzz-4.0.0.jar`（23 MB） |
| 源码包 | `hbmzz-4.0.0-sources.jar`（8.6 MB） |
| 游戏目录 | `E:\pcl2\.minecraft\versions\DFC+RBMK 1.0.0\mods\hbmzz-4.0.0.jar` |
| MD5 | `15520702e7118dc2441e0f3176cd3805` |
| 旧包处理 | `hbmzz-3.9.0.jar` 已备份至 `backup-mods/hbmzz-3.9.0.jar.bak` 后替换 |

## 二、主要结果

### Aegis 遥测终端（全新实现，代号「神盾」）

基于 4.0.0 从零重建 DFC 监控屏，独立包 `com.hbmzz.aegis`，共 17 个新类，**未复用旧 `dfc_monitor` 任何代码片段**，界面与配色全部重新设计。

- **数据层**：`AegisTelemetry`（36 字段、对称序列化）、`AegisProbe`（反射探针，字段协议对齐第三方核心，代码全新）、`AegisConfig`（独立配置 `config/hbmzz-aegis.cfg`）。
- **方块**：`BlockAegisTerminal`（主屏，墙挂朝向）+ `BlockAegisCell`（填充边框）。
- **运行时**：`TileAegisTerminal`（服务端采样推送、NBT、绑定管理、历史趋势；无人观看时不做反射）。
- **网络**：`AegisDataPacket` / `AegisRequestPacket` / `AegisControlPacket`，占用消息 ID 29/30/31；客户端处理走反射桥 `AegisClientBridge`，专用服务端不加载客户端类。
- **渲染**：`AegisRenderer`（TESR）+ `AegisCanvas`（离屏画布）+ `AegisDraw` / `AegisFont` / `AegisTexture` / `AegisPalette`。

三页界面：概览（温度 / 稳定 / 能量 / 趋势曲线）、组件（部件计数 / 双燃料罐）、诊断（催化剂磨损 / 功率 / 信号完整性），含开机扫描动画与「NO SIGNAL」断流态（静默 5 秒后清空旧数值）。交互：右键切页、潜行右键重置绑定，核心半径内自动扫描。

### 首次真实编译验证

本机原先无 JDK 8，编译验证一直缺失。本次装妥工具链后完成真实 `gradle build`：

- JDK：Temurin 8u512-b09（`E:\jdk\jdk8u512-b09`，`javac 1.8.0_512`）
- Gradle：4.10.3（`E:\gradle\gradle-4.10.3`，wrapper 指定版本）
- 结果：**BUILD SUCCESSFUL**，18 个任务全部执行，Aegis 全部类已确认编入 jar。

## 三、关键变更

### 本次修复的 24 个编译错误（8 组根因）

Aegis 新代码（4 组）：
1. `AegisCanvas` 5 处 draw 调用实参与形参顺序颠倒（frame / d 对调）
2. `AegisDraw.bar` 的 `markerFraction` 形参 int → double（方法体按 0.0~1.0 判断，调用传 1.0D/-1.0D）
3. `TileAegisTerminal` 缺 `AegisDataPacket`、`IBlockState` 两个 import
4. `BlockAegisTerminal.getCollisionBoundingBox` 误用 `World`，1.12.2 正确签名为 `IBlockAccess`

4.0.0 原有代码（4 组，远端快照自带，非本次新增，但阻断整体编译）：
5. `GuiCodeBroadcaster` 把静态字段当类导入 `import java.util.Locale.ROOT` → 改用 `Locale` + `Locale.ROOT`
6. `BlockReactorComponent` 用了 1.12.2 不存在的 `BlockRotation` → 删除无实际作用的 rotate 空覆盖
7. `BlockReactorCore.getDrops` 缺 `IBlockAccess` import
8. `TileEntityReactorCore`：`offset(dx,dy,dz)` → `add(dx,dy,dz)`；`getRedstonePowerFromNeighbors` → `isBlockIndirectlyGettingPowered`

### 注册与接线

- `ModContent`：+2 方块、+2 物品、+2 配方（网格与既有方块不冲突）
- `HBMZZ`：加载 Aegis 配置 + 注册 TileEntity
- `NetworkHandler`：+3 网络消息
- `ClientProxy`：绑定 TESR，三张模型表（items / metas / itemModels）对齐
- 资源：blockstates / models（block + item）/ 中英文 lang

## 四、验证

| 检查 | 结果 |
| --- | --- |
| `gradle build`（JDK 8 真实编译） | BUILD SUCCESSFUL，18/18 任务 |
| Aegis 类编入 jar | 已确认（jar tf 列出全部 Aegis class） |
| regcheck | 150/150 通过（注册 / 配方 / 模型表一致） |
| 序列化 encode/decode | 36 字段收发对称 |
| 服务端 / 客户端隔离 | 服务端类无客户端依赖，仅反射桥用 `Class.forName` |
| jar 部署 MD5 | 构建产物与游戏目录一致 |

详见实现说明：`docs/aegis-terminal.md`。

## 五、注意事项

- 编译告警均为历史遗留：`snapshot_20171003` mapping 为 1.12 设计、ForgeGradle 2.3.4 已不受支持（不影响构建）；`BlockGiantPipe` 的 unchecked 警告（`rsaudio` 并入时的原样代码）。
- 本次为代码实现 + 首次编译验证 + 部署，未打 tag、未推 GitHub。
- 电表功率读取（诊断页 POWER METER）为可选扩展，当前核心监控功能完整，绑定入口预留。
- `HBMZZ-CORE` 仓库 GitHub 与本地均不存在；项目集群实际为 `HBMZZ-MOD` 与 `HBMZZ-MOD-addon`。

## 六、热修：Aegis 终端朝向（同日追加）

- **现象**：手持终端有贴图，放置到世界里却像「空气」——方块本体是 `EnumBlockRenderType.INVISIBLE`，画面完全由 TESR 绘制。
- **根因**：`AegisRenderer` 把屏幕四边形画在局部 **+Z** 面，却沿用了旧版「把局部 -Z 对齐 FACING」的角度映射（`yawOf`）。结果画面朝向反了 180°，埋进墙体背面，正面什么都看不见。
- **修复**：`yaw()` 改为 +Z 面映射——南 0° / 东 90° / 北 180° / 西 270°（+Z 绕 Y 轴旋转到对应朝向）。
- **验证**：`gradle build` 再次 BUILD SUCCESSFUL，新 jar 已重新部署进游戏 `mods`，MD5 `852b0040…cea2` 一致；部署前旧包备份为 `backup-mods/hbmzz-4.0.0.jar.pre-yawfix`。

## 七、热修：全中文界面 + 右键配置菜单 + 手动绑定（同日追加）

- **界面汉化**：`AegisCanvas` 全部屏显文案改为中文（三页总览/部件/诊断、堆芯温度、熔点、稳定度、组件计数、流体罐、催化剂磨损、功率、信号完整性、倒计时、开机/待机/无信号态）；`AegisProbe` 回传的 `EMPTY/UNKNOWN/NONE/GLITCH` 也改中文。字体 `AegisFont` 内置 CJK 探测（微软雅黑/黑体/Noto），中文正常显示。
- **右键配置菜单**：新增 `GuiAegisTerminal`，普通右键终端打开；提供「绑定最近核心 / 解除绑定 / 切换页面 / 电源开关 / 完成」五个按钮，窗口内实时显示连接状态、核心坐标、堆芯温度与当前页面。潜行右键保留为快速翻页。
- **手动绑定核心**：新增 `AegisSetupPacket`（网络 ID 32，C→S），四个动作（绑定最近/解绑/翻页/电源）全走服务端校验与决策；`AegisProbe.nearestCore` 在配置半径内找最近 `TileEntityCore` 并绑定。此前只能靠 16 格自动扫描，现在可手动指定。
- **proxy/方块接线**：`CommonProxy.openAegis` + `ClientProxy` 实现开窗；`BlockAegisTerminal.onBlockActivated` 改为普通右键开窗、潜行右键翻页；中英文 lang 的操作提示同步更新。
- **温度计量**：堆芯温度单位为摄氏度（°C），读自核心 `getDFCTemperature`，熔点默认 2250 °C；颜色按「当前温度/熔点」比例判定（见 `hbmzz-aegis.cfg` 的 `warnHeat`/`dangerHeat`）。
- **验证**：JDK 8 `gradle build` BUILD SUCCESSFUL，regcheck PASS，jar 已部署游戏 `mods`，MD5 `5de26701…d54d` 一致；旧包备份 `backup-mods/hbmzz-4.0.0.jar.pre-cn-gui`。

## 八、热修：自主排查修复 4 处 bug（2026-11-21 追加）

对整个 Aegis 包（17 类）逐文件复审，修复 4 处真实缺陷，均不影响 API / 存档格式 / 网络协议，纯行为修正：

1. **画面刷新被 4 秒节流卡死（最严重，用户可感知）**：`AegisCanvas` 每帧重绘开机动画 / 倒计时闪烁 / 扫描线 / 断流扫屏，但 `AegisTexture.upload` 里 `needsUpload` 置 false 后只有 `now - lastForced > 4000L` 才会再上传，而 `invalidate()` 无任何调用方——结果画面实际每 4 秒才刷新一次，所有动画变成 1/4 FPS，温度数字也每 4 秒才跳一次。改为按 16 ms 上限节流（`MIN_UPLOAD_INTERVAL_MS`），`invalidate()` 重置时间戳；动画流畅，仍能自愈 GL context 丢失。
2. **篡改状态在自动扫描模式下永远不显示**：`AegisProbe.sample` 把篡改查询的键传成 `boundCore`（手动绑定坐标），自动扫描时该值为 null，`stateFor(world, null)` 只返回全局 CUT， per-core 的 DISCONNECT/DEGRADED 全被吞掉。改为传实际读到的 `core.getPos()`，与 `HackService.touch(world, te.getPos())` 的登记键对齐。
3. **换存档泄漏 GL 纹理**：`ClientEvents.onWorldUnload` 清理了其他全部渲染器缓存，唯独漏掉 Aegis；WeakHashMap 只回收 Java 对象，`glDeleteTextures` 不会自动触发。补入 `AegisClientBridge.clearTextures()`。
4. **死代码 / 注释失真清理**：`AegisRenderer` 里 `fresh` 分支声称「清空旧数值」实为 no-op（无信号判定本就在 canvas 内按 worldTick 完成），删除该死分支；`AegisProbe` 类注释原称「成员查找按类缓存」，实现并无缓存，改为如实描述慢速轮询策略。

- **验证**：JDK 8 `gradle build` BUILD SUCCESSFUL（`compileJava` / `jar` / `reobfJar` 全执行）；新 jar 已部署游戏 `mods`，MD5 `e233655b70d3d75e6a112d2380df5e01`，与构建产物一致；旧包备份 `hbmzz-4.0.0.jar.pre-bugfix`（MD5 `5de26701…d54d`）。
- **未推 GitHub**：遵守约定，除非用户本人发出代理名 `XingZuo-Cloud Bot`，否则不推送。
