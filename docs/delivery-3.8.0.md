# HBMZZ 3.8.0 交付文档

本文件记录 **3.8.0** 这条线上每一次交付：交了什么、产物在哪、怎么验证的、出问题怎么退回去。
以后每次改动都在文末「交付批次」追加一条，不新开文件——版本号固定 3.8.0，除非明确要求升版。

---

## 一、当前交付批次

| 项 | 值 |
| --- | --- |
| 版本 | 3.8.0（build.gradle / mcmod.info / HBMZZ.VERSION 三处一致） |
| 代码基线 | 3.7.1 = upstream master 快照，提交 `26195b1`（2026-11-17） |
| 当前提交 | `faa7f85`（修复启动崩溃） |
| 游戏侧模组 | `hbmzz-3.8.0.jar`（23,696,423 字节） |
| 模组 SHA-256 | `985dd964dc7b96aa49cfc93df99b04c72984afec39a8905d391dcb23286bc472` |
| 源码包 | `hbmzz-3.8.0-sources.jar`（build/libs，8,793,935 字节，sha256 `2314f282…`） |
| 源码仓库 | `C:\Users\37594\WorkBuddy\Claw\HBMZZ-MOD`（分支 master） |
| 部署位置 | `E:\pcl2\.minecraft\versions\DFC+RBMK 1.0.0\mods\hbmzz-3.8.0.jar` |
| 旧版归档 | `E:\pcl2\.minecraft\versions\DFC+RBMK 1.0.0\backupsmod\hbmzz-old-jars\` |

部署命令固定为 `python tools/deploy.py`（`deploy.local.json` 已指向该 mods 目录）。
脚本会比对 sha256、把旧 jar 改名并归档到 `backupsmod\hbmzz-old-jars`，`mods` 里只留当前版本。

---

## 二、3.8.0 交付内容

### 1. 视频大屏（新增）

- 方块 `hbmzz:video_screen`，可 1×1 ~ 3×3 拼接，潜行右键循环尺寸。
- 拆任意一格整块消失；与 DFC 监控屏共用填充板 `BlockDFCMonitorPart`，但加了守卫，不会误拆邻近的监控屏。
- 连上视频广播器后，动画**直接渲染在方块表面**，不使用静态贴图，也不是弹窗。
- 右键打开观看界面；世界内多块大屏共用一份解码器与时钟，超过 96 格冻结，默认静音（避免多屏叠音）。

### 2. 观看界面（改造）

- 从全屏接管改成底部悬浮面板：高度不超过屏幕 52%，不再遮挡视野。
- `F` 切全屏 / 退出全屏，`ESC` 关闭。
- 广播模式仍是单次播放，放完自动退出；观看窗口按包声明的循环方式执行，单次播完停在最后一帧并提示结束。

### 3. 序列帧播放器（重写）

- `VideoClip` 从「文件列表」重构为「文件集 + 时间线」：文件只解一次，时间线槽位可以重复引用同一张图，定格与分段重复不额外占图片。
- 顺序：默认按文件名自然排序（`f2` 在 `f10` 前，不要求补零）；`"order":"list"` + `"playlist"` 显式指定；也认安卓开机动画的 `desc.txt`（`p 2 0 part0` = 播两遍，`p 1 1.5 part1` = 播完定格 1.5 秒）。
- 帧率：`fps` 支持 0.5~60 的小数，`1/fps` 即每张图的停留时间。
- 循环：`"loop": true`（默认）/ `false`（停在最后一帧）/ `"pingpong"`（往返）/ 数字（播 N 遍）。广播走 `asSinglePass()` 强制单次。
- 播放头是单调递增的位置，循环接缝与往返倒放都由解码线程顺序预取，不回退重解；后台线程解码，只保留有限帧环，丢帧不排队。
- 音画同步：音轨与第一帧同一时钟启动，每个循环重新对齐；画面为主时钟，漂移超过 300ms 才 seek；`audioOffset` 跳过音轨开头空白；往返倒放段静音。
- 视频源目录仍在首次加载时自动创建并落 `README.txt`（3.7.1 已有行为，本次未改动）。

### 4. DFC 监控屏（仅优化）

- 卡顿根因：每帧重建 `ConsoleModel` 的 Box/Face。
- 改为按「列数 / 行数 / 倾角 / 通电 / 吊装」打包成 long 型 key 缓存几何，带容量上限与清理入口。
- 外观、尺寸、逻辑全部沿用，监控屏自身既有缺陷不在本次范围。

### 5. 移植的两件东西（来自 3.7.0 分支）

- **防爆窗帘**：没有照搬 3.7.0 的旧门实现，而是接回 3.7.1 的门体系做成一个变体（`BlockBlastCurtain` + 面板 + `TileBlastDoor` 的 `curtain` 状态）。铁铲潜行右键放行，3×3×3 范围内需要玻璃。
- **纪念挂画**：走 `RenderWorldLastEvent` 而非 TESR，带一条 chant 音效（`ModSounds` 只注册这一条）。

### 6. 工具与文档

- `tools/video/pack.py`：新增小数 `--fps`、`--loop`、`--passes`、`--audio-offset`、`--audio`，帧名按自然序重排成 `f000001` 起。
- 视频目录 `README.txt` 与 `docs/guide.html` 写清包格式、顺序、帧率、循环、同步规则。
- `docs/changelog.html` 与 `tools/site/content.json` 补 v3.8.0 条目。
- 版本号固定 3.8.0，三处对齐；提交 `c1b1b78` 起不再随改动升版。

### 7. 启动崩溃修复（批次 #3）

- **症状**：客户端在 `Initializing game` 阶段直接退出，堆栈指向
  `BlockBlastDoor.<init>` 与 `ModBlocks.<clinit>`，报
  `IllegalStateException: Attempted to set registry name with existing registry name! New: rsaudio:blast_door Old: rsaudio:blast_door`。
- **根因**：为了让 `tools/regcheck` 能静态读到注册名，在 `BlockBlastDoor` / `BlockBlastDoorPanel`
  的无参构造函数里补写了一行字面量 `setRegistryName(RsAudio.MODID, "…")`；
  而被委托的父类构造函数里已经用同样的名字设过一次。`IForgeRegistryEntry` 的注册名只能设一次，
  第二次直接抛异常，而这个初始化发生在 static 字段里，所以整个客户端启动即死。
  `BlockBlastCurtain` / `BlockBlastCurtainPanel` 同理，只是还没轮到它们就崩了。
- **修法**：父类构造函数不再设注册名（只设显示名），注册名一律由具体类用字面量设一次；
  既满足 regcheck 的静态读取，也只调用一次。
- **防复发**：regcheck 新增第 64 条守卫「同一个类里 setRegistryName 只允许出现一次」。
  反向验证：把重复的那行临时塞回 `BlockBlastDoor`，检查当场 FAIL 并指出文件；移除后恢复 PASS。
- **部署工具同时修了一处误伤**：`tools/deploy.py` 原来用前缀 `hbmzz-` 判断「我们自己的 jar」，
  把第三方附加包 `hbmzz-addon-0.1.0.jar` 当成旧版归档走了。现在判据改成
  `hbmzz-<数字版本>.jar`（`OWN_JAR` / `OWN_BACKUP` 两个正则），已误归档的附加包已恢复回 `mods`。

---

## 三、验证记录

| 项目 | 结果 |
| --- | --- |
| `./gradlew build -x test --offline` | BUILD SUCCESSFUL，产出 `build/libs/hbmzz-3.8.0.jar` |
| jar 内 `mcmod.info` | modid `hbmzz`，version `3.8.0` |
| regcheck（注册 / 配方 / 模型表） | 64/64（新增「注册名只许设一次」守卫） |
| statecheck（blockstate 键序 / 状态数） | 4/4 |
| videocheck（顺序 / 帧率 / 循环 / 同步） | 55/55 |
| linkercheck（连接器握手） | 21 通过，1 跳过 |
| rbmkcheck | 50/50 |
| termcheck | 67/67 |
| cecpcheck | 8/8 |
| `checkall.py --fast` | 7/7 全 PASS |
| 部署校验 | 目标目录 jar 与构建产物 sha256 一致 |

---

## 四、已知限制

- 不内置 H.264 解码：运行时只吃图片序列 + WAV（也认 aiff/au）。ffmpeg 只在 `pack.py` 里离线抽帧用。
- 渲染观感、多屏同放的显存占用、窗帘开合手感**没有进游戏看过**，只有编译与离线检查。
- 世界内大屏默认静音是设计取舍（多屏共用一份播放，出声会叠音）。
- 3.7.0 分支的四向开合门、自定义贴图系统、旧 mixin 与运行时 FFmpeg 管线没有并入。

---

## 五、回滚

1. 把 `backupsmod\hbmzz-old-jars\` 里对应的 `*.jar.bak` 改回 `*.jar` 放回 `mods`。
2. 删掉 `mods\hbmzz-3.8.0.jar`（或一并归档）。
3. 源码侧回滚到 `26195b1` 即完全回到 3.7.1 基线。

---

## 六、交付批次

| 批次 | 提交 | 内容 | 产物 sha256（前 12 位） |
| --- | --- | --- | --- |
| 2026-11-18 #1 | `7c4948f` `3bbe4aa` `bd8a7a0` | 防爆窗帘 + 纪念挂画 + DFC 几何缓存；视频大屏与观看界面悬浮化；序列帧播放器（顺序 / 帧率 / 循环 / 音画同步） | `317f7bd4…`（3.9.0 标签期） |
| 2026-11-18 #2 | `c1b1b78` | 版本号回退并固定在 3.8.0，功能全部保留；重新出包并部署，3.9.0 归档 | `0895d885…` |
| 2026-11-18 #3 | `faa7f85` | 修复启动崩溃（blast_door 注册名设两次）；regcheck 加第 64 条守卫；deploy.py 不再误归档 `hbmzz-addon` | `985dd964…` |

> 说明：批次 #1 的功能与批次 #2 的 jar 内容一致，差别只在三处版本戳。批次 #1 当时误升到 3.9.0，
> 已按指示回退，3.9.0 的 jar 保留在 `backupsmod\hbmzz-old-jars\hbmzz-3.9.0.jar.bak`。

---

## 七、每次交付的固定动作

1. 编译：`./gradlew build -x test --offline`，确认 `build/libs/hbmzz-3.8.0.jar` 的 `mcmod.info` 版本是 3.8.0。
2. 检查：`python tools/checkall.py --fast`，再按改动范围补跑对应单测（视频改动的跑 videocheck）。
3. 部署：`python tools/deploy.py`，旧 jar 自动归档到 `backupsmod\hbmzz-old-jars`。
4. 文档：在本文件「六、交付批次」追加一行，更新「一、当前交付批次」的提交与哈希，需要时补「四、已知限制」。
5. 公告：`tools/site/content.json` 的 `timeline` 与 `docs/changelog.html` 同步新增/补充当前版本条目，然后
   `python tools/site/sync_site.py` 刷新数据层。
6. 汇报：向用户给出「模组 jar 路径 + 源码位置/提交 + 本次改了什么 + 验证结果 + 未验证项」五件事。
7. 口吻：公告、文档、汇报一律按「刚发布」写 —— 新鲜、及时、简洁，像内容刚上线；
   不写「之前提到」「待同步」「暂时」这类滞后或草稿感的话，也不写大段版本沿革。
