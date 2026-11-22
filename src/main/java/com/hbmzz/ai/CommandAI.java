/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ai} - configure and drive AI control nodes.
 *
 * <p>Global service settings are changed here and written straight into
 * {@link AiConfig} (and saved to hbmzz-ai.cfg). Node-local operations target
 * the node the player is looking at, or the nearest one within a few blocks.</p>
 *
 * <pre>
 * /ai endpoint &lt;url&gt;      AI HTTP interface URL
 * /ai key &lt;token&gt;           API key (Bearer token), "-" clears it
 * /ai model &lt;name&gt;          AI model id (default FAAS-REMIX)
 * /ai prompt &lt;text...&gt;      set the global custom prompt (clear resets it)
 * /ai preset &lt;name&gt;         pick a built-in behaviour preset
 * /ai presets                 list available behaviour presets
 * /ai tts &lt;name&gt;            TTS voice label (AI voice uses rsaudio embedded Piper)
 * /ai poll &lt;seconds&gt;        polling interval
 * /ai speed &lt;0.6-1.8&gt;       TTS speech speed
 * /ai on | off              master switch
 * /ai voice on|off          reasim voice routing master switch
 * /ai display on|off        AI drives the whole-map code displays on anomaly
 * /ai control on|off        allow the AI to write DFC machines (autopilot)
 * /ai autopilot on|off      alias of /ai control
 * /ai autoscram on|off      local threshold SCRAM when the AI gives no action (default off)
 * /ai act &lt;op&gt; [part] [value]  run one DFC control action now
 * /ai show &lt;code&gt; [text]  push a code to every display (operator override)
 * /ai clear                 clear the AI alarm back to GREEN on every display
 * /ai status                show global config + node under crosshair
 * /ai bind                  bind the node under crosshair to the machine under crosshair
 * /ai unbind                clear the node's binding
 * /ai arm | disarm          arm / disarm the node under crosshair
 * /ai test                  force one analysis of the node's bound machine
 * /ai selftest              speak a short test announcement to the whole server
 * </pre>
 */
public final class CommandAI extends CommandBase {

    private static final int NODE_REACH = 8;

    @Override
    public String getName() {
        return "ai";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ai <endpoint|key|model|prompt|preset|presets|tts|poll|speed|on|off|voice|display|control|autopilot|autoscram|act|show|clear|status|bind|unbind|arm|disarm|test|selftest>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos pos) {
        if (args.length == 1) {
            return Arrays.asList("endpoint", "key", "model", "prompt", "preset", "presets",
                    "tts", "poll", "speed", "on", "off", "voice", "display", "control",
                    "autopilot", "autoscram", "act", "show", "clear", "status", "bind", "unbind",
                    "arm", "disarm", "test", "selftest");
        }
        if (args.length == 2 && "preset".equalsIgnoreCase(args[0])) {
            return new ArrayList<String>(AiPrompt.presetKeys());
        }
        if (args.length == 2 && "prompt".equalsIgnoreCase(args[0])) {
            return Arrays.asList("clear");
        }
        if (args.length == 2 && ("voice".equalsIgnoreCase(args[0])
                || "display".equalsIgnoreCase(args[0])
                || "control".equalsIgnoreCase(args[0])
                || "autoscram".equalsIgnoreCase(args[0])
                || "autopilot".equalsIgnoreCase(args[0]))) {
            return Arrays.asList("on", "off");
        }
        if (args.length == 2 && "act".equalsIgnoreCase(args[0])) {
            return Arrays.asList("start", "scram", "stabiliser", "receiver", "emitter", "temperature");
        }
        if (args.length == 2 && "show".equalsIgnoreCase(args[0])) {
            java.util.List<String> codes = new java.util.ArrayList<String>();
            for (com.hbmzz.alert.AlertCode code : com.hbmzz.alert.AlertCode.values()) {
                codes.add(code.label());
            }
            return codes;
        }
        return super.getTabCompletions(server, sender, args, pos);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length == 0) {
            reply(sender, getUsage(sender));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        World world = sender.getEntityWorld();

        // ---- global service settings ---------------------------------------
        if ("endpoint".equals(sub)) {
            requireArg(args, 2);
            AiConfig.endpoint = args[1];
            saveConfig();
            reply(sender, "AI 接口已设为: " + AiConfig.endpoint);
            return;
        }
        if ("key".equals(sub)) {
            requireArg(args, 2);
            AiConfig.apiKey = "-".equals(args[1]) ? "" : args[1];
            saveConfig();
            reply(sender, "API 密钥已" + (AiConfig.apiKey.isEmpty() ? "清除" : "更新"));
            return;
        }
        if ("model".equals(sub)) {
            requireArg(args, 2);
            AiConfig.model = args[1];
            saveConfig();
            reply(sender, "AI 模型已设为: " + AiConfig.model);
            return;
        }
        if ("prompt".equals(sub)) {
            requireArg(args, 2);
            StringBuilder text = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(args[i]);
            }
            String value = text.toString().trim();
            if (value.equalsIgnoreCase("clear") || value.equals("-")) {
                AiConfig.prompt = "";
                reply(sender, "全局自定义提示词已清除（保留预设 " + AiConfig.promptPreset + "）");
            } else {
                if (value.length() > 2048) {
                    value = value.substring(0, 2048);
                }
                AiConfig.prompt = value;
                reply(sender, "全局自定义提示词已更新（" + value.length() + " 字）");
            }
            saveConfig();
            return;
        }
        if ("preset".equals(sub)) {
            requireArg(args, 2);
            if (!AiPrompt.isPreset(args[1])) {
                reply(sender, "未知预设 " + args[1] + "，可用: " + AiPrompt.listPresets());
                return;
            }
            AiConfig.promptPreset = AiPrompt.preset(args[1]).key;
            saveConfig();
            reply(sender, "AI 行为预设已设为 " + AiConfig.promptPreset
                    + "（" + AiPrompt.preset(AiConfig.promptPreset).zhName + "）");
            return;
        }
        if ("presets".equals(sub)) {
            reply(sender, "可用预设: " + AiPrompt.listPresets());
            reply(sender, "当前: " + AiConfig.promptPreset
                    + "；/ai preset <名称> 切换，/ai prompt <文字> 叠加自定义提示词。");
            return;
        }
        if ("tts".equals(sub)) {
            requireArg(args, 2);
            AiConfig.ttsModel = args[1];
            saveConfig();
            // The voice is always the rsaudio embedded Piper engine; this only
            // renames the /ai status label. Say so so an operator isn't fooled
            // into thinking the string picks a separate TTS asset.
            reply(sender, "TTS 标签已设为: " + AiConfig.ttsModel
                    + "（AI 语音固定走 rsaudio 内嵌 Piper 引擎，实际音色取服务端安装的 .onnx 模型）");
            return;
        }
        if ("poll".equals(sub)) {
            requireArg(args, 2);
            int sec = parseAiInt(args[1]);
            AiConfig.pollSeconds = AiConfig.clampPollSeconds(sec);
            saveConfig();
            reply(sender, "轮询间隔: " + AiConfig.pollSeconds + " 秒");
            return;
        }
        if ("speed".equals(sub)) {
            requireArg(args, 2);
            double v;
            try {
                v = Double.parseDouble(args[1]);
            } catch (NumberFormatException nfe) {
                reply(sender, "速度必须是 0.6 到 1.8 的数字");
                return;
            }
            AiConfig.lengthScale = AiConfig.clampLengthScale(v);
            saveConfig();
            reply(sender, "TTS 语速: " + String.format(Locale.ROOT, "%.1f", AiConfig.lengthScale));
            return;
        }
        if ("on".equals(sub) || "off".equals(sub)) {
            AiConfig.enabled = "on".equals(sub);
            saveConfig();
            reply(sender, "AI 功能已" + (AiConfig.enabled ? "开启" : "关闭"));
            return;
        }
        if ("voice".equals(sub)) {
            requireArg(args, 2);
            AiConfig.autoBroadcast = "on".equalsIgnoreCase(args[1]);
            saveConfig();
            reply(sender, "异常语音广播已" + (AiConfig.autoBroadcast ? "开启" : "关闭"));
            return;
        }
        if ("display".equals(sub)) {
            requireArg(args, 2);
            AiConfig.autoDisplay = "on".equalsIgnoreCase(args[1]);
            saveConfig();
            reply(sender, "AI 控制全图代码显示器已" + (AiConfig.autoDisplay ? "开启" : "关闭"));
            return;
        }
        if ("control".equals(sub) || "autopilot".equals(sub)) {
            requireArg(args, 2);
            AiConfig.autoControl = "on".equalsIgnoreCase(args[1]);
            saveConfig();
            reply(sender, "AI 自动接管 DFC 已" + (AiConfig.autoControl ? "开启" : "关闭"));
            return;
        }
        if ("autoscram".equals(sub)) {
            requireArg(args, 2);
            AiConfig.autoScram = "on".equalsIgnoreCase(args[1]);
            saveConfig();
            reply(sender, "无动作时的本地兜底 SCRAM 已" + (AiConfig.autoScram ? "开启" : "关闭")
                    + "（默认关，AI 只报告等指令）");
            return;
        }
        if ("act".equals(sub)) {
            requireArg(args, 2);
            AiAction action = new AiAction();
            action.op = AiAction.normalize(args[1]);
            if (args.length > 2) {
                action.part = args[2];
            }
            if (args.length > 3) {
                try {
                    action.value = Double.parseDouble(args[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            net.minecraft.world.World actWorld = server.getEntityWorld();
            BlockPos at = sender instanceof EntityPlayer
                    ? ((EntityPlayer) sender).getPosition() : sender.getPosition();
            List<String> results = AiAutopilot.run(actWorld, at,
                    java.util.Collections.singletonList(action));
            if (results.isEmpty()) {
                results = new ArrayList<String>();
                results.add("指令已执行或无需写入（检查 /ai control 是否开启）。");
            }
            for (String text : results) {
                reply(sender, text);
            }
            return;
        }
        if ("show".equals(sub)) {
            requireArg(args, 2);
            com.hbmzz.alert.AlertCode code = com.hbmzz.alert.AlertCode.byLabel(args[1]);
            boolean valid = false;
            for (com.hbmzz.alert.AlertCode candidate : com.hbmzz.alert.AlertCode.values()) {
                if (candidate.label().equalsIgnoreCase(args[1])) {
                    valid = true;
                }
            }
            if (!valid) {
                reply(sender, "未知代码 " + args[1] + "，可用: GREEN/YELLOW/ORANGE/RED/MED/OMEGA/ALPHA/DELTA");
                return;
            }
            StringBuilder detail = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (detail.length() > 0) {
                    detail.append(' ');
                }
                detail.append(args[i]);
            }
            net.minecraft.world.World aiWorld = server.getEntityWorld();
            boolean changed = AiDisplay.raise(aiWorld, code, sender.getPosition(), detail.toString());
            reply(sender, (changed ? "已向全图显示器推送 " : "显示器已在显示 ") + code.label()
                    + "（" + code.zhTitle() + "）");
            return;
        }
        if ("clear".equals(sub)) {
            net.minecraft.world.World aiWorld = server.getEntityWorld();
            boolean changed = AiDisplay.clear(aiWorld, sender.getPosition());
            reply(sender, changed ? "已清除 AI 警报，全图显示器恢复 GREEN" : "全图显示器已是 GREEN");
            return;
        }
        if ("selftest".equals(sub)) {
            BlockPos at = sender instanceof EntityPlayer
                    ? ((EntityPlayer) sender).getPosition() : sender.getPosition();
            boolean queued = AiBroadcast.selfTest(at);
            reply(sender, queued ? "全服语音自检已排队" : "自检未发声（TTS 引擎未就绪或已关闭）");
            return;
        }
        if ("status".equals(sub)) {
            reply(sender, "AI: " + (AiConfig.enabled ? "开" : "关")
                    + " | 接口=" + AiConfig.endpoint
                    + " | 模型=" + AiConfig.model
                    + " | 预设=" + AiConfig.promptPreset
                    + " | 自定义提示词=" + (AiConfig.prompt.isEmpty() ? "无" : AiConfig.prompt.length() + "字")
                    + " | TTS=" + AiConfig.ttsModel + "("
                    + com.rsaudio.util.TtsServer.statusMessage() + ")"
                    + " | 轮询=" + AiConfig.pollSeconds + "s"
                    + " | 语音=" + (AiConfig.autoBroadcast ? "开" : "关")
                    + " | 显示器=" + (AiConfig.autoDisplay ? "开" : "关")
                    + " | 接管=" + (AiConfig.autoControl ? "开" : "关")
                    + " | 兜底SCRAM=" + (AiConfig.autoScram ? "开" : "关"));
            TileEntityAIControlNode node = findNode(sender, world);
            if (node != null) {
                reply(sender, "节点 @" + node.getPos() + " | 状态=" + node.getLastLevel()
                        + " | " + node.getLastStatus());
            } else {
                reply(sender, "附近 " + NODE_REACH + " 格内没有 AI 控制节点。");
            }
            return;
        }

        // ---- node-local operations -----------------------------------------
        TileEntityAIControlNode node = findNode(sender, world);
        if (node == null) {
            reply(sender, "附近 " + NODE_REACH + " 格内没有 AI 控制节点。");
            return;
        }
        if ("bind".equals(sub)) {
            BlockPos target = sender instanceof EntityPlayer
                    ? ((EntityPlayer) sender).getPosition() : sender.getPosition();
            // Bind to the machine at the position the player is looking at, or
            // failing that, the block directly under the node.
            BlockPos machine = target;
            if (sender instanceof EntityPlayer) {
                net.minecraft.util.math.RayTraceResult hit =
                        ((EntityPlayer) sender).rayTrace(NODE_REACH + 8, 1.0F);
                if (hit != null && hit.typeOfHit == net.minecraft.util.math.RayTraceResult.Type.BLOCK) {
                    BlockPos hitPos = hit.getBlockPos();
                    if (!(world.getTileEntity(hitPos) instanceof TileEntityAIControlNode)) {
                        machine = hitPos;
                    }
                }
            }
            if (machine.equals(node.getPos())) {
                reply(sender, "不能把节点绑定到自己。请看向要监控的机器再执行 /ai bind。");
                return;
            }
            node.bind(machine, world.provider.getDimension());
            reply(sender, "节点已绑定机器 " + machine);
            return;
        }
        if ("unbind".equals(sub)) {
            node.bind(null, 0);
            reply(sender, "节点绑定已清除");
            return;
        }
        if ("arm".equals(sub) || "disarm".equals(sub)) {
            node.setArmed("arm".equals(sub));
            reply(sender, "节点已" + ("arm".equals(sub) ? "启用" : "停用"));
            return;
        }
        if ("test".equals(sub)) {
            node.testNow();
            reply(sender, "已触发一次分析，稍后用 /ai status 查看结果");
            return;
        }
        reply(sender, getUsage(sender));
    }

    private static void requireArg(String[] args, int need) throws CommandException {
        if (args.length < need) {
            throw new net.minecraft.command.WrongUsageException(
                    "/ai <endpoint|key|model|prompt|preset|presets|tts|poll|speed|on|off|voice|display|control|autopilot|autoscram|act|show|clear|status|bind|unbind|arm|disarm|test|selftest>",
                    new Object[0]);
        }
    }

    private static int parseAiInt(String s) throws CommandException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            throw new net.minecraft.command.WrongUsageException("需要一个整数", new Object[0]);
        }
    }

    /** Node under the crosshair first, else the nearest node within reach. */
    private static TileEntityAIControlNode findNode(ICommandSender sender, World world) {
        BlockPos anchor = sender instanceof EntityPlayer
                ? ((EntityPlayer) sender).getPosition() : sender.getPosition();
        if (sender instanceof EntityPlayer) {
            net.minecraft.util.math.RayTraceResult hit =
                    ((EntityPlayer) sender).rayTrace(NODE_REACH, 1.0F);
            if (hit != null && hit.typeOfHit == net.minecraft.util.math.RayTraceResult.Type.BLOCK) {
                TileEntity te = world.getTileEntity(hit.getBlockPos());
                if (te instanceof TileEntityAIControlNode) {
                    return (TileEntityAIControlNode) te;
                }
            }
        }
        TileEntityAIControlNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (TileEntity te : world.loadedTileEntityList) {
            if (te instanceof TileEntityAIControlNode && !te.isInvalid()) {
                double d = te.getPos().distanceSq(anchor);
                if (d < bestDist && d <= NODE_REACH * NODE_REACH) {
                    bestDist = d;
                    best = (TileEntityAIControlNode) te;
                }
            }
        }
        return best;
    }

    private static void saveConfig() {
        AiConfig.save();
    }

    private static void reply(ICommandSender sender, String text) {
        ITextComponent c = new TextComponentString(text);
        c.setStyle(new Style().setColor(TextFormatting.AQUA));
        sender.sendMessage(c);
    }
}
