# 交接文档：HBMZZ AI 管理系统（AI 控制节点体系）

> 面向接手的子代理/开发者。仓库：`C:\Users\Administrator\_hbmzz_400`（branch `master`）。
> 生成日期：2026-11-22。用户风格：直言不讳，要求先验证再动手，别重做已完成的事。

## 一、系统是什么

Minecraft 1.12.2 Forge 模组内的"AI 管理系统"：玩家放置 **AI 控制节点**方块，它轮询采集全图机器数值（DFC 核心/稳定器/接收器/发射器、温度、稳定度等），通过 HTTP 发给大模型（JSON 协议），AI 返回裁决后：

1. **全图代码显示器**显示状态面板（AiDisplay）
2. **全图音频广播**：TTS 语音播报异常（走 rsaudio 内嵌 Piper 引擎）+ 聊天行
3. **执行动作**：AI 可直接反射控制 DFC 稳定器/接收器/发射器、启停核心（DfcControl/AiAction）
4. **玩家交互**：AI 终端物品（GUI）可直接向 AI 发自然语言指令、配置个人提示词

## 二、文件清单（全部在 `_hbmzz_400`）

### 核心 `src/main/java/com/hbmzz/ai/`
| 文件 | 职责 |
|---|---|
| `AiConfig.java` | 全局配置 + `hbmzz-ai.cfg` 持久化 |
| `AiClient.java` | HTTP JSON 请求/响应；Verdict 解析（顶层/嵌套键深度感知 `findValue`）；prompt 注入 |
| `AiPrompt.java` | 提示词层：预设（DEFAULT_PRESET=balanced）+ 全局自定义 + 玩家个人覆盖 + 机器数值占位符展开 |
| `AiBroadcast.java` | 异常广播编排：display→TTS→聊天行；3s 冷却；selfTest |
| `AiDisplay.java` | 写 FacilityState 全图代码显示器 |
| `AiMachineProbe.java` | 采样机器数值（temperatureC/stabilityPct/组件数） |
| `AiAction.java` | actions 数组解析 + opcode 归一化 |
| `DfcControl.java` | 反射控制 NTM DFC 组件与核心 |
| `AiAutopilot.java` | 兜底 SCRAM，**受 autoScram 开关门控（默认 false）** |
| `AiTerminalService.java` | 终端本地命令 + 自然语言转发 + 个人提示词；MAX_INPUT=2048（勿动，黑客平板的 MAX_LINE=120 是另一回事） |
| `TileEntityAIControlNode.java` | 节点 TE，轮询循环 |
| `BlockAIControlNode.java` | 方块 |
| `CommandAI.java` | `/ai` 指令：status/tts/endpoint/model/prompt/开关等 |

### TTS 链 `src/main/java/com/rsaudio/`
- `tts/TtsEngine.java`：Piper 子进程引擎。`discover(File ttsDir)` 探测 `piper.exe`+`.onnx(.onnx.json)`；`synthesize(...)` 阻塞合成 WAV（长选项 `--model/--output_file/--length_scale/--speaker`）
- `util/TtsServer.java`（在主 mod 的 rsaudio 包内）：服务端单线程合成 → `TtsCache` → 服务端线程 16KB 分片下发
- 网络包 `PacketTtsBegin/Chunk/End`；客户端 `TtsClientPlayer` + `AudioReassembler`

### 终端 UI
`com/hbmzz/item/ItemAITerminal.java`、`com/hbmzz/client/GuiAITerminal.java`、`com/hbmzz/network/AiCommandMessage.java`/`AiReplyMessage.java`（NetworkHandler ID **35/36，新增包须避开已占 ID**）。

## 三、配置（hbmzz-ai.cfg，`/ai` 可改）

`endpoint`、`apiKey`、`model="FAAS-REMIX"`、`promptPreset`、`prompt="不要擅自停止反应堆，不要擅自止损，只做报告，等待指令"`（默认）、`ttsModel`、`pollSeconds=5`、`httpTimeoutMs=8000`、`lengthScale=1.0`、`autoBroadcast=true`、`autoDisplay=true`、`autoControl=true`、**`autoScram=false`**、`autoScramTemp=2800`、`autoMinStability=35`、`enabled=true`

## 四、HTTP JSON 协议

请求：`{model, command, prompt, machine, values}`
响应：`{abnormal, message, reason, level, display{...}, actions[...]}`
- `display` 渲染到全图显示器；`actions` 经 AiAction 归一化后由 DfcControl 执行
- 解析容忍顶层/嵌套同名键（`findValue` 深度感知）

## 五、已知事实（别踩坑）

1. **`ttsModel` 只是标签字段**，从不传给引擎；实际音色取决于服务端 TTS 目录安装的 `.onnx`（默认 zh_CN-huayan-medium）。`/ai tts` 和 `/ai status` 文案已如实标注"固定走 rsaudio 内嵌 Piper"。
2. **AI 语音链路已走 rsaudio 内嵌 TTS**：`AiBroadcast` → `TtsServer.speak` → `TtsEngine`(Piper)。不要重复接入。
3. `autoScram` 默认关闭是有意设计：默认提示词要求 AI"只报告、等待指令"，本地兜底 SCRAM 不能擅自停堆。
4. ClientProxy 的 models/items/metas 三张表长度必须相等（曾因 ai_terminal 补 0 修过一次）。
5. JDK8 按 GBK 读源码：编译必须 `-encoding UTF-8` + `-Dfile.encoding=UTF-8`；编译日志错误行用 python 按 gbk/utf-8 解码提取。

## 六、待办（按用户最新意图排序）

### 1. 外部 TTS 接入（用户明确说"我要使用外部的tts"，尚未实施）
- 目标：AI 语音可选**外部 TTS 服务**（HTTP 调用，用户自配端点），内嵌 Piper 作为回退
- 建议方案：`AiConfig` 加 `ttsMode`（`embedded`/`external`）+ `ttsEndpoint`/`ttsApiKey`；`TtsServer.speak` 前按 mode 分流；外部模式返回音频后仍走现有 16KB 分片下发链路（复用 PacketTtsBegin/Chunk/End，注意把外部返回的 mp3/wav 统一转 PCM 或直接透传 + 格式标记）
- `/ai tts` 指令同步扩展；`ttsModel` 标签字段可顺势改成真实配置
- **先问用户外部 TTS 是什么服务/什么协议**，别猜接口

### 2. 真机测试（用户问过"是否能测试"，未答）
- 所有 AI 功能编译/单测通过，但**未进游戏实测**；DfcControl 反射钩子是否命中取决于所装 NTM/CE 分支版本
- 测试路径：放置 AI 控制节点 → `/ai endpoint` 配置可用大模型端点 → selfTest → 观察显示器/TTS/DFC 动作

### 3. 版本管理
- 工作区有未提交改动（README/docs 等），AI 相关改动也已编译通过但**未提交未推送**；发版需用户确认

## 七、构建与验证流程

```bash
export JAVA_HOME=/d/DSH/toolchain/jdk8/jdk8u512-b09
cd /c/Users/Administrator/_hbmzz_400
./gradlew compileJava --offline --no-daemon -Dfile.encoding=UTF-8
# 静态检查：regcheck（150 项）、checkall（7 项）
# 单测套路：AI JSON 解析 8 项、显示指令 23 项、actions 20 项、提示词 23 项
```
- Gradle 4.10.3 / ForgeGradle 2.3，必须完整 JDK8（JRE 没有 tools.jar，跑不了）
- 系统 JAVA_HOME 是坏的（指向不存在的 jdk-21），每次手动设

## 八、验证清单（接手后自查）

- [ ] `compileJava` 通过（UTF-8 编码）
- [ ] regcheck / checkall 通过
- [ ] `/ai status` 输出如实（TTS 标签 + 引擎就绪状态）
- [ ] 改 TTS 相关代码后确认 mix 分流不影响内嵌 Piper 原链路
- [ ] 不新增 NetworkHandler ID 冲突（35/36 已占）
