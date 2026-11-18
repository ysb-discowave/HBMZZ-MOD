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

package com.hbmzz.client;

import com.hbmzz.panel.LogicProgram;
import com.hbmzz.panel.PanelLayout;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Named panel presets, kept next to the game's config.
 *
 * <p>The file is client-side on purpose. A preset is a <em>design asset</em> -
 * the thing an operator builds once and stamps onto thirty screens - not world
 * state, and putting it in the save would mean every world needs its own
 * library and none of them can be copied to another world. Being a plain NBT
 * file also makes it shareable: send the file, get the panel.</p>
 *
 * <p>Loading a preset into a monitor still goes through the normal 保存 path,
 * so the server remains the authority on what a screen actually shows.</p>
 */
@SideOnly(Side.CLIENT)
public final class PanelPresets {

    private static final int MAX_PRESETS = 32;
    private static final int MAX_NAME = 16;

    private static PanelPresets instance;

    /** Cross-monitor copy/paste, session only: no reason to persist it. */
    private static PanelLayout clipboardLayout;
    private static LogicProgram clipboardLogic;

    private final File file;
    private final List<String> names = new ArrayList<String>();
    private final List<NBTTagCompound> entries = new ArrayList<NBTTagCompound>();

    private PanelPresets(File file) {
        this.file = file;
        load();
    }

    public static PanelPresets get() {
        if (instance == null) {
            File dir = new File("config");
            instance = new PanelPresets(new File(dir, "hbmzz-panels.nbt"));
        }
        return instance;
    }

    public static boolean hasClipboard() {
        return clipboardLayout != null && clipboardLogic != null;
    }

    public static void setClipboard(PanelLayout layout, LogicProgram program) {
        clipboardLayout = layout.copy();
        clipboardLogic = program.copy();
    }

    public static PanelLayout clipboardLayout() {
        return clipboardLayout == null ? null : clipboardLayout.copy();
    }

    public static LogicProgram clipboardLogic() {
        return clipboardLogic == null ? null : clipboardLogic.copy();
    }

    public List<String> names() {
        return new ArrayList<String>(this.names);
    }

    public boolean isEmpty() {
        return this.names.isEmpty();
    }

    /** Stores (or overwrites) a preset under {@code name}. */
    public void put(String name, PanelLayout layout, LogicProgram program) {
        String safe = sanitize(name);
        if (safe.isEmpty()) {
            return;
        }
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("name", safe);
        entry.setTag("layout", layout.write());
        entry.setTag("logic", program.write());
        int existing = this.names.indexOf(safe);
        if (existing >= 0) {
            this.entries.set(existing, entry);
        } else {
            if (this.names.size() >= MAX_PRESETS) {
                this.names.remove(0);
                this.entries.remove(0);
            }
            this.names.add(safe);
            this.entries.add(entry);
        }
        save();
    }

    public PanelLayout layout(int index) {
        NBTTagCompound entry = entry(index);
        return entry == null ? null : PanelLayout.read(entry.getCompoundTag("layout"));
    }

    public LogicProgram logic(int index) {
        NBTTagCompound entry = entry(index);
        return entry == null ? new LogicProgram() : LogicProgram.read(entry.getCompoundTag("logic"));
    }

    public void remove(int index) {
        if (index >= 0 && index < this.names.size()) {
            this.names.remove(index);
            this.entries.remove(index);
            save();
        }
    }

    private NBTTagCompound entry(int index) {
        if (index < 0 || index >= this.entries.size()) {
            return null;
        }
        return this.entries.get(index);
    }

    public static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            trimmed = trimmed.substring(0, MAX_NAME);
        }
        return trimmed;
    }

    // -------------------------------------------------------------------- io

    private void load() {
        this.names.clear();
        this.entries.clear();
        if (this.file == null || !this.file.isFile()) {
            return;
        }
        try {
            NBTTagCompound root = CompressedStreamTools.read(this.file);
            if (root == null) {
                return;
            }
            NBTTagList list = root.getTagList("presets", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound entry = list.getCompoundTagAt(i);
                String name = entry.getString("name");
                if (!name.isEmpty()) {
                    this.names.add(name);
                    this.entries.add(entry);
                }
            }
        } catch (Throwable ignored) {
            // A corrupt library must not stop the game from starting; it just
            // has nothing in it.
        }
    }

    private void save() {
        try {
            File parent = this.file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            NBTTagCompound root = new NBTTagCompound();
            NBTTagList list = new NBTTagList();
            for (NBTTagCompound entry : this.entries) {
                list.appendTag(entry);
            }
            root.setTag("presets", list);
            CompressedStreamTools.write(root, this.file);
        } catch (Throwable ignored) {
            // Same reasoning as load: the game matters more than the library.
        }
    }
}
