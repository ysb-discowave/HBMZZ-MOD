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
 * Stand-in for NTM: Cursed Edition's {@code com.hbm.render.util.NTMImmediate}.
 *
 * <p>CE keeps one reusable, pre-allocated {@code BufferBuilder} and starts it
 * with a vertex format chosen per primitive type; the GUI code then appends
 * vertices and calls {@link #draw()} once. This shim keeps exactly that shape
 * on top of the vanilla {@link Tessellator}.</p>
 *
 * <p>Ported from NTM: Cursed Edition (com.hbm.render.util.NTMImmediate).</p>
 */
@SideOnly(Side.CLIENT)
public class CtrlImm {

    public static final CtrlImm INSTANCE = new CtrlImm();

    private CtrlBuf current;

    /**
     * True between {@code begin*} and {@code draw()}. The vanilla
     * {@link BufferBuilder} throws {@code IllegalStateException("Already
     * building!")} if a second batch starts before the first is drawn, and the
     * ported control code is not written to avoid that - some paths blit a GUI
     * rect or a model part from inside what looks like one batch. Tracking the
     * state here lets every entry point flush the pending vertices first
     * instead of taking the client down.
     */
    private boolean building;

    public CtrlBuf beginPositionQuads(int quads) {
        return begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
    }

    public CtrlBuf beginPosition(int quads) {
        return begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
    }

    /** Mode-taking variant, used by the node graph's line strip. */
    public CtrlBuf beginPosition(int mode, int expectedVertices) {
        return begin(mode, DefaultVertexFormats.POSITION);
    }

    public CtrlBuf beginPositionColor(int quads) {
        return begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
    }

    public CtrlBuf beginPositionTex(int quads) {
        return begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
    }

    public CtrlBuf beginPositionTexQuads(int quads) {
        return begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
    }

    public CtrlBuf beginPositionTexColorQuads(int quads) {
        return begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
    }

    private CtrlBuf begin(int mode, VertexFormat format) {
        ensureIdle();
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(mode, format);
        this.building = true;
        this.current = new CtrlBuf(bb, format);
        return this.current;
    }

    public CtrlBuf getCurrent() {
        return this.current;
    }

    /**
     * The buffer a batch is currently writing into. CE reaches for
     * {@code Tessellator.getBuffer()} and appends directly; this is the same
     * thing, wrapped so the append helpers stay available.
     *
     * <p>Callers are supposed to have opened a batch already (the node graph
     * does {@code beginPosition(GL_LINES, 0)} before walking its connections).
     * If nothing is open - a stale wrapper from the previous frame would be
     * unwritable - open a line batch rather than hand back a dead buffer.</p>
     */
    public CtrlBuf current() {
        if (this.current == null || !this.building) {
            return begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        }
        return this.current;
    }

    /** True while a batch is open and not yet drawn. */
    public boolean isBuilding() {
        return this.building;
    }

    /** Flushes a half-finished batch, if there is one. Cheap when there is not. */
    public void ensureIdle() {
        if (this.building) {
            this.building = false;
            Tessellator.getInstance().draw();
        }
    }

    public void draw() {
        if (this.building) {
            this.building = false;
            Tessellator.getInstance().draw();
        }
    }
}
