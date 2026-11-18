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

package com.hbmzz.reactor;

/**
 * The operating states of a reactor, and the only transitions that are legal.
 *
 * <p>This mirrors the shape of a research-reactor console (idle, ramp, steady,
 * scram, spent) rather than the staged RBMK blow-up in {@code com.hbmzz.rbmk}.
 * The two must not be confused: {@code rbmk} models one specific accident; this
 * package models the normal life cycle that any reactor core can be in.</p>
 */
public enum ReactorPhase {

    /** Cold, subcritical, producing nothing. Safe to approach and to load. */
    OFFLINE,
    /** Rods withdrawing, flux rising, heat still below the operating band. */
    STARTING,
    /** Steady operation: flux and heat inside the configured band. */
    RUNNING,
    /**
     * Emergency shutdown. Rods are driven fully in and the coolant loop is
     * forced open. Heat is still falling, so this is not the same as OFFLINE.
     */
    SCRAM,
    /**
     * Terminal: heat crossed the meltdown line. There is no transition out of
     * SPENT - a spent core is removed and replaced, never restarted.
     */
    SPENT;

    /** True while the core may still be hot enough to hurt. */
    public boolean isHot() {
        return this == STARTING || this == RUNNING || this == SCRAM;
    }

    /** True once the core is delivering power. */
    public boolean isActive() {
        return this == STARTING || this == RUNNING;
    }

    /** True when the core is safe to open. */
    public boolean isSafe() {
        return this == OFFLINE || this == SPENT;
    }

    /**
     * Legal transition check. The machine is deliberately stricter than a
     * plain enum comparison: a spent core can never be started again, and a
     * scram cannot be skipped by going straight back to RUNNING.
     */
    public boolean canTransitionTo(ReactorPhase next) {
        if (next == null || this == SPENT) {
            return false;
        }
        switch (this) {
            case OFFLINE:
                return next == STARTING || next == SCRAM;
            case STARTING:
                return next == RUNNING || next == SCRAM;
            case RUNNING:
                return next == SCRAM;
            case SCRAM:
                // After a scram the core must pass through OFFLINE before it
                // can be started again. Skipping this would let a tripped
                // reactor re-light while it is still cooling.
                return next == OFFLINE;
            default:
                return false;
        }
    }
}
