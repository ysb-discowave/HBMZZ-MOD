# HBMZZ 3.8.0 交付文档

本文件记录 **3.8.0** 这条线上每一次交付：交了什么、产物在哪、怎么验证的、出问题怎么退回去。
以后每次改动都在文末「交付批次」追加一条，不新开文件——版本号固定 3.8.0，除非明确要求升版。

---

## 一、当前交付批次

| 项 | 值 |
| --- | --- |
| 版本 | 3.8.0（build.gradle / mcmod.info / HBMZZ.VERSION 三处一致） |
| 代码基线 | 3.7.1 = upstream master 快照，提交 `26195b1`（2026-11-17） |
| 当前提交 | `0b0d835`（批次 #11：视频格式全支持——导入器） |
| 游戏侧模组 | `hbmzz-3.8.0.jar`（23,751,413 字节） |
| 模组 SHA-256 | `94fd3bd7c02e161a…`（`build/libs` 与部署目录两处一致） |
| 源码包 | `hbmzz-3.8.0-sources.jar`（build/libs，8,843,269 字节，sha256 `2a32aea3…`） |
| 源码仓库 | `C:\Users\37594\WorkBuddy\Claw\HBMZZ-MOD`（分支 master） |
| 部署位置 | `E:\pcl2\.minecraft\versions\DFC+RBMK 1.0.0\mods\hbmzz-3.8.0.jar` |
| 旧版归档 | `E:\pcl2\.minecraft\versions\DFC+RBMK 1.0.0\backupsmod\hbmzz-old-jars\` |
| 视频包 | `…\config\hbmzz\videos\hbmzz-intro.zip`（1,721,077 B，静音，sha256 `c86dafe3…`）<br>`…\config\hbmzz\videos\hbmzz-credits.zip`（60,747,521 B，仓库原版配乐，sha256 `7007fb93…`） |
| 旧视频包归档 | `…\backupsmod\hbmzz-old-videos\`（instrumental / voiced 两版都在，随时可换回） |

部署命令固定为 `python tools/deploy.py`（`deploy.local.json` 已指向该 mods 目录）。
脚本会比对 sha256、把旧 jar 改名并归档到 `backupsmod\hbmzz-old-jars`，`mods` 里只留当前版本
（第三方 `hbmzz-addon-0.1.0.jar` 不动）。

---

## 二、3.8.0 交付内容

### 1. 视频大屏（重做）

- 方块 `hbmzz:video_screen`：控制器格有方块实体，其余格是同一方块 `part=true` 的填充格，
  **填充格没有方块实体**——画面只可能被画一次，不会出现「十二个控制器各画一遍」或「谁都以为别人画了」。
- 朝向与镜像一次算清，全部取自 `dfc/ConsoleRig`：
  - `anchor(cols, rows, facing)`：控制器是墙的左上格，面板中心要按列 / 行推过去；
  - `yawOf(facing)`：把渲染器局部 `-z` 转到方块朝向；
  - `panelUv()`：u 反向（玩家站在正面时局部 +x 是他的左手），图像左边缘才落在观看者左手边。
  三处各自为政就是「北面正常、东西镜像、上下颠倒」的来源；现在四个朝向一致。
- 画面用 `PanelPainter.glass` 画一张带 UV 的四边形，位置在方块表面外 0.002 格，不与方块模型 z-fighting。
- 尺寸：1–32 格 × 1–32 格，从配置窗口调。变大补格、变小只收掉这面墙自己的格
  （`trimParts` 只清 `part=true` 的同方块格，不碰玩家放的东西）。
- 拆任意一格整面消失，只掉一个物品（`getDrops` 空表，掉落由拆除函数在被打的那格手动生成）。
  控制器方块实体已丢失时按列 / 行扫描兜底，不会留下孤儿格。

### 2. 大屏遥控器（新增）

- 物品 `hbmzz:video_remote`，16×16 手持机贴图（深灰机身、顶部信号窗、方向键、两枚按键），
  由 `tools/art/make_video_textures.py` 生成，可重跑。
- **普通物品，不是 `ItemBlock`**：不可放置、没有点击放置行为、也不会弹「不能放置」；
  右键别的方块返回 `PASS`，把点击让回去。
- 只做连接：右键播放器对准 → 右键大屏（点任一格）绑定。两者相距上限 64 格，玩家要够得着大屏（12 格内）。
- 绑定在服务端落地：写大屏的播放器坐标、片名、音量，写播放器的大屏坐标。

### 3. 播放器（视频广播器）与拉杆语义

- **站立右键 = 直接开播**：对着播放器右键就进播放界面（`GuiVideoScreen`），放的是这台机器当前存的
  片源与音量；没有片源时开的是菜单，让人先挑一个。播放界面里 **F 键切全屏**（`Minecraft.toggleFullscreen()`，
  再按一次回窗口），退出时把进入前的显示模式还回去；界面底部常驻一行 `F 全屏` 的提示。
- **蹲下右键 = 菜单**：原来那个"片名 + 选择器 + 打开文件夹 + 刷新列表"的窗口搬到蹲下；
  音量与「强制限制返回」不再单独占一个右键，改为这个窗口里的「播放器设置」按钮。
- 红石上升沿触发保持不变。
- **接上大屏之后**，红石只做开 / 停：上升沿让大屏开始播放，下降沿让它停；
  误触拉杆不会把任何人切进全屏。
- **没接大屏时**保持原逻辑：上升沿直接全屏接管，按「强制限制返回」（默认开启）决定能不能 ESC 中途退出。
- 大屏所在区块没加载时不会偷偷降级成全屏，只写一行状态；大屏被拆则自动解绑，拉杆回到原行为。
- 首次 tick 只「采纳」当前信号、不触发任何动作（登录在通电的拉杆旁边不会被突然接管）。

### 4. 音量与「强制限制返回」

- 从大屏搬到了播放器，入口是「蹲下右键播放器 → 播放器设置」：窗口里只有一条 0–200% 音量滑条
  与一个「强制限制返回」开关。它不再自己占一个右键，因为右键现在是"直接播放"。
- **默认开启**（`config/hbmzz.cfg` 的 `video.allowEsc` 只是新方块与旧存档缺字段时的种子值，
  默认 `false` 即限制开启）。开关是**每台播放器各自一份**，存在方块 NBT 里（键 `restrictBack`，
  旧存档的 `forceExit` 仍然读得懂）。
- **只在没接大屏时生效**：没接大屏，全屏接管期间按 ESC 只提示、不退出；接上大屏后立刻不生效，
  退出归大屏与拉杆，画面上连相关提示都不再出现；大屏被拆掉则自动恢复限制。
- **同步是"每次现问"而不是"打开时记一份"**：`GuiVideoScreen` 每次判键、每 tick 都回查这台播放器的
  方块实体（`hasScreen()`），所以遥控器中途接上大屏、或者大屏被拆，下一次按键就是新结论。
  方块实体不在已加载区时沿用上一次的结论（"看不见"不等于"被拆了"）；方块本身没了则按没接大屏处理。
- **播放界面里没有任何按钮或入口**：`GuiVideoScreen` 不建 `buttonList`，regcheck #67 守着这一条。
  能改它的只有这个设置窗口；窗口里同时直接写出当前状态（"当前已接大屏 · 本项不生效" /
  "当前未接大屏 · 本项生效中"），不用去别处猜。
- 两项随广播包走（片名 + 音量 + 限制开关 + 播放器坐标），全屏与大屏两条路径用同一套设置；
  坐标是给客户端回查连接状态用的（regcheck #69 守着报文里那一格）。
- 滑条是自绘的（`GuiVideoPlayerConfig`）：0.5 基线 × 百分比，100% = 默认音量，200% 才真的更响
  （音频层在 1.0 处夹紧；基线若设成 0.65，滑条后半段会没有反应）。

### 5. 视频目录：运行中换片

- 广播器窗口新增「打开视频文件夹」（`Desktop.open`，失败时把路径写进状态行）与「刷新列表」。
- 刷新现在会**说出被跳过的文件**：目录里不是 `.zip` 也不是子目录的东西（典型是一个 `.mp4`）
  会进 `VideoLibrary.skipped()`，窗口与 F7 的回执都写明"跳过 1 个不支持的文件：xxx.mp4（需打包成 zip）"。
  `config/hbmzz/videos/README.txt` 也补了同一句话。
- 新增刷新键 **F7**（`key.hbmzz.video_refresh`）：`VideoLibrary.rescan()` 清掉一秒列表缓存，并在物品栏上方报数量。
  没用 F3+T —— 那是原版重载资源包，按一次会重新烘焙所有模型。

### 6. 回退到仓库原实现，音乐回原版

- 撤回上一版的替代实现：`VideoClip` 的「文件集 + 时间线 / playlist / desc.txt / pingpong」、
  悬浮观看窗、多屏共用解码、旧的 `VideoScreenPlayback` 全部移除；
  `VideoClip` / `VideoPlayer` / `VideoAudio` / `VideoLibrary` / `GuiVideoScreen` / `ItemMonitorLinker`
  一律还原为仓库版本，视频仍按「文件名 + fps + 帧号」读取。
- 数据线（`monitor_linker`）不再参与接大屏，回到仪表 / 堆芯 / 摄像头。
- 音乐回到仓库原版：片头恢复**静音**（192 帧 / 24 fps / 8.0000 s，无 `audio` 字段），
  片尾仍是仓库自带的 `the-ending-of-melting.mp3`（2326 帧 / 12 fps / 193.8333 s，带 `audio.wav`）。
  自制配乐与英文口播的脚本（`tools/audio/ost_synth.py`、`voice_over.py`）与成片全部移除。

### 7. 播放定位与同步（本批新增）

- **右键大屏**打开 `GuiVideoSeek`：可拖动的进度条、`-30s` / `-5s` / `+5s` / `+30s`、
  「回开头（重新播放）」、「暂停 / 继续」。蹲下右键仍是尺寸 / 倍速窗口，两个问题分开问。
- 播放头是**方块上的状态**，不是窗口里的变量：`TileEntityVideoScreen` 存
  `progressMs`（基准毫秒）+ `anchorTick`（世界 tick）+ `paused`，
  `positionMs()` = 基准 + 走过 tick × 50ms × 倍速；暂停、停止时基准就是最终位置。
- **一次定位走一条路**：窗口 → `VideoSeekMessage`（id 27）→ 服务端 → 改方块 → `notifyBlockUpdate`
  → 范围内所有客户端重算位置。按键的人、旁边的人、服务器另一头的人落到同一帧；
  **单机就是集成服务端跑同一段代码**，两种模式不存在第二条实现。
- 时间边界**两层夹**：服务端只接受 `0..MAX_PROGRESS_MS`（6 小时）；客户端再用本机那份片子的
  `durationSeconds()` 夹一次。「-5 秒」不会变成负数，片尾之后也不会因为「目标越界」反复重定位。
- 暂停是第三种状态，不复用拉杆：`playing` 归拉杆，`paused` 只是把时钟按住——
  画面定在当帧、解码器不释放、音轨停在同一位置，继续时从原位接上，不会从头开始。
- **停止 = 回开头**：`setPlaying(false)` 把进度清零并解暂停，与「红石一路一次播放」的原意一致；
  换片（`setVideo` 名字变化）同样回开头。`applySpeed` 先按旧倍速折算进度再改倍速，调倍速不会跳帧。
- 客户端 `VideoScreenPlayback` 每帧把本机播放器往方块的时间轴对齐，偏差 > 0.35 秒才 `seekTo`；
  暂停期间不动。`VideoPlayer` 的时钟改成「基准时间 + 走时」，`seekTo` 让解码线程跳到目标帧
  （解码线程不再在片尾退出，可回退重新解码），音轨一起跳。
- 运行中刷新：窗口里的时间与状态每帧从方块读，墙上画面由同一条对齐链路跟上，没有额外的定时同步包。

### 8. 保留的既有交付

- 防爆窗帘、纪念挂画（3.7.0 分支移植）。
- DFC 监控屏几何缓存（按列 / 行 / 倾角 / 通电 / 吊装缓存 `ConsoleModel`）。
- 启动崩溃修复：`rsaudio:blast_door` 注册名只设一次，父类只设显示名；regcheck 第 64 条守卫。

### 9. 「刷新无效 / 装进去不生效」的排查结论（本批新增）

- **证据**：这台机器的 `config/hbmzz/videos/` 里躺着 `Video_1781791115891_250.mp4`（2.0 MB），
  另有 `hbmzz-intro.zip`、`hbmzz-credits.zip`。`latest.log` 里 `hbmzz@3.8.0` 正常加载
  （"Client attempting to join with 22 mods ... hbmzz@3.8.0"），所以**不是模组没装进去**。
- **原因**：视频库只认 `<名字>.zip` 和 `<名字>/` 两种形状（`VideoLibrary.names()` 的过滤条件），
  `.mp4` 被静默忽略。列表永远只有那两个 zip，于是"按了刷新没变化"看起来像按钮坏了；
  把 mp4"装进去"也当然不生效——这个模组读的是图片帧序列，1.12.2 的 JRE 没有 H.264 解码器。
- **修复**：库记账（`skipped()`），窗口与 F7 把被跳过的文件名念出来并给出做法（`tools/video/pack.py`
  转成 zip）；README 同步说明。**没有**假装支持 mp4——那需要一个每平台各带一份的本地解码库。
- **验证**：用模组自己的 `VideoLibrary` 在真实目录上跑了一遍：`clips = [hbmzz-credits, hbmzz-intro]`、
  `skipped = [Video_1781791115891_250.mp4]`、`exists("hbmzz-intro") = true`。
- **"装进去不生效"的第二种可能**（游戏读的不是刚换的 jar）现在会自己说出来：
  preInit 打一行 `HBMZZ 3.8.0 loaded from <jar 路径>`，重启后先看这行指向哪个文件。

### 10. FFmpeg 自动下载：确认已丢失，按上一版恢复（本批新增）

- **核对结论：逻辑不在了。** 当前源码的 `BundledFfmpeg` 只剩"把 jar 内 `/assets/rsaudiofm/ffmpeg.exe`
  释放出来"，而两种包里都没有这个资源（`unzip -l` 查过），于是候选 0 每次都是 null，日志只剩
  一句"未找到可用的 ffmpeg"。上一版的下载器只留在 `backupsmod/hbmzz-old-jars/hbmzz-3.7.0.jar.bak` 里：
  `BundledFfmpeg` 17 KB（带 `$HeadInfo` / `$Progress`）与 `GuiFfmpegDownload`。源码回到 3.7.1 基线时，
  这两个文件跟着没了。
- **恢复的东西**（据 3.7.0 的源码 `_srcpkg/370` 原样搬回，只补了一处并发保护）：
  - `BundledFfmpeg`：在线下载 BtbN 的 `ffmpeg-master-latest-win64-gpl.zip`，走
    `https://yxz.ysbdwz.dpdns.org/download?url=` 代理；HEAD 拿总长与 `Accept-Ranges`，支持就切 64 段
    并行（每段一个 `.seg` + `.done` 标记，断了下次只补没下完的段），不支持退回单线程；合并后校验总长，
    再流式解压出 `bin/ffmpeg.exe`；落到 `config/rsaudiofm/bin/`，同目录 `.stamp` 记住源 URL，命中就复用。
    代理与源可被 `-Drsaudiofm.ffmpeg.proxy` / `-Drsaudiofm.ffmpeg.source` 覆盖。
  - `GuiFfmpegDownload`：进世界前的进度页。后台线程跑 `ensure()`，主线程每帧只读 `progress()` 画条，
    ESC 期间不响应，完成/失败都自动关闭并回调。
  - `FormatsBootstrap.onClientTick` 第一次 tick：`setGameHint` 之后先问 `BundledFfmpeg.isReady(mc.mcDataDir)`；
    没有就打开进度页，回调里 `FfmpegFinder.reset() + ConvertQueue.scanAndEnqueue()`（这样刚下好的 exe
    立刻被识别）；有就直接 reset + 扫描。
- **补的一处并发保护**：`ensure()` 用 `AtomicBoolean` 保证同一时刻只有一次下载。播放线程在下载中途问
  `FfmpegFinder.path()` 时不再跟着下一遍（那会和进度页往同一批分片里写，拼出来的 zip 是坏的），
  而是立刻拿 null 走外置候选，本次播放按"没有 ffmpeg"降级，下载完自动恢复。
- **实跑验证**（本地假代理 + 假 zip，结构同 BtbN 包：`<root>/bin/ffmpeg.exe`）：
  - 分段模式：1 个 HEAD + 64 个 206 Range 请求，1.4 s 完成，解出 1,048,576 字节，
    `.stamp` 写入 `src=...`，`isReady` 由 false 变 true，下载目录只剩 `ffmpeg.exe` + `.stamp`（分片与 zip 全部清理）。
  - 不支持 Range 的服务端：自动退回单线程，72 ms 同样成功，结果一致。
- **首次启动的表现**：主菜单阶段弹出"正在准备音频播放器"进度页（约 190 MB），下完自动关闭，之后不再重复下载。
  已有匹配 stamp 的本地副本时直接跳过这一步。

### 11. 视频格式全支持：把"播放"换成"导入"（本批新增）

- **结论先行：播放端一个解码器都没加。** 1.12.2 的 JRE 只有一套，塞 H.264/HEVC/AV1 解码
  意味着每个平台各带一份二进制，还得跟着显卡驱动走。所以"支持市面上所有格式"落在导入这一步：
  用 ffmpeg 把任何容器拆成图片帧 + PCM 音轨，再按 `VideoClip` 认识的形状打包。ffmpeg 认多少
  格式，这里就能导多少格式，而模组自己仍然只做 `ImageIO` 能做的事。
- **新增 `VideoFormats`**：认得 mp4 / m4v / mov / 3gp / mkv / webm / avi / wmv / asf / flv /
  ts / m2ts / mts / mpg / mpeg / vob / rm / rmvb / ogv / mxf 等容器，外加 mp3 / flac / wav /
  aac / opus 这类纯音频（导进来只有声音，界面上明说）。列表以外的文件仍旧按"不支持"提示，
  不会静默消失。
- **新增 `VideoImportQueue`**：单线程守护队列，一次一集。
  - 探测时长（`ffmpeg -i` 的 `Duration:` 行）→ 估总帧数 → 每 400 ms 数一次临时目录里的 jpg
    来推进度条；真实帧数解完再数一遍写进 `meta.json`。
  - 命令：`-vf fps=F,scale=W:-2:flags=lanczos -q:v 4`，音轨单独一遍
    `-vn -ac 2 -ar 44100 -c:a pcm_s16le`（没有音轨就跳过，不留空文件）。
  - 打包用 STORED（jpg 本来就压过），产物 `<名字>.zip` 直接落进 `config/hbmzz/videos/`，
    撞名自动加 `_2`；临时目录 `.import/` 转完即清。
  - 关窗口不打断转换；重新打开还能看到进度。取消会 `destroy()` 当前 ffmpeg 并清空队列。
- **名字放开到各语言字母**：`VideoLibrary.isValidName` 以前只收 ASCII，中文片名转出来的包只能叫
  `video`。现在按 `Character.isLetterOrDigit` 判，中文名原样保留；分隔符、空格、控制字符、
  首字符非字母数字、末尾点号仍然一律拒绝——没有 `/` 和 `\` 就没有目录穿越，这条没松。
- **两处入口**：蹲下右键播放器的窗口多了「导入视频…」按钮；刷新/F7 的回执把"待导入 N 个视频"
  和"跳过 N 个不支持的文件"分开说（mp4 不再算坏文件）。
- **实跑验证（真 ffmpeg + 模组自己的类）**：
  - 3 秒测试片（640×360 / 30 fps / AAC）→ 10 fps、480 宽：30 帧、3.00 秒、`audio.wav` 529,278 字节、
    包名 `测试片段.zip`、`VideoClip` 读回首帧与末帧都成功。
  - 目录里那个真片 `Video_1781791115891_250.mp4`（104.43 秒 / 854×426 / AV1）→ 10 fps、480 宽：
    **1044 帧、104.40 秒、25 MB zip，几秒内转完**，首末帧解码成功；进度条 30% → 69% → 打包。
  - 包内结构核对：`meta.json`（fps/frames/title/audio）+ `f000001.jpg…` + `audio.wav`，与手打包一致。

---

## 三、验证记录

| 项目 | 结果 |
| --- | --- |
| `./gradlew build --offline`（JDK 8 / Gradle 4.10.3，离线） | BUILD SUCCESSFUL，产出 `build/libs/hbmzz-3.8.0.jar` |
| jar 内 `mcmod.info` | modid `hbmzz`，version `3.8.0` |
| regcheck（注册 / 配方 / 模型表） | **83/83**（#65 遥控器目标必须让出右键；#66 服务器先夹再存定位；#66b 客户端按片长再夹一次；#67 播放界面不得出现可退出按钮；#68 限制只在大屏缺席时成立、客户端不许缓存结论；#69 广播包必须带播放器坐标。#67 / #68 / #70 / #71 做过反向验证：故意塞进 `buttonList.add`、把 `hasScreen()` 改成 `false`、把两个右键分支对调、把 F 换成 G，对应项都变红；#73 / #74 也反向验证：把 `isReady` 判断改成 `false` 后「启动时会去下 ffmpeg」立刻变红；#77 把按钮改成 `null` 后「导入入口」变红） |
| statecheck（blockstate 键序 / 状态数） | 4/4 |
| videocheck（视频库 / 名字白名单） | 28/28 |
| linkercheck（连接器握手） | 21 通过，1 跳过（CE 不在 classpath） |
| rbmkcheck | 50/50 |
| termcheck | 67/67 |
| cecpcheck | 8/8 |
| gatecheck / dfccheck / reasimcheck | 全 PASS |
| `python tools/checkall.py --fast` | 7/7 全 PASS（含站点数据一致性） |
| 部署校验 | 目标目录 jar（23,751,413 字节）与构建产物 sha256 一致：`94fd3bd7…` |
| 视频包解析（模组 `VideoClip` 实跑） | 片头 192 帧 / 24 fps / 8.0000 s / **无音轨**；片尾 2326 帧 / 12 fps / 193.8333 s / 有音轨 |
| 贴图 | 4 张方块贴图 + 1 张物品贴图，16×16，`make_video_textures.py` 可重跑 |
| 网络 | 新消息 id 24 / 25 / 26 / 27（定位），与既有 id 不冲突（regcheck「包序号唯一」通过） |
| 定位链路 | 代码级核对：窗口只发请求不碰本地解码器；服务端改方块 → `notifyBlockUpdate` → 客户端 `onDataPacket` 收 `progress`/`anchor`/`paused`；客户端两侧都夹片长 |
| 时间轴边界 | 服务端 `clampMs(0..MAX_PROGRESS_MS)`；客户端 `within()` 夹 `durationSeconds()`；两端夹法均由 regcheck 守 |
| 视频库刷新 | 用模组自身的 `VideoLibrary` 在真实 `config/hbmzz/videos` 上实跑：识别 2 个 zip，跳过 1 个 mp4 并给出文件名 |
| 强制限制返回 | 代码级核对：报文带播放器坐标 → `GuiVideoScreen` 每 tick / 每次按键回查 `hasScreen()`；接上大屏即放行且不再画相关提示，拆掉即恢复；设置窗口显示当前生效状态 |

---

## 四、已知限制

- **没有进游戏实测**：大屏四个朝向的画面、32×32 的铺格手感、倍速下的观感、音量滑条、
  遥控器两步连接、定位后的音画对齐与拖动进度条的手感，全部只经过编译、静态检查与离线解析。
  这是本批最大的一条未验证项。
- 播放头按**世界 tick** 推算（20 tick/s），不是逐帧同步：跨客户端最多差一两个 tick 的取整，
  肉眼不可见，但不等于帧级严格。真正的逐帧一致需要每帧广播位置，代价不值得。
- 客户端每帧只在**偏差 > 0.35 秒**时重定位；这个阈值是为了不把解码缓冲区每秒清空好几次。
- 片尾不会自动循环也不会自动停止：片子播完停在最后一帧，位置读数到片长为止，
  要重放按「回开头」或扳一次拉杆。
- 世界内大屏在同一客户端最多同时解码 **4 路**（超出的大屏保持黑屏，重新扳一次拉杆可再试），
  目的是不让一屋子屏幕各开一条解码线程。
- 音量 200% 受系统混音器 1.0 上限约束，响度到顶即止，不会破音。
- 「强制限制返回」的"有没有大屏"读的是本机这份方块实体：播放器所在区块被卸载时沿用上一次的结论，
  所以极端情况下（边走远边拆大屏）限制状态要等区块重新加载才更新；画面本身这时也已经不在播了。
- F7 只在没有打开 GUI 时响应（原版按键状态在 GUI 内不更新）；窗口里有「刷新列表」按钮。
- 大屏没有音轨时就是静音播放，播放器音量对它无效。
- 帧率仍取包的 `fps`（整数），倍速只改播放头速度，不改解码帧率。
- **播放端仍然只解图片帧**：mp4 / mkv / mov 这些要先转成帧包。区别是现在不用命令行——
  蹲下右键播放器 →「导入视频…」→ 选文件 → 转换，转身就出现在列表里（底层还是 ffmpeg）。
  命令行 `tools/video/pack.py` 仍然可用，适合批量。
- **手动进入的播放界面不受「强制限制返回」约束**：ESC 随时可退，那条限制只作用于红石触发的全屏接管。
- 播放界面按 F 切换的是**系统全屏**（整块显示器），不是"隐藏底部进度条"；退出播放会还原进入前的显示模式。
- 3.7.0 分支的四向开合门、自定义贴图系统、旧 mixin 与运行时 FFmpeg 管线没有并入。

---

## 五、回滚

1. 把 `backupsmod\hbmzz-old-jars\` 里对应的 `*.jar.bak` 改回 `*.jar` 放回 `mods`。
2. 删掉 `mods\hbmzz-3.8.0.jar`（或一并归档）。
3. 源码侧回滚到 `26195b1` 即完全回到 3.7.1 基线；只想退掉「强制限制返回」这一批，回滚到 `927aac4`；
   只想退回上一版大屏与配乐，回滚到 `d5ed30b`。
4. 视频包想换回配乐 / 口播版：把 `backupsmod\hbmzz-old-videos\` 里的
   `hbmzz-intro-instrumental.zip`、`hbmzz-intro-voiced.zip`、`hbmzz-credits-instrumental.zip`
   复制进 `config\hbmzz\videos\` 并改名为 `hbmzz-intro.zip` / `hbmzz-credits.zip`。

---

## 六、交付批次

| 批次 | 提交 | 内容 | 产物 sha256（前 12 位） |
| --- | --- | --- | --- |
| 2026-11-18 #1 | `7c4948f` `3bbe4aa` `bd8a7a0` | 防爆窗帘 + 纪念挂画 + DFC 几何缓存；视频大屏与观看界面悬浮化；序列帧播放器（顺序 / 帧率 / 循环 / 音画同步） | `317f7bd4…`（3.9.0 标签期） |
| 2026-11-18 #2 | `c1b1b78` | 版本号回退并固定在 3.8.0，功能全部保留；重新出包并部署 | `0895d885…` |
| 2026-11-18 #3 | `faa7f85` | 修复启动崩溃（blast_door 注册名设两次）；regcheck 加第 64 条守卫；deploy.py 不再误归档 `hbmzz-addon` | `985dd964…` |
| 2026-11-18 #4 | `dac749c` | 片头 / 片尾重做原创配乐（后于 #6 撤回） | 片头包 `f7462851…`、片尾包 `d4869fac…` |
| 2026-11-18 #5 | `ebe84ff` | 两条片子加英文口播（后于 #6 撤回） | 片头包 `fdfed696…`、片尾包 `fd8d5619…` |
| 2026-11-18 #6 | `d1eabf0` | **撤回替代方案、恢复仓库原实现**；大屏朝向与镜像修正；遥控器取代数据线接大屏；拉杆只管开关；音量 / 退出限制搬到播放器；尺寸与倍速配置窗口（≤32×32、0.25–3x）；打开文件夹 + F7 刷新；音乐回仓库原版 | `936c09c1…` |
| 2026-11-18 #7 | `927aac4` | **播放进度定位与同步**：右键大屏开定位窗口（进度条 / ±秒 / 回开头 / 暂停继续）；播放头落到方块（progress + anchor + paused）；`VideoSeekMessage` 走服务端，单机同一条路；两层边界夹取；解码线程可回退、音轨随之定位；regcheck 67 条 | `92c36f4f…` |
| 2026-11-18 #8 | `5e144a8` `7c4ea70` `ff1472b` | **强制限制返回**：默认开启，开关只在播放器设置窗口（当时的入口：蹲下右键），播放界面无按钮无入口；只在没接大屏时拦 ESC 并显示提示，接上大屏立即失效且不显示；状态每 tick / 每次按键现问方块实体，接上 / 拆掉都即时同步；广播包补播放器坐标；regcheck 71 条 | `d99621a2…` |
| 2026-11-18 #9 | `8c3af70` | **交互重排**：站立右键直接开播、蹲下右键开菜单、播放界面 F 切全屏（退出还原显示模式）；播放器设置移到菜单按钮；**刷新无效排查**：目录里的 .mp4 被静默忽略 → 库记账并在窗口 / F7 里点名，README 同步；preInit 打印加载来源；regcheck 76 条 | `9272273c…` |
| 2026-11-18 #10 | `53378ce` | **恢复 FFmpeg 自动下载**：源码里只剩"释放 jar 内资源"，而包里没有该资源 → 补回 3.7.0 的在线下载器（代理 + 64 段并行 + 续传 + zip 解压 + stamp 复用）与下载进度页，启动时 `isReady` 为假就自动去下；`ensure()` 加同一时刻只跑一次的并发保护；regcheck 79 条 | `f1c9d369…` |
| 2026-11-18 #11 | `0b0d835` | **视频格式全支持**：新增 `VideoFormats`（容器白名单）与 `VideoImportQueue`（ffmpeg 拆帧 + 音轨 + 打包，后台队列、进度、取消）；广播器窗口加「导入视频…」；待导入与真不支持分开记；包名放开到中文；regcheck 83 条、videocheck 28 条 | `94fd3bd7…` |

> 说明：批次 #1 的功能与批次 #2 的 jar 内容一致，差别只在三处版本戳。批次 #1 当时误升到 3.9.0，
> 已按指示回退，3.9.0 的 jar 保留在 `backupsmod\hbmzz-old-jars\hbmzz-3.9.0.jar.bak`。
> 批次 #4 / #5 的自制配乐与口播已随 #6 撤回，产物仍在 `backupsmod\hbmzz-old-videos\`。

---

## 七、每次交付的固定动作

1. 编译：`./gradlew build --offline`，确认 `build/libs/hbmzz-3.8.0.jar` 的 `mcmod.info` 版本是 3.8.0。
2. 检查：`python tools/checkall.py --fast`，再按改动范围补跑对应单测
   （视频改动跑 `tools/videocheck/run.sh`，注册表改动跑 `tools/regcheck/check.py`）。
   Java 单测需要 `JAVA_HOME` 指向 JDK 8；`linkercheck` / `cecpcheck` 需要 `FASTUTIL` 指向
   gradle 缓存里的 fastutil（本机缓存在 `E:\hbm\gradle-home`）。
3. 部署：`python tools/deploy.py`，旧 jar 自动归档到 `backupsmod\hbmzz-old-jars`。
4. 文档：在本文件「六、交付批次」追加一行，更新「一、当前交付批次」的提交与哈希，需要时补「四、已知限制」。
5. 公告：`tools/site/content.json` 的 `timeline` 与 `docs/changelog.html` 同步当前版本条目，然后
   `python tools/site/sync_site.py` 刷新数据层。
6. 汇报：向用户给出「模组 jar 路径 + 源码位置/提交 + 本次改了什么 + 验证结果 + 未验证项」五件事。
7. 口吻：公告、文档、汇报一律按「刚发布」写 —— 新鲜、及时、简洁，像内容刚上线；
   不写「之前提到」「待同步」「暂时」这类滞后或草稿感的话，也不写大段版本沿革。