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

package com.hbmzz.alert;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The facility's status code, as one record for the whole save.
 *
 * <h3>Why this is world data and not a tile entity field</h3>
 * <p>The requirement is that every display in the world agrees, instantly and
 * permanently. The only way to make "every display agrees" a property you cannot
 * break is to have exactly one copy of the answer. So the code lives here, on the
 * overworld's map storage - shared by every dimension - and the displays are
 * <em>views</em>: they hold no state of their own beyond a cache of what they
 * were last told. Breaking a display loses nothing, placing a new one a hundred
 * blocks away shows the same code on its first packet, and no display can drift
 * from another because there is nothing in a display to drift.</p>
 *
 * <p>It is a {@link WorldSavedData} rather than a static field so it survives a
 * save/reload and so a server restart does not silently reset a DELTA to GREEN.</p>
 *
 * <h3>Latched, not timed</h3>
 * <p>A code stays up until another one replaces it. Real alarm panels latch for
 * the same reason: an unattended auto-clear is indistinguishable from "the
 * alarm was wrong". The display shows how long the code has been up
 * ({@code T+mm:ss}), which is the part a timeout would have destroyed.</p>
 */
public final class FacilityState extends WorldSavedData {

    public static final String NAME = "hbmzz_facility";

    /** Newest first. Six is what fits on the display without shrinking the text. */
    public static final int HISTORY = 6;

    /** One line of the event log: which code, when, and who said so. */
    public static final class Entry {

        public final int code;
        public final long tick;
        public final String source;

        public Entry(int code, long tick, String source) {
            this.code = code;
            this.tick = tick;
            this.source = source == null ? "" : source;
        }

        public AlertCode alert() {
            return AlertCode.byId(this.code);
        }
    }

    private int code = AlertCode.GREEN.id();
    private long since;
    private String source = "";
    private String actor = "";
    private final List<Entry> history = new ArrayList<Entry>();

    // ---- last detonation, so a monitor whose device is gone can still say what happened

    private boolean hasBlast;
    private long blastTick;
    private int blastX;
    private int blastY;
    private int blastZ;
    private int blastYield;
    private int blastDim;

    /** Required by {@link MapStorage}: it constructs this reflectively by name. */
    public FacilityState(String name) {
        super(name);
    }

    public FacilityState() {
        this(NAME);
    }

    /**
     * The one record for this save, loading it on first use.
     *
     * <p>Always resolved through the overworld: a per-dimension copy would mean
     * the Nether could hold a different code from the surface, which is exactly
     * the disagreement this class exists to prevent.</p>
     */
    public static FacilityState get(World world) {
        if (world == null || world.isRemote) {
            return null;
        }
        MinecraftServer server = world.getMinecraftServer();
        World storageWorld = server != null && server.getWorld(0) != null ? server.getWorld(0) : world;
        MapStorage storage = storageWorld.getMapStorage();
        if (storage == null) {
            return null;
        }
        WorldSavedData data = storage.getOrLoadData(FacilityState.class, NAME);
        if (!(data instanceof FacilityState)) {
            FacilityState fresh = new FacilityState(NAME);
            storage.setData(NAME, fresh);
            return fresh;
        }
        return (FacilityState) data;
    }

    // ------------------------------------------------------------------ read

    public AlertCode alert() {
        return AlertCode.byId(this.code);
    }

    public int codeId() {
        return this.code;
    }

    /** World tick the current code was raised at. */
    public long since() {
        return this.since;
    }

    /** Who raised it, in one short label: a block name or an event name. */
    public String source() {
        return this.source;
    }

    /** Who did it, when a player was involved. Empty for machine-raised codes. */
    public String actor() {
        return this.actor;
    }

    public List<Entry> history() {
        return Collections.unmodifiableList(this.history);
    }

    public boolean hasBlast() {
        return this.hasBlast;
    }

    public long blastTick() {
        return this.blastTick;
    }

    public int blastX() {
        return this.blastX;
    }

    public int blastY() {
        return this.blastY;
    }

    public int blastZ() {
        return this.blastZ;
    }

    public int blastYield() {
        return this.blastYield;
    }

    public int blastDim() {
        return this.blastDim;
    }

    // ----------------------------------------------------------------- write

    /**
     * Raises a code, unconditionally.
     *
     * <p>Unconditional rather than "only if worse": an operator who has dealt
     * with a fire and needs to clear a RED down to GREEN must be able to say so.
     * The ladder is used by the automatic sources (a warhead raises ALPHA even
     * if the panel was showing DELTA), not by the manual one.</p>
     *
     * @return true when this actually changed the displayed code
     */
    public boolean raise(World world, AlertCode next, String source, String actor) {
        if (next == null) {
            return false;
        }
        long now = world == null ? 0L : world.getTotalWorldTime();
        boolean changed = next.id() != this.code;
        this.code = next.id();
        this.since = now;
        this.source = trim(source, 48);
        this.actor = trim(actor, 32);
        push(new Entry(this.code, now, this.source));
        markDirty();
        return changed;
    }

    /** Raises only when {@code next} is at least as severe as what is up. */
    public boolean raiseAtLeast(World world, AlertCode next, String source, String actor) {
        if (next != null && next.severity() < alert().severity()) {
            return false;
        }
        return raise(world, next, source, actor);
    }

    public void noteBlast(World world, int x, int y, int z, int yield, int dim) {
        this.hasBlast = true;
        this.blastTick = world == null ? 0L : world.getTotalWorldTime();
        this.blastX = x;
        this.blastY = y;
        this.blastZ = z;
        this.blastYield = yield;
        this.blastDim = dim;
        markDirty();
    }

    private void push(Entry entry) {
        this.history.add(0, entry);
        while (this.history.size() > HISTORY) {
            this.history.remove(this.history.size() - 1);
        }
    }

    private static String trim(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        this.code = nbt.getByte("code");
        this.since = nbt.getLong("since");
        this.source = nbt.getString("source");
        this.actor = nbt.getString("actor");
        this.history.clear();
        NBTTagList list = nbt.getTagList("history", 10);
        for (int i = 0; i < list.tagCount() && i < HISTORY; i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            this.history.add(new Entry(entry.getByte("c"), entry.getLong("t"), entry.getString("s")));
        }
        this.hasBlast = nbt.getBoolean("hasBlast");
        this.blastTick = nbt.getLong("blastTick");
        this.blastX = nbt.getInteger("blastX");
        this.blastY = nbt.getInteger("blastY");
        this.blastZ = nbt.getInteger("blastZ");
        this.blastYield = nbt.getInteger("blastYield");
        this.blastDim = nbt.getInteger("blastDim");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setByte("code", (byte) this.code);
        nbt.setLong("since", this.since);
        nbt.setString("source", this.source == null ? "" : this.source);
        nbt.setString("actor", this.actor == null ? "" : this.actor);
        NBTTagList list = new NBTTagList();
        for (Entry entry : this.history) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setByte("c", (byte) entry.code);
            tag.setLong("t", entry.tick);
            tag.setString("s", entry.source);
            list.appendTag(tag);
        }
        nbt.setTag("history", list);
        nbt.setBoolean("hasBlast", this.hasBlast);
        nbt.setLong("blastTick", this.blastTick);
        nbt.setInteger("blastX", this.blastX);
        nbt.setInteger("blastY", this.blastY);
        nbt.setInteger("blastZ", this.blastZ);
        nbt.setInteger("blastYield", this.blastYield);
        nbt.setInteger("blastDim", this.blastDim);
        return nbt;
    }
}
