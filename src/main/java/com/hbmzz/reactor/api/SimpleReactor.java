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
import com.hbmzz.reactor.ReactorMachine;
import com.hbmzz.reactor.ReactorOutput;
import com.hbmzz.reactor.ReactorSpec;
import com.hbmzz.reactor.ReactorState;

/**
 * Reference {@link ReactorControl} implementation: one core, one spec, no world.
 *
 * <p>This is the object a tile entity keeps. It holds the mutable handle
 * (the current {@link ReactorState} and the last output) while all decisions
 * stay in the pure machine. Splitting them this way means the game-facing
 * object can be as stateful as it likes without contaminating the rules.</p>
 */
public class SimpleReactor implements ReactorControl {

    private final ReactorSpec spec;
    private ReactorState state;
    private ReactorOutput output;
    /** Latched by {@link #scram()}; stays true until the core is back OFFLINE. */
    private boolean scramLatched;

    public SimpleReactor(ReactorSpec spec) {
        this.spec = spec == null ? ReactorSpec.DEFAULT : spec;
        this.state = ReactorState.COLD;
        this.output = ReactorMachine.observe(this.state, ReactorInput.idle(), this.spec);
    }

    @Override
    public ReactorSpec spec() {
        return this.spec;
    }

    @Override
    public ReactorState state() {
        return this.state;
    }

    @Override
    public ReactorOutput output() {
        return this.output;
    }

    @Override
    public ReactorOutput tick(ReactorInput input) {
        ReactorInput in = input == null ? ReactorInput.idle() : input;
        // A latched scram outranks any later "start" command from the panel.
        // Only a full cool-down clears it; see ReactorRules.SCRAM recovery.
        boolean wantScram = this.scramLatched || in.scram;
        if (wantScram) {
            in = new ReactorInput(in.rod, in.coolant, in.fuel, in.start, true);
        }
        this.state = ReactorMachine.step(this.state, in, this.spec);
        this.output = ReactorMachine.observe(this.state, in, this.spec);
        if (this.state.phase == com.hbmzz.reactor.ReactorPhase.OFFLINE) {
            this.scramLatched = false;
        }
        if (this.output.scramming) {
            this.scramLatched = true;
        }
        return this.output;
    }

    @Override
    public void scram() {
        this.scramLatched = true;
    }

    @Override
    public boolean isSpent() {
        return this.state.phase == com.hbmzz.reactor.ReactorPhase.SPENT;
    }

    @Override
    public void restore(ReactorState saved) {
        this.state = saved == null ? ReactorState.COLD : saved;
        this.output = ReactorMachine.observe(this.state, ReactorInput.idle(), this.spec);
        // Never restore a running core into a forgotten scram: a trip is a
        // physical state of the hardware, not something the save file undoes.
        this.scramLatched = this.state.phase == com.hbmzz.reactor.ReactorPhase.SCRAM;
    }
}
