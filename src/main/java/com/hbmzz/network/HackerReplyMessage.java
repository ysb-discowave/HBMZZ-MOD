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
import com.hbmzz.hack.HackState;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * What the server has to say back to the tablet: the lines to print, plus the
 * state of the world so the window can paint its frame without a second round
 * trip.
 *
 * <p>The state is repeated on every reply on purpose. A cut is global and can be
 * triggered by somebody else's tablet, so a window that only learned its state
 * when it opened would keep showing a healthy link while every monitor in the
 * world was reading ERROR#！. Re-sending it with each reply means the frame is
 * never older than the last thing the player typed.</p>
 */
public class HackerReplyMessage implements IMessage {

    /** Hard cap on lines per reply; a field dump is the longest legitimate one. */
    private static final int MAX_ENTRIES = 48;

    private int state = HackState.NORMAL;
    private int ticksLeft;
    private boolean hasTarget;
    private int targetX;
    private int targetY;
    private int targetZ;
    private String targetLabel = "";
    private final List<HackService.Line> lines = new ArrayList<HackService.Line>();

    public HackerReplyMessage() {
    }

    public HackerReplyMessage(HackService.Reply reply) {
        this.state = reply.state;
        this.ticksLeft = reply.ticksLeft;
        this.hasTarget = reply.hasTarget;
        this.targetX = reply.targetX;
        this.targetY = reply.targetY;
        this.targetZ = reply.targetZ;
        this.targetLabel = reply.targetLabel == null ? "" : reply.targetLabel;
        for (HackService.Line line : reply.lines) {
            if (this.lines.size() >= MAX_ENTRIES) {
                break;
            }
            this.lines.add(line);
        }
    }

    public List<HackService.Line> lines() {
        return this.lines;
    }

    public int state() {
        return this.state;
    }

    public int ticksLeft() {
        return this.ticksLeft;
    }

    public boolean hasTarget() {
        return this.hasTarget;
    }

    public int targetX() {
        return this.targetX;
    }

    public int targetY() {
        return this.targetY;
    }

    public int targetZ() {
        return this.targetZ;
    }

    public String targetLabel() {
        return this.targetLabel;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.state = buf.readByte();
        this.ticksLeft = buf.readInt();
        this.hasTarget = buf.readBoolean();
        this.targetX = buf.readInt();
        this.targetY = buf.readInt();
        this.targetZ = buf.readInt();
        this.targetLabel = ByteBufUtils.readUTF8String(buf);
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count; i++) {
            int tone = buf.readByte();
            this.lines.add(new HackService.Line(tone, ByteBufUtils.readUTF8String(buf)));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(this.state);
        buf.writeInt(this.ticksLeft);
        buf.writeBoolean(this.hasTarget);
        buf.writeInt(this.targetX);
        buf.writeInt(this.targetY);
        buf.writeInt(this.targetZ);
        ByteBufUtils.writeUTF8String(buf, clip(this.targetLabel, 96));
        int count = Math.min(this.lines.size(), MAX_ENTRIES);
        buf.writeByte(count);
        for (int i = 0; i < count; i++) {
            HackService.Line line = this.lines.get(i);
            buf.writeByte(line.tone);
            ByteBufUtils.writeUTF8String(buf, clip(line.text, 160));
        }
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * Client-only delivery, isolated behind a reflective call so this handler
     * class stays loadable on a dedicated server (it is needed there to
     * register the discriminator).
     */
    public static class Handler implements IMessageHandler<HackerReplyMessage, IMessage> {

        @Override
        public IMessage onMessage(HackerReplyMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientPacketBridge")
                            .getMethod("onHackerReply", HackerReplyMessage.class)
                            .invoke(null, message);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
