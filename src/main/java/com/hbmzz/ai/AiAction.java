/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

/**
 * One control instruction the AI may emit in a verdict or a terminal reply.
 *
 * <p>Kept as a plain object with no Minecraft types so it can be parsed on the
 * AI worker and handed to {@link AiAutopilot} / {@link AiTerminalService} on the
 * server thread.</p>
 */
public final class AiAction {

    /** Normalised opcode, one of the constants below. */
    public String op = "";
    /** Target component for {@code part_on}/{@code part_off}: stabiliser/receiver/emitter... */
    public String part = "";
    /** Boolean argument: enable for part_on/part_off, start for ignite. */
    public boolean on = true;
    /** Numeric argument, e.g. degrees for {@code temperature}. */
    public double value = Double.NaN;

    public static final String START = "start";
    public static final String SCRAM = "scram";
    public static final String PART_ON = "part_on";
    public static final String PART_OFF = "part_off";
    public static final String TEMPERATURE = "temperature";
    public static final String CLEAR = "clear";
    public static final String SPEAK = "speak";

    /** Maps the various spellings an AI service might return to a canonical op. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String r = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (r.equals("ignite") || r.equals("启动") || r.equals("启动核心")
                || r.equals("start_core") || r.equals("start")) {
            return START;
        }
        if (r.equals("stop") || r.equals("shutdown") || r.equals("停堆")
                || r.equals("scram") || r.equals("cooldown")) {
            return SCRAM;
        }
        if (r.equals("enable") || r.equals("on") || r.equals("开") || r.equals("开启")) {
            return PART_ON;
        }
        if (r.equals("disable") || r.equals("off") || r.equals("关") || r.equals("关闭")) {
            return PART_OFF;
        }
        if (r.equals("temp") || r.equals("set_temp") || r.equals("set_temperature")
                || r.equals("temperature") || r.equals("温度")) {
            return TEMPERATURE;
        }
        if (r.equals("reset") || r.equals("clear") || r.equals("clear_collapse")) {
            return CLEAR;
        }
        if (r.equals("say") || r.equals("broadcast") || r.equals("speak")) {
            return SPEAK;
        }
        return r;
    }

    /** True for a normalised opcode this module understands. */
    public static boolean isKnown(String normalized) {
        return START.equals(normalized) || SCRAM.equals(normalized)
                || PART_ON.equals(normalized) || PART_OFF.equals(normalized)
                || TEMPERATURE.equals(normalized) || CLEAR.equals(normalized)
                || SPEAK.equals(normalized);
    }

    /**
     * True when an AI-supplied {@code op} word is really a component name.
     * Services often answer with the component as the verb and a separate
     * {@code on} flag, e.g. {@code {"op":"发射器","on":false}} — that form is
     * turned into a part toggle by the JSON decoder.
     */
    public static boolean isComponentWord(String raw) {
        if (raw == null) {
            return false;
        }
        String r = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return r.contains("stabilis") || r.contains("stabiliz") || r.equals("stab")
                || r.contains("receiver") || r.contains("absorber")
                || r.contains("emitter") || r.contains("injector") || r.contains("detonator")
                || r.equals("稳定器") || r.equals("稳定") || r.equals("接收器") || r.equals("接收")
                || r.equals("发射器") || r.equals("发射");
    }
}
