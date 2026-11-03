/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM Cursed Addon (CE Addon); see NOTICE.md for the list
 * of changes.
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

package com.hbmzz.cecp.nodes.addon;

import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeDropdown;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.NodeOutput;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Plays a sound at the panel.
 *
 * <p>Ported from CE Addon's {@code NodeSounder}. Two things had to change, and
 * both are about not being the addon:</p>
 *
 * <ul>
 *   <li>The sounds are looked up by name at runtime instead of being read off
 *   {@code HBMSoundHandler} / {@code LeafiaSoundEvents} fields. Those classes
 *   belong to CE and to the addon, so a bare CE install has neither, and a
 *   missing sound has to disappear from the list rather than crash the node.</li>
 *   <li>It answers {@code true} where upstream answers {@code false}. Returning
 *   false means "cancel the receiver" to the broadcast node, so upstream's
 *   version silently eats the event it was attached to. A sound is a side
 *   effect; it has no business stopping the graph.</li>
 * </ul>
 */
public class NodeSounder extends NodeOutput {

    public enum NodeSnd {
        BUTTON_ACCEPT("com.hbm.lib.HBMSoundHandler", "buttonYes"),
        BUTTON_DENY("com.hbm.lib.HBMSoundHandler", "buttonNo"),
        BUTTON("com.leafia.init.LeafiaSoundEvents", "sbWallButton"),
        CLICK_ACCEPT(null, null),
        CLICK_INVALID("com.leafia.init.LeafiaSoundEvents", "UI_BUTTON_INVALID"),
        KEYPAD("com.leafia.init.LeafiaSoundEvents", "UI_BUTTON_KEYPAD"),
        ARMED("com.hbm.lib.HBMSoundHandler", "fstbmbStart"),
        TIMER("com.hbm.lib.HBMSoundHandler", "fstbmbPing"),
        BLEEP("com.hbm.lib.HBMSoundHandler", "techBleep"),
        POP("com.hbm.lib.HBMSoundHandler", "techBoop"),
        SHUTDOWN("com.hbm.lib.HBMSoundHandler", "shutdown"),
        AZ_5("com.leafia.init.LeafiaSoundEvents", "az5");

        private final String owner;
        private final String field;

        NodeSnd(String owner, String field) {
            this.owner = owner;
            this.field = field;
        }

        /** The sound, or null when the mod that registers it is not installed. */
        public SoundEvent resolve() {
            if (this.owner == null) {
                return SoundEvents.UI_BUTTON_CLICK;
            }
            try {
                Class<?> type = Class.forName(this.owner, true, NodeSounder.class.getClassLoader());
                Object value = type.getField(this.field).get(null);
                return value instanceof SoundEvent ? (SoundEvent) value : null;
            } catch (Throwable missing) {
                return null;
            }
        }

        static NodeSnd byName(String name) {
            for (NodeSnd value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return null;
        }
    }

    public NodeSnd snd = NodeSnd.BUTTON_ACCEPT;

    public NodeSounder(float x, float y) {
        super(x, y);
        NodeDropdown sounds = new NodeDropdown(this, this.otherElements.size(), s -> {
            NodeSnd picked = NodeSnd.byName(s);
            if (picked != null) {
                this.snd = picked;
            }
            return null;
        }, () -> this.snd.name());
        List<String> available = new ArrayList<>();
        for (NodeSnd value : NodeSnd.values()) {
            if (value.resolve() != null) {
                available.add(value.name());
            }
        }
        String[] list = available.toArray(new String[0]);
        Arrays.sort(list);
        sounds.list.addItems(list);
        this.otherElements.add(sounds);
        if (!available.contains(this.snd.name()) && !available.isEmpty()) {
            this.snd = NodeSnd.byName(available.get(0));
        }
        this.inputs.add(new NodeConnection("Volume", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0.5F)));
        this.inputs.add(new NodeConnection("Pitch", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(1)));
        this.recalcSize();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        NodeSnd read = NodeSnd.byName(tag.getString("sound"));
        if (read != null) {
            this.snd = read;
        }
        super.readFromNBT(tag, sys);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "sounder");
        tag.setString("sound", this.snd.name());
        return super.writeToNBT(tag, sys);
    }

    @Override
    public boolean doOutput(IControllable from, Map<String, NodeSystem> sendNodeMap, Map<String, BlockPos> positions) {
        World world = from.getControlWorld();
        SoundEvent evt = this.snd.resolve();
        if (evt == null) {
            return true;
        }
        float volume = MathHelper.clamp(this.inputs.get(0).evaluate().getNumber(), 0.0F, 10.0F);
        float pitch = MathHelper.clamp(this.inputs.get(1).evaluate().getNumber(), 0.5F, 2.0F);
        BlockPos pos = from.getControlPos();
        world.playSound(null, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                evt, SoundCategory.BLOCKS, volume, pitch);
        return true;
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorOutput;
    }

    @Override
    public String getDisplayName() {
        return "播放声音";
    }
}
