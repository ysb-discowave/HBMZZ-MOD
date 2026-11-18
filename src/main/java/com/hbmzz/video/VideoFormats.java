/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.video;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 这段文件是不是一段视频（容器），能不能交给 ffmpeg 去解。
 *
 * <p>播放路线只有一条：图片帧序列。所以"支持所有格式"不是给播放器加解码器，
 * 而是把任何 ffmpeg 认得的容器先转成帧包 —— 这一步在
 * {@link VideoImportQueue} 里做，这个类只负责回答一个问题：这个后缀值不值得
 * 拿去问 ffmpeg。</p>
 *
 * <p><b>为什么还是列后缀。</b> ffmpeg 自己会探测内容，不看后缀；但这个列表的
 * 用途是"把一个文件放进『可导入』清单"，而不是解码。目录里同时躺着 png、txt、
 * 星露谷存档，全丢给 ffmpeg 试一遍既慢又吵。所以这里保守地列出市面上能遇到的
 * 容器：认得的才进清单，认不得的仍旧按"不支持"提示，不会静默消失。</p>
 *
 * <p>列表以外的东西也不是不能转 —— 玩家可以改个后缀，或者用命令行那把
 * {@code tools/video/pack.py}。这里只决定图形界面主动推荐什么。</p>
 */
public final class VideoFormats {

    /** 常见容器与封装：mp4 / mkv / mov / avi / webm / flv / ts / wmv / rmvb … */
    private static final Set<String> CONTAINERS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    // ISO base media 家族
                    "mp4", "m4v", "mov", "3gp", "3g2", "f4v", "ismv",
                    // Matroska / WebM
                    "mkv", "mk3d", "webm",
                    // AVI 与老式 Windows 封装
                    "avi", "wmv", "asf", "divx", "xvid",
                    // 流与广播
                    "ts", "m2ts", "mts", "flv", "mpg", "mpeg", "mpe", "m2v", "vob",
                    // RealMedia / QuickTime 老片
                    "rm", "rmvb", "qt",
                    // Ogg 家族的视频封装
                    "ogv",
                    // 光碟与其它
                    "mxf", "dv", "gxf", "nut", "y4m", "yuv")));

    /** 音轨类：能转，但转出来的片子只有声音，没有画面 —— 界面上要说明。 */
    private static final Set<String> AUDIO_ONLY = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus", "wma", "aiff", "aif", "ape")));

    private VideoFormats() {
    }

    /** 小写后缀，没有后缀时返回空串。 */
    public static String extensionOf(File file) {
        if (file == null) {
            return "";
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 这个文件是不是一段可以导入的视频容器。 */
    public static boolean isVideo(File file) {
        return file != null && file.isFile() && CONTAINERS.contains(extensionOf(file));
    }

    /** 这个文件是不是纯音频（同样能导，但只有声音）。 */
    public static boolean isAudioOnly(File file) {
        return file != null && file.isFile() && AUDIO_ONLY.contains(extensionOf(file));
    }

    /** 视频或音频：两者都能进导入清单。 */
    public static boolean isImportable(File file) {
        return isVideo(file) || isAudioOnly(file);
    }

    /** 给界面用的一句话，说明这类文件导进来会得到什么。 */
    public static String describe(File file) {
        if (isVideo(file)) {
            return "视频 · 转成帧包";
        }
        if (isAudioOnly(file)) {
            return "纯音频 · 只有声音";
        }
        return "不支持";
    }
}
