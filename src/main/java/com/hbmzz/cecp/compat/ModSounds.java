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

import net.minecraft.util.SoundEvent;

/**
 * Looks up a sound that lives in another mod's static field.
 *
 * <p>The addon's controls reference sounds on {@code HBMSoundHandler} and
 * {@code LeafiaSoundEvents} directly. Those are compile-time dependencies this
 * mod deliberately does not have - a plain CE install has the first but not the
 * second, and neither is guaranteed - so the field is resolved by name and a
 * missing sound degrades to silence instead of a crash.</p>
 */
public final class ModSounds {

    public static SoundEvent find(String owner, String field) {
        if (owner == null || field == null) {
            return null;
        }
        try {
            Class<?> type = Class.forName(owner, true, ModSounds.class.getClassLoader());
            Object value = type.getField(field).get(null);
            return value instanceof SoundEvent ? (SoundEvent) value : null;
        } catch (Throwable missing) {
            return null;
        }
    }

    private ModSounds() {
    }
}
