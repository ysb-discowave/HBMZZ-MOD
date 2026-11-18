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

import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: "here is a lump of NBT for the console at this position".
 * Carries both a whole-panel save ({@code full_set}) and a single control click
 * ({@code click_control}), exactly like NTM: Cursed Edition's packet of the
 * same name.
 */
public class NBTControlPacket implements IMessage {

    private int x;
    private int y;
    private int z;
    private NBTTagCompound nbt;

    public NBTControlPacket() {
    }

    public NBTControlPacket(NBTTagCompound nbt, BlockPos pos) {
        this(nbt, pos.getX(), pos.getY(), pos.getZ());
    }

    public NBTControlPacket(NBTTagCompound nbt, int x, int y, int z) {
        this.nbt = nbt;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        try {
            this.nbt = new PacketBuffer(buf).readCompoundTag();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.x);
        buf.writeInt(this.y);
        buf.writeInt(this.z);
        new PacketBuffer(buf).writeCompoundTag(this.nbt);
    }

    public static class Handler implements IMessageHandler<NBTControlPacket, IMessage> {

        @Override
        public IMessage onMessage(NBTControlPacket m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.mcServer.addScheduledTask(() -> {
                if (player.world == null || m.nbt == null) {
                    return;
                }
                BlockPos pos = new BlockPos(m.x, m.y, m.z);
                // Cheap reach check: the packet must come from within arm's length.
                if (player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (te instanceof TileEntityCtrlConsole) {
                    ((TileEntityCtrlConsole) te).receiveControl(player, m.nbt);
                }
            });
            return null;
        }
    }
}
