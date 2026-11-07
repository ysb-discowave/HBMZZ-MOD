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

package com.hbmzz.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The shockwave, on the client.
 *
 * <p>Three things happen from one packet: the camera rolls, the hotbar slides,
 * and a ring of dust runs outward along the ground. All three are generated
 * here from a position and a strength, because they are animation, not world
 * state - the server already said "an explosion happened at x, y, z"; how a
 * blast looks from six blocks away is the client's business.</p>
 *
 * <p>The camera displacement is a sum of sines rather than a random walk. A
 * random offset per frame reads as a broken graphics driver; a low-frequency
 * wobble whose amplitude decays quadratically reads as mass. Two frequencies
 * per axis, detuned, so the three axes never line up into a single diagonal
 * judder.</p>
 *
 * <p>The hotbar is translated instead of the whole HUD, because the point is
 * that the blast reached the player's hands. Sliding the crosshair as well
 * would make it unreadable at exactly the moment the player is trying to run
 * away.</p>
 */
public final class ClientShake {

    /** One live shockwave. */
    private static final class Wave {
        final double x;
        final double y;
        final double z;
        final float intensity;
        final float range;
        final int duration;
        final boolean ring;
        final double seed;
        int age;

        Wave(double x, double y, double z, float intensity, float range, int duration, boolean ring) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.intensity = intensity;
            this.range = range;
            this.duration = Math.max(1, duration);
            this.ring = ring;
            this.seed = (x * 12.9898D + y * 78.233D + z * 37.719D) % (Math.PI * 2.0D);
        }

        /** 0 outside the radius, 1 at the centre, squared so the edge is soft. */
        double falloff() {
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player == null) {
                return 0.0D;
            }
            double distance = Math.sqrt(player.getDistanceSq(this.x, this.y, this.z));
            double f = MathHelper.clamp(1.0D - distance / this.range, 0.0D, 1.0D);
            return f * f;
        }

        /** Remaining life, eased so the shake dies away instead of stopping. */
        double life() {
            double t = 1.0D - (double) this.age / (double) this.duration;
            return t * t;
        }
    }

    private static final List<Wave> WAVES = new ArrayList<Wave>();

    /** True between the hotbar's Pre and Post, so the matrix stack balances. */
    private static boolean hotbarShifted;

    private ClientShake() {
    }

    /** Registered from the client proxy; nothing here runs on a server. */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new ClientShake());
    }

    /**
     * Entry point for {@code ShakeMessage}. Reflection signature must match.
     *
     * <p>Clamped rather than trusted: a hostile or merely buggy server can send
     * any float it likes, and a shake of 10^6 degrees is a screen a player
     * cannot see through and cannot turn off without leaving the world.</p>
     */
    public static void onShake(double x, double y, double z,
                               float intensity, float range, int duration, boolean ring) {
        final float safeIntensity = MathHelper.clamp(intensity, 0.0F, 24.0F);
        final float safeRange = MathHelper.clamp(range, 4.0F, 512.0F);
        final int safeDuration = MathHelper.clamp(duration, 1, 400);
        if (safeIntensity <= 0.0F) {
            return;
        }
        // The packet handler runs on the netty thread; WAVES is read and written
        // on the client thread every tick. Adding from here would be a
        // ConcurrentModificationException waiting for the next tick - the exact
        // shape of the 2.4.2 crash. Queue it instead.
        final double px = x;
        final double py = y;
        final double pz = z;
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                WAVES.add(new Wave(px, py, pz, safeIntensity, safeRange, safeDuration, ring));
                // Never more than a handful: a reactor that keeps tripping would
                // otherwise accumulate waves until the sum of their sines is
                // unreadable.
                while (WAVES.size() > 6) {
                    WAVES.remove(0);
                }
            }
        });
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || WAVES.isEmpty()) {
            return;
        }
        for (Iterator<Wave> it = WAVES.iterator(); it.hasNext(); ) {
            Wave wave = it.next();
            wave.age++;
            if (wave.ring) {
                emitRing(wave);
            }
            if (wave.age >= wave.duration) {
                it.remove();
            }
        }
    }

    /**
     * The dust front, one circle per tick.
     *
     * <p>Runs outward at a constant-ish rate and thins as it goes, which is the
     * cheap trick that makes it read as a front rather than as a growing
     * circle: the same number of particles spread over a longer circumference
     * is a thinner ring, and a thinner ring looks faster.</p>
     */
    private void emitRing(Wave wave) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        double progress = (double) wave.age / (double) wave.duration;
        double radius = wave.range * 0.30D * Math.pow(progress, 0.65D);
        if (radius < 0.5D) {
            return;
        }
        int points = Math.max(12, 48 - (int) (progress * 24.0D));
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0D) * i / points;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            mc.world.spawnParticle(EnumParticleTypes.CLOUD,
                    wave.x + dx * radius, wave.y + 0.2D, wave.z + dz * radius,
                    dx * 0.35D, 0.02D, dz * 0.35D);
        }
    }

    @SubscribeEvent
    public void onCamera(EntityViewRenderEvent.CameraSetup event) {
        if (WAVES.isEmpty()) {
            return;
        }
        float partial = (float) event.getRenderPartialTicks();
        double yaw = 0.0D;
        double pitch = 0.0D;
        double roll = 0.0D;
        for (Wave wave : WAVES) {
            double amplitude = wave.intensity * wave.falloff() * wave.life();
            if (amplitude <= 0.0001D) {
                continue;
            }
            double phase = wave.seed + (wave.age + partial) * 0.9D;
            yaw += Math.sin(phase * 1.7D) * amplitude * 0.55D;
            pitch += Math.sin(phase * 2.3D + 1.1D) * amplitude * 0.65D;
            roll += Math.sin(phase * 1.3D + 2.2D) * amplitude * 1.15D;
        }
        event.setYaw(event.getYaw() + (float) yaw);
        event.setPitch(event.getPitch() + (float) pitch);
        event.setRoll(event.getRoll() + (float) roll);
    }

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR || WAVES.isEmpty()) {
            return;
        }
        float[] offset = guiOffset();
        if (offset[0] == 0.0F && offset[1] == 0.0F) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(offset[0], offset[1], 0.0F);
        hotbarShifted = true;
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR || !hotbarShifted) {
            return;
        }
        GlStateManager.popMatrix();
        hotbarShifted = false;
    }

    /** Hotbar displacement in GUI pixels - smaller than it sounds. */
    private static float[] guiOffset() {
        double x = 0.0D;
        double y = 0.0D;
        for (Wave wave : WAVES) {
            double amplitude = wave.intensity * wave.falloff() * wave.life();
            if (amplitude <= 0.0001D) {
                continue;
            }
            double phase = wave.seed + wave.age * 1.15D;
            x += Math.sin(phase * 2.1D + 0.7D) * amplitude * 1.5D;
            y += Math.sin(phase * 1.9D + 2.9D) * amplitude * 1.2D;
        }
        return new float[]{(float) x, (float) y};
    }
}
