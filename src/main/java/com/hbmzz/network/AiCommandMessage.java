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
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * One typed line from the AI terminal to the server.
 *
 * <p>Only text travels. The server decides which machine is in range and what
 * the line means, exactly like the hacker tablet's packet — a client cannot name
 * a component or an arbitrary temperature without the command saying so.</p>
 */
public class AiCommandMessage implements IMessage {

    private String line = "";

    public AiCommandMessage() {
    }

    public AiCommandMessage(String line) {
        this.line = line == null ? "" : line;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int length = buf.readUnsignedShort();
        if (length > AiTerminalService.MAX_INPUT * 4) {
            length = AiTerminalService.MAX_INPUT * 4;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        this.line = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        String text = this.line;
        if (text.length() > AiTerminalService.MAX_INPUT) {
            text = text.substring(0, AiTerminalService.MAX_INPUT);
        }
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    public static class Handler implements IMessageHandler<AiCommandMessage, IMessage> {

        @Override
        public IMessage onMessage(AiCommandMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final String line = message.line;
            player.mcServer.addScheduledTask(() -> {
                try {
                    AiTerminalService.handle(player, line);
                } catch (Throwable throwable) {
                    AiTerminalService.Reply reply = new AiTerminalService.Reply();
                    reply.add(HackService.TONE_ERR,
                            "指令执行异常: " + throwable.getClass().getSimpleName());
                    if (NetworkHandler.INSTANCE != null) {
                        NetworkHandler.sendTo(player, new AiReplyMessage(reply));
                    }
                }
            });
            return null;
        }
    }
}
