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

package com.hbmzz.panel;

import com.hbmzz.dfc.DFCSnapshot;

/**
 * Evaluates a {@link LogicProgram} against live telemetry.
 *
 * <p>Runs on the server only. That is not an implementation detail: the alarm
 * has to be heard by everyone standing near the panel, the redstone line has to
 * be real, and two players must never see different alarms for the same
 * monitor. Clients are told the result, they never compute it.</p>
 *
 * <p>Per-rule state (cooldown, edge memory, check clock) lives here rather than
 * in the rule itself, because it is runtime, not design - saving it would mean
 * a reloaded world thinks a rule is still cooling down from a session that
 * ended yesterday.</p>
 *
 * <h3>The five triggers</h3>
 * <ul>
 *   <li><b>周期检测</b> - every N ticks, if the conditions hold. Level
 *       triggered: stays true while the core is hot.</li>
 *   <li><b>数值穿越</b> - once, on the tick the conditions go false to true.
 *       This is the one to use for "log the crossing" rules.</li>
 *   <li><b>坍缩开始</b> - once, on the rising edge of the collapse flag.</li>
 *   <li><b>数据中断</b> - once, when the telemetry feed goes stale.</li>
 *   <li><b>开机时</b> - once, when the screen powers on or a save loads.</li>
 * </ul>
 */
public final class PanelEngine {

    /** How often the whole program is looked at, in ticks. */
    public static final int EVAL_TICKS = 10;

    /** Where fired actions go. Implemented by the tile entity. */
    public interface Effect {
        void alarm(int level, String text, int ticks);

        void banner(String text, int ticks);

        void sound(double pitch, int ticks);

        void page(int page);

        void log(String text);

        void redstone(int strength, int ticks);

        void clearAlarm();
    }

    private int[] cooldown = new int[0];
    private int[] due = new int[0];
    private boolean[] armed = new boolean[0];
    private int size;

    private boolean bootPending = true;
    private boolean prevCollapsing;
    private boolean prevFresh = true;
    /**
     * First pass after a reset only records edges. Without it every reload
     * would fire "坍缩开始" and "数据中断" once, because the engine starts out
     * believing the core was calm and the link was up.
     */
    private boolean firstPass = true;

    /** Forget everything: used when the program is replaced or the screen boots. */
    public void reset() {
        for (int i = 0; i < this.size; i++) {
            this.cooldown[i] = 0;
            this.due[i] = 0;
            this.armed[i] = false;
        }
        this.bootPending = true;
        this.prevCollapsing = false;
        this.prevFresh = true;
        this.firstPass = true;
    }

    /** Marks the "开机时" trigger as pending, without touching rule state. */
    public void markBoot() {
        this.bootPending = true;
    }

    private void ensure(int needed) {
        if (this.cooldown.length >= needed) {
            this.size = needed;
            return;
        }
        int capacity = Math.max(needed, this.cooldown.length * 2 + 4);
        this.cooldown = java.util.Arrays.copyOf(this.cooldown, capacity);
        this.due = java.util.Arrays.copyOf(this.due, capacity);
        this.armed = java.util.Arrays.copyOf(this.armed, capacity);
        this.size = needed;
    }

    /**
     * One pass. {@code snapshot} may be null when the link is down; rules that
     * need data simply do not match, while 数据中断 rules still fire.
     *
     * @param fresh true when {@code snapshot} is a current sample
     */
    public void tick(LogicProgram program, DFCSnapshot snapshot, boolean fresh, Effect sink) {
        if (program == null) {
            return;
        }
        ensure(program.rules.size());
        boolean collapsing = snapshot != null && snapshot.collapsing;

        for (int i = 0; i < program.rules.size(); i++) {
            Rule rule = program.rules.get(i);
            if (!rule.enabled) {
                this.armed[i] = false;
                continue;
            }
            if (this.cooldown[i] > 0) {
                this.cooldown[i] -= EVAL_TICKS;
            }
            boolean met = snapshot != null && rule.matches(snapshot);
            boolean fire = false;

            switch (rule.trigger) {
                case Rule.TRIGGER_BOOT:
                    fire = this.bootPending;
                    break;
                case Rule.TRIGGER_COLLAPSE:
                    fire = collapsing && !this.prevCollapsing && !this.firstPass;
                    break;
                case Rule.TRIGGER_LINK:
                    fire = !fresh && this.prevFresh && !this.firstPass;
                    break;
                case Rule.TRIGGER_EDGE:
                    fire = met && !this.armed[i];
                    break;
                case Rule.TRIGGER_INTERVAL:
                default:
                    this.due[i] -= EVAL_TICKS;
                    if (this.due[i] > 0) {
                        this.armed[i] = met;
                        continue;
                    }
                    // While the conditions are false the timer is reset to a
                    // single pass instead of a full interval: the point of the
                    // interval is to space out *repeats* of a standing alarm,
                    // not to make a cold core that heats up mid-cycle wait out
                    // the rest of the cycle before anyone hears about it.
                    this.due[i] = met ? Math.max(EVAL_TICKS, rule.interval) : EVAL_TICKS;
                    fire = met;
                    break;
            }

            if (fire && this.cooldown[i] <= 0) {
                this.cooldown[i] = Math.max(0, rule.cooldown) * 20;
                run(rule, sink);
            }
            this.armed[i] = met;
        }

        this.bootPending = false;
        this.prevCollapsing = collapsing;
        this.prevFresh = fresh;
        this.firstPass = false;
    }

    private void run(Rule rule, Effect sink) {
        for (Action action : rule.actions) {
            switch (action.type) {
                case ALARM:
                    sink.alarm((int) Math.max(1.0D, Math.min(2.0D, action.amount)),
                            action.text, (int) (action.seconds * 20.0D));
                    break;
                case BANNER:
                    sink.banner(action.text, (int) (action.seconds * 20.0D));
                    break;
                case SOUND:
                    sink.sound(action.amount > 5.0D ? 1.6D : 1.0D, (int) (action.seconds * 20.0D));
                    break;
                case PAGE:
                    sink.page(action.page);
                    break;
                case LOG:
                    sink.log(action.text);
                    break;
                case REDSTONE:
                    sink.redstone((int) Math.max(1.0D, Math.min(15.0D, action.amount)),
                            (int) (action.seconds * 20.0D));
                    break;
                case CLEAR:
                    sink.clearAlarm();
                    break;
                default:
                    break;
            }
        }
    }
}
