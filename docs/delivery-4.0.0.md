# HBMZZ 4.0.0 交付文档

> 交付时间：2026-11-20
> 基准版本：4.0.0（Forge 1.12.2，ForgeGradle 2.3.4，Java 8）
> 本次主题：**Aegis 遥测终端（DFC 监控屏从零重写）** + 首次真实编译验证

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
