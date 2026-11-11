package com.rsaudio.client;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rsaudio.RsAudio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.settings.GameSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 把 config/rsaudio/audio 里的音频文件"镜像"成一个真正的资源包，放在
 * resourcepacks/rsaudio_dynamic，然后自动启用它。
 * <p>
 * 这样任意 ogg 文件就接进了 Minecraft 的声音系统，原版的音量、距离衰减、
 * 音效分类（唱片机音量条）等机制全部可用。
 */
public class DynamicSoundPack {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 生成/更新动态资源包。
     *
     * @return true 表示 sounds.json 内容变化了（需要重载资源）
     */
    public static boolean generate() throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        File packDir = new File(mc.mcDataDir, "resourcepacks" + File.separator + RsAudio.PACK_FOLDER);
        File soundsDir = new File(packDir, "assets/" + RsAudio.MODID + "/sounds");
        mkdirs(packDir);
        mkdirs(soundsDir);

        JsonObject meta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "红石音频 - 由模组自动生成的音乐包");
        pack.addProperty("pack_format", 3);
        meta.add("pack", pack);
        writeIfChanged(new File(packDir, "pack.mcmeta"), GSON.toJson(meta));

        // sounds.json
        // 注意：1.12.2 的 SoundListSerializer 直接 new ResourceLocation(name)，
        // 所以 name 必须带命名空间；ogg 路径 = assets/<ns>/sounds/<path>.ogg
        JsonObject root = new JsonObject();
        for (AudioLibrary.Entry entry : AudioLibrary.getEntries()) {
            JsonObject event = new JsonObject();
            event.addProperty("category", "record");
            JsonArray sounds = new JsonArray();
            JsonObject sound = new JsonObject();
            sound.addProperty("name", RsAudio.MODID + ":" + entry.soundName);
            sound.addProperty("stream", true);
            sounds.add(sound);
            event.add("sounds", sounds);
            root.add(entry.soundName, event);
        }
        File soundsJson = new File(packDir, "assets/" + RsAudio.MODID + "/sounds.json");
        boolean changed = writeIfChanged(soundsJson, GSON.toJson(root));

        for (AudioLibrary.Entry entry : AudioLibrary.getEntries()) {
            copyIfNeeded(entry.file, new File(soundsDir, entry.soundName + ".ogg"));
        }
        return changed;
    }

    /**
     * 启用资源包并重载资源。
     * <p>
     * 顺序和原版资源包界面（GuiScreenResourcePacks）里点"完成"时完全一致：
     * setRepositories -> 更新 options -> saveOptions -> refreshResources。
     */
    public static void enableAndReload() {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            ResourcePackRepository repository = mc.getResourcePackRepository();
            repository.updateRepositoryEntriesAll();

            ResourcePackRepository.Entry target = null;
            for (ResourcePackRepository.Entry entry : repository.getRepositoryEntriesAll()) {
                if (RsAudio.PACK_FOLDER.equals(entry.getResourcePackName())) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                RsAudio.warn("在 resourcepacks 里找不到 %s，音乐无法播放（请检查目录是否有写权限）",
                        RsAudio.PACK_FOLDER);
                return;
            }

            List<ResourcePackRepository.Entry> entries = Lists.newArrayList(repository.getRepositoryEntries());
            if (!entries.contains(target)) {
                entries.add(target); // 放最后 = 优先级最高
                repository.setRepositories(entries);
            }

            GameSettings settings = mc.gameSettings;
            if (!settings.resourcePacks.contains(RsAudio.PACK_FOLDER)) {
                settings.resourcePacks.add(RsAudio.PACK_FOLDER);
                settings.saveOptions();
            }
            mc.refreshResources();
        } catch (Throwable t) {
            RsAudio.warn("启用音频资源包失败: " + t);
        }
    }

    public static boolean isEnabled() {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        return settings.resourcePacks.contains(RsAudio.PACK_FOLDER)
                || settings.incompatibleResourcePacks.contains(RsAudio.PACK_FOLDER);
    }

    // ------------------------------------------------------------------ 工具

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir);
        }
    }

    private static boolean writeIfChanged(File file, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (file.isFile()) {
            byte[] old = Files.readAllBytes(file.toPath());
            if (Arrays.equals(old, bytes)) {
                return false;
            }
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return true;
    }

    /**
     * 把音频文件放进资源包目录。
     * <p>
     * 优先创建<b>硬链接</b>（NTFS/ext4 都支持，普通权限即可），这样资源包里不会再占一份
     * 同样的空间（音乐动辄上百 MB）。硬链接不可用时退回普通复制。
     */
    private static void copyIfNeeded(File source, File target) throws IOException {
        if (target.isFile()) {
            boolean sameSize = target.length() == source.length();
            if (sameSize && isSameFile(source, target)) {
                return;
            }
            if (sameSize && target.lastModified() >= source.lastModified()) {
                return;
            }
        }
        // 先试硬链接
        try {
            Files.deleteIfExists(target.toPath());
            Files.createLink(target.toPath(), source.toPath());
            return;
        } catch (Throwable ignored) {
            // 文件系统不支持 / 跨盘 / 权限不足 —— 退回复制
        }
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        target.setLastModified(source.lastModified());
    }

    /** 已经是同一个文件（硬链接）就不用动 */
    private static boolean isSameFile(File a, File b) {
        try {
            return Files.isSameFile(a.toPath(), b.toPath());
        } catch (Throwable t) {
            return false;
        }
    }
}
