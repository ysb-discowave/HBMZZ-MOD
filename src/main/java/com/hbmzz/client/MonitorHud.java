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

import com.hbmzz.HBMZZConfig;
import com.hbmzz.dfc.DFCSnapshot;
import com.hbmzz.tile.TileEntityDFCMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Locale;

/**
 * Compact readout that appears while the crosshair is on a monitor, so the
 * numbers can be checked without right clicking (and without losing the item
 * you are holding).
 */
public class MonitorHud {

    private static final int WIDTH = 152;
    private static final int HEIGHT = 62;
    private static final int PAD = 6;

    private float alpha;

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        TileEntityDFCMonitor monitor = targeted(mc);
        float target = monitor == null || !HBMZZConfig.hudOverlay ? 0.0F : 1.0F;
        this.alpha += (target - this.alpha) * 0.25F;
        if (this.alpha < 0.02F || monitor == null) {
            return;
        }
        try {
            draw(mc, monitor, event.getResolution());
        } catch (Throwable throwable) {
            // Never let a readout glitch kill the client.
            this.alpha = 0.0F;
        }
    }

    private TileEntityDFCMonitor targeted(Minecraft mc) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null
                || mc.gameSettings.hideGUI || mc.gameSettings.showDebugInfo) {
            return null;
        }
        RayTraceResult hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        double range = (double) HBMZZConfig.hudRange;
        if (mc.player.getDistanceSqToCenter(pos) > range * range) {
            return null;
        }
        TileEntity te = mc.world.getTileEntity(pos);
        return te instanceof TileEntityDFCMonitor ? (TileEntityDFCMonitor) te : null;
    }

    private void draw(Minecraft mc, TileEntityDFCMonitor monitor, ScaledResolution resolution) {
        FontRenderer fr = mc.fontRenderer;
        float a = Math.max(0.0F, Math.min(1.0F, this.alpha));
        int x = 6;
        int y = 6;

        // card
        Gui.drawRect(x, y, x + WIDTH, y + HEIGHT, MonitorPalette.alpha(MonitorPalette.BG_OUTER, a * 0.92F));
        Gui.drawRect(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, MonitorPalette.alpha(MonitorPalette.BG, a * 0.95F));
        Gui.drawRect(x, y, x + WIDTH, y + 1, MonitorPalette.alpha(MonitorPalette.BORDER, a));
        Gui.drawRect(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, MonitorPalette.alpha(MonitorPalette.BORDER_DIM, a));
        Gui.drawRect(x, y, x + 1, y + HEIGHT, MonitorPalette.alpha(MonitorPalette.BORDER_DIM, a));
        Gui.drawRect(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, MonitorPalette.alpha(MonitorPalette.BORDER_DIM, a));

        DFCSnapshot d = monitor.getClientData();
        boolean stale = monitor.isStale() || d == null;

        int title = MonitorPalette.alpha(MonitorPalette.GREEN, a);
        fr.drawStringWithShadow("DFC MONITOR", x + PAD, y + 5, title);

        String status;
        int statusColor;
        if (stale) {
            status = "NO LINK";
            statusColor = MonitorPalette.AMBER;
        } else if (d.predictionMode == 1) {
            status = "CRITICAL";
            statusColor = MonitorPalette.RED;
        } else if (!d.coreFound) {
            status = "NO CORE";
            statusColor = MonitorPalette.AMBER;
        } else {
            status = "LINK OK";
            statusColor = MonitorPalette.GREEN;
        }
        fr.drawStringWithShadow(status, x + WIDTH - PAD - fr.getStringWidth(status), y + 5,
                MonitorPalette.alpha(statusColor, a));

        Gui.drawRect(x + PAD, y + 16, x + WIDTH - PAD, y + 17, MonitorPalette.alpha(MonitorPalette.BORDER_DIM, a));

        if (stale) {
            fr.drawStringWithShadow("bind a core with the link cable", x + PAD, y + 24,
                    MonitorPalette.alpha(MonitorPalette.MUTED, a));
            fr.drawStringWithShadow("right click for the terminal", x + PAD, y + 36,
                    MonitorPalette.alpha(MonitorPalette.MUTED, a));
            return;
        }

        bar(fr, x + PAD, x + WIDTH - PAD, y + 22, "TEMP", String.format(Locale.ROOT, "%.0fC", d.temperature),
                d.meltingPoint <= 0.0D ? 0.0D : d.temperature / d.meltingPoint,
                d.temperature >= d.meltingPoint * 0.85D ? MonitorPalette.RED : MonitorPalette.AMBER, a);
        bar(fr, x + PAD, x + WIDTH - PAD, y + 34, "ENERGY", shortEnergy(d.containedEnergy),
                d.containedEnergy / 100000.0D, MonitorPalette.CYAN, a);
        bar(fr, x + PAD, x + WIDTH - PAD, y + 46, "STAB", String.format(Locale.ROOT, "%.0f%%", d.stabilization * 100.0D),
                d.stabilization, MonitorPalette.GREEN, a);
    }

    private void bar(FontRenderer fr, int left, int right, int y, String label, String value,
                     double fraction, int color, float a) {
        fr.drawStringWithShadow(label, left, y, MonitorPalette.alpha(MonitorPalette.MUTED, a));
        fr.drawStringWithShadow(value, right - fr.getStringWidth(value), y,
                MonitorPalette.alpha(color, a));

        int barX = left + 46;
        int barW = right - fr.getStringWidth(value) - 4 - barX;
        if (barW < 10) {
            barW = 10;
        }
        Gui.drawRect(barX, y + 4, barX + barW, y + 8, MonitorPalette.alpha(MonitorPalette.TRACK, a));
        double clamped = Math.max(0.0D, Math.min(1.0D, fraction));
        Gui.drawRect(barX, y + 4, barX + (int) (barW * clamped), y + 8, MonitorPalette.alpha(color, a * 0.9F));
    }

    private static String shortEnergy(double value) {
        if (Math.abs(value) >= 1.0E9D) {
            return String.format(Locale.ROOT, "%.1fG", value / 1.0E9D);
        }
        if (Math.abs(value) >= 1.0E6D) {
            return String.format(Locale.ROOT, "%.1fM", value / 1.0E6D);
        }
        if (Math.abs(value) >= 1.0E3D) {
            return String.format(Locale.ROOT, "%.1fk", value / 1.0E3D);
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
