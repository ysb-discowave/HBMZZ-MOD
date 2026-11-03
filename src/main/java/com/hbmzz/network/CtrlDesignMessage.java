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

package com.hbmzz.network;

import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.tile.TileEntityCtrlPanel;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Saves an edited panel design.
 *
 * <p>Whole-design, like the monitor's studio: the editor holds a working copy,
 * and nothing in the world changes until 保存 is pressed. The server re-runs
 * {@link CtrlPanel#validate()} on arrival - counts, grid bounds, ranges, link
 * indices - because an editor's own limits are a convenience, never a security
 * boundary.</p>
 */
public class CtrlDesignMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private NBTTagCompound tag = new NBTTagCompound();

    public CtrlDesignMessage() {
    }

    public CtrlDesignMessage(BlockPos pos, CtrlPanel panel) {
        this.pos = pos;
        this.tag = panel.write();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        NBTTagCompound read = ByteBufUtils.readTag(buf);
        this.tag = read == null ? new NBTTagCompound() : read;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        ByteBufUtils.writeTag(buf, this.tag);
    }

    public static class Handler implements IMessageHandler<CtrlDesignMessage, IMessage> {

        @Override
        public IMessage onMessage(CtrlDesignMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final NBTTagCompound payload = message.tag;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 16.0D * 16.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (te instanceof TileEntityCtrlPanel) {
                    ((TileEntityCtrlPanel) te).setPanel(CtrlPanel.read(payload));
                }
            });
            return null;
        }
    }
}
