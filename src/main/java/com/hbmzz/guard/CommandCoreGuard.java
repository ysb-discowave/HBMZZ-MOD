/*
 * HBMZZ - independent DFC core thermal guard.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */

package com.hbmzz.guard;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /coreguard} - entry point for the standalone core thermal guard.
 *
 * <ul>
 *   <li>{@code /coreguard scram} - cool the nearest DFC core to zero.</li>
 *   <li>{@code /coreguard forecast} - seconds until the nearest core melts.</li>
 *   <li>{@code /coreguard status} - list active shutdowns.</li>
 *   <li>{@code /coreguard off} - cancel all shutdowns in this world.</li>
 * </ul>
 */
public final class CommandCoreGuard extends CommandBase {

    private static final int RADIUS = 64;

    @Override
    public String getName() {
        return "coreguard";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/coreguard <scram|forecast|status|off>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // emergency brake: any player may cool a core
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos pos) {
        if (args.length == 1) {
            return Arrays.asList("scram", "forecast", "status", "off");
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

        if ("off".equals(sub)) {
            reply(sender, CoreGuard.disarm(world));
            return;
        }
        if ("status".equals(sub)) {
            reply(sender, CoreGuard.status(world));
            return;
        }
        if (!"scram".equals(sub) && !"forecast".equals(sub)) {
            reply(sender, getUsage(sender));
            return;
        }

        BlockPos anchor = sender.getPosition();
        if (sender instanceof EntityPlayer) {
            anchor = ((EntityPlayer) sender).getPosition();
        }
        TileEntity core = CoreThermal.findCore(world, anchor, RADIUS);
        if (core == null) {
            reply(sender, "附近 " + RADIUS + " 格内没有 DFC 核心。");
            return;
        }
        BlockPos corePos = core.getPos();
        if ("forecast".equals(sub)) {
            reply(sender, CoreGuard.forecast(world, corePos));
        } else {
            reply(sender, CoreGuard.arm(world, corePos));
        }
    }

    private static void reply(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }
}
