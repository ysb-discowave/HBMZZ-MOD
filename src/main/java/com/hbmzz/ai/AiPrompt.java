/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt layer for the AI control node.
 *
 * <p>Before this existed the JSON request carried only the model name and the
 * machine snapshot: the service decided on its own how to judge the readings.
 * That made it impossible to steer the AI's behaviour ("be conservative",
 * "only diagnose, never act", "answer in English", ...). This class assembles
 * a <b>prompt</b> string that is sent alongside every request, from three
 * layers:</p>
 *
 * <ol>
 *   <li>a fixed {@link #CONTRACT} that describes the JSON reply format the mod
 *       can parse (so custom prompts can never break decoding);</li>
 *   <li>a built-in {@link Preset} chosen by key — several ready-made behaviours;</li>
 *   <li>free-form custom text, one global (operator) and one per player.</li>
 * </ol>
 *
 * <p>Custom text may contain {@code {placeholders}} that are expanded from the
 * live machine snapshot at request time, e.g. {@code {temperatureC}} or
 * {@code {stabilityPct}}, so a single prompt can react to the actual readings.</p>
 *
 * <p>Per-player text lives in memory keyed by UUID (deliberately not persisted
 * to disk: it is a session-level tool, and the global prompt is the durable
 * default). All maps are concurrent because terminal commands and analysis
 * callbacks can touch them from different ticks.</p>
 */
public final class AiPrompt {

    private AiPrompt() {
    }

    /**
     * Reply-format contract prepended to every prompt. Keeping this under the
     * mod's control guarantees that even a wildly custom prompt still asks the
     * service for a verdict we can decode.
     */
    public static final String CONTRACT =
            "You are the facility safety AI of HBMZZ. Analyse the 'machine' and 'values' JSON. "
                    + "Reply with ONE strict JSON object (no markdown, no prose). Required: "
                    + "\"abnormal\": true|false. Optional: \"message\" (short spoken text), "
                    + "\"reason\" (why), \"level\" (GREEN/YELLOW/ORANGE/RED/MED/OMEGA/ALPHA/DELTA or 0-7), "
                    + "\"display\": {\"level\":0-7,\"label\":\"...\",\"rgb\":16727770,\"zhTitle\":\"...\","
                    + "\"enTitle\":\"...\",\"zhDetail\":\"...\",\"enDetail\":\"...\"}, "
                    + "\"actions\": [{\"op\":\"start|scram|part_on|part_off|temperature\","
                    + "\"part\":\"stabiliser|receiver|emitter\",\"on\":true|false,\"value\":123}]. "
                    + "When abnormal is false, omit actions.";

    /** A built-in behaviour preset. */
    public static final class Preset {
        public final String key;
        public final String zhName;
        public final String enName;
        public final String text;

        Preset(String key, String zhName, String enName, String text) {
            this.key = key;
            this.zhName = zhName;
            this.enName = enName;
            this.text = text;
        }
    }

    private static final Map<String, Preset> PRESETS = new LinkedHashMap<String, Preset>();

    static {
        add("balanced", "均衡", "Balanced",
                "Balance safety and output. Treat readings as abnormal only when "
                        + "temperature approaches melting or stability drops clearly. "
                        + "Prefer adjusting components over a full scram.");
        add("safety", "安全优先", "Safety first",
                "Safety first. Be conservative: flag anomalies early, lower the "
                        + "temperature/stability tolerance, and scram at the first "
                        + "sign of an unstable core. Explain the risk in 'reason'.");
        add("power", "功率优先", "Power first",
                "Maximise power output. Tolerate higher temperature and lower "
                        + "stability; only flag a genuine meltdown risk. Avoid "
                        + "scramming unless detonation is imminent.");
        add("diagnostic", "只诊断", "Diagnose only",
                "Diagnostic mode. NEVER emit any 'actions'. Only report whether "
                        + "readings are abnormal and explain them. Do not control machines.");
        add("quiet", "静默", "Quiet",
                "Only report truly critical states; keep 'message' very short. "
                        + "Ignore minor fluctuations and produce no display for normal readings.");
        add("verbose", "详细", "Verbose",
                "Give a detailed 'reason', always fill the 'display' object with "
                        + "Chinese and English titles/details, and describe every recommended action.");
        add("zh", "中文播报", "Chinese",
                "Write 'message' and 'reason' in Simplified Chinese. Facility staff are Chinese-speaking.");
        add("en", "English", "English",
                "Write 'message' and 'reason' in English. Facility staff are English-speaking.");
    }

    private static void add(String key, String zh, String en, String text) {
        PRESETS.put(key, new Preset(key, zh, en, text));
    }

    /** The default preset used when none is configured. */
    public static final String DEFAULT_PRESET = "balanced";

    /** @return preset by key (case-insensitive), or null if unknown. */
    public static Preset preset(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        return PRESETS.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** All preset keys, in insertion order. */
    public static List<String> presetKeys() {
        return Collections.unmodifiableList(new ArrayList<String>(PRESETS.keySet()));
    }

    /** True when the key names a built-in preset. */
    public static boolean isPreset(String key) {
        return preset(key) != null;
    }

    /**
     * Human-readable preset list for chat / terminal output.
     * Format: {@code key(中文)  key2(中文) ...}
     */
    public static String listPresets() {
        StringBuilder sb = new StringBuilder();
        for (Preset p : PRESETS.values()) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(p.key).append('(').append(p.zhName).append(')');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ per-player text

    /** Per-player custom prompt text (session-level, keyed by UUID). */
    private static final Map<UUID, String> PLAYER_TEXT = new ConcurrentHashMap<UUID, String>();
    /** Per-player preset override; absent means use the global preset. */
    private static final Map<UUID, String> PLAYER_PRESET = new ConcurrentHashMap<UUID, String>();

    public static void setPlayerText(UUID uuid, String text) {
        if (uuid == null) {
            return;
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            PLAYER_TEXT.remove(uuid);
        } else {
            PLAYER_TEXT.put(uuid, t);
        }
    }

    public static String getPlayerText(UUID uuid) {
        return uuid == null ? "" : (PLAYER_TEXT.get(uuid) == null ? "" : PLAYER_TEXT.get(uuid));
    }

    public static void clearPlayerText(UUID uuid) {
        if (uuid != null) {
            PLAYER_TEXT.remove(uuid);
        }
    }

    public static void setPlayerPreset(UUID uuid, String key) {
        if (uuid == null) {
            return;
        }
        Preset p = preset(key);
        if (p == null) {
            PLAYER_PRESET.remove(uuid);
        } else {
            PLAYER_PRESET.put(uuid, p.key);
        }
    }

    public static String getPlayerPreset(UUID uuid) {
        return uuid == null ? "" : (PLAYER_PRESET.get(uuid) == null ? "" : PLAYER_PRESET.get(uuid));
    }

    public static void clearPlayerPreset(UUID uuid) {
        if (uuid != null) {
            PLAYER_PRESET.remove(uuid);
        }
    }

    /** Forget all state for one player (e.g. on logout, if ever wanted). */
    public static void forget(UUID uuid) {
        if (uuid != null) {
            PLAYER_TEXT.remove(uuid);
            PLAYER_PRESET.remove(uuid);
        }
    }

    // ------------------------------------------------------------ assembly

    /**
     * Build the final prompt string.
     *
     * @param presetKey    built-in behaviour preset (falls back to balanced)
     * @param globalCustom operator-wide custom text (may be empty)
     * @param playerCustom per-player custom text (may be empty)
     */
    public static String build(String presetKey, String globalCustom, String playerCustom) {
        Preset p = preset(presetKey);
        if (p == null) {
            p = preset(DEFAULT_PRESET);
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append(CONTRACT);
        sb.append('\n');
        sb.append(p.text);
        appendCustom(sb, globalCustom);
        appendCustom(sb, playerCustom);
        return sb.toString();
    }

    private static void appendCustom(StringBuilder sb, String custom) {
        if (custom == null) {
            return;
        }
        String c = custom.trim();
        if (!c.isEmpty()) {
            sb.append('\n');
            sb.append(c);
        }
    }

    /**
     * Expand {@code {key}} placeholders from a live snapshot. Known aliases:
     * machine/dim/x/y/z, plus every numeric key in {@link Snapshot#values} and
     * these short forms: temp, stability, heat, melt, power. Unknown keys are
     * left intact so a typo is visible rather than silently dropped.
     */
    public static String expand(String template, AiMachineProbe.Snapshot snap) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, String> vars = new LinkedHashMap<String, String>();
        if (snap != null) {
            vars.put("machine", safe(snap.machineType));
            vars.put("dim", String.valueOf(snap.dimension));
            vars.put("x", String.valueOf(snap.x));
            vars.put("y", String.valueOf(snap.y));
            vars.put("z", String.valueOf(snap.z));
            for (Map.Entry<String, Double> e : snap.values.entrySet()) {
                vars.put(e.getKey(), fmt(e.getValue()));
            }
            // friendly aliases -> the canonical values key
            alias(vars, "temp", "temperatureC");
            alias(vars, "stability", "stabilityPct");
            alias(vars, "heat", "heatC");
            alias(vars, "melt", "meltC");
            alias(vars, "power", "powerPerSecond");
        }
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private static void alias(Map<String, String> vars, String alias, String canonical) {
        if (!vars.containsKey(alias) && vars.containsKey(canonical)) {
            vars.put(alias, vars.get(canonical));
        }
    }

    private static String fmt(Double v) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) {
            return "null";
        }
        double d = v.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1.0E15D) {
            return String.valueOf((long) d);
        }
        // Trim trailing zeros so prompt numbers read naturally (42.5, not 42.5000).
        String s = String.format(Locale.ROOT, "%.6g", d);
        if (s.indexOf('.') >= 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
            while (s.endsWith("0")) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
