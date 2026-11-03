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

import com.hbmzz.panel.LogicProgram;
import com.hbmzz.panel.PanelLayout;
import com.hbmzz.tile.TileEntityDFCMonitor;
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
 * Saves a custom panel design to a monitor.
 *
 * <p>Whole-design rather than per-edit. A design is a few hundred bytes and it
 * is edited in a window that already holds the authoritative copy, so sending
 * it once on 保存 is both simpler and safer than replaying a stream of small
 * mutations that could desync if a packet is lost. It also gives 保存 an honest
 * meaning: nothing changes in the world until the operator says so.</p>
 *
 * <p>The server re-validates everything it is given (counts, grid bounds,
 * ranges). A client is not trusted to respect the limits just because its own
 * editor does.</p>
 */
public class PanelEditMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private NBTTagCompound tag = new NBTTagCompound();

    public PanelEditMessage() {
    }

    public PanelEditMessage(BlockPos pos, PanelLayout layout, LogicProgram program) {
        this.pos = pos;
        NBTTagCompound payload = new NBTTagCompound();
        payload.setTag("layout", layout.write());
        payload.setTag("logic", program.write());
        this.tag = payload;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        NBTTagCompound payload = ByteBufUtils.readTag(buf);
        this.tag = payload == null ? new NBTTagCompound() : payload;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        ByteBufUtils.writeTag(buf, this.tag);
    }

    public static class Handler implements IMessageHandler<PanelEditMessage, IMessage> {

        @Override
        public IMessage onMessage(PanelEditMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final NBTTagCompound payload = message.tag;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 32.0D * 32.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (!(te instanceof TileEntityDFCMonitor)) {
                    return;
                }
                TileEntityDFCMonitor monitor = (TileEntityDFCMonitor) te;
                PanelLayout layout = payload.hasKey("layout")
                        ? PanelLayout.read(payload.getCompoundTag("layout")) : monitor.getPanelLayout();
                LogicProgram program = payload.hasKey("logic")
                        ? LogicProgram.read(payload.getCompoundTag("logic")) : monitor.getLogicProgram();
                monitor.setPanelDesign(layout, program);
                NetworkHandler.sendTo(player, new DFCDataMessage(pos, monitor.snapshot()));
            });
            return null;
        }
    }
}
