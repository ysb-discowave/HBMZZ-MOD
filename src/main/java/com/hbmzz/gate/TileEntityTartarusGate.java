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

package com.hbmzz.gate;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The corner cell's memory: is a redstone signal holding the door?
 *
 * <h3>Why this exists at all</h3>
 * <p>The door's open state lives in the block states, so nine cells agree by
 * construction. What the blocks cannot remember is the <em>previous</em> signal
 * level, and without that a manual open would be undone by the next neighbour
 * change: an unpowered door with {@code open=true} looks exactly like a powered
 * door that has lost its signal, if all you know is the current level.</p>
 *
 * <p>So the corner keeps one boolean and re-reads the signal once a second.
 * Every cell forwards its own neighbour changes, which handles the edges; the
 * poll only exists for a signal that was already on when the chunk loaded.
 * Polling 676 cells more often than that would cost more than the door.</p>
 */
public class TileEntityTartarusGate extends TileEntity implements ITickable {

    private boolean powered;
    private int timer;

    public boolean isPowered() {
        return this.powered;
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        // Every cell forwards its own neighbour changes, so this poll is only a
        // safety net for a signal that appeared while the chunk was unloaded.
        // At 676 cells it is not something to run twice a second, let alone
        // twice a tick.
        if (++this.timer < 20) {
            return;
        }
        this.timer = 0;
        checkSignal(this.world, this.pos);
    }

    /**
     * Re-reads the signal and opens or closes the door if it changed.
     *
     * <p>Idempotent: a click that changes the door does not change the signal,
     * so the next poll does nothing. Only a real edge here moves the door.</p>
     */
    public void checkSignal(World world, BlockPos origin) {
        if (world == null || world.isRemote) {
            return;
        }
        boolean signal = BlockTartarusGate.powered(world, origin);
        if (signal == this.powered) {
            return;
        }
        this.powered = signal;
        BlockTartarusGate.applyOpen(world, origin, signal);
    }
}
