/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3.
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
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Orbiting camera for the last seconds of a machine's life.
 *
 * <h3>How the camera is moved</h3>
 * <p>Forge only lets a mod adjust the camera's <em>angles</em> in
 * {@code CameraSetup}; the position comes from whatever entity the renderer is
 * looking through. So the camera is moved by giving the renderer a different
 * entity: a client-only {@link CameraEntity} that is never spawned into the
 * world, sat on a circle around the target, and installed as
 * {@code Minecraft.renderViewEntity} for the duration of the shot. The player
 * entity is untouched, so there is nothing for the server to correct - a
 * teleport-based orbit would be rubber-banded back within a second and would
 * drag the player's hitbox through whatever was in the way.</p>
 *
 * <p>Head rotation is not taken away either: {@code CameraSetup} is overridden
 * to look at the target, but the player can still walk, and the angle they were
 * facing is restored the moment the camera is handed back.</p>
 *
 * <h3>Parameters</h3>
 * <ul>
 *   <li><b>radius</b> - distance from the target, blocks. Default 18. Clamped to
 *       6..40: closer than six and the machine fills the screen, further than
 *       forty and the chunks between the camera and the player may not be
 *       loaded.</li>
 *   <li><b>height</b> - vertical offset above the target, blocks. Default 7,
 *       which puts a reactor lid at eye level instead of underfoot.</li>
 *   <li><b>speed</b> - degrees per tick. 1.0 is a full turn in 18 seconds; the
 *       DFC finale uses 2.0 because it only has five seconds to work with.</li>
 *   <li><b>start angle</b> - taken from where the player is standing, so the
 *       shot begins from their side of the machine rather than snapping round
 *       to a fixed compass bearing.</li>
 * </ul>
 */
public final class ClientCinematic {

    private static final double MIN_RADIUS = 6.0D;
    private static final double MAX_RADIUS = 40.0D;

    private static boolean active;
    private static CameraEntity camera;
    private static double targetX;
    private static double targetY;
    private static double targetZ;
    private static double radius = 18.0D;
    private static double height = 7.0D;
    private static float speed = 1.0F;
    private static float angle;

    private ClientCinematic() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.register(new ClientCinematic());
    }

    /** Entry point for {@code CinematicMessage}; the signature must match. */
    public static void onMessage(final byte action, final double x, final double y, final double z,
                                 final float r, final float h, final float s) {
        // Same reason as ClientShake: this arrives on the netty thread, and both
        // renderViewEntity and the tick state belong to the client thread.
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                apply(action, x, y, z, r, h, s);
            }
        });
    }

    private static void apply(byte action, double x, double y, double z,
                              float r, float h, float s) {
        if (action == 0) {
            stop();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }
        // The director re-sends START while the shot runs (once a second for the
        // DFC poll) so the target can follow a machine that moved. Recomputing
        // the start angle on those would snap the camera back to the player's
        // bearing every second, so the angle is only seeded when the shot is
        // genuinely new: not active, or aimed at a different place.
        boolean fresh = !active || camera == null || camera.world != mc.world
                || Math.abs(x - targetX) > 1.5D || Math.abs(y - targetY) > 1.5D || Math.abs(z - targetZ) > 1.5D;
        targetX = x;
        targetY = y;
        targetZ = z;
        radius = MathHelper.clamp(r, (float) MIN_RADIUS, (float) MAX_RADIUS);
        height = MathHelper.clamp(h, -8.0F, 40.0F);
        speed = MathHelper.clamp(s, 0.0F, 12.0F);
        // Begin on the player's bearing, so the first frame is continuous with
        // what they were already looking at.
        if (fresh) {
            angle = (float) Math.toDegrees(Math.atan2(mc.player.posZ - z, mc.player.posX - x));
        }
        if (camera == null || camera.world != mc.world) {
            camera = new CameraEntity(mc.world);
        }
        active = true;
        place();
        // setRenderViewEntity, not the field: 1.12.2 keeps renderViewEntity
        // private and this setter also reloads the entity shader. Writing the
        // field directly (as older versions of this code did) does not compile
        // against the deobfuscated jar, and by hand would skip the shader step.
        mc.setRenderViewEntity(camera);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || camera == null || camera.world != mc.world) {
            stop();
            return;
        }
        angle += speed;
        if (angle >= 360.0F) {
            angle -= 360.0F;
        }
        place();
        // Re-assert once per tick: a dimension change or another mod's cinematic
        // may have put the old view entity back, and orbiting nothing is worse
        // than no orbit at all.
        if (mc.getRenderViewEntity() != camera) {
            mc.setRenderViewEntity(camera);
        }
    }

    @SubscribeEvent
    public void onCamera(EntityViewRenderEvent.CameraSetup event) {
        if (!active || camera == null) {
            return;
        }
        double eyeY = camera.posY + 0.1D;
        double dx = targetX - camera.posX;
        double dy = targetY - eyeY;
        double dz = targetZ - camera.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        event.setYaw(yaw);
        event.setPitch(MathHelper.clamp(pitch, -89.0F, 89.0F));
        event.setRoll(0.0F);
    }

    private static void place() {
        double radians = Math.toRadians(angle);
        double cx = targetX + Math.cos(radians) * radius;
        double cz = targetZ + Math.sin(radians) * radius;
        double cy = targetY + height;
        camera.setPosition(cx, cy, cz);
        // Keep the previous and last-tick positions equal, otherwise the
        // renderer interpolates from wherever the camera used to be and the
        // first frames of the shot whip around the target.
        camera.prevPosX = cx;
        camera.prevPosY = cy;
        camera.prevPosZ = cz;
        camera.lastTickPosX = cx;
        camera.lastTickPosY = cy;
        camera.lastTickPosZ = cz;
        camera.motionX = 0.0D;
        camera.motionY = 0.0D;
        camera.motionZ = 0.0D;
    }

    private static void stop() {
        active = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (camera != null && mc.getRenderViewEntity() == camera) {
            // Only if there is somebody to give the view back to; during a
            // disconnect the player is already gone and handing the renderer a
            // null view entity is a crash in a place nobody would look for it.
            if (mc.player != null) {
                mc.setRenderViewEntity(mc.player);
            }
        }
        camera = null;
    }

    /**
     * A camera with no body.
     *
     * <p>Never added to the world, so it is never ticked, saved, rendered or
     * synced. It exists to answer one question - "where is the eye?" - for the
     * few seconds the shot lasts.</p>
     */
    private static final class CameraEntity extends Entity {

        CameraEntity(World world) {
            super(world);
            this.noClip = true;
            this.preventEntitySpawning = true;
            this.setSize(0.1F, 0.1F);
            this.setPosition(0.0D, 0.0D, 0.0D);
        }

        @Override
        protected void entityInit() {
        }

        @Override
        protected void readEntityFromNBT(NBTTagCompound compound) {
        }

        @Override
        protected void writeEntityToNBT(NBTTagCompound compound) {
        }
    }
}
