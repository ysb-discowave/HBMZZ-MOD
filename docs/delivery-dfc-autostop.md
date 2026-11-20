# DFC 自动停堆 / 强制抑制 —— 交付说明

## 术语（既定，未改写、未扩展）

- **HBM**：HBM's Nuclear Tech。环境为 HBM 社区版 + 诅咒版；"诅咒版可嘉"为既定设定，原样保留。
- **DFC**：HBM 模组中的暗物质反应堆（Dark Fusion Core）。
- **停堆**：正常 / 待机状态。
- **自动停堆**：DFC 温度超过 5000℃ 且正在破坏建筑时立即进入，不存在任何中间等待状态。
- **强制抑制**：逐步降温后温度仍高于 5000℃ 时进入；先播放一段明确的特效过程，特效结束时温度立即降为 0 —— 既不是持续降温，也不是无过程的直接归零。

## 改了什么

1. 新增纯逻辑状态机 `DfcAutoStopPlan`（`com.hbmzz.dfc`）：
   - 触发：`温度 > 5000℃ && 正在破坏建筑`，立即 `STOPPED -> AUTO_STOP`。
   - 逐步降温：每 tick 降 `stepC`（默认 250℃），最多 `rampTicks`（默认 100）tick。
   - 强制抑制：降温结束仍 `> 5000℃` 则进入 `FORCE_SUPPRESS`；特效播放 `suppressFxTicks`（默认 60）tick 期间完全不写温度，特效结束那一 tick 一次性写 0，随后回到停堆。
   - 对外文案只有三个词：`displayState()` 返回 停堆 / 自动停堆 / 强制抑制。

2. `DfcAutoShutdown`：新增按核心保存的温度驱动流程，与原有倒计时降温流程并存、互不干扰；温度流程运行时独占温度写入。进入自动停堆时经 rsa 的 `RemoteAudio.playAt` 播放配置曲目（默认 `passion`），并放一次内爆特效；强制抑制期间逐 tick 放收紧特效，结束时归零并清除坍缩标志。

3. 统一显示：`DFCSnapshot` 增加 `stopState` 字段随网络下发，监控屏核心页画一条高亮状态带；所有显示面（方块面 / 终端 / HUD / 窗口）共用这一个数据源，不会各说各话。

4. 文案：`zh_cn.lang` / `en_us.lang` 的自动停机相关条目改为自动停堆 / 强制抑制表述，移除"自动停机"旧说法。

5. 特效：`DfcFxMessage` 新增 `SUPPRESS` 模式，`ClientDfcFx` 绘制收紧粒子壳。

## 配置项（`config/hbmzz.cfg` 的 `[dfcShutdown]` 段）

- `autoStopTempC`：触发温度，默认 5000。
- `autoStopCoolPer2sC`：每两秒通过指令强制降低的度数，默认 1000（换算 25℃/tick）。
- `autoStopHardZeroC`：紧急熔断温度，默认 10002（达到即立即通过指令写 0，不等待任何过程）。
- `autoStopRampTicks`：逐步降温阶段最长 tick，默认 100。
- `autoStopSuppressFxTicks`：强制抑制特效时长，默认 60（特效结束后温度立即归 0）。
- `autoStopMusic`：进入自动停堆时播放的音频文件名（不含 .ogg），默认 `passion`；文件需放在 `config/rsaudio/audio/passion.ogg`。

## 关于"两台监控屏改单台"

当前代码中每个监控屏方块（`TileEntityDFCMonitor`）本就是**单屏单核心**：只有一个 `corePos` 绑定，不存在"双屏 / 双核心"布局或状态位。"两台"只出现在上一轮自动停机缺陷的回归场景里——两台监控屏盯同一核心、关掉一台不应影响另一台，那属于必须保留的会话归属修复，本次没有撤销。本次把状态读取与显示统一到单一 `DFCSnapshot.stopState`，即每台监控屏都只从这一个状态源显示停堆状态。

## 验证

- JDK8 离线完整编译：BUILD SUCCESSFUL。
- dfccheck：56/56（原 43 + 新增 13 项，覆盖触发阈值、立即进入、无停顿/暂停、逐步降温、特效期间不降温、特效结束瞬时归零、降回阈值下不进抑制）。
- regcheck：160/160（新增 6 项防回退，锁定术语、阈值、强制抑制时序、passion 播放、状态单一数据源、文案统一）。
- checkall 中依赖 WSL 的若干脚本在本机因未安装 WSL 而跳过，与本次改动无关。
- 官网版本号 / 日志本次未动：这是缺陷 / 功能改动、未发新版号，按"每次发版才同步官网"的约定保持不动。
