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
 * Portions of the render structure and the offscreen-canvas pipeline are derived
 * from JiezhiMod by Shushang de Jiezhi, used under written permission to license
 * them under GPL-3.0. See AUTHORIZATION.md.
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client;

import com.hbmzz.client.screen.OffscreenPanel;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The one GL texture behind an offscreen panel.
 *
 * <p>Split away from {@link OffscreenPanel} so the painting half has no OpenGL
 * in it, which is what lets a screen be rendered to a PNG on a machine with no
 * game running. Everything that is easy to get wrong lives here, and it is the
 * same set of rules the DFC monitor's canvas learned the hard way:</p>
 *
 * <ol>
 *   <li><b>Bind on unit 0, through {@link GlStateManager}.</b> The active unit
 *       belongs to whoever drew last; an upload to the wrong unit fills a texture
 *       nothing samples while unit 0 keeps the terrain atlas, and the quad then
 *       reads as scrambled blocks scattered over the world.</li>
 *   <li><b>BGRA from an ARGB int buffer.</b> Little-endian {@code 0xAARRGGBB}
 *       lands in memory as B,G,R,A, which is exactly {@code GL_BGRA}.</li>
 *   <li><b>CLAMP_TO_EDGE and no mipmaps.</b> Neither dimension is a power of
 *       two, and REPEAT wrap makes some drivers call the texture incomplete.</li>
 * </ol>
 *
 * <p>A full re-upload is forced every few seconds: resource-pack reloads and a
 * few other mods invalidate texture contents behind our back, and a panel showing
 * a steady reading would otherwise never dirty itself again.</p>
 */
public final class PanelTexture {

    private int id;
    private int allocW;
    private int allocH;
    private ByteBuffer upload;
    private int[] pixels;
    private long lastUploadMs;
    private boolean dirty = true;

    public int id() {
        return this.id;
    }

    public boolean has() {
        return this.id != 0;
    }

    /** Marks the texture stale so the next upload rewrites every pixel. */
    public void invalidate() {
        this.dirty = true;
    }

    public void upload(OffscreenPanel panel) {
        if (panel == null || panel.image() == null) {
            return;
        }
        int w = panel.image().getWidth();
        int h = panel.image().getHeight();
        long now = System.currentTimeMillis();
        boolean forced = now - this.lastUploadMs > 4000L;
        if (!panel.isDirty() && !this.dirty && !forced) {
            return;
        }
        panel.clearDirty();
        this.dirty = false;
        this.lastUploadMs = now;

        ensureTexture(w, h);
        if (this.pixels == null || this.pixels.length != w * h) {
            this.pixels = new int[w * h];
        }
        panel.image().getRGB(0, 0, w, h, this.pixels, 0, w);
        ByteBuffer buffer = buffer(w * h * 4);
        buffer.clear();
        buffer.asIntBuffer().put(this.pixels);
        buffer.position(0).limit(this.pixels.length * 4);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.id);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);
        applyParams();
    }

    private void ensureTexture(int w, int h) {
        if (this.id == 0) {
            this.id = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(this.id);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.allocW = w;
            this.allocH = h;
            applyParams();
            return;
        }
        if (this.allocW != w || this.allocH != h) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(this.id);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.allocW = w;
            this.allocH = h;
        }
    }

    private void applyParams() {
        GlStateManager.bindTexture(this.id);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    private ByteBuffer buffer(int needed) {
        if (this.upload == null || this.upload.capacity() < needed) {
            this.upload = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
        }
        return this.upload;
    }

    public void dispose() {
        if (this.id != 0) {
            GL11.glDeleteTextures(this.id);
            this.id = 0;
            this.allocW = 0;
            this.allocH = 0;
            this.lastUploadMs = 0L;
        }
        this.dirty = true;
    }
}
