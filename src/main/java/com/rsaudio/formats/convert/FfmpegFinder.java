package com.rsaudio.formats.convert;

import com.rsaudio.formats.FmConfig;
import com.rsaudio.formats.FmLog;
import com.rsaudio.formats.FormatsProps;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Locates a usable ffmpeg binary and caches the answer for the session.
 *
 * <p>Search order: explicit configuration -> game directory -> mods directory -> PATH. Every
 * candidate is logged on its own line, so a user can see why ffmpeg was not found.</p>
 */
public final class FfmpegFinder {

    private static final String EXE = "ffmpeg.exe";
    private static final long PROBE_TIMEOUT_MS = 5000L;

    private static String resolved;
    private static String versionLine;
    private static boolean probed;

    private FfmpegFinder() {
    }

    public static synchronized void reset() {
        resolved = null;
        versionLine = null;
        probed = false;
    }

    /** Absolute path of a working ffmpeg, or {@code null} when none was found. */
    public static synchronized String path() {
        probe();
        return resolved;
    }

    /** First line of {@code ffmpeg -version} as captured during the probe, or {@code null}. */
    public static synchronized String versionLine() {
        probe();
        return versionLine;
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;

        List<String> notes = new ArrayList<String>();
        for (String candidate : candidates(notes)) {
            for (String note : notes) {
                FmLog.log("[ffmpeg] " + note);
            }
            notes.clear();
            if (probeCandidate(candidate)) {
                FmLog.log("[ffmpeg] 命中: " + resolved + " | " + versionLine);
                return;
            }
        }
        for (String note : notes) {
            FmLog.log("[ffmpeg] " + note);
        }
        FmLog.warn("[ffmpeg] 未找到可用的 ffmpeg（已尝试 配置路径 → 游戏目录 → mods 目录 → PATH）");
    }

    private static boolean probeCandidate(String exe) {
        if (exe == null || exe.isEmpty()) {
            return false;
        }
        File file = new File(exe);
        if (!file.isFile()) {
            return false;
        }
        String firstLine = runVersion(file.getAbsolutePath());
        if (firstLine == null) {
            return false;
        }
        versionLine = firstLine;
        resolved = file.getAbsolutePath();
        return true;
    }

    /** Runs {@code ffmpeg -version}; returns its first non-empty line, or null when it does not work. */
    private static String runVersion(String exe) {
        Process process = null;
        try {
            process = new ProcessBuilder(exe, "-hide_banner", "-version")
                    .redirectErrorStream(true)
                    .start();
            final StringBuilder head = new StringBuilder();
            final Process child = process;
            Thread drain = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"));
                        String line;
                        int kept = 0;
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().isEmpty()) {
                                continue;
                            }
                            if (kept++ == 0) {
                                head.append(line.trim());
                            }
                            if (kept >= 8) {
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                        // drain best-effort only
                    } finally {
                        closeQuietly(reader);
                    }
                }
            }, "rsaudioformats-ffmpeg-version");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            drain.join(500L);
            if (process.exitValue() != 0 || head.length() == 0) {
                return null;
            }
            return head.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Set<String> candidates(List<String> notes) {
        Set<String> out = new LinkedHashSet<String>();

        // 0) 打包在 jar 内的 ffmpeg：首次播放时释放到 config/rsaudiofm/bin
        File bundled = BundledFfmpeg.ensure(gameDir(), notes);
        if (bundled != null) {
            out.add(bundled.getPath());
        }

        // 1) explicit configuration: the config value, or -Drsaudiofm.ffmpeg= for headless runs
        String configured = configuredPath();
        if (configured != null && !configured.isEmpty()) {
            File c = new File(configured);
            String target = c.isDirectory() ? new File(c, EXE).getPath() : c.getPath();
            notes.add("候选 1 配置路径 (" + target + "): " + (new File(target).isFile() ? "存在" : "不存在"));
            out.add(target);
        } else {
            notes.add("候选 1 配置路径: 未配置");
        }

        // 2) game directory
        File gameDir = gameDir();
        if (gameDir != null) {
            notes.add("候选 2 游戏目录 (" + gameDir.getPath()
                    + "): 探测 ffmpeg.exe / ffmpeg/ffmpeg.exe / rsaudiofm/ffmpeg.exe");
            out.add(new File(gameDir, EXE).getPath());
            out.add(new File(new File(gameDir, "ffmpeg"), EXE).getPath());
            out.add(new File(new File(gameDir, "rsaudiofm"), EXE).getPath());
        } else {
            notes.add("候选 2 游戏目录: 不可用（非游戏环境）");
        }

        // 3) mods directory
        File modsDir = modsDir();
        if (modsDir != null) {
            notes.add("候选 3 mods 目录 (" + modsDir.getPath() + "): 探测 ffmpeg.exe / rsaudiofm/ffmpeg.exe");
            out.add(new File(modsDir, EXE).getPath());
            out.add(new File(new File(modsDir, "rsaudiofm"), EXE).getPath());
        } else {
            notes.add("候选 3 mods 目录: 不可用（无法从代码位置推断）");
        }

        // 4) PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isEmpty()) {
            String[] dirs = pathEnv.split(File.pathSeparator);
            notes.add("候选 4 PATH: 共 " + dirs.length + " 个目录");
            for (String dir : dirs) {
                if (dir != null && !dir.trim().isEmpty()) {
                    out.add(new File(dir.trim(), EXE).getPath());
                }
            }
        } else {
            notes.add("候选 4 PATH: 空");
        }
        return out;
    }

    private static String configuredPath() {
        String configured = FmConfig.ffmpegPath();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String property = FormatsProps.get("ffmpeg");
        return property == null ? null : property.trim();
    }

    /** Handed in by the mod, which reads {@code mc.gameDir} without reflection. */
    private static volatile File gameHint;

    public static void setGameHint(File dir) {
        gameHint = dir;
    }

    /** The running game directory, or null outside a game environment. */
    private static File gameDir() {
        // In production the MCP names are gone, so try the srg name first.
        Object minecraft = reflectiveStatic("net.minecraft.client.Minecraft", "func_71410_x");
        if (minecraft == null) {
            minecraft = reflectiveStatic("net.minecraft.client.Minecraft", "getMinecraft");
        }
        if (minecraft != null) {
            Object dir = firstFile(
                    reflectiveCall(minecraft, "getGameDir"),
                    reflectiveField(minecraft, "gameDir"),
                    reflectiveField(minecraft, "field_71412_D"));
            if (dir instanceof File) {
                return (File) dir;
            }
        }
        if (gameHint != null) {
            return gameHint;
        }
        File mods = modsDir();
        return mods == null ? null : mods.getParentFile();
    }

    private static Object firstFile(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof File) {
                return candidate;
            }
        }
        return null;
    }

    /** Directory holding our own jar (i.e. the mods folder), or null when not determinable. */
    private static File modsDir() {
        try {
            java.security.CodeSource cs = FfmpegFinder.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            File self = new File(cs.getLocation().toURI());
            File dir = self.isFile() ? self.getParentFile() : self;
            return dir != null && "mods".equalsIgnoreCase(dir.getName()) ? dir : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /*
     * Minecraft itself is reached reflectively on purpose: this package must stay loadable (and
     * unit testable) outside a game process. Every other neighbour is a direct, compile-checked
     * call.
     */
    private static Object reflectiveStatic(String className, String method) {
        try {
            return Class.forName(className).getMethod(method).invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object reflectiveCall(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object reflectiveField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable t) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
