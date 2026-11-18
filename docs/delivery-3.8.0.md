# HBMZZ 3.8.0 交付文档

本文件记录 **3.8.0** 这条线上每一次交付：交了什么、产物在哪、怎么验证的、出问题怎么退回去。
以后每次改动都在文末「交付批次」追加一条，不新开文件——版本号固定 3.8.0，除非明确要求升版。

---

## 一、当前交付批次

| 项 | 值 |
| --- | --- |
| 版本 | 3.8.0（build.gradle / mcmod.info / HBMZZ.VERSION 三处一致） |
| 代码基线 | 3.7.1 = upstream master 快照，提交 `26195b1`（2026-11-17） |
| 当前提交 | `d1eabf0`（批次 #6：大屏重做 + 遥控器 + 播放器设置） |
| 游戏侧模组 | `hbmzz-3.8.0.jar`（23,698,711 字节） |
| 模组 SHA-256 | `936c09c1939519ea17cf0b8731cd5ec5056c7577c4aa5c13541c84a2246e5668` |
| 源码包 | `hbmzz-3.8.0-sources.jar`（build/libs，8,799,358 字节，sha256 `509da4be…`） |
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

- 保留仓库原样子：右键开配置窗口（片名 + 选择器），红石上升沿触发。
- **接上大屏之后**，红石只做开 / 停：上升沿让大屏开始播放，下降沿让它停；
  误触拉杆不会把任何人切进全屏。
- **没接大屏时**保持原逻辑：上升沿直接全屏接管，按「强制限制退出」决定能不能 ESC 中途退出。
- 大屏所在区块没加载时不会偷偷降级成全屏，只写一行状态；大屏被拆则自动解绑，拉杆回到原行为。
- 首次 tick 只「采纳」当前信号、不触发任何动作（登录在通电的拉杆旁边不会被突然接管）。

### 4. 音量与「是否强制限制退出」

- 从大屏搬到播放器：蹲下右键播放器打开，窗口里只有一条 0–200% 音量滑条与一个开关。
- 开启后播放结束前 ESC 只提示、不退出；关闭后 ESC 立刻放行。
- 两项随广播包走（片名 + 音量 + 退出策略），全屏与大屏两条路径用同一套设置。
- 滑条是自绘的（`GuiVideoPlayerConfig`）：0.5 基线 × 百分比，100% = 默认音量，200% 才真的更响
  （音频层在 1.0 处夹紧；基线若设成 0.65，滑条后半段会没有反应）。

### 5. 视频目录：运行中换片

- 广播器窗口新增「打开视频文件夹」（`Desktop.open`，失败时把路径写进状态行）与「刷新列表」。
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

### 7. 保留的既有交付

- 防爆窗帘、纪念挂画（3.7.0 分支移植）。
- DFC 监控屏几何缓存（按列 / 行 / 倾角 / 通电 / 吊装缓存 `ConsoleModel`）。
- 启动崩溃修复：`rsaudio:blast_door` 注册名只设一次，父类只设显示名；regcheck 第 64 条守卫。

---

## 三、验证记录

| 项目 | 结果 |
| --- | --- |
| `./gradlew build --offline`（JDK 8 / Gradle 4.10.3，离线） | BUILD SUCCESSFUL，产出 `build/libs/hbmzz-3.8.0.jar` |
| jar 内 `mcmod.info` | modid `hbmzz`，version `3.8.0` |
| regcheck（注册 / 配方 / 模型表） | **65/65**（新增第 65 条「遥控器要连的方块必须让出右键」；第 64 条仍是注册名守卫） |
| statecheck（blockstate 键序 / 状态数） | 4/4 |
| videocheck（视频库 / 名字白名单） | 26/26 |
| linkercheck（连接器握手） | 21 通过，1 跳过（CE 不在 classpath） |
| rbmkcheck | 50/50 |
| termcheck | 67/67 |
| cecpcheck | 8/8 |
| gatecheck / dfccheck / reasimcheck | 全 PASS |
| `python tools/checkall.py --fast` | 7/7 全 PASS（含站点数据一致性） |
| 部署校验 | 目标目录 jar 与构建产物 sha256 一致：`936c09c1…` |
| 视频包解析（模组 `VideoClip` 实跑） | 片头 192 帧 / 24 fps / 8.0000 s / **无音轨**；片尾 2326 帧 / 12 fps / 193.8333 s / 有音轨 |
| 贴图 | 4 张方块贴图 + 1 张物品贴图，16×16，`make_video_textures.py` 可重跑 |
| 网络 | 新消息 id 24 / 25 / 26，与既有 23 个 id 不冲突（regcheck「包序号唯一」通过） |

---

## 四、已知限制

- **没有进游戏实测**：大屏四个朝向的画面、32×32 的铺格手感、倍速下的观感、音量滑条、
  遥控器两步连接，全部只经过编译、静态检查与离线解析。这是本批最大的一条未验证项。
- 世界内大屏在同一客户端最多同时解码 **4 路**（超出的大屏保持黑屏，重新扳一次拉杆可再试），
  目的是不让一屋子屏幕各开一条解码线程。
- 音量 200% 受系统混音器 1.0 上限约束，响度到顶即止，不会破音。
- F7 只在没有打开 GUI 时响应（原版按键状态在 GUI 内不更新）；窗口里有「刷新列表」按钮。
- 大屏没有音轨时就是静音播放，播放器音量对它无效。
- 帧率仍取包的 `fps`（整数），倍速只改播放头速度，不改解码帧率。
- 3.7.0 分支的四向开合门、自定义贴图系统、旧 mixin 与运行时 FFmpeg 管线没有并入。

---

## 五、回滚

1. 把 `backupsmod\hbmzz-old-jars\` 里对应的 `*.jar.bak` 改回 `*.jar` 放回 `mods`。
2. 删掉 `mods\hbmzz-3.8.0.jar`（或一并归档）。
3. 源码侧回滚到 `26195b1` 即完全回到 3.7.1 基线；只想退回上一版大屏与配乐，回滚到 `d5ed30b`。
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
