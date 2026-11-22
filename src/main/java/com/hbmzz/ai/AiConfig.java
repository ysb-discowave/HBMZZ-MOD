/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Global settings for the AI control node.
 *
 * <p>The endpoint / key / model are process-wide and live in a dedicated config
 * file next to the main one, so an operator can point every node at the same
 * AI service without walking up to each block. Per-machine bindings and
 * thresholds live on the individual tile entities (see
 * {@link TileEntityAIControlNode}).</p>
 *
 * <p>Nothing here is read on the render thread. Values are plain fields and
 * are written only during {@link #load(File)} or by the {@code /ai} command on
 * the server thread.</p>
 */
public final class AiConfig {

    /** HTTP endpoint that receives a JSON snapshot and returns a verdict. */
    public static String endpoint = "http://127.0.0.1:8080/hbmzz/analyze";
    /** Bearer token / API key sent as a header. Empty means no auth header. */
    public static String apiKey = "";
    /** Model identifier forwarded to the endpoint in the JSON payload. */
    public static String model = "FAAS-REMIX";
    /** TTS model name used when a verdict carries a spoken message. */
    public static String ttsModel = "FAAS-REMIX";
    /** Seconds between two polls of the bound machine. */
    public static int pollSeconds = 5;
    /** Wall-clock timeout for one HTTP call, milliseconds. */
    public static int httpTimeoutMs = 8000;
    /** TTS length scale (speech speed) forwarded to the TTS engine. */
    public static double lengthScale = 1.0D;
    /** Whether an anomaly automatically broadcasts a voice announcement. */
    public static boolean autoBroadcast = true;
    /** Master switch: while false, nodes sample nothing and speak nothing. */
    public static boolean enabled = true;

    private static Configuration config;

    private AiConfig() {
    }

    /** Persist current field values to the config file. Safe to call repeatedly. */
    public static synchronized void save() {
        if (config == null) {
            return;
        }
        config.get("ai", "enabled", enabled).set(enabled);
        config.get("ai", "endpoint", endpoint).set(endpoint);
        config.get("ai", "apiKey", apiKey).set(apiKey);
        config.get("ai", "model", model).set(model);
        config.get("ai", "ttsModel", ttsModel).set(ttsModel);
        config.get("ai", "pollSeconds", pollSeconds).set(pollSeconds);
        config.get("ai", "httpTimeoutMs", httpTimeoutMs).set(httpTimeoutMs);
        config.get("ai", "lengthScale", (float) lengthScale).set((float) lengthScale);
        config.get("ai", "autoBroadcast", autoBroadcast).set(autoBroadcast);
        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void load(File suggested) {
        File file = new File(suggested.getParentFile(), "hbmzz-ai.cfg");
        Configuration cfg = new Configuration(file);
        config = cfg;
        try {
            cfg.load();
            enabled = cfg.getBoolean("enabled", "ai", true,
                    "Master switch for the AI control node feature.");
            endpoint = cfg.getString("endpoint", "ai", endpoint,
                    "HTTP endpoint that accepts a JSON machine snapshot and returns a verdict.");
            apiKey = cfg.getString("apiKey", "ai", apiKey,
                    "Bearer token sent in the Authorization header. Empty = no header.");
            model = cfg.getString("model", "ai", model,
                    "Model identifier included in the JSON payload for the AI service.");
            ttsModel = cfg.getString("ttsModel", "ai", ttsModel,
                    "TTS model name recorded on voice broadcast requests.");
            pollSeconds = cfg.getInt("pollSeconds", "ai", 5, 1, 300,
                    "Seconds between two samples of the bound machine.");
            httpTimeoutMs = cfg.getInt("httpTimeoutMs", "ai", 8000, 1000, 60000,
                    "Timeout for one HTTP request, in milliseconds.");
            lengthScale = cfg.getFloat("lengthScale", "ai", 1.0F, 0.6F, 1.8F,
                    "TTS speech speed. 1.0 = model default, larger = slower.");
            autoBroadcast = cfg.getBoolean("autoBroadcast", "ai", true,
                    "Whether an anomaly automatically speaks through the TTS system.");
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }

    public static int clampPollSeconds(int value) {
        return value < 1 ? 1 : (value > 300 ? 300 : value);
    }

    public static double clampLengthScale(double value) {
        if (Double.isNaN(value)) {
            return 1.0D;
        }
        return value < 0.6D ? 0.6D : (value > 1.8D ? 1.8D : value);
    }
}
