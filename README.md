# HBMZZ

> 给 HBM 的核科技装一块真的显示器，再顺手给它配齐一整套核电站、安保、电影和娱乐设施。

HBMZZ 是一个 Minecraft **1.12.2** + **Forge** 模组，依附于 **HBM's Nuclear Tech 社区版（NTM:CE）** 和 **Leafia 的诅咒版附属（NTM Cursed Addon）**。它做的事情可以分三大块：

1. **让核反应堆"看得见"**：一块能实时显示 DFC 核反应堆完整仪表盘的大屏，配一个可缩放的终端、一个能自定义页面和报警逻辑的面板工坊，以及一套驱动物理机器的控制面板和节点图控制台。
2. **让核事故"拍得出来"**：核弹、事故代码广播、爆炸传感器、自动停机、塔塔鲁斯大门、RBMK 过热事故，外加一个 DFC 塌缩时的电影级机位（DFC-TSTX）。
3. **给基地配齐"设施"**：内嵌的 **Redstone Audio（红石音频）** 模组带来音频播放器、六色警报灯、防核门、防爆窗帘、广播器、DMR / F9 两种反应堆、电梯、列车、监控、门禁、气闸、起重机、货运卡车，一直到纪念挂画。

同一个 jar 里还带了枪械、三种线圈和喷气背包这些 Roblox 风格的小玩意。下面是**全部玩法和部件**的清单与玩法说明。

---

## 怎么装、需要什么

| 项目 | 要求 |
| --- | --- |
| Minecraft | 1.12.2 |
| 加载器 | Forge（对应 1.12.2） |
| 前置模组 | HBM's Nuclear Tech 社区版（NTM:CE） |
| 可选附属 | NTM Cursed Addon（诅咒版附属，机器控制台/节点图需要） |
| 本模组 | HBMZZ 4.0.0（内含 Redstone Audio 1.0.0，二选一，无需单独再装） |

把 HBMZZ 的 jar 放进 `mods` 文件夹即可。它自带第二个模组容器 `rsaudio`，所以旧存档里的红石音频方块 id 不会因为换模组而错位。

> 视频、音乐是**每台客户端各自读取**的：视频放各自客户端的 `config/hbmzz/videos/`，音乐放 `config/rsaudio/audio/`。服务端不存素材，玩家各自准备。

---

## 一、看反应堆：DFC 监控三件套

这是整个模组的招牌玩法。把 DFC 核反应堆装到地上，再用下面三样东西把它的状态"投影"出来。

### DFC 监控屏（DFC Monitor）
- **右键**打开 **DFC 监控终端**：可以拖右下角、滚轮或双击标题栏缩放，按 `R` 复位。
- 屏幕**正面**会实时整面渲染完整的仪表盘，准星对准就能看到，不用打开 GUI。
- 屏幕左列底部那个按钮可以调**墙屏尺寸**（最大 4×3），按钮下方是**面板工坊**。

### 面板工坊（Panel Studio）
- 用来自定义监控屏的**第 4 页**：放哪些控件、怎么摆布局、写什么报警逻辑。
- 逻辑是"**当 …… 若 …… 则 ……**"三段式，配好之后反应堆一异常，屏幕上就按你的设定亮起来、跳数字。

### 监控摄像头（Monitor Camera）
- 摆在你想看的角度，用**监控数据线**右键记录它，再右键监控屏，屏幕就自动**切到第 4 页**显示这个摄像头画面。潜行右键可断开。
- 摄像头是"看反应堆内部"的眼睛，频道、朝向都可以调。

### 监控数据线（Monitor Link Cable）
这是把所有屏幕、机器、摄像头"接起来"的那根线，一把多用途工具：
- 右键**功率计 / DFC 核心 / 监控摄像头** → 记录坐标；再右键**监控屏**完成绑定。
- 右键**可控机器**记录后，手持它右键**机器控制面板**开窗，潜行右键直接绑进空槽位（面板六个槽位全满会提示先清空一个）。
- 机器距离上限 24 格，太远的机器绑不上。

---

## 二、控机器：控制面板 + 节点图控制台

### 机器控制面板（Machine Control Panel）
- 通过 **IControllable 协议**直接驱动 NTM 里的物理机器，面板上的每个控件对应一台机器的一个可写参数。
- **空手右键**按下面板上的控件；面板本身还能用红石节点去驱动红石信号，做"看仪表盘→联动红石"的自动化。
- 六个槽位，对应六台机器，用监控数据线绑。

### 机器控制台（Machine Control Console）
- 一块**节点图**控制台：把控件、事件、变量和节点逻辑拖出来连成图，比面板更自由。
- 控件、事件、变量与节点逻辑移植自 NTM: Cursed Edition，节点图控件多达二十余种，节点三十余种（完整清单见 [参考手册](docs/reference.html)）。
- 手持**监控数据线**右键打开布局编辑器；"建立连接"页也能吃多发起爆器的记录。

### 黑客平板（Hacker Tablet）
- **右键打开终端**，这是全模组里唯一能"绕过控制棒直接写字段"的工具，所以做得很贵。
- 先 `target x y z` 锁定一台机器，再 `set 字段 数值` 直接改写它的内部字段。
- 可以"入侵"机器：短暂断连、降级、切断，配合事故玩法用（可配是否启用）。

---

## 三、拍视频：视频广播系统

### 视频广播器（Video Broadcaster）
- 一个"播放器"方块。给红石**上升沿**就触发：被选中的那个玩家的屏幕会被接管、播放视频。
- 画面读自**每台客户端自己的** `config/hbmzz/videos/`（放 `.mp4` 后在广播器窗口导入）。

### 视频大屏（Video Screen）
- 一块墙上的大屏。
  - **右键**：在屏正中开一台电视（`F` 全屏 / 再按 `F` 回窗口）。
  - **Shift + 右键**：菜单（进度条 / ±5s / ±30s / 暂停 / 回开头 / 尺寸）。
- 接法：先用**大屏遥控器**右键播放器对准它，再右键大屏把这块大屏接上。
- 播放 / 停止由播放器上的**红石拉杆**控制。

### 大屏遥控器（Screen Remote）
- 只能连接大屏、不能放置。右键播放器对准，再右键大屏完成连接。

---

## 四、拍事故：核事故系统 + 电影

### 核弹（Nuclear Device）
- **全图只允许放一台**。红石信号一到就起爆。
- **威力 6 档可调**：拿**斧子右键（不蹲）**核弹循环切换——试验 / 战术 / 战略 / 重型 / 破芯 / **毁档**。最高"毁档"= 设施毁灭级（DELTA 代码），**一炸抹平整座设施**：先来一发大爆炸，随后一道湮灭波逐 tick 清掉半径内所有已加载区块的方块和实体——连地形一起从存档里擦掉，只剩基岩底板。这不是大号弹坑，是整个基地消失。
- **引信 6 档可调**：**空手蹲下右键**核弹循环切换——5 / 15 / **30（默认）** / 60 / 120 / 300 秒。倒计时比原来（5 秒）拉长了，默认给足撤离时间。
- 拿**斧子蹲下右键**核弹记录坐标，再蹲下右键**核弹信号控制器**或**核弹监视屏**完成联动；蹲下右键正在倒计时的核弹可以中止。
- 倒计时中不能调档，先中止再改。档位存进核弹自己的 NBT，重启不丢。
- 初始档位可在 config 的 `powerTier` / `fuseTier` 改；爆炸力上限 `blastCap` 默认 120，往下调能缩小弹坑。
- 湮灭波范围 `annihilateRadius` 默认 512 格，`annihilateBlocksPerTick` 默认 65536 格/tick（觉得掉帧就调小，抹得慢但稳），`annihilateEntities` 控制是否连实体一起清，`annihilate=false` 可把毁档降级成单纯的大爆炸。只清**已加载**区块，未加载的区块不会被强制载入（那是引擎限制，强载会把整个存档拖进内存）。

### 核弹信号控制器（Nuke Signal Controller）
- 接到核弹上，红石信号控制起爆 / 中止，状态会回显到监视屏。

### 核弹监视屏（Nuke Monitor）
- 实时显示核弹状态：`状态 / 威力档 / 当量 kT / 引信档 / 控制器 / 设施代码`；毁档会用红色高亮。

### 代码广播器（Code Broadcaster）
- 预设一套**设施事故代码**（不同颜色、不同级别），给红石信号就全图广播。
- 支持"自定义状态模式"，可以广播任意的状态文字。

### 事故显示器（Accident Display）
- 收到事故代码后亮起来、显示当前设施状态，靠近它的玩家会被同步进事故画面。

### 状态提示灯（Status Lamp）
- 把某个条件（事故级别、机器状态等）转成一盏灯，方便远程看"现在到底出事了没有"。

### 代码旋转警报灯（Code Alarm Beacon）
- 事故代码级别**高于设定安全级别**时才会点亮并旋转。默认 2 级（橙色）起才醒——红色或更糟才转。

### DFC 爆炸传感器（DFC Blast Sensor）
- 检测到**同维度内**某处 DFC 核反应堆炸了（爆炸发生在堆芯上方才算，避免基地里别的核弹误触发），就输出红石。
- 信号强度默认 15，可配检测范围、保持时长、衰减时长；设成 0 范围 = 不限距离。

### DFC 自动停机（DFC Auto Shutdown）
- 挂在监控屏上：先绑定一台 DFC，再开自动停机。反应堆开始**坍缩**时自动降温、最后一分钟自动收回，减少爆炸。没绑定 DFC 或绑定位置没有堆芯时保持关闭。

### DFC-TSTX（电影机位）
- 一个强制的电影导演：DFC 堆芯**刚进入塌缩**的瞬间，镜头会被接管，做一段约三秒的环绕轨道拍摄。
- 规则名就叫 `DFC-TSTX`，第一次进这个世界时自动注册；两个导演不会抢同一个镜头。

### 塔塔鲁斯大门（Tartarus Gate）
- 一座巨大的结构门，需要 **26×26 的空位**。
- 红石供电时锁定手动开关；用**塔塔鲁斯大门结构板**搭建框架。

### RBMK 过热事故
- RBMK 堆芯过热会触发**盖体抛飞 → 落地二次爆炸**的事故链，伴随相机震动、热区地面扩散环。
- 可配是否触发连锁、过热温度、二次爆炸威力、是否震屏。

### AI 控制节点（AI Control Node）
- 把节点用 `/ai bind` 绑定到任意机器（DFC 堆芯走专用遥测，其他方块读 NBT 数值）。节点定期把读数发给 AI 接口（`FAAS-REMIX`），由 AI 判断数值是否异常。
- 异常时三件事一起发生：**TTS 全图语音广播**（reasim 可处理该音频）、**全图显示器同步告警**、**聊天与音频播放器脉冲**。
- AI 可**直接操作全图代码显示器**：判定里带 `level`（`RED`/`DELTA`/0–7）即可让所有事故显示器/代码屏显示对应代码；还可带 `display` 对象自定义等级、标签、RGB 颜色与中英文标题/详情，未被覆盖的字段回退到标准代码。判定持久化的 `T+` 计时正常增长，同内容不会重复刷屏。
- AI 判定里不写 `display` 也能工作：直接用 `level` 映射标准代码，缺省按 `RED`。
- 手动覆盖（管理员）：`/ai display on|off` 开关 AI 自动显示器控制；`/ai show <代码> [文字]` 立即向全图显示器推送；`/ai clear` 恢复 GREEN。`/ai status` 显示当前开关。

> AI 接口协议：请求 `{"model":"FAAS-REMIX","prompt":"...","machine":{...},"values":{...}}`，返回 `{"abnormal":true,"message":"...","reason":"...","level":"RED","display":{"level":3,"label":"AI 警报","rgb":16727770,"zhTitle":"...","enTitle":"...","zhDetail":"...","enDetail":"..."}}`（`display` 整段可选，颜色也接受 `0xRRGGBB`/`#RRGGBB`）。`prompt` 由行为预设 + 自定义提示词拼成，可含占位符（见下）。

### 自定义 AI 提示词（不同效果）
- 每条 AI 请求都带 `prompt` 字段，由三层拼成：固定的**回复格式契约** + **行为预设** + **自定义文字**。契约保证无论提示词怎么写，回复仍是 mod 能解析的 JSON。
- **行为预设**（`/ai preset <名称>`）：`balanced` 均衡（默认）、`safety` 安全优先（早报警早停堆）、`power` 功率优先（容忍高温）、`diagnostic` 只诊断（绝不输出 `actions`）、`quiet` 静默（只报严重状态）、`verbose` 详细（中英文详情齐全）、`zh` 中文播报、`en` 英文播报。管理员用 `/ai presets` 查看全部。
- **全局自定义**：`/ai prompt <文字>` 在预设后追加自定义指令，影响所有节点与玩家；`/ai prompt clear` 清除。
- **默认提示词**：出厂即为「不要擅自停止反应堆，不要擅自止损，只做报告，等待指令」——AI 默认只报告异常、不主动停堆或止损，等玩家下指令再动作。想恢复通用默认值用 `/ai prompt clear`。
- **玩家个人**：在 AI 终端里 `preset <名称>` / `prompt <文字>` 设置只作用于自己请求的行为与提示词，`prompt`（无参数）查看当前生效全文，`prompt clear` 清除。个人设置叠加在全局之上，优先级：个人预设 > 全局预设，个人文字接在全局文字之后。
- **占位符**：提示词里写 `{temperatureC}`、`{stabilityPct}`、`{machine}`、`{dim}`、`{x|y|z}` 及采样里的任意数值键，发请求时按当前读数展开；还提供 `{temp}`/`{stability}`/`{heat}`/`{melt}`/`{power}` 简写。例：`温度超过 {temp}°C 就保守判断`。

### AI 直接接管 DFC + AI 终端
- 判定里可再带 `actions` 数组，AI 就能**直接控制机器**：`start`（启动核心）、`scram`（停堆强制冷却）、`part_on`/`part_off`（开关稳定器/接收器/发射器，`part` 取 `stabiliser`/`receiver`/`emitter`，也接受中文）、`temperature`（`value` 为摄氏度）。例如 `"actions":[{"op":"part_on","part":"stabiliser"},{"op":"start"}]`。
- **自动接管**：节点绑定的机器是 DFC 核心且读数异常时，若 `autoControl` 开启，AI 若给了 `actions` 就照做。若 AI 没给动作，仅在 `autoscram` 开启时才会按 `autoScramTemp`/`autoMinStability` 阈值兜底 SCRAM——**默认关闭**，以符合"只报告、等指令"的默认提示词。写操作经反射作用于 NTM 核心，找不到可用钩子会如实回报，不假装成功。管理员可用 `/ai autoscram on` 打开这条兜底。
- 采样随请求附上 `temperatureC`/`stabilityPct` 及 `stabilisers`/`receivers`/`emitters` 组件数，供 AI 判断。
- **AI 终端**（物品）：右键打开命令行。`status`/`start`/`scram`/`stabiliser on|off`/`receiver`/`emitter`/`temp <度>`/`autopilot on|off` 是本地命令（无需 AI 服务）；其它文字连同核心读数发给 AI，由其返回 `actions` 执行，结果回显在窗口。
- 命令：`/ai control on|off`（同 `/ai autopilot`，是否允许写 DFC）、`/ai act <op> [part] [value]` 立即执行一条动作、`/ai status` 显示接管开关。默认允许，管理员可关停。

---

## 五、堆反应堆：多区块 DFC 反应堆 + 单块堆芯

### HBMZZ 反应堆堆芯（单块，HBMZZ Reactor Core）
- 一块式堆芯。
  - **背面**：控制棒（0 全抽出 / 15 全插入）。
  - **左侧**：冷却剂阀门。
  - **右侧**：脉冲启动，持续供电 = 紧急停堆（SCRAM）。
  - **正面**：功率输出。
  - 右键读取通量、热量与当前输出功率。

### 多区块 DFC 反应堆（Multi-block）
围绕一块堆芯搭建，四周用功能件扩展：
- **反应堆堆芯（多区块）**：核心，正面功率输出，可由机器控制台驱动。
- **反应堆发射器**：围绕放置，增加燃料（更多裂变）。
- **反应堆吸收器**：围绕放置，增加冷却。
- **反应堆稳定器**：增加温度反馈（让温度更稳）。
- **反应堆控制棒**：由红石深度调节的节流棒。

---

## 六、Roblox 风格小件：枪械 / 线圈 / 喷气背包

### 枪械（hitscan）
| 枪 | 弹 | 说明 |
| --- | --- | --- |
| Mk.1 制式手枪（Mk.1 Sidearm） | 9mm 弹药 | 半自动 |
| Mk.2 卡宾枪（Mk.2 Carbine） | 5.56mm 弹药 | 全自动 / 半自动 |
| Mk.3 破门霰弹枪（Mk.3 Breaker） | 12 号霰弹 | 霰弹 |

- **右键**开火，**潜行右键**换弹。有口径、弹匣、单发伤害、射击模式等参数。

### 线圈（Roblox 风格被动道具）
| 线圈 | 效果 |
| --- | --- |
| 速度线圈 | 手持时获得移速加成 |
| 跳跃线圈 | 手持时获得跳跃/浮空类被动 |
| 恢复线圈 | 手持时获得生命恢复 |

（手持时获得被动效果，放下即停。）

### 喷气背包（Jetpack）
- 穿在身上，按住跳跃键飞行，**消耗耐久**。

---

## 七、Redstone Audio（红石音频）：基地设施全家桶

HBMZZ 自带 `rsaudio` 这个内嵌模组，下面这些部件都来自它，和 HBMZZ 一起装、一起用。

### 音频 / 信号
- **音频播放器**：从 `config/rsaudio/audio/` 读 `.ogg`，带每格音量、回声、失真、低音质喇叭模型；支持脉冲 / 循环 / 脉冲循环，可开"播完输出红石"。
- **红石延迟器**：背面进、正面出，右键输入延迟时间（支持 `2` / `1.5s` / `500ms` / `10t` / `1m` 等写法），可附广播文字与颜色。
- **结构加载器**：加载并放置结构。
- **自动化红石信号输出计算机**：按脚本/计划输出红石信号。
- **广播器**：在聊天栏播放/广播文字，可设颜色。

### 警报灯（六色）
- **红色 / 橙色 / 黄色 / 绿色 / 蓝色 / 紫色警报灯**：配合事故代码和事故显示器做全基地的视觉报警。

### 防护 / 设施
- **防核门（+ 防核门板）**：拿**木斧右键**开关门，木斧潜行右键进设置界面。
- **防爆窗帘（+ 防爆窗帘布）**：可收起/放下的防爆帘。
- **巨大管道 / 排风扇 / 巨大标志牌**（白/琥珀/红/绿/青/蓝/紫 7 色）：基地布景。
- **纪念挂画**：纯装饰。
- **起重机 / 货运卡车车位 / 列车轨道 / 列车站台**：货运与运输布景。

### 电力 / 反应堆
- **DMR 反应堆**：控制柜 + 外壳 + 堆芯腔体（暗物质反应堆）。
- **F9 反应堆**：控制柜 + 外壳 + 压力容器（高压水反应堆）。

### 监控 / 门禁 / 交通
- **监控摄像头 / 监控控制台**：安保监控。
- **FAAS 门禁终端 / 气闸控制器 / 远程控制面板**：门禁与气闸。
- **塔塔鲁斯舱盖门（+ 舱盖格）**：舱盖式大门。
- **电梯**：井道 + 轿厢 + 层门，配客运 / 货运 / 斜面三种控制柜。

---

## 八、HUD

- 打开后在屏幕上叠一层**监控 HUD**（`hudOverlay`），在 `hudRange` 格范围内对准 DFC 堆芯就能看到关键读数，不用贴到屏幕前。

---

## 九、配置项一览

改 `config/hbmzz.cfg`（客户端/服务端各自一份）：

| 分组 | 键 | 默认 | 说明 |
| --- | --- | --- | --- |
| monitor | scanRadius | 16 | 扫描半径（4–64） |
| monitor | defaultWidth / defaultHeight | 420 / 280 | 终端默认尺寸 |
| monitor | minWidth / minHeight | 300 / 214 | 最小尺寸 |
| monitor | maxWidth / maxHeight | 1200 / 800 | 最大尺寸 |
| monitor | updateInterval | 10 | 更新间隔（tick，2–100） |
| monitor | bootLog | true | 是否显示开机日志 |
| display | displayInterval | 10 | 显示刷新（tick） |
| display | displayRange | 32 | 离屏渲染范围 |
| display | liveScreen | true | 是否启用实时墙屏 |
| display | hudOverlay | true | 是否启用 HUD |
| display | hudRange | 16 | HUD 触发范围 |
| display | defaultScreenCols / Rows | 2 / 2 | 墙屏默认列/行 |
| display | maxScreenCols / Rows | 4 / 3 | 墙屏最大列/行 |
| video | cooldownTicks | 20 | 视频触发冷却 |
| video | allowEsc | false | 全屏时是否允许 ESC 退出 |
| video | operatorOnly | false | 是否仅操作端可触发 |
| video | audio | true | 视频是否出声 |
| video | cacheFrames | 6 | 视频帧缓存 |
| video | maxWidth / maxHeight | 960 / 540 | 视频最大分辨率 |
| hacker | enabled | true | 是否启用黑客平板 |
| hacker | disconnectTicks | 60 | 断连判定 |
| hacker | degradedTicks | 600 | 降级判定 |
| hacker | cutTicks | 1200 | 切断判定 |
| rbmk | surge | true | 是否触发 RBMK 连锁 |
| rbmk | heat | 1000 | 过热温度 |
| rbmk | jumpTicks | 1200 | 连锁跳变时长 |
| rbmk | blastPower | 10.0 | 连锁爆炸威力 |
| rbmk | melt | true | 是否熔毁 |
| rbmk | lidBlastPower | 30.0 | 盖体落地二次爆炸威力 |
| rbmk | shake | true | 是否震屏 |
| nuke | powerTier | 5 | 放置时的威力档 0-5（5=毁档/设施毁灭级） |
| nuke | fuseTier | 2 | 放置时的引信档 0-5（2=30 秒） |
| nuke | yield | 100 | 旧当量（kT），仅老存档回退用 |
| nuke | fuseTicks | 100 | 旧引信（tick），仅老存档回退用 |
| nuke | blastBase | 6.0 | 零当量基础爆炸力（旧曲线） |
| nuke | blastPerKt | 0.28 | 每千吨附加爆炸力（旧曲线） |
| nuke | blastCap | 120.0 | 爆炸力上限（毁档需 ≥120 才满威力） |
| nuke | annihilate | true | 毁档是否抹平已加载设施 |
| nuke | annihilateRadius | 512 | 湮灭波半径（格） |
| nuke | annihilateBlocksPerTick | 65536 | 每 tick 清除的方块数（掉帧就调小） |
| nuke | annihilateEntities | true | 湮灭波是否清除实体 |
| alert | refreshTicks | 20 | 事故显示器追赶推送间隔 |
| alert | klaxon | true | 广播事故代码时全基地音效 |
| alert | safeLevel | 2 | 代码警报灯唤醒的安全级别（高于此级才亮） |
| dfcSensor | enabled | true | 是否启用 DFC 爆炸传感器 |
| dfcSensor | range | 512 | 传感器响应范围（0=不限） |
| dfcSensor | coreRange | 12 | 判定"DFC 爆炸"时搜堆芯的半径 |
| dfcSensor | anyBlast | false | 是否把所有核爆炸都当 DFC 爆炸 |
| dfcSensor | holdTicks | 100 | 信号满强度保持时长 |
| dfcSensor | decayTicks | 200 | 信号衰减时长 |
| dfcSensor | strength | 15 | 峰值红石强度（15=满） |
| dfcShutdown | enabled | true | 是否启用 DFC 自动停机 |
| ai（hbmzz-ai.cfg） | enabled | true | AI 控制节点总开关 |
| ai（hbmzz-ai.cfg） | autoBroadcast | true | 异常时是否 TTS 全图语音广播 |
| ai（hbmzz-ai.cfg） | autoDisplay | true | 异常时是否自动驱动全图代码显示器 |
| ai（hbmzz-ai.cfg） | autoControl | true | 是否允许 AI 写入 DFC（启动/停堆/组件开关） |
| ai（hbmzz-ai.cfg） | autoScramTemp | 2800 | 自动接管温度阈值（°C） |
| ai（hbmzz-ai.cfg） | autoMinStability | 35 | 自动接管稳定度下限（%） |
| ai（hbmzz-ai.cfg） | endpoint / apiKey / model / ttsModel | — | AI 接口地址 / 密钥 / 模型 / TTS 模型 |
| ai（hbmzz-ai.cfg） | promptPreset | balanced | AI 行为预设（balanced/safety/power/diagnostic/quiet/verbose/zh/en） |
| ai（hbmzz-ai.cfg） | prompt | 不要擅自停止反应堆，不要擅自止损，只做报告，等待指令 | 全局自定义提示词（支持 {temperatureC} 等占位符，上限 2048 字） |
| ai（hbmzz-ai.cfg） | autoScram | false | AI 未给动作时是否按温度/稳定度阈值兜底 SCRAM（默认关，只报告等指令） |
| ai（hbmzz-ai.cfg） | pollSeconds / httpTimeoutMs | 5 / 8000 | 轮询间隔（秒）/ HTTP 超时（毫秒） |
| ai（hbmzz-ai.cfg） | lengthScale | 1.0 | TTS 语速（0.6–1.8） |

---

## 十、配方

HBMZZ 注册了全部合成配方（在 `ModContent.registerRecipes` 里，共 31 条，全部用合成表形状 / 无序合成）。下面是完整清单，材质记号：`I`=铁锭、`G`=玻璃板、`R`=红石、`C`=比较器、`O`=侦测器、`Q`=石英、`P`/`E`=末影珍珠、`L`=红石灯、`B`=烈焰棒、`S`=史莱姆球、`F`=羽毛、`M`=西瓜、`金`=金锭、`黑`=黑曜石；`红石块`=红石块、`火`=火药。个别行内字符含义与记号冲突的，会在该行单独注明。

| 部件 | 合成 |
| --- | --- |
| DFC 监控屏 | `I G I / G R G / I C I` |
| 机器控制面板 | `I R I / G C G / I R I` |
| 机器控制台 | `I C I / R O R / I I I` |
| 监控数据线 | `· R 金 / R E R / 金 R ·`（此行 `金`=金锭、`E`=末影珍珠） |
| 黑客平板 | `G R G / I P I / I C I` |
| AI 终端 | `G E G / I P I / I R I`（`E`=绿宝石、`P`=末影珍珠、`R`=红石块） |
| 视频广播器 | `I G I / R O R / I I I` |
| 视频大屏 | `G G G / I R I / I I I` |
| 大屏遥控器 | `· R · / I G I / · I ·` |
| 核弹 | `I O I / P R P / I O I`（`R`=红石块） |
| 核弹信号控制器 | `I E I / R C R / I I I` |
| 核弹监视屏 | `I O I / G R G / I C I` |
| 代码广播器 | `I R I / G L G / I R I` |
| 事故显示器 | `I G I / G R G / I E I` |
| 状态提示灯 | `· R · / I G I / I I I` |
| 代码旋转警报灯 | `· Q · / I G I / R I R` |
| DFC 爆炸传感器 | `I Q I / G R G / I Q I` |
| 与门 | `I R I / R Q R / I I I` |
| 或门 | `I R I / R Q R / G G G`（金锭） |
| 异或门 | `I R I / R Q R / Q Q Q` |
| 非门 | `I R I / R Q R / R R R` |
| 反应堆堆芯（多区块） | `I C I / R I R / I C I` |
| 反应堆发射器 | `I I I / I R I / R R R` |
| 反应堆吸收器 | `I I I / I G I / I R I` |
| 反应堆稳定器 | `G G G / G Q G / I I I` |
| 反应堆控制棒 | `I R I / I R I / I R I`（`R`=红石块） |
| 塔塔鲁斯大门 | `I I I / I O I / I R I`（全铁块 + 黑曜石 + 红石块） |
| 监控摄像头 | `I G I / G R G / I R I` |
| 速度线圈 | `G · G / · R · / G · G`（金锭 + 红石） |
| 跳跃线圈 | `S · S / · F · / S · S` |
| 恢复线圈 | `M · M / · S · / M · M` |
| 喷气背包 | `I I I / I B I / R · R` |
| 9mm 弹药 ×16 | 无序：铁粒 + 火药 ×2 |
| 5.56mm 弹药 ×12 | 无序：铁粒 ×2 + 火药 ×3 |
| 12 号霰弹 ×8 | 无序：铁粒 + 火药 ×2 + 纸 |
| Mk.1 制式手枪 | `I · I / I I I / · R 火`（`火`=火药） |
| Mk.2 卡宾枪 | `I I I / I 火 I / I R ·`（`火`=火药） |
| Mk.3 破门霰弹枪 | `I I I / 火 火 · / R R ·`（`火`=火药） |

> **红石音频（rsaudio）** 的设施（电梯 / 列车 / 起重机 / DMR / F9 / 防核门等）不在 HBMZZ 这边注册配方，由 `rsaudio` 自己的代码或整合包提供。方块 / 物品 id 与模组 id（`hbmzz` / `rsaudio`）一一对应，可直接 `/give` 或数据包投放。

---

## 十一、更多资料

- **使用指南**（每个部件怎么摆、怎么连、怎么玩）：[`docs/guide.html`](docs/guide.html)
- **参考手册**（全部控件 / 节点 / 方块 / 配置 / 配方查表）：[`docs/reference.html`](docs/reference.html)
- **更新日志**：[`docs/changelog.html`](docs/changelog.html)
- **原声**（本站用到的五首曲目）：[`docs/ost.html`](docs/ost.html)
- **署名 / 授权 / 许可**：[`AUTHORIZATION.md`](AUTHORIZATION.md)、[`NOTICE.md`](NOTICE.md)、[`LICENSE`](LICENSE)

---

## 许可与署名

- 机器控制面板与节点图控制台**衍生自 NTM: Cursed Edition**（`com.hbm.inventory.control_panel`，GPL-3.0）；附属节点与控件来自 NTM Cursed Addon / Leafia（GPL-3.0，部分 LGPL-3.0）。
- 监控面板设计参考 **JiezhiMod（DFC-Map-Creation-Tool）** by Shushangde Jiezhi，已获书面授权，见 [`AUTHORIZATION.md`](AUTHORIZATION.md)。
- Redstone Audio（`rsaudio`）自同一作者的独立模组原样移植，保留独立 modid，以便旧存档的方块 / 实体 id 不被改动。完整出处见 [`NOTICE.md`](NOTICE.md)。
- 本模组以 **GPL-3.0** 授权，见 [`LICENSE`](LICENSE) 与 [`CONTRIBUTING.md`](CONTRIBUTING.md)。
