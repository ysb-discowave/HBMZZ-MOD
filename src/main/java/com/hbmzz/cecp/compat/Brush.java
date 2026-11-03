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
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * Stand-in for the addon's {@code com.leafia.dev.LeafiaBrush}.
 *
 * <p>Leafia's brush is a thin wrapper over the vanilla {@link Tessellator} that
 * adds the vertex helpers CE's instrument code is written against. The field
 * {@link #buf} is exposed because a few instruments (the RBMK grid) write
 * straight into the underlying builder.</p>
 *
 * <p>Single instance, like upstream: {@code start*} opens a batch, the
 * {@code addVertex*} calls append, {@link #draw()} flushes.</p>
 */
@SideOnly(Side.CLIENT)
public final class Brush {

    public static final Brush instance = new Brush();

    public BufferBuilder buf;

    public void startDrawingQuads() {
        startDrawing(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
    }

    public void startDrawing(int mode, VertexFormat format) {
        buf = Tessellator.getInstance().getBuffer();
        buf.begin(mode, format);
    }

    public void addVertexWithUV(double x, double y, double z, double u, double v) {
        buf.pos(x, y, z).tex(u, v).endVertex();
    }

    public void addVertexWithColor(double x, double y, double z, float r, float g, float b, float a) {
        buf.pos(x, y, z).color(r, g, b, a).endVertex();
    }

    /** Plain position, for POSITION / POSITION_COLOR batches. */
    public void addVertex(double x, double y, double z) {
        buf.pos(x, y, z).endVertex();
    }

    public void draw() {
        Tessellator.getInstance().draw();
        buf = null;
    }
}
