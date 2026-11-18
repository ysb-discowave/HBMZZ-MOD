package com.rsaudio.formats;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps rsaudio's generated sound pack from doubling the music on disk.
 *
 * <p>rsaudio rebuilds {@code resourcepacks/rsaudio_dynamic} by copying every {@code .ogg} out of the
 * library folder, so the same 400+ MB ends up stored twice. This class does two things once per
 * version, on a background thread:
 *
 * <ol>
 *   <li><b>Relocate:</b> the generated pack is moved to {@code config/rsaudio/pack} and the
 *       {@code resourcepacks} entry becomes an NTFS junction pointing at it, so the pack actually
 *       lives next to the music instead of inside the player's resource pack list.</li>
 *   <li><b>Deduplicate:</b> every copied {@code .ogg} is replaced by a hard link to the library file
 *       it came from (matched by size, then full SHA-1), so both names share one copy on disk.</li>
 * </ol>
 *
 * <p>Everything is best effort: unsupported filesystems, a read-only game directory or a running
 * antivirus simply leave the pack as it was, which is the pre-existing behaviour anyway.
 */
final class PackHousekeeping {

    private static final String PACK_DIR = "rsaudio_dynamic";
    private static final String STAMP = ".housekeeping";

    private PackHousekeeping() {
    }

    /** Runs the whole thing in the background; safe to call once from init. */
    static void schedule(final File gameDir) {
        if (gameDir == null) {
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    execute(gameDir);
                } catch (Throwable t) {
                    FmLog.warnOnce("pack.housekeeping", "pack housekeeping failed: " + FmLog.describe(t));
                }
            }
        }, "rsaudio-formats-pack");
        worker.setDaemon(true);
        worker.start();
    }

    private static void execute(File gameDir) throws Exception {
        File resourcepacks = new File(gameDir, "resourcepacks");
        File oldPack = new File(resourcepacks, PACK_DIR);
        File newPack = new File(gameDir, "config" + File.separator + "rsaudio" + File.separator + "pack");

        if (oldPack.isDirectory() && !isReparsePoint(oldPack)) {
            relocate(oldPack, newPack);
        }

        File target = oldPack.isDirectory() ? oldPack : newPack;
        File sounds = new File(target, "assets" + File.separator + "rsaudio" + File.separator + "sounds");
        File stamp = new File(target, STAMP);
        if (!sounds.isDirectory() || stamp.isFile()) {
            return;
        }
        int linked = deduplicate(new File(gameDir, "config" + File.separator + "rsaudio" + File.separator + "audio"), sounds);
        if (linked > 0) {
            FmLog.log("声音包去重完成：把 " + linked + " 个 ogg 换成了指向曲库的硬链接（不再重复占磁盘）");
        }
        try {
            stamp.createNewFile();
        } catch (IOException ignored) {
            // 标记写不进去只是下次启动会再算一遍
        }
    }

    private static void relocate(File oldPack, File newPack) throws Exception {
        if (newPack.exists()) {
            deleteQuietly(newPack);
        }
        File parent = newPack.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        if (!oldPack.renameTo(newPack) && !moveFallback(oldPack, newPack)) {
            return;
        }
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", oldPack.getAbsolutePath(), newPack.getAbsolutePath())
                .redirectErrorStream(true)
                .start();
        process.waitFor();
        if (oldPack.isDirectory()) {
            FmLog.log("声音包已迁到 config/rsaudio/pack，resourcepacks 里只留一个链接目录");
        } else {
            FmLog.warnOnce("pack.junction", "junction 创建失败，声音包留在 config/rsaudio/pack");
        }
    }

    private static boolean moveFallback(File from, File to) {
        try {
            copyTree(from, to);
            deleteQuietly(from);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) {
                throw new IOException("cannot create " + to);
            }
            File[] children = from.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyTree(child, new File(to, child.getName()));
                }
            }
        } else {
            Files.copy(from.toPath(), to.toPath());
        }
    }

    /** Replaces identical pack files with hard links to the library originals; returns the count. */
    private static int deduplicate(File library, File sounds) throws Exception {
        if (!library.isDirectory()) {
            return 0;
        }
        Map<Long, List<File>> bySize = new HashMap<Long, List<File>>();
        File[] libFiles = library.listFiles();
        if (libFiles == null) {
            return 0;
        }
        for (File file : libFiles) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".ogg")) {
                Long size = Long.valueOf(file.length());
                List<File> list = bySize.get(size);
                if (list == null) {
                    list = new ArrayList<File>();
                    bySize.put(size, list);
                }
                list.add(file);
            }
        }
        File[] packFiles = sounds.listFiles();
        if (packFiles == null) {
            return 0;
        }
        int linked = 0;
        for (File packFile : packFiles) {
            if (!packFile.isFile() || !packFile.getName().toLowerCase().endsWith(".ogg")) {
                continue;
            }
            if (linkCount(packFile) > 1) {
                continue;
            }
            List<File> candidates = bySize.get(Long.valueOf(packFile.length()));
            if (candidates == null) {
                continue;
            }
            String digest = sha1(packFile);
            if (digest == null) {
                continue;
            }
            for (File candidate : candidates) {
                if (!digest.equals(sha1(candidate))) {
                    continue;
                }
                if (replaceWithLink(packFile, candidate)) {
                    linked++;
                }
                break;
            }
        }
        return linked;
    }

    private static boolean replaceWithLink(File link, File target) {
        Path linkPath = link.toPath();
        Path targetPath = target.toPath();
        File backup = new File(link.getParentFile(), link.getName() + ".old");
        try {
            Files.deleteIfExists(backup.toPath());
            Files.move(linkPath, backup.toPath());
        } catch (Throwable t) {
            return false;
        }
        try {
            Files.createLink(linkPath, Paths.get(targetPath.toString()));
        } catch (Throwable t) {
            try {
                Files.move(backup.toPath(), linkPath);
            } catch (Throwable ignored) {
                // 还原失败就保持 .old，至少不丢文件
            }
            return false;
        }
        try {
            Files.deleteIfExists(backup.toPath());
        } catch (Throwable ignored) {
            // 残留的 .old 不影响播放
        }
        return true;
    }

    private static String sha1(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    md.update(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long linkCount(File file) {
        try {
            Object attributes = Files.getAttribute(file.toPath(), "unix:nlink");
            return attributes instanceof Integer ? ((Integer) attributes).longValue() : 1L;
        } catch (Throwable t) {
            return 1L;
        }
    }

    private static boolean isReparsePoint(File file) {
        try {
            return Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteQuietly(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteQuietly(child);
            }
        }
        file.delete();
    }
}
