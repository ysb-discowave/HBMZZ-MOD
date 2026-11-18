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

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * Blend-state queries lifted from NTM: Cursed Edition's {@code com.hbm.util.RenderUtil}.
 * The control panel GUI needs to know whether blending was already on before it
 * flips it for its own overlays, so it can put the state back afterwards.
 */
@SideOnly(Side.CLIENT)
public final class RenderUtil {

    private RenderUtil() {
    }

    public static boolean isBlendEnabled() {
        return GL11.glIsEnabled(GL11.GL_BLEND);
    }

    public static int getBlendSrcFactor() {
        return GL11.glGetInteger(GL11.GL_BLEND_SRC);
    }

    public static int getBlendDstFactor() {
        return GL11.glGetInteger(GL11.GL_BLEND_DST);
    }

    public static void restoreBlend(boolean enabled, int src, int dst) {
        if (enabled) {
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(src, dst);
        } else {
            GlStateManager.disableBlend();
        }
        GlStateManager.enableAlpha();
        OpenGlHelper.glBlendFunc(src, dst, GL11.GL_ONE, GL11.GL_ZERO);
    }
}
