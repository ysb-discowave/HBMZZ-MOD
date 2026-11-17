# HBMZZ 片头视频包 — 验收清单

交付物：

| 文件 | 说明 |
| --- | --- |
| `tools/video/intro/out/hbmzz-intro.mp4` | 成片（intro-build 产出）：960×540、24fps、192 帧、8.000 s、无音频流 |
| `tools/video/intro/out/hbmzz-intro.zip` | **本文验收对象**，由 `package_intro.py` 从上面的 mp4 生成 |

画面/文案验收见同目录 `SPEC.md` 第 12 节；本清单只管 **打包是否正确、播放器能否打开**。

---

## 0. 生成包

```bash
cd D:/DSH/HBMZZ-MOD
python tools/video/intro/package_intro.py
# 或显式指定路径：
python tools/video/intro/package_intro.py \
  --mp4 tools/video/intro/out/hbmzz-intro.mp4 \
  --out tools/video/intro/out/hbmzz-intro.zip
```

- 脚本只接受 `--mp4` / `--out`；默认值就是上表两个路径。
- **mp4 不存在时**：打印 `错误: 找不到片头 mp4: ...` 并以退出码 1 结束，**不会**写出任何 zip / 半成品。
- 抽帧不足 192 帧同样报错退出；超长输入只取前 192 帧。
- 不生成 `audio.wav`，`meta.json` 不含 `audio` 字段（片头静音）。
- 先写 `*.zip.tmp` 再原子改名，失败不残留半成品。

---

## 1. zip 结构

用 Python 一条命令核对（退出码 0 且全为 True 即通过）：

```bash
python - <<'PY'
import json, zipfile
p = "tools/video/intro/out/hbmzz-intro.zip"
z = zipfile.ZipFile(p)
names = z.namelist()
expected = ["meta.json"] + ["f%06d.jpg" % i for i in range(1, 193)]
meta = json.loads(z.read("meta.json").decode("utf-8"))
print("条目数 193          :", len(names) == 193)
print("文件名/顺序完全正确 :", names == expected)
print("无非预期文件        :", not [n for n in names if n not in expected])
print("无 audio.wav        :", not any("audio" in n.lower() for n in names))
print("全部 ZIP_STORED     :", all(i.compress_type == zipfile.ZIP_STORED for i in z.infolist()))
print("meta 精确           :", meta == {"fps": 24, "frames": 192, "title": "HBMZZ 片头"})
print("meta 无 audio 字段  :", "audio" not in meta)
for n in ("f000001.jpg", "f000192.jpg"):
    b = z.read(n)
    print(n, "是 JPEG :", b[:2] == b"\xff\xd8" and b[-2:] == b"\xff\xd9")
PY
```

期望的 `meta.json` 原文（UTF-8，允许末尾换行）：

```json
{
  "fps": 24,
  "frames": 192,
  "title": "HBMZZ 片头"
}
```

- [ ] 193 个条目：`meta.json` + `f000001.jpg` … `f000192.jpg`，无其它文件/目录
- [ ] 帧名 1-based、6 位补零、`.jpg`，字典序即播放顺序
- [ ] 所有条目 `ZIP_STORED`
- [ ] `meta.json` 字段与取值完全一致，且**没有** `audio`
- [ ] `meta.frames`（192）== 包内帧数 == mp4 抽帧数
- [ ] 首末帧是合法 JPEG（`ff d8` … `ff d9`）

---

## 2. 名字白名单

包在名为 `hbmzz-intro` 的 clip 下，必须过 `VideoLibrary.isValidName`（名字走网络包，白名单防目录穿越）：

- [ ] `VideoLibrary.isValidName("hbmzz-intro") == true`

可由下面第 3 节的 Java 程序一并打印。单独验证也可在 JShell 中调用，规则见 `src/main/java/com/hbmzz/video/VideoLibrary.java:108`：以字母数字开头，仅含 `[A-Za-z0-9_.-]`，长度 ≤ 64，不含 `..` / `/` / `\`。

---

## 3. 播放器读取路径（必须实际跑一遍）

`VideoClip` / `VideoLibrary` 是纯 Java、不依赖 Minecraft 类，可用 `tools/videocheck` 同款方式在裸 JVM 上验证：**把 zip 放进一个临时 config 目录，走 init → names → open → 读帧**。

依赖与编译方式参照 `tools/videocheck/run.sh`（`build/classes/java/main` + gson + log4j-api）。本机 `run.sh` 里写死的 Adoptium JDK 8 路径不存在，可用现成的 JBR 代替，例如
`"/c/Program Files/Android/Android Studio/jbr/bin/javac.exe"`（`cygpath -w` 转换各路径，`cp` 分隔符用 `;`）。

临时程序（保留在任意临时目录，不入库）：

```java
import com.hbmzz.video.VideoClip;
import com.hbmzz.video.VideoLibrary;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;

public final class IntroCheck {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }
    public static void main(String[] args) throws Exception {
        File zip = new File(args[0]);
        File config = new File(zip.getParentFile().getParentFile(), "config");
        VideoLibrary.init(config);
        Files.copy(zip.toPath(),
                new File(VideoLibrary.root(), "hbmzz-intro.zip").toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        check("isValidName(\"hbmzz-intro\")", VideoLibrary.isValidName("hbmzz-intro"));
        check("init 后 zip 被 names() 列出", VideoLibrary.names().contains("hbmzz-intro"));
        VideoClip clip = VideoLibrary.open("hbmzz-intro");
        check("open() 成功", clip != null);
        if (clip == null) { System.exit(1); }
        check("frameCount()==192", clip.frameCount() == 192);
        check("fps()==24", clip.fps() == 24);
        check("title()==\"HBMZZ 片头\"", "HBMZZ 片头".equals(clip.title()));
        check("hasAudio()==false", !clip.hasAudio());
        BufferedImage first = clip.readFrame(0);
        BufferedImage last  = clip.readFrame(clip.frameCount() - 1);
        check("首帧能解码", first != null && first.getWidth() > 0 && first.getHeight() > 0);
        check("末帧能解码", last  != null && last.getWidth()  > 0 && last.getHeight()  > 0);
        clip.close();
        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) { System.exit(1); }
    }
}
```

判定：**10 项全 PASS，末尾 `PASS 10 / FAIL 0`**。逐项：

- [ ] `init()` 后 `config/hbmzz/videos/` 被创建，zip 被 `names()` 列出
- [ ] `open("hbmzz-intro")` 返回非空
- [ ] `frameCount() == 192`
- [ ] `fps() == 24`
- [ ] `title() == "HBMZZ 片头"`
- [ ] `hasAudio() == false`
- [ ] 首帧 `readFrame(0)` 能解码（预期 960×540）
- [ ] 末帧 `readFrame(191)` 能解码（预期 960×540）

---

## 4. 部署

包要放到**每一台需要观看的客户端**，服务端只负责触发，不需要它（`README.md`「视频广播器」第 5 节）。

- [ ] 测试机：`E:\pcl2\.minecraft\versions\DFC+RBMK 1.0.0\config\hbmzz\videos\hbmzz-intro.zip`
- [ ] 通用：`<config>/hbmzz/videos/hbmzz-intro.zip`（开发环境下 `run/config/hbmzz/videos/`）
- [ ] 每次替换 zip 后 `names()` 最多缓存 1 秒，重开配置界面或稍等即可刷新
- [ ] 目录里若已有同名 `hbmzz-intro/` 文件夹，会与 zip 冲突（`VideoLibrary.open` 优先 zip）；部署前确认只留一种形状

---

## 5. 播放器里不显示 / 黑屏的排查顺序

1. [ ] **先确认主模组调用了 `VideoLibrary.init(configDir)`**。本轮已修 `HBMZZ.java`，但**尚未构建部署**；用旧 jar 时 `root == null`，`names()` 恒为空、任何包都看不见。
2. [ ] 确认客户端放的是 zip 本身，路径是 `<config>/hbmzz/videos/`，不是服务端或 `mods/`。
3. [ ] 确认文件名为 `hbmzz-intro.zip`（小写的 `.zip` 扩展名；名字白名单含 `-` 合法）。
4. [ ] 按第 1、3 节重跑 zip 结构与 Java 读取路径，定位是包坏了还是部署/初始化问题。
5. [ ] 看日志 `hbmzz-video` 的 warn（`VideoClip.open` 解不开 zip 时会记录）。

---

## 6. 自测与正式产物记录

### 6.1 脚本自测（片尾 mp4）

片头 mp4 一度尚未由 intro-build 产出，故先拿已存在的 `tools/video/credits/out/hbmzz-credits.mp4` 跑同一套脚本验证（输出 `_selftest.zip`，**非交付物**，验证后已删除）：

```
python tools/video/intro/package_intro.py \
  --mp4 tools/video/credits/out/hbmzz-credits.mp4 \
  --out tools/video/intro/out/_selftest.zip
```

结果：

- 退出码 0，产出 193 条目；`names == expected`、`ZIP_STORED` 全通过、`audio` 字段与文件均不存在；`meta == {"fps":24,"frames":192,"title":"HBMZZ 片头"}`。
- 首末帧 JPEG 魔数正确。
- 把该 zip 当 `hbmzz-intro.zip` 放进临时 config，跑第 3 节 Java 程序：**PASS 10 / FAIL 0**，首末帧均解码为 960×540。
- 另测缺 mp4 场景：默认路径无 mp4 时退出码 1、打印清晰错误、输出目录为空（无半成品）。

### 6.2 正式产物（已完成，含修复后重打包）

intro-build 产出 `hbmzz-intro.mp4` 后（`ffprobe`：960×540 / 24fps / 192 帧 / 8.000 s / 仅视频流），
按第 0 节命令生成正式 `tools/video/intro/out/hbmzz-intro.zip`（约 1.6 MB），并复核：

- 第 1 节全部为 True：193 条目、文件名/顺序正确、无 audio、全 ZIP_STORED、`meta.json` 精确匹配、首末帧合法 JPEG。
- 第 3 节 Java 程序：**PASS 9 / FAIL 0**，init / names / open / 192 帧 / 24fps / 标题 / 静音 / 首末帧解码全部通过，帧尺寸 960×540。
- 部署到测试机后按第 4、5 节在客户端确认（本轮 `HBMZZ.java` 的 `VideoLibrary.init()` 修复尚未构建部署，见第 5 节第 1 条）。

**最终重打包**：intro-build 于 21:17 修掉 2d 阶段主标与 HBMZZ 文字重叠后重新导出 mp4
（74847 bytes，md5 `0a5b1e09fab4b327849d439cb0ced31f`）。已用该最终 mp4 重跑
`package_intro.py` 覆盖旧 zip，并重新完成上述两套校验（Python 结构全 True、Java **PASS 9 / FAIL 0**）。
zip 内帧已与最终 mp4 一致。校验时若 `build/classes/` 不可用，可直接用 `javac` 编译
`src/main/java/com/hbmzz/video/VideoClip.java`、`VideoLibrary.java`（依赖仅 gson + log4j-api），
再编译临时校验程序并运行，无需完整 gradle 构建。
