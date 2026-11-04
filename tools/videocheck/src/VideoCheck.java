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

    private static boolean throwsOnBadFrame(VideoClip clip) {
        try {
            clip.readFrame(99);
            return false;
        } catch (Exception expected) {
            return true;
        }
    }

    private static int defaultFps(File root) throws Exception {
        File dir = new File(VideoLibrary.root(), "noMeta");
        if (!dir.mkdirs()) {
            return -1;
        }
        writePng(new File(dir, "a.png"), 8, 8, 0x808080);
        writePng(new File(dir, "b.png"), 8, 8, 0x808080);
        VideoClip clip = VideoLibrary.open("noMeta");
        if (clip == null) {
            return -1;
        }
        int fps = clip.fps();
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
