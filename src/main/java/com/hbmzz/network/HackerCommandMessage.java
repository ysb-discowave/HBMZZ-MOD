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

import com.hbmzz.hack.HackService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * One typed line, sent from the tablet to the server.
 *
 * <p>Only the text travels. The client cannot name a target, a field or a value
 * independently of the command string: the server parses the line against the
 * world it actually has, checks that the position is loaded and owned by a
 * machine this tool may touch, and only then assigns. A compromised client can
 * therefore send any string it likes and still only reach the fields
 * {@link HackService} is willing to write - which is the same trust model the
 * panel terminal uses, for the same reason.</p>
 */
public class HackerCommandMessage implements IMessage {

    private String line = "";

    public HackerCommandMessage() {
    }

    public HackerCommandMessage(String line) {
        this.line = line == null ? "" : line;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int length = buf.readUnsignedShort();
        if (length > HackService.MAX_LINE * 4) {
            length = HackService.MAX_LINE * 4;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        this.line = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        String text = this.line;
        if (text.length() > HackService.MAX_LINE) {
            text = text.substring(0, HackService.MAX_LINE);
        }
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    public static class Handler implements IMessageHandler<HackerCommandMessage, IMessage> {

        @Override
        public IMessage onMessage(HackerCommandMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final String line = message.line;
            // SimpleImpl runs handlers off the tick thread. Writing a field on a
            // live tile entity from there races the machine's own update, so the
            // whole command is replayed on the server thread.
            player.mcServer.addScheduledTask(() -> {
                HackService.Reply reply;
                try {
                    reply = HackService.run(player, line);
                } catch (Throwable throwable) {
                    // A command must never take the tick loop with it. Answer
                    // with a refusal so the window shows something rather than
                    // hanging on a request that will never come back.
                    reply = new HackService.Reply();
                    reply.rejected = true;
                    reply.lines.add(new HackService.Line(HackService.TONE_ERR,
                            "指令执行异常: " + throwable.getClass().getSimpleName()));
                }
                NetworkHandler.sendTo(player, new HackerReplyMessage(reply));
            });
            return null;
        }
    }
}
