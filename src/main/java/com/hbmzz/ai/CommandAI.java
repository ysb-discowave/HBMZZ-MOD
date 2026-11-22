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
 * /ai tts &lt;name&gt;            TTS model name
 * /ai poll &lt;seconds&gt;        polling interval
 * /ai speed &lt;0.6-1.8&gt;       TTS speech speed
 * /ai on | off              master switch
 * /ai voice on|off          reasim voice routing master switch
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
        return "/ai <endpoint|key|model|tts|poll|speed|on|off|voice|status|bind|unbind|arm|disarm|test|selftest>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos pos) {
        if (args.length == 1) {
            return Arrays.asList("endpoint", "key", "model", "tts", "poll", "speed",
                    "on", "off", "voice", "status", "bind", "unbind", "arm", "disarm",
                    "test", "selftest");
        }
        if (args.length == 2 && ("voice".equalsIgnoreCase(args[0]))) {
            return Arrays.asList("on", "off");
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
        if ("tts".equals(sub)) {
            requireArg(args, 2);
            AiConfig.ttsModel = args[1];
            saveConfig();
            reply(sender, "TTS 模型已设为: " + AiConfig.ttsModel);
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
                    + " | TTS=" + AiConfig.ttsModel
                    + " | 轮询=" + AiConfig.pollSeconds + "s"
                    + " | 语音=" + (AiConfig.autoBroadcast ? "开" : "关"));
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
                    "/ai <endpoint|key|model|tts|poll|speed|on|off|voice|status|bind|unbind|arm|disarm|test|selftest>",
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
