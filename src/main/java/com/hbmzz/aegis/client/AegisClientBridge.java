/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.aegis.client;

import com.hbmzz.aegis.AegisTelemetry;
import com.hbmzz.aegis.TileAegisTerminal;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Physical-client-only entry point for Aegis telemetry packets, invoked by the
 * network handler through reflection so the handler class stays loadable on a
 * dedicated server.
 */
public final class AegisClientBridge {

    private AegisClientBridge() {
    }

    public static void onData(BlockPos pos, AegisTelemetry frame) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            World world = mc.world;
            if (world == null || pos == null || frame == null) {
                return;
            }
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileAegisTerminal) {
                ((TileAegisTerminal) te).accept(frame);
            }
        });
    }

    /** Called on a world unload to release per-terminal GL textures. */
    public static void clearTextures() {
        AegisRenderer.clearCaches();
    }
}
