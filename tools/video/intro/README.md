# HBMZZ 短片头生成器

在 `tools/video/intro/` 下用 Pillow 逐帧重绘品牌矢量图形，再用 ffmpeg 编码成 **8 秒静音 mp4**。纯本地、离线、可重复运行——不联网、不依赖系统字体安装。所有几何与颜色都从 `docs/assets/brand/logo-mark.svg`（64 网格）与 `logo-lockup.svg`（横排版）派生，不修改品牌资产目录。

## 一条命令重新生成

在仓库根目录执行：

```bash
py -3.12 tools/video/intro/make_intro.py
```

输出：`tools/video/intro/out/hbmzz-intro.mp4`

说明：
- 需要 Python 3.12 + Pillow（`py -3.12 -c "import PIL"` 可自检）与 PATH 中的 ffmpeg。
- 若直接写 `python tools/video/intro/make_intro.py` 而当前解释器没有 Pillow，脚本会自动尝试切换到 `py -3.12` 重跑；都不行就明确报错，不会静默降级。
- 字体随脚本附带在 `fonts/DejaVuSansMono-Bold.ttf`，无需系统安装。
- 已验证：相同输入两次运行产出**字节完全相同**的 mp4（md5 `0a5b1e09fab4b327849d439cb0ced31f`）。

若同时需要刷新播放器可识别的 zip 包，在重生成 mp4 后再跑一次（zip 由 `package_intro.py` 从 mp4 抽帧产出，帧必须与 mp4 一致）：

```bash
python tools/video/intro/package_intro.py
```

zip 的结构与 Java `VideoLibrary` 验收见同目录 `VALIDATE.md`。

## 输出规格

| 项 | 值 |
|---|---|
| 路径 | `tools/video/intro/out/hbmzz-intro.mp4` |
| 编码 | h264 / yuv420p |
| 分辨率 | 960 × 540 |
| 帧率 / 帧数 | 24 fps / 192 帧 |
| 时长 | 8.000 s |
| 音轨 | 无（1 条视频流，`-an`） |

## 三段式时间轴

帧号 1-based，时刻 `t = (n−1)/24`。阶段边界按 SPEC.md：帧 1–53 / 54–164 / 165–192。

| 阶段 | 时间 | 帧 | 内容 |
|---|---|---|---|
| 一、开场引入 | 0.0–2.2s | 1–53 | 深底 `#0a0c0f` 上，屏框描边从左上角起顺时针逐边显现（帧 1 已有可见笔画，非黑屏）；四螺钉、H 立柱、琥珀横杠随后淡入（帧 30–52），帧 53 完整主标静止一帧。 |
| 二、Logo 动态变形 | 2.2–6.8s | 54–164 | **2a 拆解**（54–82）：11 个原子部件沿不同方向小幅飞散并轻微旋转，屏框四边张开成断框，琥珀横杠全程不淡出、不变色、不飞出安全区；**2b 重组**（83–116）：`easeInOutCubic` 精确回归 64 网格原坐标，旋转归零，无跳帧；**2c 缩放**（117–145）：整体绕画面中心 `1.00× → 1.18×`，`easeInOutCubic`；**2d 发光与过渡**（146–164）：琥珀柔和外发光渐入（强度 ≤0.28）、钢色极弱补光（≤0.10），发光层在原图之下，主标同步平移到横排位、HBMZZ 字样淡入。 |
| 三、最终定格 | 6.8–8.0s | 165–192 | 锁定为横排版：主标 + 等宽粗体 `HBMZZ` + 琥珀下划线，整体水平/垂直居中，四周留白 ≥8%（实测左右约 23%、上下约 34%）。末 28 帧完全静止，倒数两帧像素一致。 |

## 颜色与字体来源

- **颜色只用四种品牌色**（来自 `docs/assets/brand/README.md` 与 `site.css`）：

  | 角色 | 色值 | 用途 |
  |---|---|---|
  | 钢板 | `#e6ecf3` | 屏框、H 立柱、HBMZZ 字样 |
  | 琥珀 | `#f5a524` | 横杠、下划线、唯一外发光色 |
  | 螺钉 | `#48525f` | 四角螺钉 |
  | 墨（底） | `#0a0c0f` | 全片背景 |

  不引入第五种彩色，不写任何与「HBMZZ玩家社群」不一致的群名。锁版 SVG 的副标 `#93a0b0` 非四品牌色，片头按 SPEC 约定省略。

- **字体**：横排字样用随脚本附带的等宽粗体 `fonts/DejaVuSansMono-Bold.ttf`（对应站点字体栈 `Consolas / Cascadia Mono / Courier New / monospace` 的粗体等宽风格），字号 84px、字距 8px，实测字高约 61px（≥54px 要求）。字体缺失时退到 PIL 自带字体；两者都不可用则明确报错，不做静默变形替换。

## 调整与替换

- **改时长 / 帧率 / 帧数**：脚本按秒数基准换算阶段边界，因此同时改 `--fps` 与 `--frames` 即可整体缩放，例如 30fps / 240 帧仍是 8 秒：

  ```bash
  py -3.12 tools/video/intro/make_intro.py --fps 30 --frames 240
  ```

  若只改 `--frames` 不改 `--fps`，阶段时刻不变，多出的帧会延长最终定格段。

- **改输出路径**：`--out /path/to/intro.mp4`，父目录会自动创建。

- **改动画参数**：文件顶部常量集中定义——`BASE`（主标像素尺寸）、`SCALE_END`（缩放终点，默认 1.18）、`TXT_SIZE` / `TXT_TRACK`（字号与字距）、`T_A0`…`T_D1`（阶段二四个子阶段的秒数基准）、`GAP`（主标与字样间距）。11 个原子部件的几何、飞散方向与旋转角在 `PARTS` 列表里，坐标即 64 网格原值，改动时保持颜色只用四品牌色。

- **换字体**：把新的等宽 `.ttf` 放到 `fonts/` 下，并更新 `FONT_CANDIDATES` 的首选项。不要用非等宽字体替换，否则字距会与锁版不一致。

- **改品牌标本身**：先改 `docs/assets/brand/logo-mark.svg`，再同步本脚本 `PARTS` 的坐标；不要直接改本脚本的硬编码坐标而不改品牌源。

## 抽帧自检

`out/check/` 下保留了自检抽帧，可直观核对：

| 文件 | 校验点 |
|---|---|
| `first_f000001.png` | 首帧非黑屏：深底上已有可见屏框描边笔画 |
| `phase1_f000053.png` | 阶段一完成：完整主标齐全，琥珀横杠清晰可见（`#f5a524`） |
| `scatter_f000070.png` | 2a 拆解：四边张开、部件飞散旋转、琥珀横杠可见且在安全区内 |
| `reassemble_f000116.png` | 2b 到位：主标精确复原、四螺钉与琥珀横杠齐全 |
| `scale_f000135.png` | 2c 缩放：放大后细节锐利、未出安全区 |
| `glow_f000155.png` / `late_transition_f000160.png` | 2d：琥珀发光可见、主标与字样不重叠、钢色清晰 |
| `last_f000192.png` | 末帧定格：HBMZZ 清晰可读、琥珀横杠与下划线存在、留白充足、无抖动 |

重新抽帧示例（ffmpeg `n` 从 0 计）：

```bash
ffmpeg -y -i out/hbmzz-intro.mp4 -vf "select=eq(n\,191)" -vsync 0 -frames:v 1 out/check/last_f000192.png
```

## 规格验证

```bash
ffprobe -v error -show_entries stream=codec_name,width,height,pix_fmt,r_frame_rate,nb_frames \
  -show_entries format=duration,nb_streams -of default=noprint_wrappers=1 \
  tools/video/intro/out/hbmzz-intro.mp4
# 期望：h264 / 960x540 / yuv420p / 24/1 / 192 / 8.000000 / nb_streams=1
```
