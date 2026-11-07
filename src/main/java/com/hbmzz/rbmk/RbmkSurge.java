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

package com.hbmzz.rbmk;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.entity.EntityRbmkLid;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.ShakeMessage;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.SoundEvents;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The staged RBMK blow-up: find a reactor that is about to go, make its rods
 * jump, then take its lid off.
 *
 * <p>The base mod destroys an overheated reactor in one tick. Correct, but it
 * happens to you rather than in front of you - the reactor is a working machine,
 * then it is rubble, and the only evidence is a screenshot you did not take.
 * This replaces the single tick with the minute before it: rods hammering in
 * their channels, steam screaming out of the lid, and then the lid itself going
 * up and coming back down twice.</p>
 *
 * <h3>What triggers it</h3>
 * <p>Heat above {@link RbmkTiming#HEAT_TRIP} and steam with nowhere left to
 * go - see {@link RbmkTiming#tripped}. Both halves are needed. Heat alone is a
 * reactor running hot, which is how a reactor is supposed to run; steam alone
 * is a boiler doing its job.</p>
 *
 * <h3>Why the jump is not ours</h3>
 * <p>The rod jump is the Cursed Edition's, driven by the {@code leafia$damage}
 * field that addon adds to every column. Writing that field gives the full
 * animation - the rod leaving its channel, the jet underneath it, the impact
 * when it comes back down - for the cost of one integer. Drawing a second jump
 * on top of that one would put two rods in the same channel.</p>
 *
 * <h3>Why it holds the heat down</h3>
 * <p>Between the trip and the blast the columns are pinned just under
 * {@code maxHeat()}. Without that the base mod reaches its own meltdown inside
 * the first few seconds - the reactor would be destroyed by the vanilla path
 * before the one-minute countdown finished, and the lid would never move. The
 * pin is released by the blast itself.</p>
 */
public final class RbmkSurge {

    /** One entry per dimension; worlds are dropped with the save. */
    private static final Map<World, State> WORLDS = new WeakHashMap<World, State>();

    /** Plates in flight, so the settling one can find its reactor back. */
    private static final Map<Integer, Tracked> PLATES = new HashMap<Integer, Tracked>();

    /** Set once, the first time another mod is found driving the same accident. */
    private static Boolean rival;

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world == null || event.world.isRemote) {
            return;
        }
        if (!HBMZZConfig.rbmkSurge || !RbmkParts.available()) {
            return;
        }
        rival();
        State state;
        synchronized (WORLDS) {
            state = WORLDS.get(event.world);
            if (state == null) {
                state = new State();
                WORLDS.put(event.world, state);
            }
        }
        state.tick(event.world);
    }

    /**
     * Says so, once, when ntm-it is installed.
     *
     * <p>That mod stages the same accident - its own rod jump, its own lid
     * plate, its own footprint blast - and it is loaded on the packs this was
     * tested against. Two implementations of one reactor meltdown on one
     * reactor means two plates thrown from lids only one of them can clear, two
     * meltdowns on the same columns, and a blast count nobody designed. The
     * warning is not politeness: it is the first thing to check when something
     * goes wrong at the moment the lid leaves.</p>
     */
    private static void rival() {
        if (rival != null) {
            return;
        }
        try {
            rival = Boolean.valueOf(net.minecraftforge.fml.common.Loader.isModLoaded("ntm-it"));
        } catch (Throwable ignored) {
            rival = Boolean.FALSE;
        }
        if (rival.booleanValue() && HBMZZ.logger != null) {
            HBMZZ.logger.warn("[RBMK] ntm-it is installed and stages the same accident. "
                    + "Both will react to one reactor; remove one of them if the lid behaves oddly.");
        }
    }

    // ================================================================ scanning

    /**
     * Finds the reactors in the loaded chunk set.
     *
     * <p>{@code loadedTileEntityList} rather than a chunk sweep: every column
     * has a tile entity, the list is already maintained by the server, and a
     * scan that costs one list pass every twenty ticks is invisible next to the
     * reactor physics it is watching.</p>
     */
    private static List<List<BlockPos>> reactorsIn(World world) {
        List<BlockPos> columns = new ArrayList<BlockPos>();
        for (TileEntity te : world.loadedTileEntityList) {
            if (RbmkParts.isColumn(te)) {
                columns.add(te.getPos());
            }
        }
        return RbmkCluster.byAdjacency(columns);
    }

    // ============================================================== per reactor

    private static final class State {
        private int scanIn;
        private int failures;
        private final List<Tracked> reactors = new ArrayList<Tracked>();

        void tick(World world) {
            if (--this.scanIn <= 0) {
                this.scanIn = RbmkTiming.SCAN_INTERVAL;
                try {
                    rescan(world);
                } catch (Throwable broken) {
                    fail("scan", broken);
                }
            }
            for (Iterator<Tracked> it = this.reactors.iterator(); it.hasNext(); ) {
                Tracked tracked = it.next();
                if (tracked.columns.isEmpty()) {
                    it.remove();
                    continue;
                }
                try {
                    tracked.tick(world);
                } catch (Throwable broken) {
                    // One reactor's accident went wrong. Drop that reactor, keep
                    // ticking the world: a meltdown that crashes the server has
                    // taken the save down with it, which is worse than the
                    // meltdown.
                    it.remove();
                    fail("reactor", broken);
                }
            }
        }

        /**
         * Logs a failure with its stack, then stops reporting after three.
         *
         * <p>Three because the interesting ones are the first three; after that
         * the same broken reactor would print sixty lines a second and bury
         * everything else in the log.</p>
         */
        private void fail(String where, Throwable broken) {
            if (this.failures++ >= 3) {
                return;
            }
            if (HBMZZ.logger != null) {
                HBMZZ.logger.error("[RBMK] " + where + " failed; that reactor is no longer watched", broken);
            }
        }

        private void rescan(World world) {
            List<List<BlockPos>> groups = reactorsIn(world);
            List<Tracked> next = new ArrayList<Tracked>();
            for (List<BlockPos> group : groups) {
                Tracked found = null;
                for (Tracked tracked : this.reactors) {
                    if (!tracked.matches(group)) {
                        continue;
                    }
                    found = tracked;
                    break;
                }
                if (found == null) {
                    found = new Tracked();
                }
                found.columns = group;
                next.add(found);
            }
            // Anything that did not reappear has been dismantled, or its chunks
            // are unloaded. Keeping it would fire the sequence at an empty hole
            // as soon as the player walked back.
            this.reactors.clear();
            this.reactors.addAll(next);
        }
    }

    private static final class Tracked {
        private List<BlockPos> columns = new ArrayList<BlockPos>();
        private RbmkTiming.Phase phase = RbmkTiming.Phase.IDLE;
        private int elapsed;
        private long steamNow;
        private long steamPrev;
        private long steamPrev2;
        private int ventCooldown;

        boolean matches(List<BlockPos> group) {
            for (BlockPos pos : group) {
                if (this.columns.contains(pos)) {
                    return true;
                }
            }
            return false;
        }

        void tick(World world) {
            switch (this.phase) {
                case IDLE:
                    if (tripped(world)) {
                        this.phase = RbmkTiming.Phase.WARMUP;
                        this.elapsed = 0;
                        // English, not Chinese: log4j writes the 1.12.2 log in
                        // the platform charset, and a GBK-encoded Chinese line
                        // shows up as mojibake in every tool that reads it as
                        // UTF-8 - including the one used to debug this.
                        report(world, "overheating, rods start jumping");
                        if (!this.columns.isEmpty()) {
                            com.hbmzz.cinematic.CinematicDirector.watchRbmk(
                                    world, this.columns.get(0), RbmkParts.columnHeight(world));
                        }
                    }
                    break;
                case WARMUP:
                    this.elapsed++;
                    jump(world);
                    if (--this.ventCooldown <= 0) {
                        this.ventCooldown = 2;
                        vent(world);
                    }
                    if (RbmkTiming.launchDue(this.elapsed, HBMZZConfig.rbmkSurgeTicks)) {
                        this.phase = RbmkTiming.Phase.SPENT;
                        launch(world);
                        // Hand the camera back at the blast, so the player sees
                        // the explosion with their own eyes rather than through
                        // an orbit that outlives its subject.
                        com.hbmzz.cinematic.CinematicDirector.releaseRbmk(world);
                    }
                    break;
                default:
                    // SPENT: the lid entity owns the sequence from here. Nothing
                    // to do but wait for it to settle.
                    break;
            }
        }

        /**
         * Reads the reactor once and answers both halves of the trip.
         *
         * <p>Steam gets two definitions on purpose - see
         * {@link RbmkTiming#steamStalled}. The declared capacity is right on a
         * stock install and wrong by a factor of five on the packs everyone
         * actually runs, so the plateau is the one that has to work.</p>
         */
        private boolean tripped(World world) {
            double[] heat = new double[this.columns.size()];
            int[] steam = new int[this.columns.size()];
            long total = 0L;
            for (int i = 0; i < this.columns.size(); i++) {
                BlockPos pos = this.columns.get(i);
                TileEntity te = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
                if (te == null || !RbmkParts.isColumn(te)) {
                    continue;
                }
                heat[i] = RbmkParts.heat(te);
                steam[i] = RbmkParts.steam(te);
                total += steam[i];
            }
            this.steamPrev2 = this.steamPrev;
            this.steamPrev = this.steamNow;
            this.steamNow = total;
            return RbmkTiming.tripped(heat, steam, RbmkParts.maxSteam(),
                    HBMZZConfig.rbmkSurgeHeat, this.steamPrev2, this.steamPrev, this.steamNow);
        }

        /**
         * Drives one tick of the jump and keeps the columns alive.
         *
         * <p>The heat pin is the contentious line in this class: a reactor that
         * is being held back is not a reactor that is behaving. It is, however,
         * the only way to have a one-minute warning in a mod whose base decides
         * a reactor is dead at 1500 degrees, and it is released the moment the
         * lid leaves.</p>
         */
        private void jump(World world) {
            int damage = RbmkTiming.jumpDamage(this.elapsed, HBMZZConfig.rbmkSurgeTicks);
            boolean animated = false;
            for (BlockPos pos : this.columns) {
                if (!world.isBlockLoaded(pos)) {
                    continue;
                }
                TileEntity te = world.getTileEntity(pos);
                if (te == null || !RbmkParts.isColumn(te)) {
                    continue;
                }
                animated |= RbmkParts.jump(te, damage);
                double ceiling = RbmkParts.maxHeat(te) - 50.0D;
                if (RbmkParts.heat(te) > ceiling) {
                    RbmkParts.setHeat(te, ceiling);
                }
            }
            if (!animated && this.elapsed == 1) {
                // No Cursed Edition: the rods cannot jump, so say so once in the
                // log instead of letting the player wonder why nothing moves.
                report(world, "overheating, but the Cursed Edition addon is absent; rods cannot jump");
            }
        }

        /**
         * Steam out of the lid, plus the noise of a boiler at its limit.
         *
         * <p>Called every other tick per column while the countdown runs. The
         * old version drew one CLOUD particle every eight ticks, which at
         * reactor scale is invisible - the plume is the only warning the player
         * gets before the lid leaves, so it is drawn as a real jet: several
         * particles per column with upward velocity and a lazy sideways drift.</p>
         */
        private void vent(World world) {
            int height = RbmkParts.columnHeight(world);
            for (BlockPos pos : this.columns) {
                if (!world.isBlockLoaded(pos)) {
                    continue;
                }
                double x = pos.getX() + 0.5D;
                double y = pos.getY() + height + 1.0D;
                double z = pos.getZ() + 0.5D;
                RbmkEffects.steam(world, x, y, z, 0.35D, 2, 0.34D);
            }
            BlockPos any = this.columns.get(0);
            world.playSound(null, any, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1.4F, 0.55F);
        }

        /**
         * Takes the lid off and throws it.
         *
         * <p>The lids are harvested one column at a time. The lid type is a
         * metadata value on the column's core - not on the block above it,
         * which only ever carries the dummy value - so the honest way to know
         * what is on top is to ask the base mod's own {@code metaToLid} about
         * the core, and to clear it the same way its own lid-drop does. Nothing
         * is deleted: the columns stay, open.</p>
         */
        private void launch(World world) {
            int height = RbmkParts.columnHeight(world);
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int topY = Integer.MIN_VALUE;
            List<int[]> entries = new ArrayList<int[]>();
            List<String> textures = new ArrayList<String>();

            for (BlockPos pos : this.columns) {
                if (!world.isBlockLoaded(pos)) {
                    continue;
                }
                TileEntity te = world.getTileEntity(pos);
                if (te == null || !RbmkParts.isColumn(te)) {
                    continue;
                }
                Block columnBlock = world.getBlockState(pos).getBlock();
                if (RbmkParts.hasOwnLid(columnBlock)) {
                    // Control rods draw their own lid as part of the rod; there
                    // is no plate to take off, and pretending otherwise would
                    // leave a rod floating over an open channel.
                    continue;
                }
                // The lid type is metadata on the core, not on the block above
                // it. See RbmkParts.lidMeta - this is the line that 2.7.x got
                // wrong, and the reason a reactor with a full set of lids
                // produced an empty plate.
                int lid = RbmkParts.lidType(RbmkParts.lidMeta(te));
                if (lid <= 0) {
                    continue;
                }
                // Where the plate sits is a different question: the lid is drawn
                // on the top block of the column, which is also where the base
                // mod drops the item when it is unscrewed.
                BlockPos lidPos = pos.up(height);
                if (!world.isBlockLoaded(lidPos)) {
                    continue;
                }
                String texture = RbmkParts.columnTexture(columnBlock);
                int index = textures.indexOf(texture);
                if (index < 0) {
                    if (textures.size() >= 8) {
                        continue;
                    }
                    index = textures.size();
                    textures.add(texture);
                }
                entries.add(new int[]{pos.getX(), pos.getZ(), lid, index});
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
                topY = Math.max(topY, lidPos.getY());
            }

            if (entries.isEmpty()) {
                // Nothing to throw - a core with no lids installed. Blow it up
                // anyway; the reactor was about to go regardless. Say so in the
                // log, because "the lid did not fly" and "there was no lid"
                // look identical from outside and only one of them is a bug.
                BlockPos any = this.columns.get(0);
                double ex = any.getX() + 0.5D;
                double ey = any.getY() + height + 1.0D;
                double ez = any.getZ() + 0.5D;
                RbmkEffects.nuclear(world, ex, ey, ez, HBMZZConfig.rbmkSurgePower, true);
                RbmkEffects.dustRing(world, ex, ey, ez, HBMZZConfig.rbmkSurgePower * 1.6D);
                shake(world, ex, ey, ez, 7.0F, 160.0F, 45, true);
                report(world, "no removable lids on this reactor; blew the core instead (" + this.columns.size() + " columns)");
                return;
            }

            double centreX = (minX + maxX + 1) / 2.0D;
            double centreZ = (minZ + maxZ + 1) / 2.0D;
            List<int[]> packed = new ArrayList<int[]>(entries.size());
            for (int[] entry : entries) {
                // Half-block offsets stored doubled, so the layout stays an
                // integer string and a 5-wide plate still centres exactly.
                int dx = (int) Math.round((entry[0] + 0.5D - centreX) * 2.0D);
                int dz = (int) Math.round((entry[1] + 0.5D - centreZ) * 2.0D);
                packed.add(new int[]{dx, dz, entry[2], entry[3]});
            }

            for (BlockPos pos : this.columns) {
                clearLid(world, pos);
            }

            String layout = RbmkLayout.encode(packed, textures);
            // +1.0: the plate is the top face of the column, and an entity's
            // origin is its feet, so spawning at the block's Y would bury the
            // plate a quarter block inside the reactor it is leaving.
            double plateY = topY + 1.0D;
            EntityRbmkLid plate = new EntityRbmkLid(world, centreX, plateY, centreZ, layout);
            world.spawnEntity(plate);
            PLATES.put(Integer.valueOf(plate.getEntityId()), this);

            RbmkEffects.nuclear(world, centreX, plateY, centreZ, HBMZZConfig.rbmkSurgePower, true);
            RbmkEffects.dustRing(world, centreX, plateY, centreZ, HBMZZConfig.rbmkSurgePower * 1.8D);
            RbmkEffects.steam(world, centreX, plateY, centreZ, Math.max(2.0D, (maxX - minX + 1) * 0.5D), 40, 0.42D);
            world.playSound(null, new BlockPos(centreX, plateY, centreZ),
                    SoundEvents.ENTITY_ENDERDRAGON_FIREBALL_EPLD, SoundCategory.BLOCKS, 6.0F, 0.6F);
            shake(world, centreX, plateY, centreZ, 9.0F, 220.0F, 60, true);
            report(world, "lid away: " + packed.size() + " of " + this.columns.size() + " columns");
        }
    }

    // ===================================================================== lids

    /**
     * Clears the lid metadata on a column's <em>core</em>.
     *
     * <p>This is the same write the base mod's screwdriver does: put
     * {@code META} back to {@code DIR_NO_LID + 10}. It has to happen on the
     * core, because that is where the lid state lives - the blocks stacked
     * above carry the plain {@code +6} dummy value and would reject this
     * write.</p>
     *
     * <p>Flag 2 (send to clients) instead of 3 (send + notify neighbours). The
     * neighbours of a reactor column are other reactor columns, and waking them
     * up for a texture change is how you get a multiblock re-validating itself
     * in the middle of being blown apart. It is also the cheaper of the two on
     * a tick that is already spawning a crater.</p>
     */
    private static void clearLid(World world, BlockPos core) {
        int none = RbmkParts.lidNoneMeta();
        if (none < 0 || !world.isBlockLoaded(core)) {
            return;
        }
        if (RbmkParts.lidType(RbmkParts.lidMeta(world.getTileEntity(core))) <= 0) {
            return;
        }
        IBlockState state = world.getBlockState(core);
        IProperty<?> property = state.getBlock().getBlockState().getProperty("meta");
        if (property == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            IProperty<Integer> integerProperty = (IProperty<Integer>) property;
            world.setBlockState(core, state.withProperty(integerProperty, Integer.valueOf(none)), 2);
        } catch (Throwable ignored) {
            // A block whose meta property refuses this value is a block whose
            // lid we do not know how to remove; it stays on, and the plate
            // simply carries fewer lids than the reactor had.
        }
    }

    /**
     * Asks every client within range to shake.
     *
     * <p>Sent to the tracker, not to everyone: a blast two hundred blocks away
     * that nobody can see should not be shaking somebody's hotbar on the other
     * side of the map. The packet carries no entities, only a position and a
     * few numbers, so a radius query is the whole of the cost.</p>
     */
    public static void shake(World world, double x, double y, double z, float intensity, float range, int duration, boolean ring) {
        if (world == null || world.isRemote || !HBMZZConfig.rbmkShake) {
            return;
        }
        try {
            if (NetworkHandler.INSTANCE == null) {
                return;
            }
            NetworkHandler.INSTANCE.sendToAllAround(
                    new ShakeMessage(x, y, z, intensity, range, duration, ring),
                    new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
                            world.provider.getDimension(), x, y, z, range));
        } catch (Throwable ignored) {
            // A missing channel costs the shake, not the explosion.
        }
    }

    /**
     * Called by the plate when it has finished falling for the last time.
     *
     * <p>Destroys the reactor through the base mod's own meltdown, so the
     * wreckage - corium, debris, the lot - is the one players already know how
     * to read, rather than a second kind of crater this mod invented.</p>
     */
    public static void onLidSettled(EntityRbmkLid plate) {
        if (plate == null || plate.world == null || plate.world.isRemote) {
            return;
        }
        Tracked tracked = PLATES.remove(Integer.valueOf(plate.getEntityId()));
        if (tracked == null || !HBMZZConfig.rbmkSurgeMelt) {
            return;
        }
        for (BlockPos pos : tracked.columns) {
            if (!plate.world.isBlockLoaded(pos)) {
                continue;
            }
            TileEntity te = plate.world.getTileEntity(pos);
            if (te == null || !RbmkParts.isColumn(te)) {
                continue;
            }
            RbmkParts.meltdown(te);
            return;
        }
    }

    private static void report(World world, String message) {
        if (HBMZZ.logger != null) {
            HBMZZ.logger.info("[RBMK] {} at {}", message, describe(world));
        }
    }

    private static String describe(World world) {
        return world == null ? "?" : (world.provider.getDimension() + "");
    }
}
