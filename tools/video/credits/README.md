# 片尾致谢动画（credits）

HBMZZ 的片尾滚动字幕。两个版本：

- **原版** — `The ending of melting` 作 BGM，白字黑底
- **核弹重制版** — `meltdown.ogg`（288 秒）作 BGM，深红黑底 + 上下警报条纹 + 红/橙/黄警示配色，名单额外加了三位特别致谢

每版输出两样东西（`out/`）：

- `hbmzz-credits*.mp4` — 带音轨的成片，用来单独观看或存档
- `hbmzz-credits*.zip` — 视频广播器能直接播的包（放客户端 `config/hbmzz/videos/`）

## 生成

```sh
python tools/video/credits/make_credits.py                  # 原版
python tools/video/credits/make_credits.py --theme meltdown # 核弹重制版
```

核弹版的完整命令（脚本内置默认值，等价于上面第二条）：

```sh
python tools/video/credits/make_credits.py --theme meltdown \
  --names tools/video/credits/names.meltdown.json \
  --bgm docs/assets/audio/meltdown.ogg --frames 3456 \
  --out hbmzz-credits-meltdown
```

`--frames` = 时长秒 × 12（播放器帧率）向上取整。核弹版 BGM 288s → 3456 帧。
`--bgm` / `--names` 传相对路径也行，脚本会自动转绝对路径。

## 换昵称

只改 `names.json`，然后：

```sh
python tools/video/credits/make_credits.py
```

脚本会重新生成 ASS、渲染 mp4、抽帧、转 WAV、打 zip，全部覆盖 `out/` 下的旧文件。

`names.json` 的结构：`title` / `subtitle`、`sections[]`（每组一个 `header` + `people[]`（`name` / `role`）+ 可选 `note`）、`outro[]`。

## 为什么是图片序列

1.12.2 的客户端只有 JRE，没有 H.264 解码器；播放器读的是「连续编号 JPG + audio.wav」。
mp4 只是给人看的中间产物，真正进游戏的是 zip。

## 参数

960×540 / 12 fps。原版 2326 帧 / 193.83 秒（对齐 BGM 193.817s），核弹版 3456 帧 / 288.00 秒。
换 BGM 时用 `--frames` 传新帧数（= 时长 × 12 向上取整），不用改脚本。
