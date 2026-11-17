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

/**
 * A block entity that owns terminal controls and can be driven by typed lines.
 *
 * <p>{@link TerminalHost} is what one terminal <em>talks to</em> - one bound
 * machine, or a group of them. This is the other end: what a block entity has
 * to offer so that the terminal packet has somewhere to deliver a line, and so
 * that the window can seed its slot strip before the server answers.</p>
 *
 * <p>It exists because the two boards that carry terminals share no class at
 * all: this package's own grid panel ({@code CtrlPanel}) and the ported Cursed
 * Edition console. The packet handler and the window therefore talk to this
 * interface rather than to either tile entity, which keeps the handlers free of
 * any one board's internals - and, when the console moves out, keeps them from
 * mentioning it altogether.</p>
 *
 * <p>The last two methods are the window's half. Seeding a {@link SnapshotHost}
 * is the console's job because only it knows how a control exposes its linker
 * tags; the window only needs the strip to be non-empty for the frame before the
 * server's reply arrives.</p>
 */
public interface TerminalTile {

    /**
     * Runs one line against control {@code index}.
     *
     * <p>Server side only: the client sends text, never a machine, so the line
     * has to be parsed against the world the server owns.</p>
     */
    TerminalShell.Result runTerminal(int index, String line, TerminalShell.Sink sink);

    /**
     * The terminal view of control {@code index}, or {@code null} when that
     * control is not a terminal.
     */
    TerminalHost terminalHost(int index);

    /** True when control {@code index} exists and is a terminal. */
    boolean hasTerminalControl(int index);

    /**
     * Fills {@code snapshot} from the local board copy, for the window's first
     * frame. A no-op when {@code index} is not a terminal.
     */
    void seedTerminalSnapshot(int index, SnapshotHost snapshot);
}
