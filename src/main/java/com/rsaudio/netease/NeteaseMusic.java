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

package com.rsaudio.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rsaudio.Config;
import com.rsaudio.RsAudio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网易云音乐接口：让玩家在线搜歌、下载并播放本地 audio 目录里没有的音乐。
 * <p>
 * 这里刻意<b>不引用任何 Minecraft 类型</b>（同 {@code ReasimDsp}）：所有方法都是
 * 阻塞式的纯 JDK 网络 + Gson，调用方负责在合适的线程上调。界面里搜歌会另开一条
 * 后台线程再回投到游戏线程；而播放链路里，{@code ReasimSession} 的推流线程会直接
 * 调 {@link #ensureLocal(long)} —— 第一次下载会阻塞那一拍，但下载完就落到
 * {@code config/rsaudio/netease/} 缓存，之后再播就是即时的。
 * <p>
 * <b>为什么用 "nmc_" 前缀当音源名：</b>网易云歌曲不进 audio 目录、不注册进 sounds.json，
 * 没法走原版引擎。它们靠这个前缀在播放链路里被认出来（{@code nmc_<歌曲id>}），
 * 再绕开原版走 formats 层的 mp3 解码 + reasim DSP，拿到和 ogg 一样的"破喇叭"效果。
 */
public final class NeteaseMusic {

    /** 音源名前缀：nmc_ + 歌曲 id。整条播放链路靠它识别"这是网易云的歌" */
    public static final String PREFIX = "nmc_";

    /** 搜索结果里最多返回几首（界面一屏放得下就够，再多翻页反而烦） */
    public static final int SEARCH_LIMIT = 20;

    /** 取直链用的码率：128kbps 是免费账号的默认档，质量够听、体积也不大 */
    private static final int BITRATE = 128000;

    private static final String SEARCH_URL = "http://music.163.com/api/search/get";
    private static final String PLAY_URL = "http://music.163.com/api/song/enhance/player/url";

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 搜索完就记住"歌曲 id → 显示名"，让界面/状态栏能把 nmc_123 翻成人看得懂的标题 */
    private static final java.util.Map<Long, String> NAMES =
            new java.util.concurrent.ConcurrentHashMap<Long, String>();

    private NeteaseMusic() {
    }

    // ------------------------------------------------------------------ 命名

    /** 这个音源名是不是网易云的歌（nmc_ 后跟纯数字） */
    public static boolean isNetease(String soundName) {
        if (soundName == null || soundName.length() <= PREFIX.length()) {
            return false;
        }
        if (!soundName.startsWith(PREFIX)) {
            return false;
        }
        long id = trackIdOf(soundName);
        return id > 0;
    }

    /** 从 nmc_<id> 里取出歌曲 id；不是网易云音源名或格式坏时返回 0 */
    public static long trackIdOf(String soundName) {
        if (!isNetease(soundName)) {
            return 0L;
        }
        try {
            return Long.parseLong(soundName.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 歌曲 id 对应的音源名 */
    public static String soundNameOf(long trackId) {
        return PREFIX + trackId;
    }

    /**
     * 音源名对应的"人看得懂的标题"。搜索过的歌有缓存就返回真实标题，
     * 没搜过（比如别人建的存档里存的 nmc_）就退回 "网易云 #<id>"。
     */
    public static String displayNameOf(String soundName) {
        long id = trackIdOf(soundName);
        if (id <= 0) {
            return soundName;
        }
        String cached = NAMES.get(id);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return "网易云 #" + id;
    }

    // ------------------------------------------------------------------ 搜索

    /** 一首搜索结果 */
    public static final class Track {
        public final long id;
        public final String name;
        public final String artist;

        Track(long id, String name, String artist) {
            this.id = id;
            this.name = name;
            this.artist = artist;
        }

        public String soundName() {
            return PREFIX + id;
        }

        /** 界面里显示的一行：标题 - 歌手 */
        public String label() {
            if (artist == null || artist.isEmpty()) {
                return name;
            }
            return name + " - " + artist;
        }
    }

    /**
     * 在线搜歌（阻塞，会真发网络请求）。
     *
     * @param query 关键词
     * @param limit 上限（建议用 {@link #SEARCH_LIMIT}）
     * @return 命中的歌曲列表；网络失败 / 解析失败时返回空列表并记一条日志
     */
    public static List<Track> search(String query, int limit) {
        String kw = (query == null ? "" : query.trim());
        if (kw.isEmpty() || limit <= 0) {
            return new ArrayList<Track>();
        }
        try {
            String encoded = URLEncoder.encode(kw, "UTF-8");
            String url = SEARCH_URL + "?s=" + encoded + "&type=1&limit=" + limit + "&offset=0";
            String body = httpGet(url, null);
            if (body == null) {
                return new ArrayList<Track>();
            }
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonObject result = root.has("result") ? root.getAsJsonObject("result") : null;
            if (result == null || !result.has("songs")) {
                return new ArrayList<Track>();
            }
            JsonArray songs = result.getAsJsonArray("songs");
            List<Track> out = new ArrayList<Track>(songs.size());
            for (JsonElement element : songs) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject song = element.getAsJsonObject();
                long id = song.has("id") ? song.get("id").getAsLong() : 0L;
                if (id <= 0) {
                    continue;
                }
                String name = song.has("name") ? song.get("name").getAsString() : ("歌曲 " + id);
                String artist = artistOf(song);
                out.add(new Track(id, name, artist));
                NAMES.put(id, name);
            }
            return out;
        } catch (Throwable t) {
            RsAudio.warn("网易云搜索失败: %s", t);
            return new ArrayList<Track>();
        }
    }

    private static String artistOf(JsonObject song) {
        if (!song.has("artists")) {
            return "";
        }
        JsonElement artists = song.get("artists");
        if (!artists.isJsonArray()) {
            return "";
        }
        JsonArray arr = artists.getAsJsonArray();
        StringBuilder sb = new StringBuilder();
        for (JsonElement element : arr) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject a = element.getAsJsonObject();
            String nm = a.has("name") ? a.get("name").getAsString() : "";
            if (nm.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(nm);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 取直链 + 下载

    /**
     * 拿到某首歌的可播放 mp3 直链。
     *
     * @return 直链；取不到（VIP 专属 / 接口异常 / 无版权）返回 null
     */
    public static String fetchUrl(long trackId) {
        if (trackId <= 0) {
            return null;
        }
        try {
            String url = PLAY_URL + "?ids=[" + trackId + "]&br=" + BITRATE;
            String body = httpGet(url, null);
            if (body == null) {
                return null;
            }
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            if (!root.has("data")) {
                return null;
            }
            JsonArray data = root.getAsJsonArray("data");
            if (data == null || data.size() == 0) {
                return null;
            }
            JsonObject first = data.get(0).getAsJsonObject();
            int code = first.has("code") ? first.get("code").getAsInt() : 0;
            if (code != 200) {
                // 404 通常是 VIP 专属 / 该地区无版权；其它码多半是接口变动
                RsAudio.warn("网易云取直链失败 id=%d code=%d（可能是 VIP 专属或无版权）",
                        trackId, code);
                return null;
            }
            String link = first.has("url") ? first.get("url").getAsString() : null;
            if (link == null || link.isEmpty() || "null".equalsIgnoreCase(link)) {
                return null;
            }
            return link;
        } catch (Throwable t) {
            RsAudio.warn("网易云取直链异常 id=" + trackId + ": " + t);
            return null;
        }
    }

    /** 缓存文件路径（config/rsaudio/netease/<id>.mp3）。目录可能还不存在 */
    public static File cacheFile(long trackId) {
        return new File(Config.getNeteaseDir(false), trackId + ".mp3");
    }

    /** 这首歌是不是已经下载过了 */
    public static boolean isCached(long trackId) {
        File file = cacheFile(trackId);
        return file.isFile() && file.length() > 0;
    }

    /**
     * 确保某首歌在本地：有缓存就直接用，没有就现取直链现下载（阻塞）。
     *
     * @return 可用的 mp3 文件；下载失败返回 null（调用方应当放弃这一首而不是静默）
     */
    public static File ensureLocal(long trackId) {
        if (trackId <= 0) {
            return null;
        }
        File file = cacheFile(trackId);
        if (file.isFile() && file.length() > 0) {
            return file;
        }
        String link = fetchUrl(trackId);
        if (link == null) {
            return null;
        }
        try {
            File dir = Config.getNeteaseDir(true);
            if (!dir.exists() && !dir.mkdirs()) {
                RsAudio.warn("无法创建网易云缓存目录: " + dir.getAbsolutePath());
                return null;
            }
            // 先下到临时名再改名，避免半截文件被当成完整缓存
            File tmp = new File(dir, trackId + ".mp3.part");
            int status = downloadTo(link, tmp);
            if (status != 200) {
                tmp.delete();
                RsAudio.warn("网易云下载失败 id=%d HTTP=%d", trackId, status);
                return null;
            }
            if (tmp.length() <= 0) {
                tmp.delete();
                return null;
            }
            if (file.exists()) {
                file.delete();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return null;
            }
            RsAudio.log("网易云已缓存 %s（%d KB）", NeteaseMusic.displayNameOf(PREFIX + trackId),
                    file.length() / 1024);
            return file;
        } catch (Throwable t) {
            RsAudio.warn("网易云下载异常 id=" + trackId + ": " + t);
            return null;
        }
    }

    /**
     * 把直链内容写到本地文件。
     *
     * @return HTTP 状态码；连接失败 / 异常时返回 0
     */
    private static int downloadTo(String link, File target) {
        HttpURLConnection conn = null;
        try {
            conn = open(link);
            int status = conn.getResponseCode();
            if (status != 200) {
                return status;
            }
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            return 200;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * 发起一个 GET，把响应体读成 UTF-8 字符串。
     *
     * @return 响应体；非 200 / 连接失败时返回 null
     */
    private static String httpGet(String url, String referer) {
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            int status = conn.getResponseCode();
            if (status != 200) {
                RsAudio.warn("网易云请求 HTTP=%d: %s", status, url);
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                return readAll(in);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            RsAudio.warn("网易云请求异常: " + t + " url=" + url);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", "http://music.163.com/");
        return conn;
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
