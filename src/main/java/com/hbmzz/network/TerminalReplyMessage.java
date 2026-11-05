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
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * The answer to one typed line: the text to append, what each line is (output,
 * error, hint...), and the machine the terminal is now pointing at.
 *
 * <p>Lines and kinds travel as two flat strings rather than a list of objects:
 * a reply is at most forty short lines, and one length-prefixed blob is both
 * smaller and impossible to desynchronise. Kinds are one character per line,
 * index for index, so the two cannot drift out of step.</p>
 *
 * <p>The slot rides along because selecting a machine changes the panel - the
 * server syncs the block as well - but the operator's own window should not
 * have to wait for that update to show the right target.</p>
 */
public class TerminalReplyMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int index = -1;
    private int slot = -1;
    private boolean clear;
    private String kinds = "";
    private String text = "";

    public TerminalReplyMessage() {
    }

    public TerminalReplyMessage(BlockPos pos, int index, int slot, boolean clear,
                                TerminalShell.Sink sink) {
        this.pos = pos;
        this.index = index;
        this.slot = slot;
        this.clear = clear;
        StringBuilder body = new StringBuilder();
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < sink.lines.size(); i++) {
            if (i > 0) {
                body.append('\n');
            }
            body.append(sink.lines.get(i));
            tags.append((char) ('0' + Math.max(0, Math.min(9, sink.kinds.get(i).intValue()))));
        }
        this.text = body.toString();
        this.kinds = tags.toString();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.index = buf.readShort();
        this.slot = buf.readByte();
        this.clear = buf.readBoolean();
        this.kinds = TerminalInputMessage.readString(buf, TerminalShell.MAX_REPLY);
        this.text = TerminalInputMessage.readString(buf, TerminalShell.MAX_REPLY * 256);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeShort(this.index);
        buf.writeByte(this.slot);
        buf.writeBoolean(this.clear);
        TerminalInputMessage.writeString(buf, this.kinds, TerminalShell.MAX_REPLY);
        TerminalInputMessage.writeString(buf, this.text, TerminalShell.MAX_REPLY * 256);
    }

    /**
     * Registered for the client, but the handler class must still load on a
     * dedicated server, so the window is reached reflectively - the same
     * arrangement {@link DFCDataMessage} uses.
     */
    public static class Handler implements IMessageHandler<TerminalReplyMessage, IMessage> {

        @Override
        public IMessage onMessage(TerminalReplyMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientTerminalBridge")
                            .getMethod("onReply", BlockPos.class, int.class, int.class,
                                    boolean.class, String.class, String.class)
                            .invoke(null, message.pos, Integer.valueOf(message.index),
                                    Integer.valueOf(message.slot), Boolean.valueOf(message.clear),
                                    message.kinds, message.text);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
