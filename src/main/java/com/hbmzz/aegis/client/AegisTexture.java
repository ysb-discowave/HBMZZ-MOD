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

package com.hbmzz.aegis.client;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Owns one OpenGL texture for one terminal screen and re-uploads the offscreen
 * bitmap only when its owner reports new pixels.
 *
 * <p>The pixel order handed to {@code glTexImage2D} is BGRA, which is what the
 * {@code BufferedImage.TYPE_INT_ARGB} layout stores on a little-endian machine;
 * reading ints straight out of the image and pushing them through an int buffer
 * avoids a per-pixel Java loop. The texture is allocated lazily so a terminal
 * that is never looked at costs no GPU memory.</p>
 */
public final class AegisTexture {

    private int glId;
    private int allocatedW;
    private int allocatedH;
    private int[] pixels;
    private ByteBuffer staging;
    /** Upper bound on the upload rate; the canvas repaints on every frame. */
    private static final long MIN_UPLOAD_INTERVAL_MS = 16L;
    /**
     * Epoch of the last upload. MUST start at 0, never Long.MIN_VALUE:
     * {@code now - Long.MIN_VALUE} overflows to a negative number, which makes
     * the interval check read as "too soon" forever and leaves the screen
     * permanently blank. 0 is safe because currentTimeMillis is positive.
     */
    private long lastUploadMs = 0L;
    private boolean everUploaded;

    public int id() {
        return this.glId;
    }

    public boolean ready() {
        return this.glId != 0;
    }

    /** Forces a full re-upload on the next call to {@link #upload}. */
    public void invalidate() {
        this.everUploaded = false;
    }

    public void upload(BufferedImage image) {
        if (image == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // The canvas repaints every frame (boot sweep, scan animation,
        // countdown blink), so the GPU copy has to follow at the same rate.
        // A tiny interval caps cost on high-refresh displays while keeping the
        // picture smooth; re-uploading also heals a texture after a GL context
        // reset. The first upload (or one after invalidate) must never be
        // throttled — gating it was the black-screen bug.
        if (this.everUploaded && now - this.lastUploadMs < MIN_UPLOAD_INTERVAL_MS) {
            return;
        }
        this.lastUploadMs = now;
        this.everUploaded = true;

        int w = image.getWidth();
        int h = image.getHeight();
        ensure(w, h);

        if (this.pixels == null || this.pixels.length != w * h) {
            this.pixels = new int[w * h];
        }
        image.getRGB(0, 0, w, h, this.pixels, 0, w);
        int bytes = w * h * 4;
        if (this.staging == null || this.staging.capacity() < bytes) {
            this.staging = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
        this.staging.clear();
        IntBuffer view = this.staging.asIntBuffer();
        view.put(this.pixels);
        this.staging.position(0).limit(bytes);

        GlStateManager.bindTexture(this.glId);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, this.staging);
        applyFilter();
    }

    private void ensure(int w, int h) {
        if (this.glId == 0) {
            this.glId = GL11.glGenTextures();
            allocate(w, h);
            return;
        }
        if (this.allocatedW != w || this.allocatedH != h) {
            allocate(w, h);
        }
    }

    private void allocate(int w, int h) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.glId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        this.allocatedW = w;
        this.allocatedH = h;
        applyFilter();
    }

    private void applyFilter() {
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    public void dispose() {
        if (this.glId != 0) {
            GL11.glDeleteTextures(this.glId);
            this.glId = 0;
        }
        this.pixels = null;
        this.staging = null;
    }
}
