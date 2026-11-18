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

package com.hbmzz.rbmk;

import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.World;

/**
 * The parts of a hydrogen-bomb blast that are not the explosion itself.
 *
 * <p>Deliberately no mushroom cloud. The base mod's own nuke entity draws one,
 * and on an RBMK it is the wrong picture twice over: a mushroom grows over
 * minutes from a ground burst, while this is a pressure vessel letting go in
 * the open, and the cloud would also hide the one thing the sequence exists to
 * show - the lid going up. What is left when you take the stem away is a
 * flash, a dust ring, and a lot of steam, which is what this class draws.</p>
 *
 * <p>Everything here is server-side and uses vanilla particles. Client-side
 * shockwave animation lives in {@code com.hbmzz.client.ClientShake}, because a
 * ring that has to be drawn for thirty ticks should be ticked once on the
 * client, not packet-spammed from the server.</p>
 */
public final class RbmkEffects {

    private RbmkEffects() {
    }

    /**
     * One explosion, plus the light.
     *
     * <p>Order matters: the vanilla explosion carves the crater first, so the
     * particles spawn into air that has already been removed rather than into
     * blocks that are about to disappear.</p>
     */
    public static void nuclear(World world, double x, double y, double z, float power, boolean debris) {
        if (world == null || world.isRemote) {
            return;
        }
        world.createExplosion(null, x, y, z, power, debris);
        flash(world, x, y, z, power);
    }

    /** The white core: a brief, very bright sphere, then fire going up. */
    public static void flash(World world, double x, double y, double z, float power) {
        if (world == null) {
            return;
        }
        double scale = Math.max(1.0D, power * 0.16D);
        for (int i = 0; i < 6; i++) {
            world.spawnParticle(EnumParticleTypes.EXPLOSION_HUGE,
                    x + jitter(world, scale), y + jitter(world, scale * 0.6D), z + jitter(world, scale),
                    0.0D, 0.0D, 0.0D);
        }
        for (int i = 0; i < 46; i++) {
            double dx = world.rand.nextGaussian() * scale * 0.55D;
            double dy = Math.abs(world.rand.nextGaussian()) * scale * 0.45D;
            double dz = world.rand.nextGaussian() * scale * 0.55D;
            world.spawnParticle(EnumParticleTypes.EXPLOSION_LARGE, x + dx, y + dy, z + dz, 0.0D, 0.0D, 0.0D);
        }
        // Fire with no stem under it: this is what separates the look from a
        // mushroom, which is a column of smoke carrying fire upward.
        for (int i = 0; i < 28; i++) {
            double dx = world.rand.nextGaussian() * scale * 0.4D;
            double dz = world.rand.nextGaussian() * scale * 0.4D;
            world.spawnParticle(EnumParticleTypes.FLAME, x + dx, y + world.rand.nextDouble() * scale, z + dz,
                    dx * 0.03D, 0.28D + world.rand.nextDouble() * 0.3D, dz * 0.03D);
        }
    }

    /**
     * The shockwave as it crosses the ground.
     *
     * <p>A ring, not a sphere. Airburst light goes up; the visible pressure
     * front runs outward along whatever surface it finds, picking dust up as it
     * goes. Drawing it flat is also what keeps it from ever looking like the
     * cap of a mushroom.</p>
     */
    public static void dustRing(World world, double x, double y, double z, double radius) {
        if (world == null) {
            return;
        }
        int points = 48;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0D) * i / points;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double r = radius * (0.35D + world.rand.nextDouble() * 0.15D);
            world.spawnParticle(EnumParticleTypes.CLOUD,
                    x + dx * r, y + 0.25D, z + dz * r, dx * 0.55D, 0.03D, dz * 0.55D);
            world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
                    x + dx * r * 0.9D, y + 0.45D, z + dz * r * 0.9D, dx * 0.4D, 0.05D, dz * 0.4D);
        }
    }

    /**
     * Steam leaving the vessel.
     *
     * <p>Called both during the countdown and for a while after the lid leaves.
     * The reactor is a boiler that has lost control of its pressure; the plume
     * is the only part of that the player can see before anything explodes, so
     * it is worth drawing properly rather than as one particle per column per
     * eight ticks.</p>
     */
    public static void steam(World world, double x, double y, double z, double spread, int count, double lift) {
        if (world == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            double ox = jitter(world, spread);
            double oz = jitter(world, spread);
            world.spawnParticle(EnumParticleTypes.CLOUD,
                    x + ox, y + world.rand.nextDouble() * 0.4D, z + oz,
                    jitter(world, 0.09D), lift * (0.55D + world.rand.nextDouble() * 0.7D), jitter(world, 0.09D));
            if (world.rand.nextInt(5) == 0) {
                world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
                        x + ox * 1.2D, y + 0.3D, z + oz * 1.2D,
                        jitter(world, 0.06D), lift * 0.8D, jitter(world, 0.06D));
            }
        }
    }

    private static double jitter(World world, double scale) {
        return (world.rand.nextDouble() - 0.5D) * 2.0D * scale;
    }
}
