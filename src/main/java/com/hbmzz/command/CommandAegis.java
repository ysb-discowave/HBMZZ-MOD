/*
 * HBMZZ - ADFC telemetry terminal.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hbmzz.command;

import com.hbmzz.guard.CoreGuard;
import com.hbmzz.guard.CoreThermal;
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
 * {@code /aegis scram [<off|status|forecast>]} - compatibility alias for the
 * standalone core thermal guard. Kept so the original command spelling keeps
 * working; all behaviour now lives in {@link CoreGuard}, which writes the real
 * CE temperature field instead of the vanilla heat field.
 */
public final class CommandAegis extends CommandBase {

    private static final int RADIUS = 64;

    @Override
    public String getName() {
        return "aegis";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/aegis scram [off|status|forecast]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos pos) {
        if (args.length == 1) {
            return Arrays.asList("scram");
        }
        if (args.length == 2 && "scram".equalsIgnoreCase(args[0])) {
            return Arrays.asList("off", "status", "forecast");
        }
        return super.getTabCompletions(server, sender, args, pos);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length == 0 || !"scram".equalsIgnoreCase(args[0])) {
            reply(sender, "用法: /aegis scram [off|status|forecast]");
            return;
        }

        World world = sender.getEntityWorld();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if ("off".equals(sub)) {
            reply(sender, CoreGuard.disarm(world));
            return;
        }
        if ("status".equals(sub)) {
            reply(sender, CoreGuard.status(world));
            return;
        }

        BlockPos anchor = sender.getPosition();
        if (sender instanceof EntityPlayer) {
            anchor = ((EntityPlayer) sender).getPosition();
        }
        if ("forecast".equals(sub)) {
            TileEntity core = CoreThermal.findCore(world, anchor, RADIUS);
            if (core == null) {
                reply(sender, "附近没有 DFC 核心。");
                return;
            }
            reply(sender, CoreGuard.forecast(world, core.getPos()));
            return;
        }

        TileEntity core = CoreThermal.findCore(world, anchor, RADIUS);
        if (core == null) {
            reply(sender, "附近 " + RADIUS + " 格内没有 DFC 核心，无法停堆。");
            return;
        }
        reply(sender, CoreGuard.arm(world, core.getPos()));
    }

    private static void reply(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }
}
