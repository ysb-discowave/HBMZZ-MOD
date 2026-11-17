# 片尾致谢动画（credits）

HBMZZ 的片尾滚动字幕，用 `The ending of melting` 作 BGM，输出两样东西：

- `out/hbmzz-credits.mp4` — 带音轨的成片，用来单独观看或存档
- `out/hbmzz-credits.zip` — 视频广播器能直接播的包（放客户端 `config/hbmzz/videos/`）

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

960×540 / 12 fps / 2326 帧 / 193.83 秒（与 BGM 的 193.817 秒对齐，误差 16ms，小于 1 帧）。
换 BGM 时改 `make_credits.py` 顶部的 `FRAME_COUNT`（= 时长 × 12）。
