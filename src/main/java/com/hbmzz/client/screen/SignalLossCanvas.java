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

package com.hbmzz.client.screen;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * The picture a panel shows once its feed is gone: dead channel, no signal.
 *
 * <p>Kept separate from {@link DfcScreen} on purpose. That class is a telemetry
 * renderer with a boot state machine and a data model behind it; a fallen
 * screen has neither - it has one job, which is to look like a display that has
 * lost its source. Bolting a "no signal" mode onto the telemetry renderer would
 * mean threading a flag through every page.</p>
 *
 * <p>The upload path is deliberately identical to {@code DfcScreen.upload()}:
 * texture unit forced to 0, binding routed through {@code GlStateManager},
 * {@code GL_BGRA} subimage. Mixing a raw {@code glBindTexture} with
 * {@code GlStateManager} leaves its cache out of sync and the next thing drawn
 * samples this canvas stretched over the world.</p>
 */
public class SignalLossCanvas {

    private final int texW;
    private final int texH;
    private final int[] pixels;
    private final BufferedImage image;
    private final Random noise = new Random(0x5EEDL);
    private final ByteBuffer buffer;

    private int texId;
    private int allocW;
    private int allocH;
    private long lastDraw = Long.MIN_VALUE;

    public SignalLossCanvas(int cols, int rows) {
        this.texW = Math.max(96, cols * 64);
        this.texH = Math.max(72, rows * 64);
        this.pixels = new int[this.texW * this.texH];
        this.image = new BufferedImage(this.texW, this.texH, BufferedImage.TYPE_INT_ARGB);
        this.buffer = ByteBuffer.allocateDirect(this.pixels.length * 4)
                .order(java.nio.ByteOrder.nativeOrder());
    }

    public boolean hasTexture() {
        return this.texId != 0;
    }

    public int textureId() {
        return this.texId;
    }

    /**
     * Repaints at 20 Hz. Noise that does not move reads as a screenshot of a
     * dead channel rather than a dead channel.
     */
    public void render(long ageMs) {
        long bucket = ageMs / 50L;
        if (bucket == this.lastDraw) {
            return;
        }
        this.lastDraw = bucket;
        this.noise.setSeed(bucket * 0x9E3779B1L);

        Graphics2D g = this.image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(new Color(0x08090A));
            g.fillRect(0, 0, this.texW, this.texH);

            // Snow. Sparse and dim - a full white-noise field would read as
            // "censored" instead of "tuned to nothing".
            for (int i = 0, n = this.texW * this.texH / 26; i < n; i++) {
                int v = this.noise.nextInt(70) + 20;
                g.setColor(new Color(v, v, v, this.noise.nextInt(90) + 40));
                int s = this.noise.nextInt(2) + 1;
                g.fillRect(this.noise.nextInt(this.texW), this.noise.nextInt(this.texH), s, s);
            }

            // Rolling tear, the way an analogue tuner loses sync.
            int bandY = (int) ((ageMs / 7L) % (this.texH * 2)) - this.texH / 2;
            g.setColor(new Color(0xFFB03A));
            g.setStroke(new BasicStroke(1.0F));
            for (int y = bandY; y < bandY + 6; y++) {
                if (y < 0 || y >= this.texH) {
                    continue;
                }
                g.setColor(new Color(255, 176, 58, 26 + this.noise.nextInt(30)));
                g.drawLine(0, y, this.texW, y);
            }

            int pad = Math.max(6, this.texW / 24);
            g.setColor(new Color(0xFFB03A));
            g.setStroke(new BasicStroke(1.6F));
            g.drawRect(pad, pad, this.texW - pad * 2, this.texH - pad * 2);

            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(9, this.texH / 22)));
            g.setColor(new Color(0xFFB03A));
            g.drawString("SIGNAL LOST", pad + 5, pad + Math.max(12, this.texH / 16));

            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(16, this.texH / 8)));
            String main = "NO SIGNAL";
            FontMetrics fm = g.getFontMetrics();
            int mx = (this.texW - fm.stringWidth(main)) / 2;
            int my = this.texH / 2 + fm.getAscent() / 3;
            g.setColor(new Color(0x1A0F04));
            g.drawString(main, mx + 2, my + 2);
            g.setColor(new Color(0xFFC65C));
            g.drawString(main, mx, my);

            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(8, this.texH / 20)));
            g.setColor(new Color(0x8A6A34));
            fm = g.getFontMetrics();
            String sub = "AWAITING UPLINK";
            g.drawString(sub, (this.texW - fm.stringWidth(sub)) / 2,
                    this.texH - pad - Math.max(6, this.texH / 22));

            // Vignette on the frame edge so the picture looks like glass.
            for (int i = 0; i < Math.max(6, this.texW / 40); i++) {
                g.setColor(new Color(0, 0, 0, 18));
                g.drawRect(i, i, this.texW - i * 2 - 1, this.texH - i * 2 - 1);
            }
        } finally {
            g.dispose();
        }
    }

    /** Uploads the CPU image. Render thread only. */
    public void upload() {
        this.image.getRGB(0, 0, this.texW, this.texH, this.pixels, 0, this.texW);
        this.buffer.clear();
        this.buffer.asIntBuffer().put(this.pixels);
        this.buffer.position(0).limit(this.pixels.length * 4);

        if (this.texId == 0) {
            this.texId = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(this.texId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.texW, this.texH, 0,
                    GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.allocW = this.texW;
            this.allocH = this.texH;
            applyParams();
            return;
        }
        if (this.allocW != this.texW || this.allocH != this.texH) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(this.texId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.texW, this.texH, 0,
                    GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.allocW = this.texW;
            this.allocH = this.texH;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.texId);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.texW, this.texH,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, this.buffer);
        applyParams();
    }

    private void applyParams() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    public void dispose() {
        if (this.texId != 0) {
            GL11.glDeleteTextures(this.texId);
            this.texId = 0;
            this.allocW = 0;
            this.allocH = 0;
        }
        this.lastDraw = Long.MIN_VALUE;
    }
}
