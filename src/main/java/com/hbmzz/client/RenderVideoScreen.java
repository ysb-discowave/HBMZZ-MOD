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

package com.hbmzz.client;

import com.hbmzz.client.video.VideoPlayer;
import com.hbmzz.client.video.VideoScreenPlayback;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.tile.TileEntityVideoScreen;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.EnumFacing;

/**
 * Draws the picture on the wall.
 *
 * <h3>Orientation: one decision, made once</h3>
 * <p>The block model supplies the frame; this renderer supplies a single textured
 * quad on top of it. Everything about <em>which way round</em> that quad goes is
 * taken from {@link ConsoleRig}:</p>
 * <ul>
 *   <li>{@code anchor(cols, rows, facing)} moves the matrix to the wall's
 *       geometric centre - the controller cell is the top-left cell, so a wall is
 *       never centred on the block it was placed from.</li>
 *   <li>{@code yawOf(facing)} rotates the local frame so local -z faces the
 *       viewer, for all four facings.</li>
 *   <li>{@code panelUv()} supplies the image's UV corners, and its u values run
 *       <b>backwards</b> on purpose: the viewer's right is local -x, so image
 *       column 0 belongs on the +x edge.</li>
 * </ul>
 *
 * <p>Skipping any of the three is how a wall ends up mirrored on two facings and
 * upside down on the other two - the exact bug that made an earlier version of
 * this renderer look "right" from the north and inside-out from everywhere
 * else.</p>
 */
public class RenderVideoScreen extends TileEntitySpecialRenderer<TileEntityVideoScreen> {

    /** Distance the picture is lifted off the frame, so the two never z-fight. */
    private static final float SURFACE = 0.502F;
    /** Margin kept inside the cell edges, in blocks, to leave the bezel visible. */
    private static final float INSET = 0.02F;

    @Override
    public void render(TileEntityVideoScreen te, double x, double y, double z,
                       float partialTicks, int destroyStage, float alpha) {
        if (te == null || te.getWorld() == null) {
            return;
        }
        EnumFacing facing = facingOf(te);
        if (facing == null) {
            return;
        }
        VideoPlayer player = VideoScreenPlayback.active(te);
        int texture = player == null ? 0 : player.textureId();
        if (texture <= 0) {
            return;
        }
        int cols = Math.max(1, te.getCols());
        int rows = Math.max(1, te.getRows());

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        try {
            double[] anchor = ConsoleRig.anchor(cols, rows, facing);
            GlStateManager.translate(x + anchor[0], y + anchor[1], z + anchor[2]);
            GlStateManager.rotate(ConsoleRig.yawOf(facing), 0.0F, 1.0F, 0.0F);
            GlStateManager.disableCull();

            // Counter-clockwise seen from the front. The listener stands outside
            // the face looking along +z, and local +x is their left.
            float hw = cols * 0.5F - INSET;
            float hh = rows * 0.5F - INSET;
            float[][] corners = {
                    {hw, -hh, -SURFACE},
                    {-hw, -hh, -SURFACE},
                    {-hw, hh, -SURFACE},
                    {hw, hh, -SURFACE}};
            PanelPainter.glass(corners, texture, 1.0F);

            GlStateManager.enableCull();
        } catch (Throwable throwable) {
            // A renderer that throws takes the client down with it; a wall that
            // misses one frame does not.
            com.hbmzz.HBMZZ.logger.error("video screen at " + te.getPos() + " failed to render", throwable);
        } finally {
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    /** Releases every wall decoder; called when the client leaves a world. */
    public static void clearCaches() {
        VideoScreenPlayback.clear();
    }

    private static EnumFacing facingOf(TileEntityVideoScreen te) {
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (!(state.getBlock() instanceof com.hbmzz.block.BlockVideoScreen)) {
            return null;
        }
        return state.getValue(com.hbmzz.block.BlockVideoScreen.FACING);
    }
}
