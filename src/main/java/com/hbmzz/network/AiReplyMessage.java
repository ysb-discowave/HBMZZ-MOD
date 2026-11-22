/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.network;

import com.hbmzz.ai.AiTerminalService;
import com.hbmzz.hack.HackService;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → terminal: the lines to print after one command.
 *
 * <p>The reply is sent even for a failure so a window never sits waiting on a
 * request that produced nothing. Client delivery goes through a reflective
 * bridge so this handler class stays loadable on a dedicated server.</p>
 */
public class AiReplyMessage implements IMessage {

    private static final int MAX_LINES = 48;

    private final List<HackService.Line> lines = new ArrayList<HackService.Line>();

    public AiReplyMessage() {
    }

    public AiReplyMessage(AiTerminalService.Reply reply) {
        if (reply == null) {
            return;
        }
        for (HackService.Line line : reply.lines) {
            if (this.lines.size() >= MAX_LINES) {
                break;
            }
            this.lines.add(line);
        }
    }

    public List<HackService.Line> lines() {
        return this.lines;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count; i++) {
            int tone = buf.readByte();
            this.lines.add(new HackService.Line(tone, ByteBufUtils.readUTF8String(buf)));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int count = Math.min(this.lines.size(), MAX_LINES);
        buf.writeByte(count);
        for (int i = 0; i < count; i++) {
            HackService.Line line = this.lines.get(i);
            buf.writeByte(line.tone);
            ByteBufUtils.writeUTF8String(buf, clip(line.text, 200));
        }
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public static class Handler implements IMessageHandler<AiReplyMessage, IMessage> {

        @Override
        public IMessage onMessage(AiReplyMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientPacketBridge")
                            .getMethod("onAiReply", AiReplyMessage.class)
                            .invoke(null, message);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
