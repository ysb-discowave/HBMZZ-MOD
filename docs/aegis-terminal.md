# Aegis 遥测终端 —— 从零重写的 DFC 监控屏

本目录（`src/main/java/com/hbmzz/aegis/`）是一套与旧 `dfc_monitor` 完全独立的监控屏实现。
所有代码为本次新写：自己的数据模型、反射探针、方块、方块实体、网络协议、渲染器与界面，
不复用、不改写旧版任何类的代码片段。视觉布局、配色和交互也全部重新设计。

## 交付内容

### Java（新包 `com.hbmzz.aegis`）
| 文件 | 职责 |
|---|---|
| `AegisTelemetry.java` | 遥测帧数据模型 + Netty 序列化；自带 64 槽历史环与页数常量 |
| `AegisProbe.java` | 反射读取第三方 DFC 核心/电表，产出不可变快照；成员查找带缓存与并发保护 |
| `AegisConfig.java` | 独立配置文件 `config/hbmzz-aegis.cfg` |
| `BlockAegisTerminal.java` | 主屏方块（墙挂朝向、薄板碰撞、右键切页、潜行右键重置绑定） |
| `BlockAegisCell.java` | 无状态的边框填充块，借既有合法纹理避免紫黑 |
| `TileAegisTerminal.java` | 方块实体：服务端采样推送、客户端历史/开机计时、NBT 只存持久设置 |

### Java（客户端 `com.hbmzz.aegis.client`）
| 文件 | 职责 |
|---|---|
| `AegisCanvas.java` | 三页界面（概览 / 组件 / 诊断）+ 开机动画 + 信号丢失，纯 AWT 绘制 |
| `AegisDraw.java` | 绘图原语：矩形/框/线/圆/文字/自适应缩放/进度条/刻度/折线/扫描线 |
| `AegisPalette.java` | 统一 ARGB 配色与告警色阶 |
| `AegisFont.java` | 等宽/中文字体探测与缓存 |
| `AegisTexture.java` | 自有 OpenGL 纹理，按需上传 BGRA 像素 |
| `AegisRenderer.java` | TESR：离屏画布贴到朝向方块正面的四边形，含边框与满亮钉扎 |
| `AegisClientBridge.java` | 仅供物理客户端加载的包处理桥（网络包经反射调用） |

### Java（网络 `com.hbmzz.network`）
| 文件 | ID | 方向 |
|---|---|---|
| `AegisDataPacket.java` | 29 | 服务端 → 客户端，一帧遥测 |
| `AegisRequestPacket.java` | 30 | 客户端 → 服务端，缺数据时补拉一帧 |
| `AegisControlPacket.java` | 31 | 客户端 → 服务端，切页/电源（预留给 GUI 扩展） |

### 资源
- `blockstates/aegis_terminal.json`、`aegis_cell.json`
- `models/block/aegis_terminal.json`、`aegis_cell.json`（借 `dfc_monitor` / `control_panel` 合法纹理）
- `models/item/aegis_terminal.json`、`aegis_cell.json`
- `lang/en_us.lang`、`zh_cn.lang`：方块名与操作提示
- 合成：终端 = 铁锭/玻璃/红石/比较器；边框 = 铁锭/玻璃

### 接入点（在既有文件中新增，未改动原逻辑）
- `HBMZZ.java`：加载 Aegis 配置、注册 `TileAegisTerminal`
- `ModContent.java`：声明/注册 2 方块 + 2 物品、2 条配方
- `NetworkHandler.java`：注册消息 29/30/31
- `ClientProxy.java`：绑定 TESR；`items()/metas()/itemModels()` 三表各补 2 项

## 数据流

服务端每 `pollTicks` 采样一次，只给 `viewRange` 内的玩家推帧；无人观看时零反射开销。
客户端把帧存进方块实体，折叠进三条历史环（温度/能量/稳定度），渲染器每帧把最新帧画进
离屏画布并上传到纹理。数据静默超过 5 秒显示「NO SIGNAL」，不残留旧数值。

## 交互
- 右键主屏：循环切换 概览 / 组件 / 诊断 三页
- 潜行 + 右键：清除显式绑定，回到半径内自动扫描核心
- 核心在读数为 `heat`（温度）、`field`（塌缩倒计时）、`tanks`（燃料）、`inventory`（核心/催化剂）等第三方字段，
  方法名与字段名为对接 HBM/诅咒版所必需的协议，代码本身全新实现

## 编译与校验说明
- 目标：Forge 1.12.2 / 14.23.5.2847，Java 8。
- 本机未安装 JDK，`gradlew build` 无法在此环境执行。
- 已用项目自带 `tools/regcheck/check.py` 全量通过（150/150，含注册/配方/模型表一致性），
  并做括号配对、包路径、跨类符号与服务端/客户端类隔离的静态核对，均通过。
- 交付前请在有 JDK 8 的环境跑一次 `gradlew build` 做编译确认。
