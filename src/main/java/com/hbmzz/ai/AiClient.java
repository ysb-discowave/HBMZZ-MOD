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
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for the AI control node.
 *
 * <p>Posts a machine snapshot as JSON and parses the verdict. The call blocks
 * on the network, so it runs on a single daemon worker; the caller gets a
 * {@link Callback} invoked back on the <b>server thread</b> with either a
 * {@link Verdict} or an error string. No Minecraft world object ever crosses
 * onto the worker - the snapshot carries only primitives.</p>
 *
 * <p>JSON is encoded/decoded by hand. The payload is a flat map of numbers plus
 * a handful of strings; pulling in a full JSON library for that would add a
 * dependency to a mod that currently has none, and the decoder only needs to
 * understand the small subset an AI verdict uses.</p>
 */
public final class AiClient {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "hbmzz-ai-" + n.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        }
    });

    private AiClient() {
    }

    public static void shutdown() {
        WORKER.shutdownNow();
    }

    /** Ask the AI to analyse one snapshot. Never throws; errors arrive via cb.error. */
    public static void analyze(final AiMachineProbe.Snapshot snapshot,
                               final String endpoint, final String apiKey,
                               final String model, final int timeoutMs,
                               final Callback cb) {
        try {
            WORKER.execute(new Runnable() {
                @Override
                public void run() {
                    Verdict verdict = null;
                    String error = null;
                    try {
                        String payload = encodeRequest(snapshot, model);
                        String response = post(endpoint, apiKey, payload, timeoutMs);
                        verdict = decodeVerdict(response);
                    } catch (Throwable t) {
                        error = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                    deliver(snapshot, verdict, error, cb);
                }
            });
        } catch (RejectedExecutionException ree) {
            // Server shutting down. Drop the request; nothing to report.
        }
    }

    /** Hop back onto the server thread before touching the world. */
    private static void deliver(final AiMachineProbe.Snapshot snapshot,
                                final Verdict verdict, final String error,
                                final Callback cb) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            // No server (e.g. single-player client tick) - run inline as a fallback.
            if (verdict != null) {
                cb.onVerdict(snapshot, verdict);
            } else {
                cb.onError(snapshot, error == null ? "no server" : error);
            }
            return;
        }
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (verdict != null) {
                    cb.onVerdict(snapshot, verdict);
                } else {
                    cb.onError(snapshot, error == null ? "unknown error" : error);
                }
            }
        });
    }

    private static String post(String endpoint, String apiKey, String payload, int timeoutMs)
            throws Exception {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("AI endpoint is not configured");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        OutputStream out = conn.getOutputStream();
        try {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(in);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + abbreviate(body));
        }
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 65536) {
                    break;
                }
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON out

    private static String encodeRequest(AiMachineProbe.Snapshot s, String model) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"model\":").append(quote(model == null ? "" : model));
        sb.append(",\"machine\":{");
        sb.append("\"type\":").append(quote(s.machineType));
        sb.append(",\"dim\":").append(s.dimension);
        sb.append(",\"pos\":[").append(s.x).append(',').append(s.y).append(',').append(s.z).append(']');
        sb.append(",\"linked\":").append(s.linked);
        sb.append(",\"ok\":").append(s.ok);
        if (s.error != null && !s.error.isEmpty()) {
            sb.append(",\"error\":").append(quote(s.error));
        }
        sb.append("},\"values\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : s.values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Double v = e.getValue();
            sb.append(quote(e.getKey())).append(':');
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) {
                sb.append("null");
            } else {
                // Trim to 6 significant decimals to keep the payload small.
                sb.append(String.format(java.util.Locale.ROOT, "%.6g", v.doubleValue()));
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON in

    /**
     * Expected response shape (all optional except abnormal):
     * <pre>{"abnormal": true, "message": "...", "reason": "...", "level": "RED"}</pre>
     * A plain {@code true}/{@code false} is also accepted as a bare abnormal flag.
     */
    public static Verdict decodeVerdict(String json) {
        Verdict v = new Verdict();
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("empty AI response");
        }
        String t = json.trim();
        if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) {
            v.abnormal = Boolean.parseBoolean(t);
            return v;
        }
        int obj = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (obj < 0 || end <= obj) {
            throw new IllegalArgumentException("AI response is not JSON: " + abbreviate(t));
        }
        String body = t.substring(obj + 1, end);
        String abnormalRaw = findValue(body, "abnormal");
        if (abnormalRaw == null) {
            // Also accept "anomaly" as an alias.
            abnormalRaw = findValue(body, "anomaly");
        }
        if (abnormalRaw == null) {
            throw new IllegalArgumentException("AI response has no abnormal flag: " + abbreviate(t));
        }
        v.abnormal = parseBool(abnormalRaw);
        v.message = unquote(findValue(body, "message"));
        v.reason = unquote(findValue(body, "reason"));
        v.level = unquote(findValue(body, "level"));
        return v;
    }

    /** Find a top-level-ish value for a key by scanning for "key" then the colon. */
    private static String findValue(String body, String key) {
        String needle = "\"" + key + "\"";
        int i = body.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int colon = body.indexOf(':', i + needle.length());
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < body.length() && Character.isWhitespace(body.charAt(p))) {
            p++;
        }
        if (p >= body.length()) {
            return null;
        }
        char c = body.charAt(p);
        if (c == '"') {
            int q = p + 1;
            StringBuilder sb = new StringBuilder();
            while (q < body.length()) {
                char ch = body.charAt(q);
                if (ch == '\\' && q + 1 < body.length()) {
                    char nx = body.charAt(q + 1);
                    switch (nx) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        default: sb.append(nx); break;
                    }
                    q += 2;
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                    q++;
                }
            }
            return "\"" + sb.toString() + "\"";
        }
        if (c == '{' || c == '[') {
            // Not needed for scalar fields; skip to the matching close.
            return null;
        }
        int e = p;
        while (e < body.length() && body.charAt(e) != ',' && body.charAt(e) != '}') {
            e++;
        }
        return body.substring(p, e).trim();
    }

    private static boolean parseBool(String raw) {
        if (raw == null) {
            return false;
        }
        String r = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(r) || "1".equals(r) || "\"true\"".equals(r)) {
            return true;
        }
        return false;
    }

    private static String unquote(String raw) {
        if (raw == null || raw.length() < 2) {
            return "";
        }
        if (raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String quote(String s) {
        if (s == null) {
            s = "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    // ------------------------------------------------------------------ types

    public interface Callback {
        void onVerdict(AiMachineProbe.Snapshot snapshot, Verdict verdict);

        void onError(AiMachineProbe.Snapshot snapshot, String error);
    }

    /** The AI's answer to one snapshot. */
    public static final class Verdict {
        public boolean abnormal;
        public String message = "";
        public String reason = "";
        public String level = "";
    }
}
