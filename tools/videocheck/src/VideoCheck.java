import com.hbmzz.video.VideoClip;
import com.hbmzz.video.VideoLibrary;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Offline check for the video shelf: can a clip that a player drops into
 * config/hbmzz/videos/ actually be opened, counted and decoded?
 *
 * <p>This is the half of the video broadcaster that fails <em>quietly</em> - a
 * misnamed folder, a meta.json with the wrong frame count, or a name that walks
 * out of its directory all end in the same place in game: a black screen and no
 * explanation. Running it here costs a second and answers the question the GUI
 * would only answer after a redstone pulse.</p>
 *
 * <p>No Minecraft classes are touched: VideoClip and VideoLibrary are plain Java
 * on purpose, which is what makes this possible.</p>
 */
public final class VideoCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("hbmzz-videocheck").toFile();
        try {
            File config = new File(root, "config");
            VideoLibrary.init(config);

            check("config/hbmzz/videos 被创建", VideoLibrary.root().isDirectory());
            check("首次运行写出 README.txt",
                    new File(VideoLibrary.root(), "README.txt").isFile());

            File folder = new File(VideoLibrary.root(), "alarm");
            check("目录形式被创建", folder.mkdirs());
            writePng(new File(folder, "f000001.png"), 32, 24, 0xFF0000);
            writePng(new File(folder, "f000002.png"), 32, 24, 0x00FF00);
            writePng(new File(folder, "f000003.png"), 32, 24, 0x0000FF);
            write(new File(folder, "meta.json"), "{\"fps\":8,\"title\":\"报警\",\"frames\":3}");

            List<String> names = VideoLibrary.names();
            check("目录形式的包出现在清单里", names.contains("alarm"));
            check("清单已排序且不含非法项", names.size() == 1);

            VideoClip clip = VideoLibrary.open("alarm");
            check("目录形式的包能打开", clip != null);
            check("帧数按 meta.json", clip.frameCount() == 3);
            check("帧率按 meta.json", clip.fps() == 8);
            check("时长 = 帧数 / 帧率", Math.abs(clip.durationSeconds() - 0.375D) < 1e-9);
            check("没有音轨时不声称有", !clip.hasAudio());

            BufferedImage first = clip.readFrame(0);
            check("第 1 帧能解码", first != null);
            check("解出来的尺寸对", first.getWidth() == 32 && first.getHeight() == 24);
            check("第 1 帧的像素是红的", (first.getRGB(1, 1) & 0xFFFFFF) == 0xFF0000);
            check("越界帧抛错而不是返回一张空图", throwsOnBadFrame(clip));
            clip.close();

            // A zip whose meta.json claims fewer frames than it contains: the
            // smaller number must win, or the player waits for frames that will
            // never arrive.
            File zipFile = new File(VideoLibrary.root(), "promo.zip");
            writeZip(zipFile, 3, 2);
            VideoClip zipped = VideoLibrary.open("promo");
            check("zip 形式的包能打开", zipped != null);
            check("声明 2 帧、实际 3 帧时取 2", zipped.frameCount() == 2);
            check("缺 meta.json 时默认 12fps", defaultFps(root) == VideoClip.DEFAULT_FPS);
            if (zipped != null) {
                zipped.close();
            }

            check("不存在的名字返回 null", VideoLibrary.open("nope") == null);
            check("非法名字返回 null", VideoLibrary.open("../config") == null);

            // ---- order, speed, loop -------------------------------------
            // f2 must come before f10. Lexical sort gets this backwards, and a
            // pack that did not zero-pad its numbering would play shuffled.
            File nat = new File(VideoLibrary.root(), "nat");
            check("自然排序包目录创建", nat.mkdirs());
            writePng(new File(nat, "f1.png"), 8, 8, 0xFF0000);
            writePng(new File(nat, "f2.png"), 8, 8, 0x00FF00);
            writePng(new File(nat, "f10.png"), 8, 8, 0x0000FF);
            write(new File(nat, "meta.json"), "{\"fps\":2.5}");
            VideoClip natural = VideoLibrary.open("nat");
            check("小数帧率被接受", natural != null && Math.abs(natural.fps() - 2.5) < 1e-9);
            check("每帧 0.4 秒", natural != null && Math.abs(natural.frameSeconds() - 0.4) < 1e-9);
            check("一个循环 1.2 秒", natural != null && Math.abs(natural.cycleSeconds() - 1.2) < 1e-9);
            check("f2 排在 f10 前面", natural != null
                    && rgbAt(natural, 0) == 0xFF0000
                    && rgbAt(natural, 1) == 0x00FF00
                    && rgbAt(natural, 2) == 0x0000FF);
            check("不写 loop 时默认一直循环", natural != null && natural.repeating());
            if (natural != null) {
                natural.close();
            }

            File onceDir = new File(VideoLibrary.root(), "once");
            check("单次包目录创建", onceDir.mkdirs());
            writePng(new File(onceDir, "a.png"), 8, 8, 0x111111);
            writePng(new File(onceDir, "b.png"), 8, 8, 0x222222);
            write(new File(onceDir, "meta.json"), "{\"fps\":4,\"loop\":false}");
            VideoClip once = VideoLibrary.open("once");
            check("loop:false 是单次", once != null
                    && once.loopMode() == VideoClip.LoopMode.ONCE && once.passes() == 1);
            check("单次播完后播放头不再前进", once != null && once.positionAt(99.0D) == 1);
            check("单次不算循环", once != null && !once.repeating());
            if (once != null) {
                once.close();
            }

            File pingDir = new File(VideoLibrary.root(), "ping");
            check("往返包目录创建", pingDir.mkdirs());
            writePng(new File(pingDir, "p1.png"), 8, 8, 0x010101);
            writePng(new File(pingDir, "p2.png"), 8, 8, 0x020202);
            writePng(new File(pingDir, "p3.png"), 8, 8, 0x030303);
            write(new File(pingDir, "meta.json"), "{\"fps\":10,\"loop\":\"pingpong\"}");
            VideoClip ping = VideoLibrary.open("ping");
            check("loop:pingpong 是往返", ping != null
                    && ping.loopMode() == VideoClip.LoopMode.PING_PONG);
            check("往返序列 0,1,2,1,0", ping != null
                    && ping.slotAt(0) == 0 && ping.slotAt(1) == 1 && ping.slotAt(2) == 2
                    && ping.slotAt(3) == 1 && ping.slotAt(4) == 0);
            check("往返时倒放段不出声", ping != null && ping.forwardAt(1) && !ping.forwardAt(3));
            check("播放头按帧率换算", ping != null && ping.positionAt(0.25D) == 2);
            check("播放头折回往返序列", ping != null && ping.slotAt(ping.positionAt(0.35D)) == 1);
            check("强制单次覆盖往返", ping != null
                    && ping.asSinglePass().loopMode() == VideoClip.LoopMode.ONCE);
            if (ping != null) {
                ping.close();
            }

            File threeDir = new File(VideoLibrary.root(), "three");
            check("多遍包目录创建", threeDir.mkdirs());
            writePng(new File(threeDir, "a.png"), 8, 8, 0x333333);
            write(new File(threeDir, "meta.json"), "{\"fps\":10,\"loop\":3}");
            VideoClip three = VideoLibrary.open("three");
            check("loop:3 播三遍", three != null && three.passes() == 3
                    && Math.abs(three.totalSeconds() - 0.3D) < 1e-9);
            if (three != null) {
                three.close();
            }

            // Explicit order beats the file system: an author who names the
            // sequence means it.
            File playlistDir = new File(VideoLibrary.root(), "playlist");
            check("playlist 包目录创建", playlistDir.mkdirs());
            writePng(new File(playlistDir, "a.png"), 8, 8, 0x00FF00);
            writePng(new File(playlistDir, "b.png"), 8, 8, 0x0000FF);
            writePng(new File(playlistDir, "c.png"), 8, 8, 0xFF0000);
            write(new File(playlistDir, "meta.json"),
                    "{\"fps\":10,\"order\":\"list\",\"playlist\":[\"c.png\",\"b.png\"]}");
            VideoClip playlist = VideoLibrary.open("playlist");
            check("playlist 只播列出的帧", playlist != null && playlist.frameCount() == 2);
            check("playlist 的顺序被尊重", playlist != null
                    && rgbAt(playlist, 0) == 0xFF0000 && rgbAt(playlist, 1) == 0x0000FF);
            if (playlist != null) {
                playlist.close();
            }

            // Boot-animation desc.txt: parts, repeat counts and pauses.
            File bootDir = new File(VideoLibrary.root(), "boot");
            check("开机动画包目录创建", bootDir.mkdirs() && new File(bootDir, "part0").mkdirs()
                    && new File(bootDir, "part1").mkdirs());
            writePng(new File(bootDir, "part0/1.png"), 8, 8, 0x101010);
            writePng(new File(bootDir, "part0/2.png"), 8, 8, 0x202020);
            writePng(new File(bootDir, "part1/1.png"), 8, 8, 0x303030);
            write(new File(bootDir, "desc.txt"), "320 200 2\np 2 0 part0\np 1 0.5 part1\n");
            VideoClip boot = VideoLibrary.open("boot");
            check("desc.txt 的帧率生效", boot != null && Math.abs(boot.fps() - 2.0D) < 1e-9);
            check("desc.txt 的 part 与定格算进时间线", boot != null && boot.slotCount() == 6);
            check("desc.txt 时间线为 1,2,1,2,1,1", boot != null
                    && boot.entryAt(0) == 0 && boot.entryAt(1) == 1 && boot.entryAt(2) == 0
                    && boot.entryAt(3) == 1 && boot.entryAt(4) == 2 && boot.entryAt(5) == 2);
            if (boot != null) {
                boot.close();
            }

            File audioDir = new File(VideoLibrary.root(), "withsound");
            check("带音轨包目录创建", audioDir.mkdirs());
            writePng(new File(audioDir, "a.png"), 8, 8, 0x404040);
            write(new File(audioDir, "audio.wav"), "RIFF0000WAVEfmt ");
            write(new File(audioDir, "meta.json"), "{\"fps\":10,\"audioOffset\":1.5}");
            VideoClip withSound = VideoLibrary.open("withsound");
            check("audio.wav 被当成音轨", withSound != null && withSound.hasAudio());
            check("audioOffset 被读出", withSound != null
                    && Math.abs(withSound.audioOffset() - 1.5D) < 1e-9);
            if (withSound != null) {
                withSound.close();
            }

            check("接受普通名字", VideoLibrary.isValidName("alarm_01"));
            check("拒绝穿越", !VideoLibrary.isValidName("../x"));
            check("拒绝路径分隔符", !VideoLibrary.isValidName("a/b"));
            check("拒绝空名字", !VideoLibrary.isValidName(""));
            check("拒绝空格", !VideoLibrary.isValidName("a b"));
            check("拒绝 65 字符", !VideoLibrary.isValidName(repeat('a', 65)));
            check("接受 64 字符", VideoLibrary.isValidName(repeat('a', 64)));
        } finally {
            delete(root);
        }

        System.out.println();
        System.out.println("PASS " + passed + " / FAIL " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static int rgbAt(VideoClip clip, int frame) {
        try {
            BufferedImage image = clip.readFrame(frame);
            return image.getRGB(0, 0) & 0xFFFFFF;
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean throwsOnBadFrame(VideoClip clip) {
        try {
            clip.readFrame(99);
            return false;
        } catch (Exception expected) {
            return true;
        }
    }

    private static double defaultFps(File root) throws Exception {
        File dir = new File(VideoLibrary.root(), "noMeta");
        if (!dir.mkdirs()) {
            return -1.0D;
        }
        writePng(new File(dir, "a.png"), 8, 8, 0x808080);
        writePng(new File(dir, "b.png"), 8, 8, 0x808080);
        VideoClip clip = VideoLibrary.open("noMeta");
        if (clip == null) {
            return -1.0D;
        }
        double fps = clip.fps();
        clip.close();
        return fps;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    private static void writePng(File file, int w, int h, int rgb) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", file);
    }

    private static void writeZip(File file, int images, int declaredFrames) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(("{\"fps\":10,\"frames\":" + declaredFrames + "}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 1; i <= images; i++) {
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                zip.putNextEntry(new ZipEntry(String.format("f%06d.png", i)));
                ImageIO.write(image, "png", zip);
                zip.closeEntry();
            }
        }
    }

    private static void write(File file, String text) throws Exception {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
