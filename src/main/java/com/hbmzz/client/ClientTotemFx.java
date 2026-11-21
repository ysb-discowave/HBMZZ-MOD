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

import com.hbmzz.HBMZZ;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * The "totem of undying" replacement effect for DFC explosions.
 *
 * <h3>What it draws</h3>
 * <ol>
 *   <li><b>Spinning logo billboard</b> - a large, semi-transparent HBMZZ emblem
 *       that hovers at the explosion centre for ~2 seconds, rotating slowly.
 *       This stands in for the vanilla totem's gold texture.</li>
 *   <li><b>Golden particle spiral</b> - ~96 redstone particles in a 3D spiral
 *       that rises from the core, mimicking the vanilla totem's upward swirl.</li>
 *   <li><b>Screen flash</b> - a brief golden tint (20 ticks) that mimics the
 *       vanilla totem's flash without harming the player.</li>
 *   <li><b>Sound</b> - the vanilla totem-of-undying sound at the core.</li>
 * </ol>
 *
 * <p>Everything runs on the client thread. {@link ClientDfcFx} calls
 * {@link #fire} when the packet's mode is {@code TOTEM_EXPLODE};
 * {@link #tick} is driven from the client tick handler. At most one effect is
 * live at a time - a second explosion overwrites the first.</p>
 */
public final class ClientTotemFx {

    /** How long the effect lasts, in ticks (~2 s). */
    private static final int DURATION = 40;
    /** How long the screen flash lasts, in ticks. */
    private static final int FLASH_TICKS = 20;
    /** How far the effect is even attempted, squared. */
    private static final double CULL_SQ = 4096.0D;

    /** The logo GL texture id. 0 = not loaded, -1 = load failed. */
    private static int logoTexId = 0;

    private static double fx;
    private static double fy;
    private static double fz;
    private static float flashRadius;
    private static int ageTicks;
    private static boolean active;

    /** Monotonic spin angle for the billboard; not reset between fires. */
    private static float spinAngle;

    /** The overlay Post event fires once per HUD element; this keeps the flash to one draw per frame. */
    private static long flashFrame;

    private ClientTotemFx() {
    }

    /**
     * Registers this effect's event handlers on the forge bus. Mirrors
     * {@link ClientShake#init()}: the render and tick handlers are instance
     * methods, and the bus wants one instance, not static methods.
     */
    public static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ClientTotemFx());
    }

    /** Triggers the totem effect. Must be called on the client thread. */
    public static void fire(double x, double y, double z, float radius) {
        ClientTotemFx.fx = x;
        ClientTotemFx.fy = y;
        ClientTotemFx.fz = z;
        ClientTotemFx.flashRadius = Math.max(4.0F, radius);
        ClientTotemFx.ageTicks = 0;
        ClientTotemFx.active = true;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }

        ensureLogoTexture();
        spawnGoldenSpiral(x, y, z, radius);
        playTotemSound(x, y, z);
    }

    /** Advances the effect one client tick. */
    public static void tick() {
        if (!active) {
            return;
        }
        if (++ageTicks >= DURATION) {
            active = false;
        }
    }

    /**
     * Client tick handler. The {@code tick()} call and the frame counter are
     * driven from here, so the class only has to be registered on the bus.
     */
    @SubscribeEvent
    public void onClientTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
            return;
        }
        clientFrame++;
        tick();
    }

    // ------------------------------------------------------------------ sound

    private static void playTotemSound(double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            mc.world.playSound(mc.player, x, y, z, SoundEvents.ENTITY_FIREWORK_LARGE_BLAST,
                    SoundCategory.MASTER, 1.0F, 1.0F);
        } catch (Throwable totemGone) {
            try {
                mc.world.playSound(mc.player, x, y, z, SoundEvents.ENTITY_GENERIC_EXPLODE,
                        SoundCategory.MASTER, 0.8F, 0.5F);
            } catch (Throwable ignored) {
            }
        }
    }

    // --------------------------------------------------------------- particles

    /**
     * Spawns the golden spiral: particles that spiral outward and rise from the
     * core, the vanilla totem's signature motion.
     */
    private static void spawnGoldenSpiral(double cx, double cy, double cz, float radius) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }
        Random rand = new Random();
        int count = 80;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (float) count;
            double angle = t * Math.PI * 5.0D + rand.nextDouble() * 0.3D;
            double spiralR = (1.0D + t * 4.0D) * 0.6D;
            double px = cx + Math.cos(angle) * spiralR;
            double pz = cz + Math.sin(angle) * spiralR;
            double py = cy + t * radius * 1.2D + rand.nextDouble() * 0.5D;
            double vx = Math.cos(angle) * 0.04D + (rand.nextDouble() - 0.5D) * 0.02D;
            double vy = 0.06D + t * 0.08D + rand.nextDouble() * 0.03D;
            double vz = Math.sin(angle) * 0.04D + (rand.nextDouble() - 0.5D) * 0.02D;
            mc.effectRenderer.addEffect(
                    new ClientDfcFx.TotemParticle(mc.world, px, py, pz,
                            1.0F, 0.85F + t * 0.1F, 0.1F + t * 0.2F).aim(vx, vy, vz));
        }
        // A few white sparkles for extra totem feel.
        for (int i = 0; i < 16; i++) {
            double angle = rand.nextDouble() * Math.PI * 2.0D;
            double r = rand.nextDouble() * radius * 0.5D;
            double px = cx + Math.cos(angle) * r;
            double py = cy + rand.nextDouble() * radius;
            double pz = cz + Math.sin(angle) * r;
            mc.effectRenderer.addEffect(
                    new ClientDfcFx.TotemParticle(mc.world, px, py, pz, 1.0F, 1.0F, 0.95F)
                            .aim((rand.nextDouble() - 0.5D) * 0.06D,
                                    0.1D + rand.nextDouble() * 0.06D,
                                    (rand.nextDouble() - 0.5D) * 0.06D));
        }
    }

    // ----------------------------------------------------------------- logo

    /**
     * Draws the spinning logo billboard in-world, after all world geometry.
     *
     * <p>The quad always faces the camera and is rotated about the view axis
     * for the "totem spins" read. Depth testing is disabled while it draws so
     * the emblem is never hidden behind the core it is floating above.</p>
     */
    /**
     * Records the client frame the flash was last drawn on. {@link #tick} is
     * called once per client tick, i.e. before the frame that follows renders,
     * so comparing against the frame counter means the flash paints at most
     * once per frame no matter how many HUD elements post their overlay.
     */
    private static long clientFrame;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!active || logoTexId <= 0 || ageTicks == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }

        float lifeFrac = (float) ageTicks / (float) DURATION;
        float alpha;
        if (lifeFrac < 0.1F) {
            alpha = lifeFrac / 0.1F;
        } else if (lifeFrac > 0.7F) {
            alpha = 1.0F - (lifeFrac - 0.7F) / 0.3F;
        } else {
            alpha = 1.0F;
        }
        alpha *= 0.85F;

        float scale = flashRadius * 0.5F * (1.0F - lifeFrac * 0.2F);
        spinAngle += 0.06F;

        Vec3d cam = mc.player.getPositionEyes(1.0F);
        double dx = fx - cam.x;
        double dy = fy - cam.y;
        double dz = fz - cam.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001D || len * len > CULL_SQ) {
            return;
        }
        float nx = (float) (dx / len);
        float ny = (float) (dy / len);
        float nz = (float) (dz / len);

        // Two orthonormal axes perpendicular to the view direction.
        float upX = 0F, upY = 1F, upZ = 0F;
        float rx = upY * nz - upZ * ny;
        float ry = upZ * nx - upX * nz;
        float rz = upX * ny - upY * nx;
        float rLen = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rLen < 0.001F) {
            rx = 1F; ry = 0F; rz = 0F;
        } else {
            rx /= rLen; ry /= rLen; rz /= rLen;
        }
        float tx = ry * nz - rz * ny;
        float ty = rz * nx - rx * nz;
        float tz = rx * ny - ry * nx;
        float tLen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tLen > 0.001F) {
            tx /= tLen; ty /= tLen; tz /= tLen;
        } else {
            tx = 0F; ty = 0F; tz = 1F;
        }

        float cosA = (float) Math.cos(spinAngle);
        float sinA = (float) Math.sin(spinAngle);
        float r2x = rx * cosA + tx * sinA;
        float r2y = ry * cosA + ty * sinA;
        float r2z = rz * cosA + tz * sinA;
        float t2x = tx * cosA - rx * sinA;
        float t2y = ty * cosA - ry * sinA;
        float t2z = tz * cosA - rz * sinA;

        float cx = (float) fx;
        float cy = (float) fy;
        float cz = (float) fz;
        int ai = (int) (alpha * 255.0F);

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, logoTexId);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_TEX_COLOR);
        // White vertex colour so the emblem paints in its own gold; only the
        // alpha carries the fade. t2 is up, r2 is right on the screen.
        // top-right, top-left, bottom-left, bottom-right
        buf.pos((float)(cx + r2x * scale + t2x * scale), (float)(cy + r2y * scale + t2y * scale),
                (float)(cz + r2z * scale + t2z * scale)).tex(1.0F, 0.0F).color(255, 255, 255, ai).endVertex();
        buf.pos((float)(cx - r2x * scale + t2x * scale), (float)(cy - r2y * scale + t2y * scale),
                (float)(cz - r2z * scale + t2z * scale)).tex(0.0F, 0.0F).color(255, 255, 255, ai).endVertex();
        buf.pos((float)(cx - r2x * scale - t2x * scale), (float)(cy - r2y * scale - t2y * scale),
                (float)(cz - r2z * scale - t2z * scale)).tex(0.0F, 1.0F).color(255, 255, 255, ai).endVertex();
        buf.pos((float)(cx + r2x * scale - t2x * scale), (float)(cy + r2y * scale - t2y * scale),
                (float)(cz + r2z * scale - t2z * scale)).tex(1.0F, 1.0F).color(255, 255, 255, ai).endVertex();
        tess.draw();

        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
    }

    /**
     * Draws the brief golden screen flash on top of the HUD.
     *
     * <p>Peaks around tick 6 and is gone by {@link #FLASH_TICKS}. It is a flat
     * tint rather than a radial gradient: the totem's flash reads as a single
     * frame of gold, and a gradient would need its own texture to look clean.</p>
     */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!active || ageTicks >= FLASH_TICKS || flashFrame == clientFrame) {
            return;
        }
        flashFrame = clientFrame;
        int intensity = ageTicks < 6 ? ageTicks : FLASH_TICKS - ageTicks;
        int a = intensity * 4; // ~1..20 *4 = up to 80/255 - a flash, not a wash
        if (a < 2) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getFramebuffer() == null) {
            return;
        }
        int sw = mc.displayWidth;
        int sh = mc.displayHeight;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        buf.pos(0F, 0F, -0.5F).color(255, 215, 0, a).endVertex();
        buf.pos((float)sw, 0F, -0.5F).color(255, 215, 0, a).endVertex();
        buf.pos((float)sw, (float)sh, -0.5F).color(255, 215, 0, a).endVertex();
        buf.pos(0F, (float)sh, -0.5F).color(255, 215, 0, a).endVertex();
        tess.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ------------------------------------------------------------- init/cleanup

    /**
     * Loads the HBMZZ logo PNG into its own GL texture.
     *
     * <p>Loaded directly from the resource rather than through the particle
     * atlas: the atlas is baked once at startup and a 16x16 emblem is the last
     * thing the world's particle pass wants to touch. The texture is a one-time
     * 16x16 upload and is reused for every effect.</p>
     */
    private static void ensureLogoTexture() {
        if (logoTexId != 0) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            ResourceLocation rl = new ResourceLocation(HBMZZ.MODID, "textures/particles/hbmzz_logo.png");
            InputStream is = mc.getResourceManager().getResource(rl).getInputStream();
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(is);
            is.close();

            int w = Math.min(32, Math.max(1, img.getWidth()));
            int h = Math.min(32, Math.max(1, img.getHeight()));
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = img.getRGB(x, y); // 0xAARRGGBB
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    int a = (p >> 24) & 0xFF;
                    buf.put((byte) b).put((byte) g).put((byte) r).put((byte) a);
                }
            }
            img.flush();
            buf.flip();

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            logoTexId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, logoTexId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        } catch (Throwable e) {
            if (HBMZZ.logger != null) {
                HBMZZ.logger.warn("DFC totem FX: could not load the HBMZZ logo texture: " + e.getMessage());
            }
            logoTexId = -1;
        }
    }
}
