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

import com.hbmzz.network.DfcFxMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRedstone;
import net.minecraft.world.World;

import java.util.Random;

/**
 * The blue half of the automatic shutdown.
 *
 * <h3>Why a custom particle instead of {@code spawnParticle}</h3>
 * <p>The server can broadcast vanilla particles, but not coloured ones - the
 * only tintable type, redstone, needs its colour passed as arguments that the
 * vanilla packet does not fill in for a modded call. Drawing them here costs one
 * small packet per burst and gives an exact colour, an exact count and, most
 * importantly, control over each particle's velocity: the retraction is a
 * contracting ring, and a contracting ring is a shape, not a spawn position.</p>
 *
 * <p>Everything runs on the client thread. The packet handler is on the netty
 * thread, and touching the effect renderer from there is the same class of
 * mistake the monitor's packet bridge exists to avoid.</p>
 */
public final class ClientDfcFx {

    /** Core blue. Slightly cyan so it reads as cold next to amber panel light. */
    private static final float CORE_R = 0.28F;
    private static final float CORE_G = 0.62F;
    private static final float CORE_B = 1.00F;

    private ClientDfcFx() {
    }

    /** Entry point for {@link DfcFxMessage}; the signature must match. */
    public static void onMessage(final byte mode, final double x, final double y, final double z,
                                 final float radius, final float progress) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }
        // A dozen effects a second from a core across the map would be invisible
        // and still cost the particle budget.
        if (mc.player.getDistanceSq(x, y, z) > 160.0D * 160.0D) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                spawn(mode, x, y, z, radius, progress);
            }
        });
    }

    private static void spawn(byte mode, double x, double y, double z, float radius, float progress) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null) {
            return;
        }
        Random rand = world.rand;
        float r = Math.max(0.4F, radius);
        switch (mode) {
            case DfcFxMessage.AURA:
                // A shell of slow motes: dense enough to read as a field, sparse
                // enough that the core's own glow is still visible through it.
                for (int i = 0; i < 9; i++) {
                    double angle = rand.nextDouble() * Math.PI * 2.0D;
                    double ring = r * (0.35D + rand.nextDouble() * 0.65D);
                    double px = x + Math.cos(angle) * ring;
                    double pz = z + Math.sin(angle) * ring;
                    double py = y - 2.0D + rand.nextDouble() * 4.0D;
                    mc.effectRenderer.addEffect(
                            particle(world, px, py, pz, CORE_R, CORE_G, CORE_B)
                                    .aim(Math.cos(angle) * 0.012D,
                                            0.018D + rand.nextDouble() * 0.012D,
                                            Math.sin(angle) * 0.012D));
                }
                break;

            case DfcFxMessage.BURST:
                // One ring, thrown outwards. This is the "energy burst": the
                // ring is what makes it read as a discharge rather than sparks.
                for (int i = 0; i < 22; i++) {
                    double angle = (Math.PI * 2.0D * i) / 22.0D + rand.nextDouble() * 0.12D;
                    double px = x + Math.cos(angle) * r * 0.55D;
                    double pz = z + Math.sin(angle) * r * 0.55D;
                    double py = y - 1.5D + rand.nextDouble() * 2.5D;
                    mc.effectRenderer.addEffect(
                            particle(world, px, py, pz, 0.42F, 0.74F, 1.0F)
                                    .aim(Math.cos(angle) * 0.16D,
                                            0.05D + rand.nextDouble() * 0.05D,
                                            Math.sin(angle) * 0.16D));
                }
                break;

            case DfcFxMessage.RING:
                // Contracting ring. Every particle starts exactly on the circle
                // and is given a velocity that points at the core, so the whole
                // shape converges instead of merely appearing closer.
                for (int i = 0; i < 12; i++) {
                    double angle = (Math.PI * 2.0D * i) / 12.0D;
                    double px = x + Math.cos(angle) * r;
                    double pz = z + Math.sin(angle) * r;
                    double py = y - 0.5D + Math.sin(angle * 3.0D) * 0.6D;
                    BlueParticle p = particle(world, px, py, pz, 0.36F, 0.70F, 1.0F);
                    aimInward(p, x, y, z, px, py, pz, 0.085D);
                    mc.effectRenderer.addEffect(p);
                }
                break;

            case DfcFxMessage.IMPLODE:
            default:
                for (int i = 0; i < 40; i++) {
                    double theta = rand.nextDouble() * Math.PI * 2.0D;
                    double phi = Math.acos(2.0D * rand.nextDouble() - 1.0D);
                    double px = x + Math.sin(phi) * Math.cos(theta) * r;
                    double py = y + Math.cos(phi) * r * 0.6D;
                    double pz = z + Math.sin(phi) * Math.sin(theta) * r;
                    BlueParticle p = particle(world, px, py, pz, 0.55F, 0.82F, 1.0F);
                    aimInward(p, x, y, z, px, py, pz, 0.20D);
                    mc.effectRenderer.addEffect(p);
                }
                break;
        }
    }

    private static BlueParticle particle(World world, double x, double y, double z,
                                         float red, float green, float blue) {
        // 1.12.2 signature: (world, x, y, z, scale, red, green, blue). The
        // scale multiplies the particle's own size roll.
        return new BlueParticle(world, x, y, z, red, green, blue);
    }

    private static void aimInward(BlueParticle p, double cx, double cy, double cz,
                                  double px, double py, double pz, double speed) {
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }
        p.aim(dx / length * speed, dy / length * speed, dz / length * speed);
    }

    /**
     * A redstone particle with a public way to set its velocity.
     *
     * <p>{@code motionX/Y/Z} are protected on {@code Particle}, so a burst
     * cannot be aimed from outside without a subclass. The alternative - the
     * vanilla spawn call - takes no velocity at all, which is why the suction
     * rings would not converge.</p>
     */
    private static final class BlueParticle extends ParticleRedstone {

        BlueParticle(World world, double x, double y, double z,
                     float red, float green, float blue) {
            super(world, x, y, z, 1.1F, red, green, blue);
        }

        BlueParticle aim(double vx, double vy, double vz) {
            this.motionX = vx;
            this.motionY = vy;
            this.motionZ = vz;
            return this;
        }
    }
}
