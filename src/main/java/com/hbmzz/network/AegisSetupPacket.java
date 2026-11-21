/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.network;

import com.hbmzz.aegis.AegisConfig;
import com.hbmzz.aegis.AegisProbe;
import com.hbmzz.aegis.TileAegisTerminal;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: one action from the terminal's configuration window.
 *
 * <p>Actions are deliberately coarse and server-authoritative: bind the nearest
 * DFC core, drop the binding, select a page, or flip the power switch. The
 * server re-validates distance and the target block, so a stale or forged window
 * can only ever affect the terminal the player is actually standing at.</p>
 */
public final class AegisSetupPacket implements IMessage {

    public static final int BIND_NEAREST = 0;
    public static final int UNBIND = 1;
    public static final int PAGE = 2;
    public static final int POWER = 3;

    private BlockPos pos;
    private int action;
    private int value;

    public AegisSetupPacket() {
    }

    public AegisSetupPacket(BlockPos pos, int action, int value) {
        this.pos = pos;
        this.action = action;
        this.value = value;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.value = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeByte(this.action);
        buf.writeInt(this.value);
    }

    public static final class Handler implements IMessageHandler<AegisSetupPacket, IMessage> {
        @Override
        public IMessage onMessage(AegisSetupPacket message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos at = message.pos;
            final int action = message.action;
            final int value = message.value;
            player.mcServer.addScheduledTask(() -> {
                // Must still be close enough to touch the block.
                if (player.getDistanceSqToCenter(at) > 12.0D * 12.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(at);
                if (!(te instanceof TileAegisTerminal)) {
                    return;
                }
                TileAegisTerminal terminal = (TileAegisTerminal) te;
                switch (action) {
                    case BIND_NEAREST: {
                        TileEntity core = AegisProbe.nearestCore(player.world, at, AegisConfig.scanRadius);
                        if (core != null) {
                            terminal.setBinding(core.getPos(), terminal.getBoundMeter());
                        }
                        break;
                    }
                    case UNBIND:
                        terminal.clearBinding();
                        break;
                    case PAGE:
                        terminal.setPage(value);
                        break;
                    case POWER:
                        terminal.setPowered(value != 0);
                        break;
                    default:
                        break;
                }
            });
            return null;
        }
    }
}
