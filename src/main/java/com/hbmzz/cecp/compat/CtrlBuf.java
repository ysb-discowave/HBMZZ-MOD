/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.compat;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Minimal, Java-8-friendly stand-in for NTM: Cursed Edition's
 * {@code com.hbm.render.util.NTMBufferBuilder}.
 *
 * <p>CE implements that interface directly on Minecraft's {@code BufferBuilder}
 * through a mixin and writes vertices with {@code sun.misc.Unsafe}. Neither is
 * available to an ordinary mod, so this class keeps the same call names and
 * the same packed-colour convention but funnels every vertex through the
 * vanilla {@link BufferBuilder} API.</p>
 *
 * <p>Ported from NTM: Cursed Edition (com.hbm.render.util.NTMBufferBuilder).</p>
 */
@SideOnly(Side.CLIENT)
public class CtrlBuf {

    private final BufferBuilder inner;
    private final VertexFormat format;
    private final boolean uv;

    public CtrlBuf(BufferBuilder inner, VertexFormat format) {
        this(inner, format, format == DefaultVertexFormats.POSITION_TEX
                || format == DefaultVertexFormats.POSITION_TEX_COLOR);
    }

    public CtrlBuf(BufferBuilder inner, VertexFormat format, boolean uv) {
        this.inner = inner;
        this.format = format;
        this.uv = uv;
    }

    public BufferBuilder vanilla() {
        return this.inner;
    }

    public int getVertexCount() {
        return this.inner.getVertexCount();
    }

    /** CE sets the count directly after writing raw ints; vanilla cannot, so this is a no-op. */
    public void setVertexCount(int vertexCount) {
    }

    private void vertex(float x, float y, float z, float u, float v, int packedColor) {
        this.inner.pos(x, y, z);
        if (this.uv) {
            this.inner.tex(u, v);
        }
        if (this.format.hasColor()) {
            this.inner.color((packedColor & 255) / 255.0F,
                    ((packedColor >> 8) & 255) / 255.0F,
                    ((packedColor >> 16) & 255) / 255.0F,
                    ((packedColor >>> 24) & 255) / 255.0F);
        }
        if (this.format.hasNormal()) {
            this.inner.normal(0.0F, 1.0F, 0.0F);
        }
        this.inner.endVertex();
    }

    public void appendPosition(float x, float y, float z) {
        this.vertex(x, y, z, 0.0F, 0.0F, 0xFFFFFFFF);
    }

    public void appendPositionUnchecked(float x, float y, float z) {
        this.vertex(x, y, z, 0.0F, 0.0F, 0xFFFFFFFF);
    }

    public void appendPositionColor(float x, float y, float z, int packedColor) {
        this.vertex(x, y, z, 0.0F, 0.0F, packedColor);
    }

    public void appendPositionColorUnchecked(float x, float y, float z, int packedColor) {
        this.vertex(x, y, z, 0.0F, 0.0F, packedColor);
    }

    public void appendPositionTex(float x, float y, float z, float u, float v) {
        this.vertex(x, y, z, u, v, 0xFFFFFFFF);
    }

    public void appendPositionTexUnchecked(float x, float y, float z, float u, float v) {
        this.vertex(x, y, z, u, v, 0xFFFFFFFF);
    }

    public void appendPositionTexColor(float x, float y, float z, float u, float v, int packedColor) {
        this.vertex(x, y, z, u, v, packedColor);
    }

    public void appendPositionTexColorUnchecked(float x, float y, float z, float u, float v, int packedColor) {
        this.vertex(x, y, z, u, v, packedColor);
    }

    public void appendPositionColorQuad(float x0, float y0, float z0,
                                        float x1, float y1, float z1,
                                        float x2, float y2, float z2,
                                        float x3, float y3, float z3,
                                        int packedColor) {
        this.vertex(x0, y0, z0, 0.0F, 0.0F, packedColor);
        this.vertex(x1, y1, z1, 0.0F, 0.0F, packedColor);
        this.vertex(x2, y2, z2, 0.0F, 0.0F, packedColor);
        this.vertex(x3, y3, z3, 0.0F, 0.0F, packedColor);
    }

    public void appendPositionColorQuadUnchecked(float x0, float y0, float z0,
                                                 float x1, float y1, float z1,
                                                 float x2, float y2, float z2,
                                                 float x3, float y3, float z3,
                                                 int packedColor) {
        this.appendPositionColorQuad(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, packedColor);
    }

    public void appendPositionTexQuadUnchecked(float x0, float y0, float z0, float u0, float v0,
                                               float x1, float y1, float z1, float u1, float v1,
                                               float x2, float y2, float z2, float u2, float v2,
                                               float x3, float y3, float z3, float u3, float v3) {
        this.vertex(x0, y0, z0, u0, v0, 0xFFFFFFFF);
        this.vertex(x1, y1, z1, u1, v1, 0xFFFFFFFF);
        this.vertex(x2, y2, z2, u2, v2, 0xFFFFFFFF);
        this.vertex(x3, y3, z3, u3, v3, 0xFFFFFFFF);
    }

    public void appendPositionTexColorQuadUnchecked(float x0, float y0, float z0, float u0, float v0, int c0,
                                                    float x1, float y1, float z1, float u1, float v1, int c1,
                                                    float x2, float y2, float z2, float u2, float v2, int c2,
                                                    float x3, float y3, float z3, float u3, float v3, int c3) {
        this.vertex(x0, y0, z0, u0, v0, c0);
        this.vertex(x1, y1, z1, u1, v1, c1);
        this.vertex(x2, y2, z2, u2, v2, c2);
        this.vertex(x3, y3, z3, u3, v3, c3);
    }

    public static int packColor(int red, int green, int blue, int alpha) {
        return ((alpha & 255) << 24) | ((blue & 255) << 16) | ((green & 255) << 8) | (red & 255);
    }

    public static int packColor(float red, float green, float blue, float alpha) {
        return packColor((int) (red * 255.0F), (int) (green * 255.0F), (int) (blue * 255.0F), (int) (alpha * 255.0F));
    }
}
