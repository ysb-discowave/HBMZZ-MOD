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

import net.minecraft.creativetab.CreativeTabs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.vector.Matrix4f;

import java.nio.FloatBuffer;

/**
 * Environment hooks that NTM: Cursed Edition's control panel code reaches into
 * {@code com.hbm.main.MainRegistry} / {@code com.hbm.main.ClientProxy} for.
 * Re-exported here so the ported sources stay otherwise unchanged.
 */
public final class CPEnv {

    public static final Logger logger = LogManager.getLogger("HBMZZ-ControlPanel");

    /** Scratch matrix buffer used to push a {@link Matrix4f} into the GL matrix stack. */
    public static final FloatBuffer AUX_GL_BUFFER = BufferUtils.createFloatBuffer(16);
    public static final FloatBuffer AUX_GL_BUFFER2 = BufferUtils.createFloatBuffer(16);

    public static CreativeTabs controlTab;

    private CPEnv() {
    }
}
