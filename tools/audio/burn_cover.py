#!/usr/bin/env python3
# HBMZZ - Minecraft 1.12.2 Forge mod
# Copyright (C) 2026 HBMZZ contributors
#
# This program is free software: you can redistribute it and/or modify it under
# the terms of the GNU General Public License as published by the Free Software
# Foundation, version 3. See LICENSE.

"""把 OST 封面「烧」进音频文件本身。

    python tools/audio/burn_cover.py          # 写入 / 覆盖
    python tools/audio/burn_cover.py --check  # 只核对，不写

两套文件、两种容器，但用户看到的是同一张图：

  docs/assets/audio/*.mp3                      → ID3v2.3 APIC（front cover）
  src/main/resources/assets/hbmzz/sounds/ost/*.ogg → Vorbis comment 里的
                                                METADATA_BLOCK_PICTURE

**为什么不用 ffmpeg**：这台机器的 ffmpeg 8.1 的 ogg 封装器根本不接受 attached_pic
（`Unsupported codec id in stream 0`），mp3 能写、ogg 不能，于是两边会不一致。所以统一走
mutagen：mp3 与 ogg 用的是同一张 PNG、同一段代码，不会一边有一边没有。

**为什么不用 1024 那张封面**：1024² 是 313 KB，塞三份就是 1 MB，而系统媒体面板（锁屏、
播放器小组件）本来也不会按 1024 显示。用 `ost-cover-512.png`（96 KB）。

注意：**改完音频要重新构建 jar**，包装的是 `src/main/resources` 里的那份 ogg。
"""

import argparse
import base64
import io
import os
import sys

try:
    from mutagen.flac import Picture
    from mutagen.id3 import ID3, APIC, ID3NoHeaderError, TALB, TIT2, TPE1, TRCK
    from mutagen.oggvorbis import OggVorbis
except ImportError:
    raise SystemExit("需要 mutagen：python -m pip install mutagen")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
COVER = os.path.join(ROOT, "docs", "assets", "brand", "ost-cover-512.png")

MP3 = [
    os.path.join(ROOT, "docs", "assets", "audio", name)
    for name in ("how-to-survive.mp3", "when-will-it-end.mp3", "the-ending-of-melting.mp3")
]
OGG = [
    os.path.join(ROOT, "src", "main", "resources", "assets", "hbmzz", "sounds", "ost", name)
    for name in ("how-to-survive.ogg", "when-will-it-end.ogg", "the-ending-of-melting.ogg")
]

MIME = "image/png"

# 标签由这里统一写，mp3 与 ogg 不许各说各话：之前 ogg 的 artist 是 "Yang Sibo"、
# 站点那份 mp3 是 "HBMZZ"，同一首歌在锁屏和下载后看到两个作者名。
ARTIST = "Yang Sibo"
ALBUM = "HBMZZ OST"
TRACKS = {
    "how-to-survive": ("How to survive?", "1"),
    "when-will-it-end": ("When will it end?", "2"),
    "the-ending-of-melting": ("The ending of melting", "3"),
}


def tags_for(path):
    """按文件名反查 (标题, 轨号)；文件名是 <stem>.mp3 / <stem>.ogg。"""
    stem = os.path.splitext(os.path.basename(path))[0]
    return TRACKS[stem]


def picture_block(data, width, height):
    """FLAC picture block（Vorbis 的 METADATA_BLOCK_PICTURE 就是它的 base64）。

    type=3 是 "front cover"。depth=32（RGBA）—— 播放器只用前 24 位，但写错位数
    会让某些严格的解析器直接丢掉这张图。
    """
    pic = Picture()
    pic.type = 3
    pic.mime = MIME
    pic.desc = "HBMZZ OST cover"
    pic.width = width
    pic.height = height
    pic.depth = 32
    pic.colors = 0
    pic.data = data
    return pic.write()


def burn_mp3(path, data, width, height):
    try:
        tags = ID3(path)
    except ID3NoHeaderError:
        tags = ID3()
    keys = [k for k in tags.keys() if k.startswith("APIC")]
    for k in keys:
        del tags[k]
    tags.add(APIC(encoding=3, mime=MIME, type=3, desc="HBMZZ OST cover", data=data))
    title, track = tags_for(path)
    tags.setall("TIT2", [TIT2(encoding=3, text=[title])])
    tags.setall("TPE1", [TPE1(encoding=3, text=[ARTIST])])
    tags.setall("TALB", [TALB(encoding=3, text=[ALBUM])])
    tags.setall("TRCK", [TRCK(encoding=3, text=[track])])
    tags.save(path, v2_version=3)
    return len(data)


def burn_ogg(path, data, width, height):
    audio = OggVorbis(path)
    title, track = tags_for(path)
    audio["title"] = [title]
    audio["artist"] = [ARTIST]
    audio["album"] = [ALBUM]
    audio["tracknumber"] = [track]
    audio["metadata_block_picture"] = [
        base64.b64encode(picture_block(data, width, height)).decode("ascii")
    ]
    audio.save()
    return len(data)


def has_cover(path, ogg):
    if ogg:
        audio = OggVorbis(path)
        return bool(audio.get("metadata_block_picture"))
    try:
        tags = ID3(path)
    except ID3NoHeaderError:
        return False
    return any(k.startswith("APIC") for k in tags.keys())


def main():
    ap = argparse.ArgumentParser(description="把 OST 封面烧进 mp3 / ogg")
    ap.add_argument("--check", action="store_true", help="只核对有没有封面，不写文件")
    args = ap.parse_args()

    if not os.path.isfile(COVER):
        raise SystemExit("找不到封面：%s（先跑 python tools/brand/render.py）" % COVER)

    # 封面尺寸从 PNG 头里读，避免和 render.py 那边写死的数字各说各话
    with open(COVER, "rb") as fh:
        data = fh.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("%s 不是 PNG" % COVER)
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")

    bad = 0
    for ogg, paths in ((False, MP3), (True, OGG)):
        for path in paths:
            name = os.path.relpath(path, ROOT)
            if not os.path.isfile(path):
                print("MISS   %s" % name)
                bad += 1
                continue
            if args.check:
                ok = has_cover(path, ogg)
                print("%s %s" % ("OK    " if ok else "NOCOVER", name))
                bad += 0 if ok else 1
                continue
            size = burn_ogg(path, data, width, height) if ogg \
                else burn_mp3(path, data, width, height)
            print("BURN   %s  (%d B 封面, %d×%d)" % (name, size, width, height))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
