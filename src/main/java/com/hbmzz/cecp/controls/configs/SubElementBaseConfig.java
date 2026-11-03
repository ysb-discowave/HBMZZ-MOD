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

package com.hbmzz.cecp.controls.configs;

import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.cecp.GuiControlEdit;
import com.hbmzz.cecp.SubElement;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.HashMap;
import java.util.Map;

public class SubElementBaseConfig extends SubElement {
    protected static final int[] DEFAULT_PREVIEW_RECT = new int[]{84, 80, 88, 88};

    public SubElementBaseConfig(GuiControlEdit gui,Map<String, DataValue> map) {
        super(gui);
    }

    public Map<String, DataValue> getConfigs() {
        Map<String, DataValue> configs = new HashMap<>();
        fillConfigs(configs);
        return configs;
    }

    public void fillConfigs(Map<String, DataValue> configs) {
    }

    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return DEFAULT_PREVIEW_RECT;
    }

    protected static void putFloatConfig(Map<String, DataValue> configs, String key, float value) {
        DataValue dataValue = configs.get(key);
        if (dataValue instanceof DataValueFloat) {
            ((DataValueFloat) dataValue).num = value;
        } else {
            configs.put(key, new DataValueFloat(value));
        }
    }

    protected static void putStringConfig(Map<String, DataValue> configs, String key, String value) {
        DataValue dataValue = configs.get(key);
        if (dataValue instanceof DataValueString) {
            ((DataValueString) dataValue).set(value);
        } else {
            configs.put(key, new DataValueString(value));
        }
    }
}
