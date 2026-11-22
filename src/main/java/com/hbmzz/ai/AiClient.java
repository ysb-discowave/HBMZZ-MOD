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
import java.util.ArrayList;
import java.util.List;
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

    /** Ask the AI to analyse one snapshot, using the operator's global prompt. */
    public static void analyze(final AiMachineProbe.Snapshot snapshot,
                               final String endpoint, final String apiKey,
                               final String model, final int timeoutMs,
                               final Callback cb) {
        submit(snapshot, null, null, endpoint, apiKey, model, timeoutMs, cb);
    }

    /**
     * Analyse one snapshot with an explicit prompt (already assembled by
     * {@link AiPrompt#build} or a caller). A null/empty prompt falls back to the
     * global prompt from {@link AiConfig}.
     */
    public static void analyze(final AiMachineProbe.Snapshot snapshot, final String prompt,
                               final String endpoint, final String apiKey,
                               final String model, final int timeoutMs,
                               final Callback cb) {
        submit(snapshot, null, prompt, endpoint, apiKey, model, timeoutMs, cb);
    }

    /**
     * Ask the AI to act on an operator's typed instruction, using the global prompt.
     *
     * <p>Same transport as {@link #analyze}, but the command text is included in
     * the JSON payload under {@code command} so the service can answer with an
     * action list (start / scram / set a component, ...). The reply still arrives
     * through {@link Callback} on the server thread.</p>
     */
    public static void instruct(final AiMachineProbe.Snapshot snapshot, final String command,
                                final String endpoint, final String apiKey,
                                final String model, final int timeoutMs,
                                final Callback cb) {
        submit(snapshot, command, null, endpoint, apiKey, model, timeoutMs, cb);
    }

    /** Act on a typed instruction with an explicit (per-player) prompt. */
    public static void instruct(final AiMachineProbe.Snapshot snapshot, final String command,
                                final String prompt, final String endpoint, final String apiKey,
                                final String model, final int timeoutMs,
                                final Callback cb) {
        submit(snapshot, command, prompt, endpoint, apiKey, model, timeoutMs, cb);
    }

    private static void submit(final AiMachineProbe.Snapshot snapshot, final String command,
                               final String prompt, final String endpoint, final String apiKey,
                               final String model, final int timeoutMs,
                               final Callback cb) {
        try {
            WORKER.execute(new Runnable() {
                @Override
                public void run() {
                    Verdict verdict = null;
                    String error = null;
                    try {
                        String payload = encodeRequest(snapshot, model, command, prompt);
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
        return encodeRequest(s, model, null, null);
    }

    private static String encodeRequest(AiMachineProbe.Snapshot s, String model, String command) {
        return encodeRequest(s, model, command, null);
    }

    private static String encodeRequest(AiMachineProbe.Snapshot s, String model, String command,
                                        String prompt) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"model\":").append(quote(model == null ? "" : model));
        // The prompt is the steerable part of the request. An explicit prompt
        // (per-player / per-call) wins; otherwise fall back to the operator's
        // global preset + custom text. Placeholders expand against this snapshot.
        String effective = prompt == null || prompt.trim().isEmpty()
                ? AiPrompt.build(AiConfig.promptPreset, AiConfig.prompt, "")
                : prompt;
        effective = AiPrompt.expand(effective, s);
        sb.append(",\"prompt\":").append(quote(effective));
        if (command != null && !command.trim().isEmpty()) {
            sb.append(",\"command\":").append(quote(command.trim()));
        }
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
     * <pre>{"abnormal": true, "message": "...", "reason": "...", "level": "RED",
     *       "display": {"level": 3, "label": "AI 警报", "rgb": 16727770,
     *                   "zhTitle": "...", "enTitle": "...",
     *                   "zhDetail": "...", "enDetail": "..."}}</pre>
     * The optional {@code display} object is the AI's instruction for the
     * facility-wide code displays. Any field may be omitted; the ones left out
     * fall back to the standard {@link com.hbmzz.alert.AlertCode} named by
     * {@code level}. Flat {@code displayXxx} keys at the top level are also
     * accepted as an alias, for services that cannot emit nested objects.
     * A plain {@code true}/{@code false} is accepted as a bare abnormal flag.
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
        parseDisplay(body, v);
        parseActions(body, v);
        return v;
    }

    /** Reads the optional {@code actions:[{op,part,on,value}]} control list. */
    private static void parseActions(String body, Verdict v) {
        String array = findArray(body, "actions");
        if (array == null) {
            return;
        }
        List<String> objects = splitObjects(array);
        for (String object : objects) {
            String opRaw = unquote(findValue(object, "op"));
            if (opRaw.isEmpty()) {
                opRaw = unquote(findValue(object, "action"));
            }
            if (opRaw.isEmpty()) {
                continue;
            }
            AiAction a = new AiAction();
            a.op = AiAction.normalize(opRaw);
            a.part = unquote(findValue(object, "part"));
            if (a.part.isEmpty()) {
                a.part = unquote(findValue(object, "target"));
            }
            String onRaw = findValue(object, "on");
            if (onRaw == null) {
                onRaw = findValue(object, "enabled");
            }
            if (onRaw != null) {
                a.on = parseBool(onRaw);
            }
            // A service may name the component as the verb itself:
            //   {"op":"发射器","on":false}  ==  {"op":"part_off","part":"emitter"}
            if (!AiAction.isKnown(a.op) && AiAction.isComponentWord(opRaw)) {
                if (a.part.isEmpty()) {
                    a.part = opRaw;
                }
                a.op = a.on ? AiAction.PART_ON : AiAction.PART_OFF;
            }
            String valueRaw = findValue(object, "value");
            if (valueRaw != null) {
                String scalar = scalar(valueRaw);
                try {
                    a.value = Double.parseDouble(scalar);
                } catch (NumberFormatException ignored) {
                }
            }
            v.actions.add(a);
        }
    }

    /** Returns the inner body of a top-level JSON array for a key. */
    private static String findArray(String body, String key) {
        String needle = "\"" + key + "\"";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                if (depth == 0 && body.startsWith(needle, i)) {
                    int colon = body.indexOf(':', i + needle.length());
                    if (colon < 0) {
                        return null;
                    }
                    int p = colon + 1;
                    while (p < body.length() && Character.isWhitespace(body.charAt(p))) {
                        p++;
                    }
                    if (p >= body.length() || body.charAt(p) != '[') {
                        return null;
                    }
                    int d = 0;
                    for (int q = p; q < body.length(); q++) {
                        char ch = body.charAt(q);
                        if (ch == '[') {
                            d++;
                        } else if (ch == ']') {
                            if (--d == 0) {
                                return body.substring(p + 1, q);
                            }
                        }
                    }
                    return null;
                }
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return null;
    }

    /** Splits an array body into its top-level {@code {...}} object bodies. */
    private static List<String> splitObjects(String arrayBody) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(arrayBody.substring(start, i));
                    start = -1;
                }
            }
        }
        return out;
    }

    /** Reads the optional screen instruction into {@code v}, if one is present. */
    private static void parseDisplay(String body, Verdict v) {
        String nested = findObject(body, "display");
        boolean hasNested = nested != null;
        String src = hasNested ? nested : body;

        String levelRaw = findValue(src, hasNested ? "level" : "displayLevel");
        if (levelRaw == null && hasNested) {
            levelRaw = findValue(src, "code"); // alias for the 0..7 level
        }
        int level = parseDisplayLevel(levelRaw);
        String label = unquote(findValue(src, hasNested ? "label" : "displayLabel"));
        int rgb = parseColor(findValue(src, hasNested ? "rgb" : "displayRgb"), -1);
        if (rgb < 0 && hasNested) {
            rgb = parseColor(findValue(src, "color"), -1); // alias
        }
        String zhTitle = unquote(findValue(src, hasNested ? "zhTitle" : "displayZhTitle"));
        String enTitle = unquote(findValue(src, hasNested ? "enTitle" : "displayEnTitle"));
        String zhDetail = unquote(findValue(src, hasNested ? "zhDetail" : "displayZhDetail"));
        String enDetail = unquote(findValue(src, hasNested ? "enDetail" : "displayEnDetail"));

        boolean any = level >= 0 || !label.isEmpty() || rgb >= 0
                || !zhTitle.isEmpty() || !enTitle.isEmpty()
                || !zhDetail.isEmpty() || !enDetail.isEmpty();
        if (!any) {
            return;
        }
        v.hasDisplay = true;
        v.displayLevel = level;
        v.displayLabel = label;
        v.displayRgb = rgb;
        v.displayZhTitle = zhTitle;
        v.displayEnTitle = enTitle;
        v.displayZhDetail = zhDetail;
        v.displayEnDetail = enDetail;
    }

    /** Returns the {@code {...}} inner body for a key whose value is an object. */
    private static String findObject(String body, String key) {
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
        if (p >= body.length() || body.charAt(p) != '{') {
            return null;
        }
        int depth = 0;
        for (int q = p; q < body.length(); q++) {
            char c = body.charAt(q);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return body.substring(p + 1, q);
                }
            }
        }
        return null;
    }

    /**
     * Strips optional surrounding quotes from a scalar token. Unlike
     * {@link #unquote}, this keeps single-character values intact, which matters
     * for numeric levels 0..7 and one-digit colours.
     */
    private static String scalar(String raw) {
        String r = raw == null ? "" : raw.trim();
        if (r.length() >= 2 && r.charAt(0) == '"' && r.charAt(r.length() - 1) == '"') {
            return r.substring(1, r.length() - 1).trim();
        }
        return r;
    }

    /** 0..7 when the value is a numeric level or a standard code label; -1 otherwise. */
    private static int parseDisplayLevel(String raw) {
        String r = scalar(raw);
        if (r.isEmpty()) {
            return -1;
        }
        try {
            int n = (int) Long.parseLong(r);
            return n >= 0 && n <= 7 ? n : -1;
        } catch (NumberFormatException ignored) {
            for (com.hbmzz.alert.AlertCode code : com.hbmzz.alert.AlertCode.values()) {
                if (code.label().equalsIgnoreCase(r)) {
                    return code.id();
                }
            }
            return -1;
        }
    }

    /** Accepts a decimal integer or a hex string ({@code 0xRRGGBB} / {@code #RRGGBB}). */
    private static int parseColor(String raw, int fallback) {
        String r = scalar(raw);
        if (r.isEmpty()) {
            return fallback;
        }
        try {
            if (r.startsWith("0x") || r.startsWith("0X") || r.startsWith("#")) {
                String hex = r.replaceFirst("^(0x|0X|#)", "");
                return (int) (Long.parseLong(hex, 16) & 0xFFFFFFL);
            }
            return (int) (Long.parseLong(r) & 0xFFFFFFL);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Find a value for a key at brace depth zero, ignoring the same key nested
     * inside {@code display} or another object. Depth-aware scanning matters
     * because the top-level {@code level} and {@code display.level} share a name.
     */
    private static String findValue(String body, String key) {
        String needle = "\"" + key + "\"";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                if (depth == 0 && body.startsWith(needle, i)) {
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
                    char v = body.charAt(p);
                    if (v == '"') {
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
                    if (v == '{' || v == '[') {
                        // Objects / arrays are read with findObject, not here.
                        return null;
                    }
                    int e = p;
                    while (e < body.length() && body.charAt(e) != ',' && body.charAt(e) != '}') {
                        e++;
                    }
                    return body.substring(p, e).trim();
                }
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return null;
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

        // ---- optional instruction for the facility-wide code displays ----
        /** True when the response carried any {@code display*} field. */
        public boolean hasDisplay;
        /** 0..7, or -1 to derive from {@link #level}. */
        public int displayLevel = -1;
        /** Packed 0xRRGGBB, or -1 to derive from the level's standard colour. */
        public int displayRgb = -1;
        public String displayLabel = "";
        public String displayZhTitle = "";
        public String displayEnTitle = "";
        public String displayZhDetail = "";
        public String displayEnDetail = "";

        /** Control instructions emitted alongside or instead of an alarm. */
        public final List<AiAction> actions = new ArrayList<AiAction>();
    }
}
