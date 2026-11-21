/*
 * HBMZZ - Aegis DFC telemetry terminal.
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

import com.hbmzz.aegis.AegisScram;
import com.hbmzz.aegis.AegisConfig;
import com.hbmzz.dfc.DFCReader;
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
 * {@code /aegis scram [<off|status>]} — command entry point for the DFC
 * emergency shutdown. Arming targets the nearest DFC core within the Aegis
 * scan radius of the player; a player may also stand right next to a core to
 * be certain which one they are braking.
 */
public final class CommandAegis extends CommandBase {

    @Override
    public String getName() {
        return "aegis";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/aegis scram [off|status]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // operators only: this writes another mod's reactor fields
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos pos) {
        if (args.length == 1) {
            return Arrays.asList("scram");
        }
        if (args.length == 2 && "scram".equalsIgnoreCase(args[0])) {
            return Arrays.asList("off", "status");
        }
        return super.getTabCompletions(server, sender, args, pos);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length == 0 || !"scram".equalsIgnoreCase(args[0])) {
            reply(sender, "用法: /aegis scram [off|status]");
            return;
        }

        World world = sender.getEntityWorld();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";

        if ("off".equals(sub)) {
            reply(sender, AegisScram.disarm(world));
            return;
        }
        if ("status".equals(sub)) {
            reply(sender, AegisScram.status(world));
            return;
        }

        // Arm: find the core to brake. Prefer the core under the player's feet
        // radius, else the nearest one in the Aegis scan radius.
        BlockPos anchor = sender.getPosition();
        if (sender instanceof EntityPlayer) {
            anchor = ((EntityPlayer) sender).getPosition();
        }
        TileEntity core = DFCReader.findCore(world, anchor, AegisConfig.scanRadius);
        if (core == null) {
            reply(sender, "附近 " + AegisConfig.scanRadius + " 格内没有 DFC 核心，无法停堆。");
            return;
        }
        reply(sender, AegisScram.arm(world, core.getPos()));
    }

    private static void reply(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }
}
