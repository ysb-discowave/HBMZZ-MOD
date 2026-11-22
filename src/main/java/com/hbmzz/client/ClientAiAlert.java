/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

/**
 * Client half of an AI anomaly broadcast.
 *
 * <p>The voice is handled entirely by the TTS stream (one synthesis, streamed
 * to every player). This class is the visual confirmation: a red warning line
 * in chat and a burst of redstone particles at the node so the player can find
 * it. It deliberately does not touch the audio-player tile entities - those
 * are driven by their own redstone/reasim logic, and poking their private
 * playback state from another mod is exactly the kind of cross-mod coupling
 * that breaks on the next update. The TTS voice already gives the "whole map
 * hears it at once" behaviour the feature asks for.</p>
 */
public final class ClientAiAlert {

    private ClientAiAlert() {
    }

    public static void onAlert(BlockPos source, String level, String message) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) {
            return;
        }
        TextFormatting colour = colourFor(level);
        ITextComponent line = new TextComponentString(
                "[" + level + "] " + (message == null ? "" : message));
        line.setStyle(new Style().setColor(colour).setBold(true));
        player.sendMessage(line);

        // A short column of sparks at the node: lets a player locate which
        // machine is shouting without opening a screen.
        World world = Minecraft.getMinecraft().world;
        if (world != null && source != null && source != BlockPos.ORIGIN) {
            for (int i = 0; i < 12; i++) {
                world.spawnParticle(EnumParticleTypes.REDSTONE,
                        source.getX() + 0.5D,
                        source.getY() + 0.5D + world.rand.nextDouble(),
                        source.getZ() + 0.5D,
                        0.0D, 0.05D, 0.0D, 1, 0, 0);
            }
        }
    }

    private static TextFormatting colourFor(String level) {
        if (level == null) {
            return TextFormatting.RED;
        }
        String l = level.toUpperCase(java.util.Locale.ROOT);
        if ("RED".equals(l) || "OMEGA".equals(l) || "DELTA".equals(l) || "ALPHA".equals(l)) {
            return TextFormatting.RED;
        }
        if ("MED".equals(l)) {
            return TextFormatting.LIGHT_PURPLE;
        }
        if ("ORANGE".equals(l) || "YELLOW".equals(l)) {
            return TextFormatting.GOLD;
        }
        return TextFormatting.RED;
    }
}
