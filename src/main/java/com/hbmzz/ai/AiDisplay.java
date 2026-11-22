/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import com.hbmzz.alert.AlertCode;
import com.hbmzz.alert.FacilityState;
import com.hbmzz.alert.NukeService;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Bridges an AI verdict to the facility-wide code displays.
 *
 * <p>The displays are views of one record ({@link FacilityState}), and every
 * change to that record is fanned out to all clients by {@code AlertSync}. To
 * "operate the displays" therefore means exactly one thing: write that record.
 * This class is the AI's one way of doing so - the anomaly verdict either names
 * a standard {@link AlertCode} or carries its own level / label / colour /
 * bilingual text, and either shape is turned into a single
 * {@link NukeService#raiseCustom} call so the record, the screens and the chat
 * line all change together.</p>
 *
 * <p>Idempotence is deliberate. A node polls every few seconds and an anomaly
 * that lasts a minute would otherwise raise the same code twelve times, filling
 * the display history and spamming chat. {@link #apply} compares the incoming
 * payload with what is already on the record and does nothing when they match,
 * so a sustained anomaly shows one entry with a growing {@code T+} timer - the
 * same behaviour an operator's alarm panel has.</p>
 *
 * <p>All methods expect to run on the server thread (they are called from
 * {@link AiBroadcast} and {@link CommandAI}, both already on it) and are safe
 * no-ops on a client or with a null world.</p>
 */
public final class AiDisplay {

    /** Source label stored on the facility record and shown on the displays. */
    public static final String SOURCE = "AI 控制节点";

    private AiDisplay() {
    }

    /**
     * Drives the displays from a verdict.
     *
     * @return true when the facility code actually changed
     */
    public static boolean apply(World world, BlockPos source, AiClient.Verdict verdict) {
        if (world == null || world.isRemote || verdict == null) {
            return false;
        }
        if (!verdict.hasDisplay) {
            // No explicit instruction: use the verdict's level word (RED, DELTA,
            // ...) or fall back to RED so an anomaly is never shown as GREEN.
            AlertCode code = codeFromLevel(verdict.level);
            if (code == null) {
                code = AlertCode.RED;
            }
            return raise(world, code, source, firstLine(verdict.message, verdict.reason));
        }

        int level = verdict.displayLevel >= 0 ? (verdict.displayLevel & 0x07) : levelFromLevel(verdict.level);
        AlertCode base = AlertCode.byId(level);
        int rgb = verdict.displayRgb >= 0 ? (verdict.displayRgb & 0xFFFFFF) : base.rgb();
        String label = pick(verdict.displayLabel, base.label());
        String zhTitle = pick(verdict.displayZhTitle, base.zhTitle());
        String enTitle = pick(verdict.displayEnTitle, base.enTitle());
        String zhDetail = pick(verdict.displayZhDetail, firstLine(verdict.message, base.zhDetail()));
        String enDetail = pick(verdict.displayEnDetail, base.enDetail());

        if (alreadyShowing(world, level, true, label, rgb, zhTitle, enTitle, zhDetail, enDetail)) {
            return false;
        }
        NukeService.raiseCustom(world, level, label, rgb, zhTitle, enTitle, zhDetail, enDetail,
                SOURCE, coord(source), zhDetail);
        return true;
    }

    /** Raises a standard code, unless that exact code is already displayed. */
    public static boolean raise(World world, AlertCode code, BlockPos source, String detail) {
        if (world == null || world.isRemote || code == null) {
            return false;
        }
        if (alreadyShowing(world, code.id(), false, code.label(), code.rgb(),
                code.zhTitle(), code.enTitle(), code.zhDetail(), code.enDetail())) {
            return false;
        }
        NukeService.raise(world, code, SOURCE, coord(source), pick(detail, code.zhDetail()));
        return true;
    }

    /**
     * Clears an AI-raised alarm back to GREEN. Broken out as an explicit command
     * rather than fired on every normal verdict: a normal reading must not undo
     * an alarm another source (a warhead, an operator) deliberately raised.
     */
    public static boolean clear(World world, BlockPos source) {
        if (world == null || world.isRemote) {
            return false;
        }
        FacilityState state = FacilityState.get(world);
        if (state != null && state.codeId() == AlertCode.GREEN.id()) {
            return false;
        }
        NukeService.raise(world, AlertCode.GREEN, SOURCE, coord(source), "AI 解除设施警报");
        return true;
    }

    private static boolean alreadyShowing(World world, int level, boolean custom, String label,
                                          int rgb, String zhTitle, String enTitle,
                                          String zhDetail, String enDetail) {
        FacilityState state = FacilityState.get(world);
        if (state == null || state.codeId() != (level & 0x07) || state.isCustom() != custom) {
            return false;
        }
        if (!custom) {
            return true;
        }
        return equals(state.customLabel(), label)
                && (state.customRgb() & 0xFFFFFF) == (rgb & 0xFFFFFF)
                && equals(state.customZhTitle(), zhTitle)
                && equals(state.customEnTitle(), enTitle)
                && equals(state.customZhDetail(), zhDetail)
                && equals(state.customEnDetail(), enDetail);
    }

    private static AlertCode codeFromLevel(String level) {
        if (level == null || level.trim().isEmpty()) {
            return null;
        }
        String word = level.trim();
        for (AlertCode code : AlertCode.values()) {
            if (code.label().equalsIgnoreCase(word)) {
                return code;
            }
        }
        try {
            int n = Integer.parseInt(word);
            return n >= 0 && n < AlertCode.count() ? AlertCode.byId(n) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int levelFromLevel(String level) {
        AlertCode code = codeFromLevel(level);
        return code == null ? AlertCode.RED.id() : code.id();
    }

    private static String firstLine(String primary, String fallback) {
        String text = primary == null ? "" : primary.trim();
        if (text.isEmpty()) {
            text = fallback == null ? "" : fallback.trim();
        }
        int nl = text.indexOf('\n');
        if (nl >= 0) {
            text = text.substring(0, nl);
        }
        return text;
    }

    private static String pick(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String coord(BlockPos pos) {
        return pos == null ? SOURCE : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
