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

package com.hbmzz.ctrl;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * What a terminal talks to: a list of bound machines, one of them current.
 *
 * <p>There are two control panels in this mod and they do not share a single
 * class. One is this package's own ({@link CtrlPanel}, a 32x18 grid of widgets
 * with six fixed link slots); the other is the ported Cursed Edition console
 * ({@code com.hbmzz.cecp.ControlPanel}, a free-form board whose controls each
 * carry their own linker tags). Both, however, can hand an operator the same
 * four things: which machines are wired, which one is selected, what it can be
 * read for, and what it accepts.</p>
 *
 * <p>So the command language is written against this interface instead of
 * either panel. {@link TerminalShell} never learns which of the two it is
 * driving, which is also what lets it be tested with a fake host - no world, no
 * Minecraft, no NBT.</p>
 *
 * <p>Slots are positional and stable for the lifetime of one window; labels are
 * what the operator types ({@code A}, {@code B}... on this mod's panel, the
 * linker tag on the ported console).</p>
 */
public interface TerminalHost {

    /** How many machines this panel can address. */
    int slots();

    /** Short label for the slot strip, also accepted by {@code use}. */
    String label(int slot);

    /** Machine name, already annotated when it cannot be reached. */
    String name(int slot);

    boolean bound(int slot);

    /** Current target index, or -1 when nothing is selected. */
    int target();

    /**
     * Makes {@code slot} the target.
     *
     * @return true when the choice changed and the panel needs saving
     */
    boolean select(int slot);

    /** Data keys the machine reports, for completion and {@code keys}. */
    List<String> keys(int slot);

    /** Events the machine accepts, for completion and {@code events}. */
    List<String> events(int slot);

    /** Event name to the variable it carries, for completion and {@code set}. */
    Map<String, String> vars(int slot);

    /**
     * Reads the slot's machine now. Empty when it is gone or says nothing.
     *
     * <p>Live, not cached: a readout that lags a tick is fine on a dial and
     * useless in a terminal.</p>
     */
    Map<String, String> read(int slot);

    /**
     * Sends one event with its variables.
     *
     * <p>Returns how many machines took it, not just whether anything did: a
     * group slot covers a whole reactor, but one event usually belongs to one
     * kind of part in it, and "×4" is the answer to the only question the
     * operator has after pressing enter - how much did I just move.</p>
     *
     * @return machines written, 0 when none accepted the event
     */
    int fire(int slot, String event, Map<String, Object> vars);

    /** False when the machine bridge is missing (NTM not installed). */
    boolean available();

    /** Position of a bound machine, for the {@code ls} coordinate line. */
    BlockPos pos(int slot);

    /**
     * How many machines this slot addresses.
     *
     * <p>One for a plain machine, N for a {@link PartGroup} - the terminal says
     * "×N" on a write so the operator can see that one command moved the whole
     * reactor rather than a single rod.</p>
     */
    default int scope(int slot) {
        return 1;
    }

    /**
     * The named sub-parts of this slot, name to count; empty when it is one
     * machine.
     *
     * <p>This is the list a same-name broadcast works through, so the window can
     * answer "what will this command actually move" before it is typed.</p>
     */
    default Map<String, Integer> parts(int slot) {
        return java.util.Collections.emptyMap();
    }
}
