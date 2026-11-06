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

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world == null || event.world.isRemote) {
            return;
        }
        if (!HBMZZConfig.rbmkSurge || !RbmkParts.available()) {
            return;
        }
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
        private final List<Tracked> reactors = new ArrayList<Tracked>();

        void tick(World world) {
            if (--this.scanIn <= 0) {
                this.scanIn = RbmkTiming.SCAN_INTERVAL;
                rescan(world);
            }
            for (Iterator<Tracked> it = this.reactors.iterator(); it.hasNext(); ) {
                Tracked tracked = it.next();
                if (tracked.columns.isEmpty()) {
                    it.remove();
                    continue;
                }
                tracked.tick(world);
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
                        report(world, "过热，控制棒开始跳动");
                    }
                    break;
                case WARMUP:
                    this.elapsed++;
                    jump(world);
                    if (--this.ventCooldown <= 0) {
                        this.ventCooldown = 8;
                        vent(world);
                    }
                    if (RbmkTiming.launchDue(this.elapsed, HBMZZConfig.rbmkSurgeTicks)) {
                        this.phase = RbmkTiming.Phase.SPENT;
                        launch(world);
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
                report(world, "过热，但没有安装诅咒版附属，控制棒不会跳动");
            }
        }

        /** Steam out of the lid, plus the noise of a boiler at its limit. */
        private void vent(World world) {
            int height = RbmkParts.columnHeight(world);
            for (BlockPos pos : this.columns) {
                if (!world.isBlockLoaded(pos)) {
                    continue;
                }
                double x = pos.getX() + 0.5D;
                double y = pos.getY() + height + 1.0D;
                double z = pos.getZ() + 0.5D;
                world.spawnParticle(EnumParticleTypes.CLOUD, x, y, z, 0.0D, 0.18D, 0.0D);
                if (world.rand.nextInt(6) == 0) {
                    world.spawnParticle(EnumParticleTypes.SMOKE_LARGE, x, y, z, 0.0D, 0.1D, 0.0D);
                }
            }
            BlockPos any = this.columns.get(0);
            world.playSound(null, any, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1.4F, 0.55F);
        }

        /**
         * Takes the lid off and throws it.
         *
         * <p>The lids are harvested one column at a time: each column's own
         * block carries its lid type in its metadata, so the only honest way to
         * know what is on top is to ask the base mod's own {@code metaToLid} and
         * then clear the metadata the same way its own lid-drop does. Nothing
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
            List<BlockPos> tops = new ArrayList<BlockPos>();

            for (BlockPos pos : this.columns) {
                if (!world.isBlockLoaded(pos)) {
                    continue;
                }
                TileEntity te = world.getTileEntity(pos);
                if (te == null || !RbmkParts.isColumn(te) || !RbmkParts.hasLid(te)) {
                    continue;
                }
                Block columnBlock = world.getBlockState(pos).getBlock();
                if (RbmkParts.hasOwnLid(columnBlock)) {
                    // Control rods draw their own lid as part of the rod; there
                    // is no plate to take off, and pretending otherwise would
                    // leave a rod floating over an open channel.
                    continue;
                }
                BlockPos lidPos = lidAt(world, pos, height);
                if (lidPos == null) {
                    continue;
                }
                IBlockState state = world.getBlockState(lidPos);
                Block block = state.getBlock();
                int lid = RbmkParts.lidType(metaOf(state));
                if (lid <= 0) {
                    continue;
                }
                String texture = RbmkParts.columnTexture(block);
                int index = textures.indexOf(texture);
                if (index < 0) {
                    if (textures.size() >= 8) {
                        continue;
                    }
                    index = textures.size();
                    textures.add(texture);
                }
                entries.add(new int[]{pos.getX(), pos.getZ(), lid, index});
                tops.add(lidPos);
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
                topY = Math.max(topY, lidPos.getY());
            }

            if (entries.isEmpty()) {
                // Nothing to throw - a core with no lids installed. Blow it up
                // anyway; the reactor was about to go regardless.
                BlockPos any = this.columns.get(0);
                world.createExplosion(null, any.getX() + 0.5D, any.getY() + height, any.getZ() + 0.5D,
                        HBMZZConfig.rbmkSurgePower, true);
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

            for (BlockPos pos : tops) {
                clearLid(world, pos);
            }
            for (BlockPos pos : this.columns) {
                clearLid(world, pos);
            }

            String layout = RbmkLayout.encode(packed, textures);
            EntityRbmkLid plate = new EntityRbmkLid(world, centreX, topY, centreZ, layout);
            world.spawnEntity(plate);
            PLATES.put(Integer.valueOf(plate.getEntityId()), this);

            world.createExplosion(null, centreX, topY, centreZ, HBMZZConfig.rbmkSurgePower, true);
            world.playSound(null, new BlockPos(centreX, topY, centreZ),
                    SoundEvents.ENTITY_ENDERDRAGON_FIREBALL_EPLD, SoundCategory.BLOCKS, 6.0F, 0.6F);
            report(world, "顶盖被炸飞");
        }
    }

    // ===================================================================== lids

    /** The lid block of a column, or null when it has none. */
    private static BlockPos lidAt(World world, BlockPos core, int height) {
        IBlockState coreState = world.getBlockState(core);
        Block columnBlock = coreState.getBlock();
        // Search down from one above the column: which block carries the lid
        // metadata depends on how the base mod assembled the multiblock, and
        // guessing wrong means either no lid or a hole in a working reactor.
        for (int dy = height + 1; dy >= 1; dy--) {
            BlockPos pos = core.up(dy);
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            IBlockState state = world.getBlockState(pos);
            if (state.getBlock() != columnBlock) {
                continue;
            }
            if (RbmkParts.lidType(metaOf(state)) > 0) {
                return pos;
            }
        }
        return null;
    }

    /** Clears the lid metadata, exactly as the base mod does when one drops. */
    private static void clearLid(World world, BlockPos pos) {
        int meta = RbmkParts.lidNoneMeta();
        if (meta < 0 || !world.isBlockLoaded(pos)) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (RbmkParts.lidType(metaOf(state)) <= 0) {
            return;
        }
        IProperty<?> property = state.getBlock().getBlockState().getProperty("meta");
        if (property == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            IProperty<Integer> integerProperty = (IProperty<Integer>) property;
            world.setBlockState(pos, state.withProperty(integerProperty, Integer.valueOf(meta)), 3);
        } catch (Throwable ignored) {
            // A block whose meta property refuses this value is a block whose
            // lid we do not know how to remove; it stays on, and the plate
            // simply carries fewer lids than the reactor had.
        }
    }

    private static int metaOf(IBlockState state) {
        return state.getBlock().getMetaFromState(state);
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
