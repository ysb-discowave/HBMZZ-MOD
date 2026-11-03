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
 * The kind of thing bolted to a machine control panel.
 *
 * <p>Split into two families, because they are wired in opposite directions:
 * an <b>operator</b> control ({@link #KNOB}, {@link #SWITCH}, ...) is something
 * a player turns, and its job is to push a value <em>into</em> a machine; a
 * <b>readout</b> ({@link #LAMP}, {@link #SEG7}, ...) is something that shows a
 * value <em>from</em> a machine. A control is never both: a knob that also
 * displayed a number would need two bindings and would confuse "who owns this
 * value". That is why a real panel has a knob next to a gauge rather than one
 * widget doing both.</p>
 *
 * <p>This mirrors the control set of NTM: Cursed Edition's control panel
 * ({@code com.hbm.inventory.control_panel.controls}), re-expressed on this mod's
 * own canvas renderer - see the attribution section in the README.</p>
 */
public enum CtrlType {

    // ---- readouts ---------------------------------------------------------
    LABEL("标签", false, 6, 1, 3, 1),
    LAMP("指示灯", false, 2, 2, 1, 1),
    LAMP_RGB("三色指示灯", false, 2, 2, 1, 1),
    SEG7("数码管", false, 5, 2, 3, 1),
    TEXT("文本屏", false, 8, 2, 4, 1),
    DIAL("指针表盘", false, 3, 3, 2, 2),
    BAR("条图", false, 8, 2, 4, 1),

    // ---- operator controls ------------------------------------------------
    BUTTON("按钮", true, 3, 2, 2, 1),
    ESTOP("急停按钮", true, 3, 3, 2, 2),
    SWITCH("拨动开关", true, 2, 3, 2, 2),
    ROTARY("旋钮开关", true, 3, 3, 2, 2),
    KNOB("旋钮", true, 3, 3, 2, 2),
    SLIDER("滑杆", true, 2, 6, 2, 3);

    public static final CtrlType[] ALL = values();

    private final String label;
    private final boolean operator;
    private final int defW;
    private final int defH;
    private final int minW;
    private final int minH;

    CtrlType(String label, boolean operator, int defW, int defH, int minW, int minH) {
        this.label = label;
        this.operator = operator;
        this.defW = defW;
        this.defH = defH;
        this.minW = minW;
        this.minH = minH;
    }

    public String label() {
        return this.label;
    }

    /** True when a player drives it, false when it only displays. */
    public boolean isOperator() {
        return this.operator;
    }

    /** True when it reads from a machine, i.e. it needs a data key. */
    public boolean usesKey() {
        return !this.operator;
    }

    /** True when it writes to a machine, i.e. it needs an event name. */
    public boolean usesEvent() {
        return this.operator;
    }

    /**
     * True when the value is a flag rather than a number.
     *
     * <p>Those controls are drawn as on/off and are clamped to 0..1 whatever
     * their range says, because a lamp at "37%" is a lie: the machine's flag is
     * either true or false, and the panel must not pretend otherwise.</p>
     */
    public boolean isBooleanish() {
        return this == LAMP || this == LAMP_RGB || this == SWITCH
                || this == BUTTON || this == ESTOP;
    }

    public int defW() {
        return this.defW;
    }

    public int defH() {
        return this.defH;
    }

    public int minW() {
        return this.minW;
    }

    public int minH() {
        return this.minH;
    }

    /** A short line for the palette, one per type. */
    public String hint() {
        switch (this) {
            case LABEL:
                return "纯文字说明，不接机器";
            case LAMP:
                return "数据非 0 就亮";
            case LAMP_RGB:
                return "按数值分绿/琥珀/红";
            case SEG7:
                return "七段数字，适合读数";
            case TEXT:
                return "显示字符串或带单位的值";
            case DIAL:
                return "指针在量程内摆动";
            case BAR:
                return "横向进度条";
            case BUTTON:
                return "按下发一次事件";
            case ESTOP:
                return "拍下锁死并发 0";
            case SWITCH:
                return "两态开关，发 0/1";
            case ROTARY:
                return "多档旋钮，按档位发值";
            case KNOB:
                return "连续旋钮，量程内取值";
            case SLIDER:
                return "上下推杆，量程内取值";
            default:
                return "";
        }
    }

    public static CtrlType byName(String name) {
        for (CtrlType type : ALL) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return LABEL;
    }

    /** What the "add" button in the palette creates. */
    public static CtrlType[] palette() {
        return ALL;
    }
}
