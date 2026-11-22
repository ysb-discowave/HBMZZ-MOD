/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import com.hbmzz.hack.HackService;
import com.hbmzz.network.AiReplyMessage;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Locale;

/**
 * The command language behind the AI terminal.
 *
 * <p>Two kinds of input are accepted. A set of deterministic local verbs
 * ({@code status}, {@code start}, {@code scram}, {@code stabiliser on}, ...) run
 * immediately through {@link DfcControl}; they always work, even with no AI
 * service configured. Anything else is treated as a natural-language request
 * and forwarded to the AI endpoint together with a snapshot of the nearest DFC
 * core, so phrases like “把稳定器打开，然后启动核心” are interpreted by the model.
 * The AI's {@code actions} are then executed by {@link AiAutopilot}.</p>
 *
 * <p>Both paths answer through {@link AiReplyMessage} back to the one player who
 * asked. The async AI callback arrives on the server thread, so the same reply
 * helper is safe for both.</p>
 */
public final class AiTerminalService {

    /** Max characters of one typed terminal line (long enough for a custom prompt). */
    public static final int MAX_INPUT = 2048;

    private AiTerminalService() {
    }

    /** Everything the window needs after one command. */
    public static final class Reply {
        public final List<HackService.Line> lines = new java.util.ArrayList<HackService.Line>();

        public void add(int tone, String text) {
            this.lines.add(new HackService.Line(tone, text));
        }
    }

    /**
     * Handles one typed line from the terminal. Called on the server thread.
     * Synchronous verbs reply here; natural-language requests reply from the AI
     * callback.
     */
    public static void handle(EntityPlayerMP player, String raw) {
        if (player == null) {
            return;
        }
        String line = raw == null ? "" : raw.trim();
        if (line.length() > MAX_INPUT) {
            line = line.substring(0, MAX_INPUT);
        }
        Reply reply = new Reply();
        World world = player.world;
        BlockPos anchor = player.getPosition();

        if (line.isEmpty()) {
            banner(reply);
            send(player, reply);
            return;
        }

        String[] words = line.split("\\s+");
        String verb = words[0].toLowerCase(Locale.ROOT);

        // ---- local, deterministic verbs ------------------------------------
        if (verb.equals("help") || verb.equals("帮助") || verb.equals("?")) {
            help(reply);
            send(player, reply);
            return;
        }
        if (verb.equals("status") || verb.equals("状态") || verb.equals("stat")) {
            reply.add(HackService.TONE_HEAD, DfcControl.read(world, anchor, radius()).describe());
            reply.add(HackService.TONE_DIM, "自动接管=" + (AiConfig.autoControl ? "开" : "关")
                    + " · 接口=" + AiConfig.endpoint + " · 模型=" + AiConfig.model);
            send(player, reply);
            return;
        }
        if (verb.equals("start") || verb.equals("启动") || verb.equals("点火")) {
            DfcControl.Result r = DfcControl.start(world, anchor, radius());
            reply.add(r.ok ? HackService.TONE_OK : HackService.TONE_ERR, r.message);
            send(player, reply);
            return;
        }
        if (verb.equals("scram") || verb.equals("停堆") || verb.equals("stop")
                || verb.equals("冷却")) {
            DfcControl.Result r = DfcControl.scram(world, anchor, radius());
            reply.add(r.ok ? HackService.TONE_OK : HackService.TONE_ERR, r.message);
            send(player, reply);
            return;
        }
        if (verb.equals("stabiliser") || verb.equals("stabilizer") || verb.equals("稳定器")
                || verb.equals("receiver") || verb.equals("接收器")
                || verb.equals("emitter") || verb.equals("发射器")) {
            DfcControl.Part part = DfcControl.Part.byKey(verb);
            boolean on = words.length < 2 || isOn(words[1]);
            DfcControl.Result r = DfcControl.setPart(world, anchor, radius(), part, on);
            reply.add(r.ok ? HackService.TONE_OK : HackService.TONE_ERR, r.message);
            send(player, reply);
            return;
        }
        if (verb.equals("temp") || verb.equals("temperature") || verb.equals("温度")) {
            if (words.length < 2) {
                reply.add(HackService.TONE_ERR, "用法: temp <摄氏度>");
            } else {
                try {
                    double v = Double.parseDouble(words[1]);
                    DfcControl.Result r = DfcControl.setTemperature(world, anchor, radius(), v);
                    reply.add(r.ok ? HackService.TONE_OK : HackService.TONE_ERR, r.message);
                } catch (NumberFormatException nfe) {
                    reply.add(HackService.TONE_ERR, "温度必须是数字。");
                }
            }
            send(player, reply);
            return;
        }
        if (verb.equals("autopilot") || verb.equals("接管")) {
            if (words.length >= 2) {
                AiConfig.autoControl = isOn(words[1]);
                AiConfig.save();
            }
            reply.add(HackService.TONE_OK, "AI 自动接管=" + (AiConfig.autoControl ? "开" : "关"));
            send(player, reply);
            return;
        }

        // ---- prompt customization (per player) -----------------------------
        if (verb.equals("prompt") || verb.equals("提示词")) {
            // Re-join the original line so multi-word text survives verb splitting.
            String text = joinFrom(line, words[0]);
            if (text.isEmpty()) {
                reply.add(HackService.TONE_HEAD, "当前生效提示词（你自己的）:");
                showPrompt(reply, player, world, anchor);
            } else if (text.equalsIgnoreCase("clear") || text.equals("-")
                    || text.equalsIgnoreCase("清除") || text.equalsIgnoreCase("重置")) {
                AiPrompt.clearPlayerText(player.getUniqueID());
                reply.add(HackService.TONE_OK, "你的自定义提示词已清除（回到全局设置）。");
            } else {
                if (text.length() > 2048) {
                    text = text.substring(0, 2048);
                }
                AiPrompt.setPlayerText(player.getUniqueID(), text);
                reply.add(HackService.TONE_OK,
                        "你的自定义提示词已设为（" + text.length() + " 字），仅影响你自己的请求。");
            }
            send(player, reply);
            return;
        }
        if (verb.equals("preset") || verb.equals("预设")) {
            if (words.length < 2) {
                reply.add(HackService.TONE_HEAD, "可用预设: " + AiPrompt.listPresets());
                reply.add(HackService.TONE_DIM,
                        "当前: " + effectivePreset(player) + "；preset <名称> 切换，prompt <文字> 追加自定义。");
            } else {
                String name = words[1];
                if (!AiPrompt.isPreset(name)) {
                    reply.add(HackService.TONE_ERR, "未知预设 " + name + "，可用: " + AiPrompt.listPresets());
                } else {
                    AiPrompt.Preset p = AiPrompt.preset(name);
                    AiPrompt.setPlayerPreset(player.getUniqueID(), p.key);
                    reply.add(HackService.TONE_OK, "你的行为预设已设为 " + p.key + "（" + p.zhName + "）。");
                }
            }
            send(player, reply);
            return;
        }
        if (verb.equals("presets") || verb.equals("预设列表")) {
            reply.add(HackService.TONE_HEAD, "可用行为预设:");
            for (String key : AiPrompt.presetKeys()) {
                AiPrompt.Preset p = AiPrompt.preset(key);
                reply.add(HackService.TONE_TEXT, key + "  " + p.zhName + " / " + p.enName);
            }
            reply.add(HackService.TONE_DIM, "当前生效: " + effectivePreset(player));
            send(player, reply);
            return;
        }

        // ---- natural language: ask the AI ----------------------------------
        if (!AiConfig.enabled) {
            reply.add(HackService.TONE_ERR, "AI 功能已被管理员关闭（/ai on 开启）。");
            send(player, reply);
            return;
        }
        reply.add(HackService.TONE_DIM, "已提交给 " + AiConfig.model + " 分析: " + line);
        send(player, reply);

        TileEntity core = DfcControl.core(world, anchor, radius());
        BlockPos sampleAt = core == null ? anchor : core.getPos();
        AiMachineProbe.Snapshot snapshot =
                AiMachineProbe.sample(world, sampleAt, com.hbmzz.aegis.AegisConfig.scanRadius);
        final String asked = line;
        // Assemble this player's effective prompt: their preset override (or the
        // global one) + the global custom text + their own custom text on top.
        String prompt = AiPrompt.build(
                effectivePreset(player),
                AiConfig.prompt,
                AiPrompt.getPlayerText(player.getUniqueID()));
        AiClient.instruct(snapshot, asked, prompt, AiConfig.endpoint, AiConfig.apiKey, AiConfig.model,
                AiConfig.httpTimeoutMs, new AiClient.Callback() {
                    @Override
                    public void onVerdict(AiMachineProbe.Snapshot snap, AiClient.Verdict verdict) {
                        Reply out = new Reply();
                        String message = verdict.message == null || verdict.message.trim().isEmpty()
                                ? "AI 未给出文字回复。" : verdict.message.trim();
                        out.add(verdict.abnormal ? HackService.TONE_WARN : HackService.TONE_OK, message);
                        if (!verdict.actions.isEmpty()) {
                            List<String> results = AiAutopilot.run(player.world, sampleAt, verdict.actions);
                            for (String text : results) {
                                out.add(HackService.TONE_OK, text);
                            }
                        } else {
                            out.add(HackService.TONE_DIM, "（本次回复未包含控制动作）");
                        }
                        send(player, out);
                    }

                    @Override
                    public void onError(AiMachineProbe.Snapshot snap, String error) {
                        Reply out = new Reply();
                        out.add(HackService.TONE_ERR, "AI 请求失败: " + error);
                        out.add(HackService.TONE_DIM, "本地命令始终可用，输入 help 查看。");
                        send(player, out);
                    }
                });
    }

    /** The preset key currently effective for a player (their override or global). */
    private static String effectivePreset(EntityPlayerMP player) {
        String own = AiPrompt.getPlayerPreset(player.getUniqueID());
        return own.isEmpty() ? AiConfig.promptPreset : own;
    }

    /** Join the original line starting after the verb token, preserving spaces. */
    private static String joinFrom(String line, String verb) {
        if (line == null || line.length() <= verb.length()) {
            return "";
        }
        return line.substring(verb.length()).trim();
    }

    /** Print a player's effective, snapshot-expanded prompt in clipped lines. */
    private static void showPrompt(Reply reply, EntityPlayerMP player, World world, BlockPos anchor) {
        TileEntity core = DfcControl.core(world, anchor, radius());
        BlockPos sampleAt = core == null ? anchor : core.getPos();
        AiMachineProbe.Snapshot snap =
                AiMachineProbe.sample(world, sampleAt, com.hbmzz.aegis.AegisConfig.scanRadius);
        String prompt = AiPrompt.build(
                effectivePreset(player),
                AiConfig.prompt,
                AiPrompt.getPlayerText(player.getUniqueID()));
        prompt = AiPrompt.expand(prompt, snap);
        reply.add(HackService.TONE_DIM, "预设=" + effectivePreset(player)
                + " · 全局自定义=" + (AiConfig.prompt.isEmpty() ? "无" : AiConfig.prompt.length() + "字")
                + " · 个人自定义=" + (AiPrompt.getPlayerText(player.getUniqueID()).isEmpty()
                        ? "无" : AiPrompt.getPlayerText(player.getUniqueID()).length() + "字"));
        int width = 150;
        for (int i = 0; i < prompt.length(); i += width) {
            reply.add(HackService.TONE_TEXT, prompt.substring(i, Math.min(i + width, prompt.length())));
        }
    }

    private static int radius() {
        return com.hbmzz.aegis.AegisConfig.scanRadius;
    }

    private static boolean isOn(String word) {
        if (word == null) {
            return true;
        }
        String w = word.toLowerCase(Locale.ROOT);
        return !(w.equals("off") || w.equals("false") || w.equals("0")
                || w.equals("关") || w.equals("关闭") || w.equals("disable"));
    }

    private static void banner(Reply reply) {
        reply.add(HackService.TONE_HEAD, "HBMZZ // AI 终端 v1.0");
        reply.add(HackService.TONE_DIM, "模型 " + AiConfig.model + " · 接口 " + AiConfig.endpoint);
        reply.add(HackService.TONE_TEXT, "输入 help 查看本地命令；其它输入交给 AI 解释。");
    }

    private static void help(Reply reply) {
        reply.add(HackService.TONE_HEAD, "本地命令（无需 AI 服务）:");
        reply.add(HackService.TONE_TEXT, "status            查看核心温度/稳定度与组件");
        reply.add(HackService.TONE_TEXT, "start / 启动       点火启动核心");
        reply.add(HackService.TONE_TEXT, "scram / 停堆       AI 接管并强制冷却");
        reply.add(HackService.TONE_TEXT, "stabiliser on|off  开关稳定器（同 receiver/emitter）");
        reply.add(HackService.TONE_TEXT, "temp <摄氏度>       直接设定核心温度");
        reply.add(HackService.TONE_TEXT, "autopilot on|off   开关异常自动接管");
        reply.add(HackService.TONE_HEAD, "提示词（自定义 AI 行为）:");
        reply.add(HackService.TONE_TEXT, "presets            列出可用行为预设");
        reply.add(HackService.TONE_TEXT, "preset <名称>       切换你自己的预设（如 safety/verbose/zh）");
        reply.add(HackService.TONE_TEXT, "prompt <文字>       设定你的自定义提示词（可用 {temperatureC} 占位符）");
        reply.add(HackService.TONE_TEXT, "prompt             查看当前生效提示词；prompt clear 清除");
        reply.add(HackService.TONE_DIM, "默认只报告等指令，AI 不会擅自停堆/止损；用本地命令或明确指令才动作。");
        reply.add(HackService.TONE_DIM, "其它文字将连同核心读数发给 AI，由其返回动作执行。");
    }

    /** Best-effort reply to the one player; a(logged) no-op if they disconnected. */
    private static void send(EntityPlayerMP player, Reply reply) {
        if (NetworkHandler.INSTANCE != null && player != null) {
            NetworkHandler.sendTo(player, new AiReplyMessage(reply));
        }
    }
}
