/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.net;

import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.compat.CPEnv;
import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import com.hbmzz.cecp.types.DataValue;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * Server to client: either a delta of the variables that changed this tick
 * ({@code updates != null}) or the whole panel NBT ({@code size == -1}).
 * Wire format matches NTM: Cursed Edition's packet of the same name.
 */
public class ControlPanelUpdatePacket implements IMessage {

    private int x;
    private int y;
    private int z;
    private List<VarUpdate> updates;
    private NBTTagCompound fullNbt;

    public ControlPanelUpdatePacket() {
    }

    public ControlPanelUpdatePacket(BlockPos pos, List<VarUpdate> toUpdate) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.updates = toUpdate;
    }

    public ControlPanelUpdatePacket(BlockPos pos, NBTTagCompound tag) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.fullNbt = tag;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer wrapper = new PacketBuffer(buf);
        this.x = wrapper.readInt();
        this.y = wrapper.readInt();
        this.z = wrapper.readInt();
        int size = wrapper.readInt();
        if (size == -1) {
            this.fullNbt = readCompound(wrapper);
            return;
        }
        VarUpdate[] received = new VarUpdate[size];
        for (int i = 0; i < size; i++) {
            int idx = wrapper.readInt();
            String name = readString(wrapper);
            received[i] = new VarUpdate(idx, name, null);
        }
        NBTTagCompound dataTag = readCompound(wrapper);
        if (dataTag != null) {
            for (int i = 0; i < size; i++) {
                if (dataTag.hasKey(String.valueOf(i))) {
                    received[i].data = DataValue.newFromNBT(dataTag.getTag(String.valueOf(i)));
                }
            }
        }
        this.updates = new java.util.ArrayList<>();
        for (VarUpdate u : received) {
            this.updates.add(u);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer wrapper = new PacketBuffer(buf);
        wrapper.writeInt(this.x);
        wrapper.writeInt(this.y);
        wrapper.writeInt(this.z);
        if (this.updates == null) {
            wrapper.writeInt(-1);
            writeCompound(wrapper, this.fullNbt);
            return;
        }
        wrapper.writeInt(this.updates.size());
        for (VarUpdate u : this.updates) {
            if (u != null) {
                wrapper.writeInt(u.varListIdx);
                writeString(wrapper, u.varName == null ? "" : u.varName);
            } else {
                wrapper.writeInt(0);
                writeString(wrapper, "");
            }
        }
        NBTTagCompound tag = new NBTTagCompound();
        int i = 0;
        for (VarUpdate u : this.updates) {
            if (u != null && u.data != null) {
                tag.setTag(String.valueOf(i), u.data.writeToNBT());
            }
            i++;
        }
        writeCompound(wrapper, tag);
    }

    // PacketBuffer's read helpers declare IOException; on the wire path there
    // is nothing sensible to do about it, so wrap into unchecked. Writing does
    // not throw in this MC version.
    private static NBTTagCompound readCompound(PacketBuffer buffer) {
        try {
            return buffer.readCompoundTag();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readString(PacketBuffer buffer) {
        return buffer.readString(64);
    }

    private static void writeCompound(PacketBuffer buffer, NBTTagCompound tag) {
        buffer.writeCompoundTag(tag);
    }

    private static void writeString(PacketBuffer buffer, String value) {
        buffer.writeString(value);
    }

    public static class Handler implements IMessageHandler<ControlPanelUpdatePacket, IMessage> {

        @Override
        @SideOnly(Side.CLIENT)
        public IMessage onMessage(ControlPanelUpdatePacket m, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                try {
                    handle(m);
                } catch (Exception e) {
                    CPEnv.logger.error("Failed to apply control panel update", e);
                }
            });
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void handle(ControlPanelUpdatePacket m) {
            if (Minecraft.getMinecraft().world == null) {
                return;
            }
            TileEntity te = Minecraft.getMinecraft().world.getTileEntity(new BlockPos(m.x, m.y, m.z));
            if (!(te instanceof TileEntityCtrlConsole)) {
                return;
            }
            ControlPanel panel = ((TileEntityCtrlConsole) te).panel;

            if (m.updates == null) {
                if (m.fullNbt != null) {
                    panel.readFromNBT(m.fullNbt);
                }
                return;
            }
            for (VarUpdate u : m.updates) {
                if (u.varName == null) {
                    continue;
                }
                if (u.varListIdx == -1) {
                    if (u.data == null) {
                        panel.globalVars.remove(u.varName);
                    } else {
                        panel.globalVars.put(u.varName, u.data);
                    }
                } else if (u.varListIdx < panel.controls.size()) {
                    if (u.data == null) {
                        panel.controls.get(u.varListIdx).vars.remove(u.varName);
                    } else {
                        panel.controls.get(u.varListIdx).vars.put(u.varName, u.data);
                    }
                }
            }
        }
    }

    public static class VarUpdate {
        final int varListIdx;
        final String varName;
        public DataValue data;

        public VarUpdate(int varListIdx, String varName, DataValue data) {
            this.varListIdx = varListIdx;
            this.varName = varName;
            this.data = data;
        }
    }
}
