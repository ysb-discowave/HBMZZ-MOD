/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.network;

import com.hbmzz.client.ClientAiAlert;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server -> all clients: an AI node raised an anomaly.
 *
 * <p>Carries no audio. The voice itself is streamed separately by the TTS
 * pipeline (one synthesis, fanned out to every player). This packet is the
 * guaranteed-delivery half: it pulses the audio-player blocks and shows a chat
 * line even when a client has no TTS engine or missed the WAV stream. Sending
 * it to every client is what makes the whole map react in the same instant.</p>
 */
public class AiAlertMessage implements IMessage {

    private BlockPos source = BlockPos.ORIGIN;
    private String level = "ALERT";
    private String message = "";

    public AiAlertMessage() {
    }

    public AiAlertMessage(BlockPos source, String level, String message) {
        this.source = source == null ? BlockPos.ORIGIN : source;
        this.level = level == null ? "ALERT" : level;
        this.message = message == null ? "" : message;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.source = BlockPos.fromLong(buf.readLong());
        this.level = ByteBufUtils.readUTF8String(buf);
        this.message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.source.toLong());
        ByteBufUtils.writeUTF8String(buf, this.level);
        ByteBufUtils.writeUTF8String(buf, this.message);
    }

    public static class Handler implements IMessageHandler<AiAlertMessage, IMessage> {

        @Override
        public IMessage onMessage(final AiAlertMessage message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientAiAlert.onAlert(message.source, message.level, message.message);
                }
            });
            return null;
        }
    }
}
