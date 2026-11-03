/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM Cursed Addon (CE Addon); see NOTICE.md for the list
 * of changes.
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
 * Resources for the control-panel instruments ported from NTM: Cursed Edition's
 * addon (CE Addon / Leafia).
 *
 * <p>The addon keeps every instrument asset under
 * {@code assets/leafia/textures/_integrated/control_panel/instruments/} and
 * resolves them through {@code AddonBase.getIntegrated}. They live here under
 * {@code hbmzz:models/control_panel/addon} and
 * {@code hbmzz:textures/models/control_panel/addon} instead, and the two solid
 * colours the addon exposes as {@code AddonBase.solid} / {@code .solid_e} /
 * {@code .invisible} sit next to the rest of the panel's GUI textures.</p>
 */
@SideOnly(Side.CLIENT)
public final class AddonRes {

    private static final String OBJ = "models/control_panel/addon/";
    private static final String TEX = "textures/models/control_panel/addon/";
    private static final String GUI = "textures/gui/control_panel/";

    private static ResourceLocation loc(String path) {
        return new ResourceLocation("hbmzz", path);
    }

    private static IModelCustom obj(String name) {
        return new ObjModel(loc(OBJ + name + ".obj"));
    }

    /** Flat white: every solid-coloured quad in the instruments is this texture. */
    public static final ResourceLocation solid = loc(GUI + "solid.png");
    /** Same, but marked emissive so lit parts ignore the lightmap. */
    public static final ResourceLocation solid_e = loc(GUI + "solid_emissive.png");
    /** Fully transparent: used to disable texturing without unbinding. */
    public static final ResourceLocation invisible = loc(GUI + "invisible.png");

    public static final IModelCustom nixie = obj("nixie");
    public static final ResourceLocation nixie_gui_tex = loc(TEX + "nixie.png");

    public static final IModelCustom large_switch = obj("starboundswitchlol");
    public static final ResourceLocation large_switch_palette = loc(TEX + "largeswitch_palette.png");
    public static final ResourceLocation large_switch_gui_tex = loc(TEX + "starboundswitchlol.png");

    public static final IModelCustom large_button = obj("starboundbuttonlol");
    public static final ResourceLocation large_button_gui_tex = loc(TEX + "starboundbuttonlol.png");

    public static final IModelCustom text_indicator = obj("textindicator");
    public static final ResourceLocation text_indicator_light_tex = loc(TEX + "textindicator_light.png");
    public static final ResourceLocation text_indicator_gui_tex = loc(TEX + "textindicator.png");

    public static final IModelCustom meter_v = obj("meter_vertical");
    public static final ResourceLocation rbmk_tex = loc(TEX + "rbmk.png");

    public static final IModelCustom lever = obj("lever");
    public static final ResourceLocation lever_h_gui_tex = loc(TEX + "lever_horizontal.png");
    public static final ResourceLocation lever_v_gui_tex = loc(TEX + "lever_vertical.png");

    /** Vertical meter dial faces, one per needle offset (-3 … +3). */
    public static final ResourceLocation[] meter_v_gui_tex = new ResourceLocation[7];

    static {
        for (int i = -3; i <= 3; i++) {
            meter_v_gui_tex[i + 3] = loc(TEX + "meter_vertical_" + i + ".png");
        }
    }

    private AddonRes() {
    }
}
