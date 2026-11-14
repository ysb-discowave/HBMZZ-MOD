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

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.alert.NukeSnapshot;
import com.hbmzz.block.BlockNukeMonitor;
import com.hbmzz.client.screen.NukeScreen;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.tile.TileEntityNukeMonitor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.EnumFacing;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a nuke monitor: one cell of wall, one device's worth of numbers.
 *
 * <p>The sample comes from the tile entity the packet handler filled; when there
 * is none, the panel paints its "no device" frame rather than going dark. That
 * distinction matters more than it looks: an unbound screen and a screen whose
 * device exploded both have no data, and a player needs to be able to tell which
 * one they are standing in front of.</p>
 */
public class RenderNukeMonitor extends TileEntitySpecialRenderer<TileEntityNukeMonitor> {

    private static final Map<TileEntityNukeMonitor, NukeScreen> CANVASES =
            new WeakHashMap<TileEntityNukeMonitor, NukeScreen>();
    private static final Map<TileEntityNukeMonitor, PanelTexture> TEXTURES =
            new WeakHashMap<TileEntityNukeMonitor, PanelTexture>();

    @Override
    public void render(TileEntityNukeMonitor te, double x, double y, double z,
                       float partialTicks, int destroyStage, float alpha) {
        if (te == null || te.getWorld() == null || !HBMZZConfig.liveScreen) {
            return;
        }
        EnumFacing facing = facingOf(te);
        if (facing == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        try {
            NukeScreen canvas = canvasFor(te);
            PanelTexture texture = textureFor(te);
            NukeSnapshot sample = te.getClientData();
            boolean live = !te.isStale() && sample != null;

            int state = sample == null ? NukeSnapshot.STATE_GONE : sample.state;
            int code = ClientAlertState.live() ? ClientAlertState.code() : 0;
            canvas.render(System.currentTimeMillis(),
                    sample != null && sample.hasDevice,
                    state,
                    sample == null ? 0 : sample.yield,
                    sample == null ? 0 : sample.fuse,
                    sample == null ? 0 : sample.countdown,
                    sample == null ? 0 : sample.x,
                    sample == null ? 0 : sample.y,
                    sample == null ? 0 : sample.z,
                    sample == null ? 0 : sample.dim,
                    sample != null && sample.hasController,
                    sample == null ? 0 : sample.cx,
                    sample == null ? 0 : sample.cy,
                    sample == null ? 0 : sample.cz,
                    sample == null ? 0 : sample.cdim,
                    code, live);
            texture.upload(canvas);

            double[] anchor = ConsoleRig.anchor(1, 1, facing);
            GlStateManager.translate(x + anchor[0], y + anchor[1], z + anchor[2]);
            GlStateManager.rotate(ConsoleRig.yawOf(facing), 0.0F, 1.0F, 0.0F);
            GlStateManager.disableCull();

            PanelRender.faces(ConsoleModel.build(1, 1, ConsoleRig.TILT_WALL, true, false));
            PanelRender.glass(texture.id(), ConsoleModel.panelCorners(1, 1, ConsoleRig.TILT_WALL));

            GlStateManager.enableCull();
        } catch (Throwable throwable) {
            HBMZZ.logger.error("Nuke monitor at " + te.getPos() + " failed to render", throwable);
        } finally {
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    private static NukeScreen canvasFor(TileEntityNukeMonitor te) {
        NukeScreen canvas = CANVASES.get(te);
        if (canvas == null) {
            canvas = new NukeScreen();
            CANVASES.put(te, canvas);
        }
        return canvas;
    }

    private static PanelTexture textureFor(TileEntityNukeMonitor te) {
        PanelTexture texture = TEXTURES.get(te);
        if (texture == null) {
            texture = new PanelTexture();
            TEXTURES.put(te, texture);
        }
        return texture;
    }

    private EnumFacing facingOf(TileEntityNukeMonitor te) {
        if (!te.hasWorld()) {
            return null;
        }
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (!(state.getBlock() instanceof BlockNukeMonitor)) {
            return null;
        }
        return state.getValue(BlockNukeMonitor.FACING);
    }

    public static void clearCaches() {
        for (PanelTexture texture : TEXTURES.values()) {
            try {
                texture.dispose();
            } catch (Throwable ignored) {
                // Nothing useful to do while tearing a world down.
            }
        }
        TEXTURES.clear();
        CANVASES.clear();
    }
}
