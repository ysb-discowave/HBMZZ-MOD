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

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "毁档" aftermath: a wave that erases a loaded facility from the save.
 *
 * <p>A vanilla explosion - however large its power - only removes blocks inside
 * a sphere and leaves the steel frame of a big base standing. That is not what
 * the top tier means. When an ANNIHILATION device goes off, this wave walks every
 * loaded chunk within range over successive ticks and clears its blocks and
 * entities, so the facility does not merely get damaged, it stops existing.</p>
 *
 * <h3>Why it is spread over ticks</h3>
 * <p>Clearing a loaded region is on the order of hundreds of thousands of block
 * writes. Doing it in the detonation tick would stall the server for seconds and
 * look like a crash. The work is budgeted per tick and resumed from a cursor, so
 * the blast is instant but the erasure reads as a wave rolling outwards and the
 * server keeps ticking throughout.</p>
 *
 * <h3>What survives, and why</h3>
 * <p>Bedrock and barriers are left alone: removing the bedrock floor drops the
 * whole map into the void, which is a corrupted save rather than a big crater.
 * Unloaded chunks are skipped, not force-loaded - forcing them would pull the
 * entire world into memory and turn a controlled wave into an OOM. Anything in
 * a loaded chunk that is not bedrock is fair game, including terrain, which is
 * what "erase the facility" means here.</p>
 *
 * <p>One wave per dimension at a time; a second annihilation replaces the first.
 * All state is in memory and is cleared on world unload and server stop, for the
 * same reason as {@link DfcBlastWatch}: a wave left in the map would resume in a
 * different save and erase it on load.</p>
 */
public final class NukeAnnihilation {

    public static final NukeAnnihilation INSTANCE = new NukeAnnihilation();

    /** dimension id → the wave currently rolling in it. */
    private final Map<Integer, Wave> waves = new HashMap<Integer, Wave>();

    private NukeAnnihilation() {
    }

    /**
     * Starts an annihilation wave centred on a detonation. Only the server calls
     * this; the guard is duplicated from the caller because a client that guesses
     * the tier wrong must never clear its own rendered chunks.
     */
    public static void start(World world, BlockPos center) {
        if (!HBMZZConfig.nukeAnnihilate || world == null || world.isRemote || center == null) {
            return;
        }
        int dim = world.provider.getDimension();
        INSTANCE.waves.put(dim, new Wave(world, center));
        if (HBMZZ.logger != null) {
            HBMZZ.logger.info(" annihilation wave started in dim {} at {} {} {}",
                    dim, center.getX(), center.getY(), center.getZ());
        }
    }

    /** True while a dimension is still being erased. */
    public static boolean active(int dim) {
        return INSTANCE.waves.containsKey(dim);
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.START || event.world == null || event.world.isRemote) {
            return;
        }
        World world = event.world;
        int dim = world.provider.getDimension();
        Wave wave = this.waves.get(dim);
        if (wave == null) {
            return;
        }
        // A new world in the same dimension id (a different save loaded in
        // place) is not the world the wave was started in. Drop the stale wave
        // instead of erasing the new save.
        if (wave.world != world) {
            this.waves.remove(dim);
            return;
        }
        if (!wave.step(world)) {
            this.waves.remove(dim);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world != null) {
            this.waves.remove(world.provider.getDimension());
        }
    }

    /** Called from the mod's serverStopping; see DfcBlastWatch for why this is not an event. */
    public void onServerStopped() {
        this.waves.clear();
    }

    /** One erasure job. Chunks are sorted nearest-first so the wave reads as an expanding front. */
    private static final class Wave {

        final World world;
        final BlockPos center;
        final double radiusSq;
        final List<int[]> chunks;

        int chunkIndex;
        int lx;
        int y;
        int lz;
        boolean entitiesDone;

        Wave(World world, BlockPos center) {
            this.world = world;
            this.center = center;
            double radius = Math.max(32.0D, HBMZZConfig.nukeAnnihilateRadius);
            this.radiusSq = radius * radius;

            int centerCx = center.getX() >> 4;
            int centerCz = center.getZ() >> 4;
            int reach = (int) Math.ceil(radius / 16.0D) + 1;
            List<int[]> found = new ArrayList<int[]>();
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    int cx = centerCx + dx;
                    int cz = centerCz + dz;
                    // Horizontal distance from the chunk's middle to the blast.
                    double ddx = (cx << 4) + 8 - center.getX();
                    double ddz = (cz << 4) + 8 - center.getZ();
                    // +128 covers the chunk's own half-diagonal, so a chunk that
                    // only clips the circle is still visited.
                    if (ddx * ddx + ddz * ddz <= radiusSq + 128.0D) {
                        found.add(new int[]{cx, cz});
                    }
                }
            }
            final double cx0 = center.getX();
            final double cz0 = center.getZ();
            java.util.Collections.sort(found, new Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    double ax = (a[0] << 4) + 8 - cx0;
                    double az = (a[1] << 4) + 8 - cz0;
                    double bx = (b[0] << 4) + 8 - cx0;
                    double bz = (b[1] << 4) + 8 - cz0;
                    return Double.compare(ax * ax + az * az, bx * bx + bz * bz);
                }
            });
            this.chunks = found;
        }

        /**
         * Processes up to {@code nukeAnnihilateBlocksPerTick} block coordinates
         * from the current cursor. Returns false when the wave is done.
         */
        boolean step(World world) {
            int budget = Math.max(1024, HBMZZConfig.nukeAnnihilateBlocksPerTick);
            while (budget > 0 && this.chunkIndex < this.chunks.size()) {
                int[] chunk = this.chunks.get(this.chunkIndex);
                if (!world.getChunkProvider().isChunkGeneratedAt(chunk[0], chunk[1])) {
                    advanceChunk();
                    continue;
                }
                if (!this.entitiesDone) {
                    clearEntities(world, chunk[0], chunk[1]);
                    this.entitiesDone = true;
                }

                int wx = (chunk[0] << 4) + this.lx;
                int wz = (chunk[1] << 4) + this.lz;
                double dx = wx - this.center.getX();
                double dz = wz - this.center.getZ();

                if (dx * dx + dz * dz <= this.radiusSq) {
                    clearColumn(world, wx, this.y, wz);
                    this.y++;
                    if (this.y > 255) {
                        this.y = 0;
                        nextColumn();
                    }
                } else {
                    // This column is outside the circle; skip all 256 levels.
                    nextColumn();
                }
                budget--;
            }
            return this.chunkIndex < this.chunks.size();
        }

        private void nextColumn() {
            this.lz++;
            if (this.lz > 15) {
                this.lz = 0;
                this.lx++;
                if (this.lx > 15) {
                    advanceChunk();
                }
            }
        }

        private void advanceChunk() {
            this.chunkIndex++;
            this.lx = 0;
            this.y = 0;
            this.lz = 0;
            this.entitiesDone = false;
        }

        private void clearColumn(World world, int x, int level, int z) {
            BlockPos pos = new BlockPos(x, level, z);
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            // Keep the world floor and admin barriers; remove everything else,
            // terrain included. Flags=2 sends the change to clients without
            // firing neighbour updates on every one of a million blocks.
            if (block == Blocks.AIR || block == Blocks.BEDROCK || block == Blocks.BARRIER) {
                return;
            }
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        }

        private void clearEntities(World world, int cx, int cz) {
            if (!HBMZZConfig.nukeAnnihilateEntities) {
                return;
            }
            AxisAlignedBB box = new AxisAlignedBB(cx << 4, 0, cz << 4,
                    (cx << 4) + 16, 256, (cz << 4) + 16);
            List<Entity> entities = world.getEntitiesWithinAABB(Entity.class, box);
            for (Entity entity : entities) {
                if (entity == null || entity.isDead) {
                    continue;
                }
                if (entity instanceof EntityLivingBase) {
                    entity.attackEntityFrom(DamageSource.GENERIC, Float.MAX_VALUE);
                } else {
                    entity.setDead();
                }
            }
        }
    }
}
