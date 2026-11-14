/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
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
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.alert;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.rbmk.RbmkEffects;
import com.hbmzz.tile.TileEntityNuke;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

/**
 * Everything that happens outside a single block: finding a device across
 * dimensions, announcing a code to the whole server, and the blast itself.
 *
 * <p>Kept apart from the blocks so the sequence "signal, announcement, fuse,
 * detonation" exists in exactly one place. The controller, the bomb and any
 * future source of a detonation all go through here, which is what makes the
 * order of the announcements trustworthy - the code is raised <em>before</em>
 * anything explodes, so a display that survives the blast already shows ALPHA
 * rather than catching up afterwards.</p>
 */
public final class NukeService {

    private NukeService() {
    }

    /**
     * The device at a recorded position, or {@code null}.
     *
     * <p>Three separate failures are folded into that {@code null}: no position
     * recorded, the dimension no longer exists, and the chunk is not loaded. All
     * three mean the same thing to a caller - "cannot fire right now" - and the
     * caller says so in its own words, because the reason a player needs to hear
     * is "the chunk is not loaded", which only the caller knows.</p>
     */
    public static TileEntityNuke find(World origin, BlockPos pos, int dim) {
        if (origin == null || pos == null) {
            return null;
        }
        MinecraftServer server = origin.getMinecraftServer();
        World target = server == null ? origin : server.getWorld(dim);
        if (target == null || !target.isBlockLoaded(pos)) {
            return null;
        }
        TileEntity te = target.getTileEntity(pos);
        return te instanceof TileEntityNuke ? (TileEntityNuke) te : null;
    }

    /**
     * Starts the fuse and tells the world about it.
     *
     * @return false when the device was already counting down, or is gone
     */
    public static boolean prime(World source, TileEntityNuke nuke, String origin, String actor) {
        if (nuke == null || nuke.getWorld() == null || nuke.getWorld().isRemote) {
            return false;
        }
        if (!nuke.prime()) {
            return false;
        }
        World world = nuke.getWorld();
        BlockPos pos = nuke.getPos();
        raise(world, AlertCode.ALPHA, origin, actor,
                "核装置进入起爆程序 " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + "，引信 " + String.format(java.util.Locale.ROOT, "%.1f", nuke.getFuseTicks() / 20.0D) + " 秒");
        klaxon(world, net.minecraft.init.SoundEvents.ENTITY_ENDERDRAGON_GROWL, 3.0F, 0.7F);
        return true;
    }

    /** The bang. Called by the device when its fuse runs out. */
    public static void detonate(TileEntityNuke nuke) {
        if (nuke == null) {
            return;
        }
        World world = nuke.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos pos = nuke.getPos();
        int dim = world.provider.getDimension();
        int yield = nuke.getYield();

        // The block goes first. An explosion that has to eat its own source has
        // to get past the resistance of a bomb casing, and the crater then has a
        // bomb-shaped hole in the middle of it.
        nuke.markSpent();
        world.setBlockToAir(pos);
        NukeRegistry registry = NukeRegistry.get(world);
        if (registry != null) {
            registry.release();
        }

        float power = blastPower(yield);
        RbmkEffects.nuclear(world, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, power, true);
        RbmkEffects.dustRing(world, pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D, power * 1.6D);

        FacilityState state = FacilityState.get(world);
        if (state != null) {
            state.noteBlast(world, pos.getX(), pos.getY(), pos.getZ(), yield, dim);
        }
        raise(world, AlertCode.ALPHA, "核装置", "",
                "核装置已引爆 " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + "，当量 " + yield + " kT");
    }

    /**
     * Yield to vanilla explosion power.
     *
     * <p>Not linear and not proportional to the cube root, which would be the
     * physically honest mapping: power 40 already re-checks every block in a
     * 90-block radius and is the practical ceiling for one tick. So the curve is
     * shallow and the cap is hard, and the difference between 100 kT and 1000 kT
     * is mostly how much of the crater is on fire rather than how far the crater
     * reaches.</p>
     */
    public static float blastPower(int yield) {
        double power = HBMZZConfig.nukeBlastBase + Math.max(0, yield) * HBMZZConfig.nukeBlastPerKt;
        return (float) Math.max(4.0D, Math.min(HBMZZConfig.nukeBlastCap, power));
    }

    /**
     * Sets the facility code, pushes it to every client, and announces it.
     *
     * <p>One call, because three things have to happen together: the record
     * changes, every screen is told, and every player is told. Doing them at
     * separate call sites is how a display ends up showing a code nobody was
     * warned about.</p>
     */
    public static void raise(World world, AlertCode code, String source, String actor, String detail) {
        if (world == null || world.isRemote || code == null) {
            return;
        }
        FacilityState state = FacilityState.get(world);
        if (state != null) {
            state.raise(world, code, source, actor);
            AlertSync.push(world, state);
        }
        announce(world, code, detail, actor);
    }

    /** Chat line to everyone on the server, in every dimension. */
    public static void announce(World world, AlertCode code, String detail, String actor) {
        MinecraftServer server = world == null ? null : world.getMinecraftServer();
        if (server == null || code == null) {
            return;
        }
        ITextComponent line = new TextComponentString("")
                .appendSibling(styled("[设施广播] ", TextFormatting.DARK_GRAY))
                .appendSibling(styled(code.label() + " ", colorOf(code)));
        String body = code.zhTitle();
        if (detail != null && !detail.isEmpty()) {
            body = body + " - " + detail;
        }
        line.appendSibling(styled(body, TextFormatting.GRAY));
        if (actor != null && !actor.isEmpty()) {
            line.appendSibling(styled("  [" + actor + "]", TextFormatting.DARK_GRAY));
        }
        server.getPlayerList().sendMessage(line);
    }

    private static ITextComponent styled(String text, TextFormatting color) {
        TextComponentString component = new TextComponentString(text);
        component.setStyle(new Style().setColor(color));
        return component;
    }

    public static TextFormatting colorOf(AlertCode code) {
        switch (code) {
            case YELLOW:
                return TextFormatting.YELLOW;
            case ORANGE:
                return TextFormatting.GOLD;
            case RED:
                return TextFormatting.RED;
            case MED:
                return TextFormatting.LIGHT_PURPLE;
            case OMEGA:
                return TextFormatting.DARK_RED;
            case ALPHA:
                return TextFormatting.LIGHT_PURPLE;
            case DELTA:
                return TextFormatting.DARK_PURPLE;
            case GREEN:
            default:
                return TextFormatting.GREEN;
        }
    }

    /**
     * A sound every player hears, wherever they are.
     *
     * <p>{@code World.playSound} is positional: half the point of a facility-wide
     * alarm is that the player three hundred blocks away hears it too. Calling
     * {@code playSound} on each player sends the packet to that player only,
     * which is what makes this work without a custom packet.</p>
     */
    public static void klaxon(World world, SoundEvent sound, float volume, float pitch) {
        MinecraftServer server = world == null ? null : world.getMinecraftServer();
        if (server == null || sound == null) {
            return;
        }
        try {
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                player.playSound(sound, volume, pitch);
            }
        } catch (Throwable throwable) {
            // A dedicated server with no players still runs this path when a
            // redstone clock fires; an empty list is fine, any failure is not
            // worth taking the tick down for.
            HBMZZ.logger.warn("Could not broadcast a facility klaxon", throwable);
        }
    }
}
