package com.rsaudio.structure;

import com.rsaudio.Config;
import com.rsaudio.RsAudio;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构文件的读写：{@code config/rsaudio/structures/<名字>.nbt}。
 * <p>
 * 放在 config 目录（而不是存档目录）的理由和音频一致：<b>整合包导出时只要带上 config 文件夹，
 * 结构就跟着走</b>；玩家之间也可以直接拷贝这个文件互相分享。
 * <p>
 * 做整合包/联机时要注意：结构是<b>服务端</b>读写的，客户端拿到的只是文件列表（用于界面显示）。
 */
public final class StructureStorage {

    public static final String EXTENSION = ".nbt";
    /** 名字长度上限 */
    public static final int MAX_NAME_LENGTH = 48;

    private StructureStorage() {
    }

    public static File dir() {
        return Config.getStructuresDir();
    }

    /**
     * 净化结构名：去掉路径分隔符和文件系统不认的字符。
     * <p>
     * 这是<b>必须</b>的（不是好看而已）：名字最终要拼成文件名，
     * 如果放任 {@code ../} 之类的东西进来，就等于让玩家往任意路径写文件。
     */
    public static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            boolean banned = c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|';
            if (banned) {
                continue;
            }
            sb.append(c);
        }
        String value = sb.toString().trim();
        // 全是点的名字（"." / ".."）不是合法文件名
        boolean onlyDots = !value.isEmpty();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '.') {
                onlyDots = false;
                break;
            }
        }
        return onlyDots ? "" : value;
    }

    public static File fileOf(String name) {
        String safe = sanitizeName(name);
        if (safe.isEmpty()) {
            return null;
        }
        return new File(dir(), safe + EXTENSION);
    }

    /** 已保存的结构名（按名字排序，不带扩展名） */
    public static List<String> listNames() {
        List<String> names = new ArrayList<String>();
        File[] files = dir().listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String fileName = file.getName();
            if (!fileName.toLowerCase().endsWith(EXTENSION)) {
                continue;
            }
            names.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /** 读结构文件；不存在或坏掉返回 null（日志里有原因） */
    public static StructureFile load(String name) {
        File file = fileOf(name);
        if (file == null || !file.isFile()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            NBTTagCompound tag = CompressedStreamTools.readCompressed(in);
            StructureFile result = StructureFile.fromNBT(tag);
            if (result == null) {
                RsAudio.warn("结构文件内容不合法: " + file.getName());
            }
            return result;
        } catch (Throwable t) {
            // 坏文件（截断/手改）不该崩服务端，报一下就当没这个结构
            RsAudio.warn("读取结构文件失败 " + file.getName() + ": " + t);
            return null;
        } finally {
            close(in);
        }
    }

    /** 保存结构；返回 null = 成功，否则是可读的错误文字 */
    public static String save(StructureFile structure) {
        if (structure == null) {
            return "没有可保存的内容";
        }
        String safe = sanitizeName(structure.name);
        if (safe.isEmpty()) {
            return "结构名不合法（不能为空，也不能只有点号）";
        }
        structure.name = safe;
        File file = new File(dir(), safe + EXTENSION);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            CompressedStreamTools.writeCompressed(structure.toNBT(), out);
            out.flush();
            return null;
        } catch (Throwable t) {
            RsAudio.warn("保存结构文件失败 " + file.getName() + ": " + t);
            return "写入文件失败：" + t;
        } finally {
            close(out);
        }
    }

    public static boolean delete(String name) {
        File file = fileOf(name);
        return file != null && file.isFile() && file.delete();
    }

    private static void close(java.io.Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关不上就算了
        }
    }
}
