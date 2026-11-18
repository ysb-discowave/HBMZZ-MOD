package com.rsaudio.formats;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Keeps the in-game music instructions honest.
 *
 * <p>rsaudio ships its own "how to add music" text saying only Ogg Vorbis can be played. That was
 * true before this jar existed; with multi-format support in place the text is misleading, so this
 * class appends a clearly marked section once and drops a standalone notice next to it. Both writes
 * are best effort: a read-only config directory only costs one log line.
 */
final class DocsNotice {

    private static final String MARKER = "[rsaudio-formats]";
    private static final String SEPARATOR = "---------------------------------";
    private static final String ORIGINAL = "如何添加音乐.txt";
    private static final String STANDALONE = "多格式支持说明.txt";

    private DocsNotice() {
    }

    static void ensure(File gameDir) {
        if (gameDir == null) {
            return;
        }
        File dir = new File(gameDir, "config" + File.separator + "rsaudio");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            FmLog.warnOnce("docs.dir", "cannot create " + dir);
            return;
        }
        try {
            writeStandalone(new File(dir, STANDALONE));
        } catch (Throwable t) {
            FmLog.warnOnce("docs.standalone", "cannot write the format notice: " + FmLog.describe(t));
        }
        File original = new File(dir, ORIGINAL);
        try {
            if (original.isFile() && !alreadyPatched(original)) {
                appendSection(original);
                FmLog.log("已在本环境音乐说明末尾追加多格式支持一节");
            }
        } catch (Throwable t) {
            FmLog.warnOnce("docs.patch", "cannot patch the music instructions: " + FmLog.describe(t));
        }
    }

    private static boolean alreadyPatched(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) Math.min(file.length(), 1 << 20)];
            int read = in.read(buffer);
            String text = new String(buffer, 0, Math.max(read, 0), "UTF-8");
            return text.contains(MARKER);
        } finally {
            in.close();
        }
    }

    private static void appendSection(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("\r\n\r\n").append(SEPARATOR).append("\r\n");
        sb.append(MARKER).append(" 多格式支持（本环境已安装）\r\n");
        sb.append(SEPARATOR).append("\r\n");
        sb.append("上面那段「只能放 ogg」是红石音频自带的说明，写在没有多格式支持的时候。\r\n");
        sb.append("本环境装了多格式支持，可以直接把下面这些格式放进 audio 文件夹，不用自己转 ogg：\r\n\r\n");
        sb.append("    wav  mp3  flac  aiff  aifc  au  m4a  m4b  aac\r\n");
        sb.append("    wma  opus  amr  mka  spx        （另有 ogg / oga 走原版引擎）\r\n\r\n");
        sb.append("做法不变：把文件放进 audio 文件夹即可，界面里点「重新扫描」或按 F3+T 生效。\r\n");
        sb.append("转码格式（mp3 等）由模组自动转成 ogg 缓存，第一次播放会慢一点，之后就不再转换。\r\n");
        sb.append("这一节由模组自动追加，原文件的内容一个字都没有改。\r\n");
        OutputStream out = new FileOutputStream(file, true);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void writeStandalone(File target) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("多格式支持说明\r\n");
        sb.append("================\r\n\r\n");
        sb.append("音乐放哪里：曲库目录（红石音频原本的要求，只需要放这一处）。\r\n");
        sb.append("  打开 config 目录下的 rsaudio，里面的 audio 文件夹就是曲库。\r\n\r\n");
        sb.append("支持格式（除 ogg 外）：\r\n");
        sb.append("    wav  mp3  flac  aiff  aifc  au  m4a  m4b  aac  wma  opus  amr  mka  spx\r\n");
        sb.append("    ogg / oga 由原版引擎直接播放\r\n\r\n");
        sb.append("怎么生效：放进文件后，在播放器界面点「重新扫描」，或按 F3+T 重载资源。\r\n");
        sb.append("转码：mp3 等格式会被转成 ogg 缓存放到 config 下的 rsaudiofm 目录，只转一次。\r\n");
        sb.append("没有 ffmpeg 时：wav / aiff / au / mp3 / flac 仍可播放，其余格式需要 ffmpeg。\r\n");
        OutputStream out = new FileOutputStream(target, false);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
