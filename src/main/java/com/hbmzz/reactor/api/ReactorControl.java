/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3.
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

package com.hbmzz.reactor.api;

import com.hbmzz.reactor.ReactorInput;
import com.hbmzz.reactor.ReactorOutput;
import com.hbmzz.reactor.ReactorSpec;
import com.hbmzz.reactor.ReactorState;

/**
 * The public control surface of a reactor - the {@code api} layer in GFBS terms.
 *
 * <p>Callers (a tile entity, a control panel, a computer bridge) talk only to
 * this interface. They never touch {@code ReactorMachine} or mutate a state.
 * That keeps the physics replaceable: a different core grade is a different
 * {@link ReactorSpec}, and a different control source is a different
 * implementation of this interface, without either leaking into the other.</p>
 */
public interface ReactorControl {

    /** The spec this core runs with. */
    ReactorSpec spec();

    /** Immutable copy of the current state. */
    ReactorState state();

    /** Immutable copy of the last observed output. */
    ReactorOutput output();

    /**
     * Supply this tick's external signals and advance the core by one tick.
     * Implementations must persist the returned state.
     */
    ReactorOutput tick(ReactorInput input);

    /** Request an emergency shutdown. Latches until the core is cool. */
    void scram();

    /** Return true once the core is spent and must be replaced. */
    boolean isSpent();

    /** Load a previously saved state. Used by the tile entity on readFromNBT. */
    void restore(ReactorState state);
}
