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

import com.hbmzz.ctrl.TerminalShell;
import com.hbmzz.tile.TileEntityCtrlPanel;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * "Line 4 of the terminal on that panel said: {@code set level 40}."
 *
 * <p>Only text travels. The client cannot name a machine, an event or a target:
 * the server parses the line against its own copy of the panel, so the terminal
 * is exactly as powerful as the panel already was - no more. That is the same
 * rule {@link CtrlUseMessage} follows with a knob's value, and it matters more
 * here because the payload is free-form.</p>
 */
public class TerminalInputMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int index = -1;
    private String line = "";

    public TerminalInputMessage() {
    }

    public TerminalInputMessage(BlockPos pos, int index, String line) {
        this.pos = pos;
        this.index = index;
        this.line = line == null ? "" : line;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.index = buf.readShort();
        this.line = readString(buf, TerminalShell.MAX_LINE);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeShort(this.index);
        writeString(buf, this.line, TerminalShell.MAX_LINE);
    }

    /** Var-int length plus bytes, clamped so a bad packet cannot allocate freely. */
    static String readString(ByteBuf buf, int limit) {
        int length = buf.readUnsignedShort();
        if (length > limit) {
            length = limit;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void writeString(ByteBuf buf, String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() > limit) {
            text = text.substring(0, limit);
        }
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    public static class Handler implements IMessageHandler<TerminalInputMessage, IMessage> {

        @Override
        public IMessage onMessage(TerminalInputMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int index = message.index;
            final String line = message.line;
            player.mcServer.addScheduledTask(() -> {
                TerminalShell.Sink sink = new TerminalShell.Sink();
                TerminalShell.Result result = new TerminalShell.Result();
                int slot = -1;
                // Same arm's length as operating a knob: if you cannot reach
                // the panel, you cannot type at it either. Say so rather than
                // dropping the line - a terminal that swallows input reads as
                // a broken terminal, not as a range limit.
                if (player.getDistanceSqToCenter(pos) > 8.0D * 8.0D) {
                    sink.err("离面板太远了（8 格内）。");
                } else {
                    TileEntity te = player.world.getTileEntity(pos);
                    if (!(te instanceof TileEntityCtrlPanel)) {
                        sink.err("面板已经不在了。");
                    } else {
                        TileEntityCtrlPanel panel = (TileEntityCtrlPanel) te;
                        result = panel.runTerminal(index, line, sink);
                        com.hbmzz.ctrl.CtrlWidget widget = panel.getPanel().get(index);
                        if (widget != null) {
                            slot = widget.link;
                        }
                    }
                }
                NetworkHandler.sendTo(player,
                        new TerminalReplyMessage(pos, index, slot, result.clear, sink));
            });
            return null;
        }
    }
}
