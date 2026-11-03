/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.controls;

import java.util.ArrayList;
import java.util.List;

// ffs with the enums
public class ControlType {
    /** Stable key. Matches the string CE registers, so it must stay English. */
    public final String name;
    /** Chinese label for the editor's "pick a control type" list. */
    public final String displayName;
    public static final List<ControlType> ALL_VALUES = new ArrayList<>();
    /// Do not use this, use registerNew
    private ControlType(String name){
        this.name = name;
        this.displayName = localized(name);
        ALL_VALUES.add(this);
    }

    public String getDisplayName() {
        return displayName;
    }

    private static String localized(String key){
        switch (key) {
            case "Button":    return "按钮";
            case "Switch":    return "开关";
            case "Slider":    return "滑条";
            case "Knob":      return "旋钮";
            case "Dial":      return "仪表";
            case "Display":   return "显示屏";
            case "Indicator": return "指示灯";
            case "Meter":     return "计量表";
            case "RBMK Display": return "RBMK 显示";
            case "Graph":     return "图表";
            case "Lever":     return "拨杆";
            case "Label":     return "标签";
            case "Unknown":   return "未知控件";
            default:          return key;
        }
    }

    public static ControlType registerOrGet(String name) {
        ControlType ret = getByName(name);
        if (ret == null)
            ret = new ControlType(name);
        return ret;
    }

    public static ControlType getByName(String name){
        for(ControlType o : ALL_VALUES){
            if(o.name.equals(name)){
                return o;
            }
        }
        return null;
    }

    public static final ControlType BUTTON = registerOrGet("Button");
    public static final ControlType SWITCH = registerOrGet("Switch");
    public static final ControlType SLIDER = registerOrGet("Slider");
    public static final ControlType KNOB = registerOrGet("Knob");
    public static final ControlType DIAL = registerOrGet("Dial");
    public static final ControlType DISPLAY = registerOrGet("Display");
    public static final ControlType INDICATOR = registerOrGet("Indicator");
    public static final ControlType METER = registerOrGet("Meter"); // unused
    public static final ControlType LABEL = registerOrGet("Label");
}
