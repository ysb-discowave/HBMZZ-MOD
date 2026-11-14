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

package com.hbmzz.alert;

import com.hbmzz.network.NetworkHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

/**
 * Sends the facility record where it has to go.
 *
 * <p>Two paths, for two different problems. {@link #push} tells the whole server
 * - that is the "every display agrees" guarantee, and it runs on every change.
 * {@link #to} tells one player - that is the catch-up path a display uses so a
 * player who walks up to a screen that has been quiet for an hour still sees the
 * current code within a second instead of waiting for the next event.</p>
 */
public final class AlertSync {

    private AlertSync() {
    }

    /** Every connected player, in every dimension. */
    public static void push(World world, FacilityState state) {
        if (world == null || world.isRemote || state == null) {
            return;
        }
        if (NetworkHandler.INSTANCE == null) {
            return;
        }
        NetworkHandler.INSTANCE.sendToAll(new AlertSyncMessage(state, world.getTotalWorldTime()));
    }

    /** One player; used by a display for whoever is standing near it. */
    public static void to(EntityPlayerMP player, World world, FacilityState state) {
        if (player == null || world == null || state == null) {
            return;
        }
        NetworkHandler.sendTo(player, new AlertSyncMessage(state, world.getTotalWorldTime()));
    }
}
