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
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes AI control actions against the DFC machinery.
 *
 * <p>Two entry points share one executor:</p>
 * <ul>
 *   <li>{@link #onVerdict} — the automatic path. A node's anomaly verdict may
 *       carry {@code actions}; if it does not, the autopilot applies its own
 *       safety rule (temperature / stability thresholds) so an AI that only
 *       reports a number still causes a SCRAM when the core is about to go.</li>
 *   <li>{@link #run} — the manual path from the AI terminal / {@code /ai}, where
 *       the operator's text is either interpreted locally or answered by the AI
 *       service and the resulting actions run here.</li>
 * </ul>
 *
 * <p>Everything is server-thread only and every action reports a line of text;
 * a hook that could not be found is never reported as a success.</p>
 */
public final class AiAutopilot {

    private AiAutopilot() {
    }

    /**
     * Automatic path from a node callback. Honours the global write switch and
     * acts on the machine the node is bound to (its position is the anchor for
     * the DFC core search).
     */
    public static List<String> onVerdict(World world, BlockPos anchor, AiClient.Verdict verdict) {
        List<String> out = new ArrayList<String>();
        if (!AiConfig.autoControl || world == null || world.isRemote || verdict == null) {
            return out;
        }
        if (!verdict.actions.isEmpty()) {
            return run(world, anchor, verdict.actions);
        }
        // No explicit actions. The local safety-net SCRAM is opt-in: the default
        // prompt tells the AI to report and wait for an instruction, so the mod
        // must not stop the reactor on its own behind that prompt's back.
        if (!AiConfig.autoScram) {
            return out;
        }
        // Safety net enabled: apply the threshold rule if a DFC is in range.
        DfcControl.Reading reading = DfcControl.read(world, anchor, com.hbmzz.aegis.AegisConfig.scanRadius);
        if (!reading.linked) {
            return out;
        }
        boolean hot = !Double.isNaN(reading.temperature)
                && reading.temperature > AiConfig.autoScramTemp;
        boolean unstable = !Double.isNaN(reading.stability)
                && reading.stability < AiConfig.autoMinStability;
        if (hot || unstable) {
            DfcControl.Result result = DfcControl.scram(world, anchor,
                    com.hbmzz.aegis.AegisConfig.scanRadius);
            String why = hot ? "温度过高" : "稳定度不足";
            out.add("[自动接管] " + why + "：" + result.message);
            HBMZZ.logger.info("[AI autopilot] {} @ {} -> SCRAM: {}", why, anchor, result.message);
            tellAll(world, "[AI 自动接管] " + why + " → " + reading.describe());
        }
        return out;
    }

    /** Executes a list of actions against the DFC nearest to {@code anchor}. */
    public static List<String> run(World world, BlockPos anchor, List<AiAction> actions) {
        List<String> out = new ArrayList<String>();
        if (!AiConfig.autoControl) {
            out.add("AI 控制写入已被管理员关闭（/ai control on 开启）。");
            return out;
        }
        if (world == null || world.isRemote || actions == null || actions.isEmpty()) {
            return out;
        }
        int radius = com.hbmzz.aegis.AegisConfig.scanRadius;
        for (AiAction a : actions) {
            if (a == null) {
                continue;
            }
            String line = apply(world, anchor, radius, a);
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        if (!out.isEmpty()) {
            tellAll(world, "[AI 控制] " + out.get(0));
        }
        return out;
    }

    private static String apply(World world, BlockPos anchor, int radius, AiAction a) {
        String op = a.op == null ? "" : a.op;
        if (AiAction.START.equals(op)) {
            return "启动核心：" + DfcControl.start(world, anchor, radius).message;
        }
        if (AiAction.SCRAM.equals(op)) {
            return "停堆：" + DfcControl.scram(world, anchor, radius).message;
        }
        if (AiAction.CLEAR.equals(op)) {
            DfcControl.Reading r = DfcControl.read(world, anchor, radius);
            return r.linked ? "坍缩清除：" + DfcControl.scram(world, anchor, radius).message
                    : "未检测到 DFC 核心。";
        }
        if (AiAction.PART_ON.equals(op) || AiAction.PART_OFF.equals(op)) {
            DfcControl.Part part = DfcControl.Part.byKey(a.part);
            if (part == null) {
                return "未知组件: " + a.part;
            }
            boolean on = AiAction.PART_ON.equals(op) ? true : a.on;
            return DfcControl.setPart(world, anchor, radius, part, on).message;
        }
        if (AiAction.TEMPERATURE.equals(op)) {
            if (Double.isNaN(a.value)) {
                return "温度指令缺少数值。";
            }
            return DfcControl.setTemperature(world, anchor, radius, a.value).message;
        }
        if (AiAction.SPEAK.equals(op)) {
            // The spoken half is handled by AiBroadcast; nothing to write here.
            return "";
        }
        return "未知指令: " + op;
    }

    /** Broadcasts one concise line to every player on the server. */
    private static void tellAll(World world, String text) {
        MinecraftServer server = world == null ? null : world.getMinecraftServer();
        if (server == null) {
            return;
        }
        TextComponentString comp = new TextComponentString(text);
        comp.setStyle(new net.minecraft.util.text.Style().setColor(TextFormatting.GOLD));
        server.getPlayerList().sendMessage(comp);
    }
}
