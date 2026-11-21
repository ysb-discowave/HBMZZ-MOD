/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.aegis;

import com.hbmzz.HBMZZ;
import com.hbmzz.network.AegisDataPacket;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime brain of one Aegis terminal.
 *
 * <p>On the server it samples the DFC on a slow clock and pushes a compact
 * frame to every player inside view range; with nobody watching it does no
 * reflection work at all. On the client it keeps the newest frame, a short
 * per-channel history ring and the boot stamp that drives the start-up sweep.</p>
 *
 * <p>Only persistent settings travel in NBT: the optional explicit core and
 * meter positions, the current page and the power switch. Samples and history
 * are intentionally ephemeral — a save file should reopen with a blank screen
 * that fills in on the first refresh, not with numbers frozen from yesterday.</p>
 */
public final class TileAegisTerminal extends TileEntity implements ITickable {

    private static final int HISTORY = AegisTelemetry.TREND_CAPACITY;

    // Geometry limits, in degrees / blocks. The panel stays within a wall tile
    // plus a small margin so a tilted screen never clips through the floor.
    public static final float TILT_MIN = -45.0F;
    public static final float TILT_MAX = 45.0F;
    public static final float WIDTH_MIN = 0.5F;
    public static final float WIDTH_MAX = 2.0F;
    public static final float HEIGHT_MIN = 0.5F;
    public static final float HEIGHT_MAX = 2.0F;

    private BlockPos boundCore;
    private BlockPos boundMeter;
    private int page;
    private boolean powered = true;

    // ---- wall-mount geometry (persistent, server-authoritative) ----
    /** Panel tilt around the local X axis, in degrees. Negative leans the top back. */
    private float tilt = 0.0F;
    /** Full panel width in blocks (renderer half-width = width / 2). */
    private float panelWidth = 0.92F;
    /** Full panel height in blocks (renderer half-height = height / 2). */
    private float panelHeight = 0.92F;

    private int pollTimer = AegisConfig.pollTicks;
    private boolean reportedFailure;

    // ---- client-only state ----
    private AegisTelemetry latest;
    private long lastArrivalMs;
    private long bootAt;
    private final float[] heatRing = new float[HISTORY];
    private final float[] energyRing = new float[HISTORY];
    private final float[] stabilityRing = new float[HISTORY];
    private int ringHead;
    private int ringCount;
    private long lastSampleTick = Long.MIN_VALUE;

    // ------------------------------------------------------------- accessors

    public int getPage() {
        return this.page;
    }

    public boolean isPowered() {
        return this.powered;
    }

    public BlockPos getBoundCore() {
        return this.boundCore;
    }

    public BlockPos getBoundMeter() {
        return this.boundMeter;
    }

    public void cyclePage() {
        this.page = (this.page + 1) % AegisTelemetry.PAGE_COUNT;
        markDirty();
        sync();
    }

    public void setPage(int value) {
        int next = Math.max(0, Math.min(AegisTelemetry.PAGE_COUNT - 1, value));
        if (next == this.page) {
            return;
        }
        this.page = next;
        markDirty();
        sync();
    }

    public void setPowered(boolean value) {
        if (value == this.powered) {
            return;
        }
        this.powered = value;
        if (value) {
            this.bootAt = System.currentTimeMillis();
        }
        markDirty();
        sync();
    }

    /** Drops the explicit binding so automatic discovery takes over again. */
    public void clearBinding() {
        this.boundCore = null;
        this.boundMeter = null;
        markDirty();
        sync();
    }

    public void setBinding(BlockPos core, BlockPos meter) {
        this.boundCore = core;
        this.boundMeter = meter;
        markDirty();
        sync();
    }

    // ------------------------------------------------------- geometry accessors

    public float getTilt() {
        return this.tilt;
    }

    public float getPanelWidth() {
        return this.panelWidth;
    }

    public float getPanelHeight() {
        return this.panelHeight;
    }

    /** Applies a geometry change with clamping; no-op (and no sync) if unchanged. */
    public void setGeometry(float newTilt, float newWidth, float newHeight) {
        float t = clamp(newTilt, TILT_MIN, TILT_MAX);
        float w = clamp(newWidth, WIDTH_MIN, WIDTH_MAX);
        float h = clamp(newHeight, HEIGHT_MIN, HEIGHT_MAX);
        if (t == this.tilt && w == this.panelWidth && h == this.panelHeight) {
            return;
        }
        this.tilt = t;
        this.panelWidth = w;
        this.panelHeight = h;
        markDirty();
        sync();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void update() {
        if (this.world == null) {
            return;
        }
        if (this.world.isRemote) {
            clientTick();
            return;
        }
        if (!this.powered || --this.pollTimer > 0) {
            return;
        }
        this.pollTimer = Math.max(2, AegisConfig.pollTicks);

        List<EntityPlayerMP> audience = viewers();
        if (audience.isEmpty()) {
            return;
        }
        AegisTelemetry frame;
        try {
            frame = AegisProbe.sample(this.world, this.pos, this.boundCore, this.boundMeter,
                    AegisConfig.scanRadius);
        } catch (Throwable failure) {
            if (!this.reportedFailure) {
                this.reportedFailure = true;
                HBMZZ.logger.error("Aegis terminal at " + this.pos + " failed to sample its DFC", failure);
            }
            return;
        }
        this.reportedFailure = false;
        for (EntityPlayerMP player : audience) {
            com.hbmzz.network.NetworkHandler.sendTo(player, new AegisDataPacket(this.pos, frame));
        }
    }

    private void clientTick() {
        if (this.bootAt == 0L && this.powered) {
            this.bootAt = System.currentTimeMillis();
        }
        if (this.latest == null) {
            this.bootAt = this.bootAt == 0L ? System.currentTimeMillis() : this.bootAt;
        }
    }

    /** Players close enough to read the panel, and not so far the feed is wasted. */
    private List<EntityPlayerMP> viewers() {
        List<EntityPlayerMP> out = new ArrayList<EntityPlayerMP>(2);
        double range = AegisConfig.viewRange;
        double max = range * range;
        for (EntityPlayer player : this.world.playerEntities) {
            if (player instanceof EntityPlayerMP && player.getDistanceSqToCenter(this.pos) <= max) {
                out.add((EntityPlayerMP) player);
            }
        }
        return out;
    }

    /** Called by the request handler when a viewer asks for an immediate frame. */
    public AegisTelemetry sampleNow() {
        if (this.world == null || this.world.isRemote) {
            return null;
        }
        try {
            return AegisProbe.sample(this.world, this.pos, this.boundCore, this.boundMeter,
                    AegisConfig.scanRadius);
        } catch (Throwable failure) {
            HBMZZ.logger.error("Aegis terminal at " + this.pos + " failed to answer a pull", failure);
            return null;
        }
    }

    // ---------------------------------------------------------------- client

    public AegisTelemetry getLatest() {
        return this.latest;
    }

    public long getBootAt() {
        return this.bootAt;
    }

    public long getLastArrivalMs() {
        return this.lastArrivalMs;
    }

    public boolean isSignalFresh() {
        return this.latest != null && System.currentTimeMillis() - this.lastArrivalMs < 5000L;
    }

    /** Folds a freshly arrived frame into the client-side history rings. */
    public void accept(AegisTelemetry frame) {
        if (frame == null) {
            return;
        }
        long now = System.currentTimeMillis();
        this.latest = frame;
        this.lastArrivalMs = now;
        if (this.bootAt == 0L) {
            this.bootAt = now;
        }
        if (frame.worldTick == this.lastSampleTick) {
            return;
        }
        this.lastSampleTick = frame.worldTick;

        this.heatRing[this.ringHead] = (float) frame.heatC;
        this.energyRing[this.ringHead] = (float) Math.min(Float.MAX_VALUE, Math.max(0.0D, frame.coreEnergy));
        this.stabilityRing[this.ringHead] = frame.stability;
        this.ringHead = (this.ringHead + 1) % HISTORY;
        if (this.ringCount < HISTORY) {
            this.ringCount++;
        }

        // Rebuild the frame's own ring oldest-to-newest so the renderer can draw
        // it without knowing about this tile's wrap-around bookkeeping.
        for (int i = 0; i < HISTORY; i++) {
            int index = (this.ringHead - this.ringCount + i + HISTORY * 2) % HISTORY;
            frame.trendHeat[i] = i < this.ringCount ? this.heatRing[index] : this.heatRing[this.ringHead];
            frame.trendEnergy[i] = i < this.ringCount ? this.energyRing[index] : this.energyRing[this.ringHead];
            frame.trendStability[i] = i < this.ringCount ? this.stabilityRing[index] : this.stabilityRing[this.ringHead];
        }
        frame.trendCount = this.ringCount;

        // Degrees per tick, derived from consecutive heat samples.
        if (this.ringCount >= 2) {
            int newest = (this.ringHead - 1 + HISTORY) % HISTORY;
            int previous = (this.ringHead - 2 + HISTORY) % HISTORY;
            frame.heatSlope = this.heatRing[newest] - this.heatRing[previous];
        } else {
            frame.heatSlope = 0.0F;
        }
    }

    @Override
    public net.minecraft.util.math.AxisAlignedBB getRenderBoundingBox() {
        // Grow enough for the largest allowed panel plus its tilt swing. A
        // screen that is tuned wide must not vanish when the player walks past
        // the block's own one-cube render bounds.
        double pad = WIDTH_MAX + 1.0D;
        return new AxisAlignedBB(getPos()).grow(pad, pad, pad);
    }

    // ------------------------------------------------------------------ nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if (this.boundCore != null) {
            compound.setLong("aegisCore", this.boundCore.toLong());
        }
        if (this.boundMeter != null) {
            compound.setLong("aegisMeter", this.boundMeter.toLong());
        }
        compound.setInteger("aegisPage", this.page);
        compound.setBoolean("aegisPowered", this.powered);
        compound.setFloat("aegisTilt", this.tilt);
        compound.setFloat("aegisWidth", this.panelWidth);
        compound.setFloat("aegisHeight", this.panelHeight);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.boundCore = compound.hasKey("aegisCore") ? BlockPos.fromLong(compound.getLong("aegisCore")) : null;
        this.boundMeter = compound.hasKey("aegisMeter") ? BlockPos.fromLong(compound.getLong("aegisMeter")) : null;
        this.page = Math.max(0, Math.min(AegisTelemetry.PAGE_COUNT - 1, compound.getInteger("aegisPage")));
        this.powered = !compound.hasKey("aegisPowered") || compound.getBoolean("aegisPowered");
        // Old terminals built before wall-mount support have no geometry keys.
        // Fall back to the defaults so a save upgrade keeps the original look.
        this.tilt = compound.hasKey("aegisTilt")
                ? clamp(compound.getFloat("aegisTilt"), TILT_MIN, TILT_MAX) : 0.0F;
        this.panelWidth = compound.hasKey("aegisWidth")
                ? clamp(compound.getFloat("aegisWidth"), WIDTH_MIN, WIDTH_MAX) : 0.92F;
        this.panelHeight = compound.hasKey("aegisHeight")
                ? clamp(compound.getFloat("aegisHeight"), HEIGHT_MIN, HEIGHT_MAX) : 0.92F;
        this.bootAt = System.currentTimeMillis();
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 4, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        // Preserve per-viewer client state that must not be reset by a page sync.
        AegisTelemetry keep = this.latest;
        long keepArrival = this.lastArrivalMs;
        long keepBoot = this.bootAt;
        readFromNBT(packet.getNbtCompound());
        this.latest = keep;
        this.lastArrivalMs = keepArrival;
        this.bootAt = keepBoot;
    }

    private void sync() {
        if (this.world != null && !this.world.isRemote) {
            IBlockState state = this.world.getBlockState(this.pos);
            this.world.notifyBlockUpdate(this.pos, state, state, 3);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
