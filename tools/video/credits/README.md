# 片尾致谢动画（credits）

HBMZZ 的片尾滚动字幕。配乐是这一版新写的，由 `tools/audio/ost_synth.py` 合成，
不读取 `docs/assets/audio/` 下的任何 mp3，也不按它们的旋律、调性或段落做对齐——
时间只跟画面走（2326 帧 / 12 fps / 193.833 s）。

输出三样东西：

- `out/credits.wav` — 配乐本体，44.1k / 16bit / 立体声
- `out/hbmzz-credits.mp4` — 配了新乐的成片，用来单独观看或存档
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

960×540 / 12 fps / 2326 帧 / 193.833 秒。配乐长度按 `FRAME_COUNT / FPS` 卡死，
末帧之后不留静音，也不提前收尾。改帧数就同步改 `ost_synth.render_credits()` 的
`CREDITS_SECONDS`（它直接读同一组常数）。

配乐结构：88 BPM，71 小节，D 小调 i–VI–III–VII 循环。前 12 小节薄，进 A 段加鼓与
旋律，桥段退回长钟（名单还在滚，音乐只退后一步），结尾收成一条长和弦，随画面一起静下去。
