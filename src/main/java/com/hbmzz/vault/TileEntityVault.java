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

package com.hbmzz.vault;

import com.hbmzz.alert.AlertCode;
import com.hbmzz.alert.FacilityState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The corner cell's memory: which facility state opens the door, and whether it
 * is open right now.
 *
 * <h3>Driven by the facility, not by redstone</h3>
 * <p>This door is not a switch. There is no signal to hold and no click that
 * flips it; the leaf is driven by the save-wide {@link FacilityState} code. The
 * tile entity polls that record once a second and swings the leaf open whenever
 * the facility is at or past the door's open condition, and shut again when it
 * drops below it. A plain right-click does nothing, so the only thing that
 * reaches the door by hand is the axe, and only to re-tune that condition.</p>
 *
 * <h3>Why it polls at all</h3>
 * <p>Nothing else watches this door. The facility code is latched, not timed -
 * nothing raises a second event when a code goes up - so the door cannot learn
 * the change from a neighbour update or a signal edge. The poll is the only
 * thing that will notice "the code is now at RED" on its own. One second is
 * fine: the door clanks, it does not need to react in a tick.</p>
 *
 * <h3>Why the last state is remembered</h3>
 * <p>{@link BlockVault#applyOpen} is idempotent, so re-applying the same state
 * every poll would just re-clank a door that has not moved. Remembering
 * whether the door is currently open is what lets the poll do nothing unless
 * the answer actually changed.</p>
 */
public class TileEntityVault extends TileEntity implements ITickable {

    /**
     * Facility code level (0..7) at or above which the door is open. Default
     * RED: the leaf swings open the moment the facility reports a major
     * accident, and closes again once the code is cleared below it. A value of
     * 0 (GREEN) means "always open"; the axe is what changes this.
     */
    private int openCondition = AlertCode.RED.id();

    /** Whether the leaf is open as the door last saw it. -1 until first read. */
    private int lastOpen = -1;

    private int timer;

    public int getOpenCondition() {
        return this.openCondition;
    }

    /** Re-tunes the open condition; clamped to the eight codes. */
    public void setOpenCondition(int level) {
        this.openCondition = Math.max(0, Math.min(AlertCode.count() - 1, level));
        // A new condition may mean the door should move now; force the next
        // poll to re-evaluate rather than waiting out the edge.
        this.lastOpen = -1;
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (++this.timer < 20) {
            return;
        }
        this.timer = 0;
        check(this.world, this.pos);
    }

    /** Re-reads the facility code and moves the door if the answer changed. */
    public void check(World world, BlockPos origin) {
        if (world == null || world.isRemote) {
            return;
        }
        FacilityState state = FacilityState.get(world);
        int severity = state == null ? 0 : state.alert().severity();
        // The door is open at or above its condition. Condition 0 (GREEN) means
        // it is always open, which is the "leave it ajar" setting, not a bug.
        int desired = severity >= this.openCondition ? 1 : 0;
        if (desired == this.lastOpen) {
            return;
        }
        this.lastOpen = desired;
        EnumFacing facing = BlockVault.facingOf(world, origin);
        if (facing == null) {
            return;
        }
        BlockVault.applyOpen(world, origin, facing, desired != 0);
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        this.openCondition = nbt.getInteger("openCondition");
        if (this.openCondition < 0 || this.openCondition >= AlertCode.count()) {
            this.openCondition = AlertCode.RED.id();
        }
        this.lastOpen = nbt.getInteger("lastOpen");
        this.timer = 0;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("openCondition", this.openCondition);
        nbt.setInteger("lastOpen", this.lastOpen);
        return nbt;
    }
}
