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
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * The slice of NTM: Cursed Edition's {@code com.hbm.render.NTMRenderHelper}
 * that the control panel GUI actually calls: textured rectangle blits with an
 * explicit UV window, plus a few float[4] rectangle predicates.
 *
 * <p>CE routes the blits through its batched immediate-mode buffers; here they
 * go straight to the vanilla {@link Tessellator} as {@code POSITION_TEX_COLOR}
 * quads, which produces the same pixels for these small GUI pieces.</p>
 */
@SideOnly(Side.CLIENT)
public final class GuiFx {

    private GuiFx() {
    }

    /**
     * Draws a textured quad.
     *
     * <p>Signature is CE's {@code NTMRenderHelper.drawGuiRect} verbatim: {@code w}
     * and {@code h} are the on-screen size, while {@code u2} / {@code v2} are the
     * <b>absolute</b> end of the UV window, not a width/height. Getting that wrong
     * is invisible on any rect that starts at {@code u = 0} and turns every other
     * one into a sample outside the sprite - on the node graph that painted the
     * whole block pure white.</p>
     */
    private static void rect(float x, float y, float u, float v, float w, float h,
                             float u2, float v2, float r, float g, float b, float a) {
        // Flush anything CtrlImm left open - the vanilla buffer refuses to be
        // begun twice.
        CtrlImm.INSTANCE.ensureIdle();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        buf.pos(x, y + h, 0.0D).tex(u, v2).color(r, g, b, a).endVertex();
        buf.pos(x + w, y + h, 0.0D).tex(u2, v2).color(r, g, b, a).endVertex();
        buf.pos(x + w, y, 0.0D).tex(u2, v).color(r, g, b, a).endVertex();
        buf.pos(x, y, 0.0D).tex(u, v).color(r, g, b, a).endVertex();
        tess.draw();
    }

    public static void drawGuiRect(float x, float y, float u, float v, float w, float h, float u2, float v2) {
        rect(x, y, u, v, w, h, u2, v2, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void drawGuiRectColor(float x, float y, float u, float v, float w, float h,
                                        float u2, float v2, float r, float g, float b, float a) {
        rect(x, y, u, v, w, h, u2, v2, r, g, b, a);
    }

    /**
     * CE's batched blit: the quad is appended to the batch the caller already
     * opened with {@code CtrlImm.beginPositionTexColorQuads(n)} and is drawn by
     * the caller's {@code draw()}. Upstream relies on that - a node frame is one
     * {@code begin(4)} followed by four of these - so drawing here instead would
     * flush the caller's batch early and paint each piece in its own draw call.
     * With no batch open it falls back to an immediate blit.
     */
    public static void drawGuiRectBatchedColor(float x, float y, float u, float v, float w, float h,
                                               float u2, float v2, float r, float g, float b, float a) {
        CtrlBuf buf = CtrlImm.INSTANCE.isBuilding() ? CtrlImm.INSTANCE.getCurrent() : null;
        if (buf == null) {
            rect(x, y, u, v, w, h, u2, v2, r, g, b, a);
            return;
        }
        int packed = CtrlBuf.packColor(r, g, b, a);
        buf.appendPositionTexColorQuadUnchecked(
                x, y + h, 0.0F, u, v2, packed,
                x + w, y + h, 0.0F, u2, v2, packed,
                x + w, y, 0.0F, u2, v, packed,
                x, y, 0.0F, u, v, packed);
    }

    /** {@code box} is {@code {minX, minY, maxX, maxY}}. */
    public static boolean intersects2DBox(float x, float y, float[] box) {
        if (box == null || box.length < 4) {
            return false;
        }
        return x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3];
    }

    public static float[] getBoxCenter(float[] box) {
        return new float[]{(box[0] + box[2]) * 0.5F, (box[1] + box[3]) * 0.5F};
    }

    public static boolean boxesOverlap(float[] a, float[] b) {
        return a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
    }

    public static boolean boxContainsOther(float[] outer, float[] inner) {
        return outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3];
    }
}
