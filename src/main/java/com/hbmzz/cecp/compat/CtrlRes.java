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

package com.hbmzz.cecp.compat;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Resource table for the control panel, mirroring the field names NTM: Cursed
 * Edition exposes on {@code com.hbm.main.ResourceManager} so the ported control
 * classes keep calling {@code ResourceManager.ctrl_button_push} verbatim.
 *
 * <p>The model and texture files themselves are CE's, copied into this mod's
 * assets under {@code hbmzz:models/control_panel} and
 * {@code hbmzz:textures/models/control_panel}.</p>
 */
@SideOnly(Side.CLIENT)
public final class CtrlRes {

    private static final String OBJ = "models/control_panel/";
    private static final String TEX = "textures/models/control_panel/";
    private static final String GUI = "textures/gui/control_panel/";

    private static ResourceLocation loc(String path) {
        return new ResourceLocation("hbmzz", path);
    }

    private static IModelCustom obj(String name) {
        return new ObjModel(loc(OBJ + name + ".obj"));
    }

    public static final ResourceLocation white = loc(GUI + "white.png");

    public static final IModelCustom control_panel_custom = obj("control_panel_custom");
    public static final ResourceLocation control_panel_custom_tex = loc(TEX + "control_panel_front.png");

    public static final IModelCustom ctrl_button_push = obj("button_push");
    public static final ResourceLocation ctrl_button_push_tex = loc(TEX + "button_push.png");
    public static final ResourceLocation ctrl_button_push_gui_tex = loc(TEX + "button_push_gui.png");

    public static final IModelCustom ctrl_button_encased_push = obj("button_encased_push");
    public static final ResourceLocation ctrl_button_encased_push_tex = loc(TEX + "button_encased_push.png");
    public static final ResourceLocation ctrl_button_encased_push_gui_tex = loc(TEX + "button_encased_push_gui.png");

    public static final IModelCustom ctrl_button_emergency_push = obj("button_emergency_push");
    public static final ResourceLocation ctrl_button_emergency_push_tex = loc(TEX + "button_emergency_push.png");
    public static final ResourceLocation ctrl_button_emergency_push_gui_tex = loc(TEX + "button_emergency_push_gui.png");

    public static final IModelCustom ctrl_dial_large = obj("dial_large");
    public static final ResourceLocation ctrl_dial_large_tex = loc(TEX + "dial_large.png");
    public static final ResourceLocation ctrl_dial_large_gui_tex = loc(TEX + "dial_large_gui.png");

    public static final IModelCustom ctrl_dial_square = obj("dial_square");
    public static final ResourceLocation ctrl_dial_square_tex = loc(TEX + "dial_square.png");
    public static final ResourceLocation ctrl_dial_square_gui_tex = loc(TEX + "dial_square_gui.png");

    public static final IModelCustom ctrl_display_seven_seg = obj("display_seven_seg");
    public static final ResourceLocation ctrl_display_seven_seg_tex = loc(TEX + "display_7seg.png");
    public static final ResourceLocation ctrl_display_seven_seg_gui_tex = loc(TEX + "display_7seg_gui.png");

    public static final IModelCustom ctrl_indicator_lamp = obj("indicator_lamp");
    public static final ResourceLocation ctrl_indicator_lamp_gui_tex = loc(TEX + "indicator_lamp_gui.png");

    public static final IModelCustom ctrl_knob_control = obj("knob_control");
    public static final ResourceLocation ctrl_knob_control_tex = loc(TEX + "knob_control.png");
    public static final ResourceLocation ctrl_knob_control_gui_tex = loc(TEX + "knob_control_gui.png");

    public static final IModelCustom ctrl_slider_vertical = obj("slider_vertical");
    public static final ResourceLocation ctrl_slider_vertical_tex = loc(TEX + "slider_vertical.png");
    public static final ResourceLocation ctrl_slider_vertical_gui_tex = loc(TEX + "slider_vertical_gui.png");

    public static final IModelCustom ctrl_switch_rotary_toggle = obj("switch_rotary_toggle");
    public static final ResourceLocation ctrl_switch_rotary_toggle_tex = loc(TEX + "switch_rotary_toggle.png");
    public static final ResourceLocation ctrl_switch_rotary_toggle_gui_tex = loc(TEX + "switch_rotary_toggle_gui.png");

    public static final IModelCustom ctrl_switch_toggle = obj("switch_toggle");
    public static final ResourceLocation ctrl_switch_toggle_tex = loc(TEX + "switch_toggle.png");
    public static final ResourceLocation ctrl_switch_toggle_gui_tex = loc(TEX + "switch_toggle_gui.png");

    private CtrlRes() {
    }
}
