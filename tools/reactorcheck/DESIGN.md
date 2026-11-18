# HBMZZ 反应堆组件 — 设计与验证说明

## 0. 来源与许可边界

本组件在架构层面参考了开源项目 **GFBS**（`LytharaLab/GFBS-Main` 及其 LGPL-3.0 的
`G.F.B.S.-Mirage` 分支）的组织思路，但**没有复制其中任何受许可证约束的源码**。
GFBS 主仓目前只公开 README，反应堆代码尚未全面开源；因此本组件的全部逻辑、
数值和结构均为本项目独立编写，沿用 HBMZZ 自身的 GPL-3.0 许可与代码风格。

借鉴的是架构分层思想（见下表），不是实现：

| GFBS 的分层 | 本组件的对应 | 说明 |
|---|---|---|
| `api` | `com.hbmzz.reactor.api` | 对外控制面，调用者不接触物理实现 |
| `objects` | `ReactorState` / `ReactorSpec` | 不可变领域模型 |
| `ccio` | `ReactorInput` / `ReactorOutput` | 外部信号在边界归一化后进入 |
| `Phenomenon` | `ReactorOutput` 的派生量 | 表现数据由状态算出，不单独存储 |
| `ServerConfig` | `ReactorSpec` | 数值外置、可校验、可替换 |
| 逻辑/表现分离 | 纯函数核心 + `TileEntityReactor` | 世界实体只做翻译，不做决策 |

## 1. 模块边界

```
com.hbmzz.reactor                 纯函数核心（零 Minecraft 依赖）
├── ReactorPhase    状态枚举 + 合法转移表
├── ReactorSpec     全部物理参数，Builder 带校验
├── ReactorInput    输入端口，构造时 clamp
├── ReactorOutput   输出快照（Phenomenon 层）
├── ReactorState    不可变状态
├── ReactorRules    物理公式 + 安全规则（纯函数）
└── ReactorMachine  单 tick 编排 + 观察 + 批量推进

com.hbmzz.reactor.api             对外门面
├── ReactorControl  接口
└── SimpleReactor   参考实现，持有可变句柄

com.hbmzz.reactor.tile            游戏适配层
└── TileEntityReactor  红石 <-> 输入/输出，NBT，同步

com.hbmzz.reactor.block
└── BlockReactor     方块，朝向，红石输出
```

与现有 `com.hbmzz.rbmk` **完全隔离**：`rbmk` 模拟一次特定的 RBMK 事故（顶盖抛射、
二次爆炸），本组件模拟任何堆芯的正常运行周期。两者不互相 import。

依赖方向严格单向：`tile/block → api → 核心`。核心不反向依赖任何游戏类。

## 2. 输入输出契约

### 输入 (`ReactorInput`，构造时归一化)

| 字段 | 范围 | 含义 |
|---|---|---|
| `rod` | 0..100 | 控制棒插入百分比；0 全抽出（满反应性），100 全插入（停堆） |
| `coolant` | 0..1 | 冷却阀开度 |
| `fuel` | boolean | 是否有燃料；无燃料则链式反应归零 |
| `start` | boolean | 上升沿启动请求 |
| `scram` | boolean | 紧急停堆请求，优先级最高 |

NaN 的控制量一律取安全极值（棒全插、阀全关），不会变成 0 而被误读成运行命令。

### 输出 (`ReactorOutput`，每 tick 派生的只读快照)

`phase` / `flux`（0..额定制）/ `heat`（度）/ `power`（0..额定输出）/ `steam` /
`scramming` / `melted`。输出不存盘，读档时重算——避免存档里的过期读数欺骗玩家。
`redstoneLevel()` 把功率映射成 0..15。

### 方块上的红石接线

- **背面**：控制棒（0=全抽出，15=全插入）
- **左侧**：冷却阀（0..15 → 0..1）
- **右侧**：命令线，一个 tick 高电平=启动，持续高电平=SCRAM（轮询边沿，与
  `TileEntityNukeController` 一致，保证方块加载到已通电位置也能看到边沿）
- **正面**：功率输出（0..15）

## 3. 状态变化（状态机）

```
OFFLINE ──start+燃料──> STARTING ──flux≥runningBand──> RUNNING
   ^                       │                              │
   │                       └────────scram/超温──────────────┤
   │                                                       ▼
   └────冷却到阈值一半且满 scramRecoverTicks──── SCRAM <─────┘
                                                  │
                                           heat≥meltHeat
                                                  ▼
                                               SPENT（终态，不可逆）
```

合法性由 `ReactorPhase.canTransitionTo` 约束：SPENT 无出口；SCRAM 必须先回
OFFLINE 才能再次启动，杜绝未冷却就重新点火。

## 4. 核心规则（物理）

1. **目标通量**：`额定通量 × 反应性(棒位) × (1 − 热量×反馈系数)`，三重门控
   （棒位/燃料/停堆），并钳制到 0..额定。温度反馈是负的——越热要求越低，
   这是反应堆可控的根本原因。
2. **通量响应**：一阶滞后逼近目标；SCRAM 时用满速率，把功率快速压到零。
3. **热平衡**：`热 += 产热(通量) − 冷却(流量) − 环境散热(热量)`。冷却在平局时
   获胜；SCRAM 强制全开冷却阀。
4. **输出功率**：仅活动且非 SCRAM 时按通量比例输出。
5. **停堆优先级**：熔毁 > 显式/超温 SCRAM > 正常运行转移。同一 tick 内先判定
   是否 SCRAM 再算通量，避免"跳闸的那一 tick 还在满功率产热"导致误熔毁。

## 5. 边界处理

- 输入 clamp + NaN 安全降级；`ReactorState` 构造时对 flux/heat/produced 做
  finite + 非负钳制，杜绝 NaN 污染后续 tick。
- `ReactorSpec.Builder` 拒绝非有限数、非正数、scram 线≥熔毁线、release 带高于
  running 带等非法配置。
- 热量上限为 `4×熔毁线+1`，防止病态参数溢出成 Infinity。
- null 输入/状态/规格全部退化为安全值，不抛异常到游戏线程。
- 熔毁状态在 `ReactorMachine.step` 中直接返回同一不可变实例，永不重启。

## 6. 验证标准

`bash tools/reactorcheck/run.sh`（需 JDK8，`JAVA_HOME` 指向可用 JDK）运行 **53 项**
离线断言，覆盖：规格校验、输入钳制、物理（反馈/滞后/热平衡/功率）、状态机
（启动/升功率/回落/不可逆）、SCRAM（超温自跳、优先级、降温、不可重启）、
边界（null/NaN/易熔规格）、API（恢复/门面）。**当前结果 53/53 通过。**

`python tools/regcheck/check.py` 静态核对声明与注册一致（方块/物品/TE）。

尚未执行（需构建/真机）：gradle 全量构建、放进存档验证红石接线与 NBT 持久化、
装/不装 HBM 诅咒版两种环境下的共存。这些属于游戏层验证，不在离线检查覆盖范围。

## 7. 如何运行

```bash
# 离线逻辑验证（无需游戏、无需 gradle）
JAVA_HOME="D:/DSH/toolchain/jdk8/jdk8u512-b09" bash tools/reactorcheck/run.sh

# 注册清单静态检查
python tools/regcheck/check.py
```

方块在创造模式「红石」分类，名为「HBMZZ 反应堆堆芯」。
