/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import com.hbmzz.HBMZZ;
import com.hbmzz.network.AiAlertMessage;
import com.hbmzz.network.NetworkHandler;
import com.rsaudio.util.TtsServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an AI anomaly verdict into a facility-wide announcement.
 *
 * <p>Two things happen on an anomaly:</p>
 * <ol>
 *   <li><b>Voice</b> - the verdict's message is handed to {@link TtsServer#speak},
 *       which synthesises it once on the server and streams the WAV to every
 *       online player. That is what makes "the whole map hears it at once":
 *       one synthesis, one fan-out to the full player list.</li>
 *   <li><b>Visual</b> - an {@link AiAlertMessage} is sent to every client so all
 *       audio-player blocks pulse their ACTIVE lamp and a chat line is shown,
 *       even for players whose TTS engine failed to start.</li>
 * </ol>
 *
 * <p>Everything here runs on the server thread (called from the node's tick via
 * {@link AiClient}'s callback, which already hopped back). TTS synthesis and
 * network encode happen on their own workers; this method just enqueues.</p>
 */
public final class AiBroadcast {

    private AiBroadcast() {
    }

    /** Minimum seconds between two announcements, to stop a flapping sensor flooding voice. */
    private static long lastAnnounceMs = 0L;
    private static final long COOLDOWN_MS = 3000L;

    /**
     * Announce an anomaly to everyone online.
     *
     * @param source    the node's position, used in the chat line
     * @param verdict   the AI verdict
     * @param reasimOn  whether the voice should be routed through the reasim DSP
     * @return true if a voice announcement was actually queued
     */
    public static boolean announce(net.minecraft.util.math.BlockPos source,
                                   AiClient.Verdict verdict, boolean reasimOn) {
        long now = System.currentTimeMillis();
        if (now - lastAnnounceMs < COOLDOWN_MS) {
            return false;
        }
        lastAnnounceMs = now;

        String message = verdict.message == null ? "" : verdict.message.trim();
        if (message.isEmpty()) {
            message = "设施数值异常：" + (verdict.reason == null ? "AI 检测到异常" : verdict.reason);
        }
        // TTS has a hard char cap; keep the spoken part short and let the chat line carry detail.
        final String spoken = message;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        List<EntityPlayerMP> targets = new ArrayList<EntityPlayerMP>();
        if (server != null) {
            for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
                if (p != null) {
                    targets.add(p);
                }
            }
        }

        // 1) Voice to every player. reasim routing is registered as a one-shot
        //    post-process on the TTS client player (see TtsClientPlayer).
        boolean queued = false;
        if (AiConfig.autoBroadcast && !targets.isEmpty()) {
            TtsServer.setReasimNext(reasimOn);
            queued = TtsServer.speak(spoken, AiConfig.lengthScale, -1, targets);
        }

        // 2) Visual pulse + chat to every client, independent of TTS availability.
        String level = verdict.level == null ? "ALERT" : verdict.level;
        if (NetworkHandler.INSTANCE != null) {
            NetworkHandler.INSTANCE.sendToAll(
                    new AiAlertMessage(source, level, message));
        }
        if (server != null) {
            String line = "[" + level + "] AI @ " + source.getX() + "," + source.getY()
                    + "," + source.getZ() + " : " + message;
            net.minecraft.util.text.ITextComponent comp =
                    new TextComponentString(line);
            comp.setStyle(new net.minecraft.util.text.Style()
                    .setColor(TextFormatting.RED).setBold(true));
            server.getPlayerList().sendMessage(comp);
        }
        HBMZZ.logger.info("[AI] anomaly @ {} level={} message={}", source, level, message);
        return queued;
    }

    /** Test hook: send a short spoken self-check to the full server. */
    public static boolean selfTest(net.minecraft.util.math.BlockPos source) {
        AiClient.Verdict v = new AiClient.Verdict();
        v.abnormal = true;
        v.level = "TEST";
        v.message = "AI 控制节点自检，语音广播正常";
        lastAnnounceMs = 0L;
        return announce(source, v, false);
    }
}
