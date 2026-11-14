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
import com.hbmzz.block.BlockAlertDisplay;
import com.hbmzz.client.screen.AlertScreen;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.tile.TileEntityAlertDisplay;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.EnumFacing;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws an accident display: a 1x1 wall panel showing the facility code.
 *
 * <p>Stateless apart from its canvas. Everything it paints comes from
 * {@link ClientAlertState}, which is the client's single copy of the facility
 * record - so two displays side by side are not two views of the same data that
 * could drift, they are literally the same three fields.</p>
 *
 * <p>Geometry is the DFC monitor's housing at 1x1 with the wall tilt: flat
 * against the wall, no ceiling bracket, because a one-cell panel bolted to a wall
 * does not need a rig and the rig would draw itself into the block above.</p>
 */
public class RenderAlertDisplay extends TileEntitySpecialRenderer<TileEntityAlertDisplay> {

    private static final Map<TileEntityAlertDisplay, AlertScreen> CANVASES =
            new WeakHashMap<TileEntityAlertDisplay, AlertScreen>();
    private static final Map<TileEntityAlertDisplay, PanelTexture> TEXTURES =
            new WeakHashMap<TileEntityAlertDisplay, PanelTexture>();

    @Override
    public void render(TileEntityAlertDisplay te, double x, double y, double z,
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
            AlertScreen canvas = canvasFor(te);
            PanelTexture texture = textureFor(te);
            long now = System.currentTimeMillis();
            net.minecraft.world.World world = te.getWorld();
            long worldTick = world == null ? ClientAlertState.nowTick() : world.getTotalWorldTime();
            canvas.render(now, ClientAlertState.code(), ClientAlertState.since(), worldTick,
                    ClientAlertState.source(), ClientAlertState.actor(),
                    ClientAlertState.historyCodes(), ClientAlertState.historyTicks(),
                    ClientAlertState.historySources(), ClientAlertState.historyCount(),
                    ClientAlertState.live());
            texture.upload(canvas);

            double[] anchor = ConsoleRig.anchor(1, 1, facing);
            GlStateManager.translate(x + anchor[0], y + anchor[1], z + anchor[2]);
            GlStateManager.rotate(ConsoleRig.yawOf(facing), 0.0F, 1.0F, 0.0F);
            GlStateManager.disableCull();

            PanelRender.faces(ConsoleModel.build(1, 1, ConsoleRig.TILT_WALL, true, false));
            PanelRender.glass(texture.id(), ConsoleModel.panelCorners(1, 1, ConsoleRig.TILT_WALL));

            GlStateManager.enableCull();
        } catch (Throwable throwable) {
            // A renderer that throws takes the client down with it; a panel that
            // skips one frame does not.
            HBMZZ.logger.error("Alert display at " + te.getPos() + " failed to render", throwable);
        } finally {
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    private static AlertScreen canvasFor(TileEntityAlertDisplay te) {
        AlertScreen canvas = CANVASES.get(te);
        if (canvas == null) {
            canvas = new AlertScreen();
            CANVASES.put(te, canvas);
        }
        return canvas;
    }

    private static PanelTexture textureFor(TileEntityAlertDisplay te) {
        PanelTexture texture = TEXTURES.get(te);
        if (texture == null) {
            texture = new PanelTexture();
            TEXTURES.put(te, texture);
        }
        return texture;
    }

    private EnumFacing facingOf(TileEntityAlertDisplay te) {
        if (!te.hasWorld()) {
            return null;
        }
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (!(state.getBlock() instanceof BlockAlertDisplay)) {
            return null;
        }
        return state.getValue(BlockAlertDisplay.FACING);
    }

    /** Releases every texture; called when the client leaves a world. */
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
