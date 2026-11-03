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
 * Portions of the render structure and the offscreen-canvas pipeline are derived
 * from JiezhiMod by Shushang de Jiezhi, used under written permission to license
 * them under GPL-3.0. See AUTHORIZATION.md.
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.tile;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.dfc.DFCReader;
import com.hbmzz.dfc.DFCSnapshot;
import com.hbmzz.network.DFCDataMessage;
import com.hbmzz.network.DFCRequestMessage;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.panel.LogicProgram;
import com.hbmzz.panel.PanelEngine;
import com.hbmzz.panel.PanelLayout;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The monitor block. Besides holding the binding it runs the live feed: on the
 * server it samples the DFC, evaluates the custom panel's automation and pushes
 * the result to every player close enough to read the screen; on the client it
 * keeps a short history so the block face, the HUD and the terminal all draw
 * the same numbers without anyone having to right click.
 *
 * <h3>What lives here</h3>
 * <ul>
 *   <li><b>Binding</b> - which core and which power meter this screen watches.</li>
 *   <li><b>Design</b> - {@link PanelLayout} and {@link LogicProgram}: the
 *       operator's panel and its automation, saved with the world.</li>
 *   <li><b>Alarm state</b> - level, text and expiry, synced to clients so the
 *       panel can paint the alarm frame for everyone, not just the player who
 *       happened to be looking when it fired.</li>
 * </ul>
 *
 * <p>Design and runtime are separated on purpose: the design is what the studio
 * edits and what a save file carries, the runtime is what a tick produces. Only
 * the design is written to NBT; the runtime (cooldowns, edge memory) restarts
 * clean when the world loads, which is the behaviour an operator expects from
 * an alarm panel that was switched off overnight.</p>
 */
public class TileEntityDFCMonitor extends TileEntity implements ITickable {

    /** Samples kept for the sparklines (48 * 10 ticks = 24 s of history). */
    public static final int HISTORY = 48;

    private BlockPos corePos;
    private BlockPos meterPos;
    private BlockPos cameraPos;
    private boolean cameraEnabled;
    private int panelW = HBMZZConfig.defaultWidth;
    private int panelH = HBMZZConfig.defaultHeight;
    /** Size of the wall panel drawn on the block faces, in blocks. */
    private int screenCols = HBMZZConfig.defaultScreenCols;
    private int screenRows = HBMZZConfig.defaultScreenRows;
    /**
     * How far the front assembly tips forward, in {@link ConsoleRig} notches.
     * A ceiling-hung screen has to look down at the floor; a wall mount sits
     * flat. Stored with the block so every client sees the same posture.
     */
    private int tilt = ConsoleRig.TILT_CEILING;
    /** Which page the panel shows: 0 core, 1 parts, 2 charts, 3 custom. */
    private int page;
    /** Soft power switch, so the screen can be parked without breaking it. */
    private boolean powered = true;

    /**
     * Client-only clocks. Deliberately not serialized: they record when this
     * client last saw the screen power on, which is what drives the boot
     * animation, and that is a per-viewer fact rather than world state.
     */
    private long clientBootAt;
    private long clientFaultAt;
    /** Last sample used to derive the temperature trend for the +/-/s badge. */
    private double clientLastTemp = Double.NaN;
    private double clientTempRate;

    private int pollTimer = HBMZZConfig.displayInterval;
    /** Client-side pull timer, used only while the push feed has gone quiet. */
    private int clientPollTimer = 20;

    private long sampleTick = -1L;
    private double sampleDamage;
    private double damageRate;
    private boolean reportedFailure;

    /** Client-side copy of the newest telemetry sample; never serialized. */
    private DFCSnapshot clientData;
    private long clientDataAt;
    private long historyTick = Long.MIN_VALUE;
    private int historyIndex;
    private int historySize;
    private final double[] tempHistory = new double[HISTORY];
    private final double[] energyHistory = new double[HISTORY];
    private final double[] stabHistory = new double[HISTORY];

    // ------------------------------------------------------------ custom panel

    /** The operator's panel. Saved, synced and drawn on page 04. */
    private PanelLayout layout = PanelLayout.defaults();
    /** The block program that drives the alarm. */
    private LogicProgram program = LogicProgram.defaults();
    /** Runtime state; never serialized. */
    private final PanelEngine engine = new PanelEngine();

    /** 0 = none, 1 = amber warning, 2 = red alarm. Synced. */
    private int alarmLevel;
    private String alarmText = "";
    /** Absolute world tick the alarm stops at; 0 when idle. */
    private long alarmUntil;
    /** Repeating beeper, in ticks. Server only. */
    private int soundTicks;
    private double soundPitch = 1.0D;
    /** Redstone output strength and the ticks it stays high. Server only. */
    private int redstoneStrength;
    private int redstoneTicks;

    private int evalTimer = PanelEngine.EVAL_TICKS;
    private int displayTimer = HBMZZConfig.displayInterval;

    public BlockPos getCorePos() {
        return corePos;
    }

    public void setCorePos(BlockPos corePos) {
        this.corePos = corePos;
        markDirty();
        sync();
    }

    public BlockPos getMeterPos() {
        return meterPos;
    }

    public void setMeterPos(BlockPos meterPos) {
        this.meterPos = meterPos;
        markDirty();
        sync();
    }

    public BlockPos getCameraPos() {
        return cameraPos;
    }

    public void setCameraPos(BlockPos cameraPos) {
        this.cameraPos = cameraPos;
        markDirty();
        sync();
    }

    public boolean isCameraEnabled() {
        return cameraEnabled;
    }

    public void setCameraEnabled(boolean enabled) {
        this.cameraEnabled = enabled;
        markDirty();
        sync();
    }

    public int getPanelW() {
        return panelW;
    }

    public int getPanelH() {
        return panelH;
    }

    public void setPanelSize(int width, int height) {
        this.panelW = HBMZZConfig.clampWidth(width);
        this.panelH = HBMZZConfig.clampHeight(height);
        markDirty();
        sync();
    }

    public int getScreenCols() {
        return screenCols;
    }

    public int getScreenRows() {
        return screenRows;
    }

    /**
     * The volume the renderer needs, which is the whole wall panel plus its
     * forward swing - not the one block this tile entity sits in.
     *
     * <p>Without this the tile entity is culled by a one-block box. The console
     * is invisible as a block, so when the box falls outside the frustum there
     * is nothing to see at all: the cells read as plain air and the panel
     * vanishes, then pops back as the camera moves. It also has to reach
     * forward, because the panel pivots out in front of its cells.</p>
     */
    @Override
    public net.minecraft.util.math.AxisAlignedBB getRenderBoundingBox() {
        int cols = Math.max(1, screenCols);
        int rows = Math.max(1, screenRows);
        return new net.minecraft.util.math.AxisAlignedBB(getPos())
                .grow(cols + 1.0D, rows + 1.0D, cols + 1.0D);
    }

    /** Sets how many blocks the wall panel spans; anchored at this block. */
    public void setScreenSize(int cols, int rows) {
        this.screenCols = HBMZZConfig.clampCols(cols);
        this.screenRows = HBMZZConfig.clampRows(rows);
        markDirty();
        sync();
    }

    public int getTilt() {
        return this.tilt;
    }

    /** Tilt notch; the server is authoritative so two viewers never disagree. */
    public void setTilt(int tilt) {
        int clamped = ConsoleRig.clampTilt(tilt);
        if (clamped == this.tilt) {
            return;
        }
        this.tilt = clamped;
        markDirty();
        sync();
    }

    public int getPage() {
        return this.page;
    }

    public void setPage(int page) {
        int clamped = Math.max(0, Math.min(3, page));
        if (clamped == this.page) {
            return;
        }
        this.page = clamped;
        markDirty();
        sync();
    }

    public boolean isPowered() {
        return this.powered;
    }

    public void setPowered(boolean powered) {
        if (powered == this.powered) {
            return;
        }
        this.powered = powered;
        // Powering on restarts the boot sequence on every client that sees it,
        // and re-arms the "开机时" trigger.
        this.clientBootAt = powered ? 0L : this.clientBootAt;
        if (powered) {
            this.engine.markBoot();
        }
        markDirty();
        sync();
    }

    public long getClientBootAt() {
        return this.clientBootAt;
    }

    public void setClientBootAt(long at) {
        this.clientBootAt = at;
    }

    public long getClientFaultAt() {
        return this.clientFaultAt;
    }

    public void setClientFaultAt(long at) {
        this.clientFaultAt = at;
    }

    /** °C per tick, smoothed, derived from consecutive samples on the client. */
    public double getClientTempRate() {
        return this.clientTempRate;
    }

    // -------------------------------------------------------- custom panel API

    public PanelLayout getPanelLayout() {
        return this.layout;
    }

    public LogicProgram getLogicProgram() {
        return this.program;
    }

    public int getAlarmLevel() {
        return this.alarmLevel;
    }

    public String getAlarmText() {
        return this.alarmText;
    }

    /** True while the alarm is still inside its duration. Client-safe. */
    public boolean isAlarmActive() {
        if (this.alarmText == null || this.alarmText.isEmpty() || this.alarmUntil <= 0L) {
            return false;
        }
        return this.world == null || this.world.getTotalWorldTime() <= this.alarmUntil;
    }

    public int getRedstoneOutput() {
        return this.redstoneTicks > 0 ? this.redstoneStrength : 0;
    }

    /**
     * Replaces the design. Server side only; the packet handler calls it after
     * validating, and it is the single point where a client's edits become
     * world state.
     */
    public void setPanelDesign(PanelLayout layout, LogicProgram program) {
        if (!this.world.isRemote) {
            PanelLayout copy = layout == null ? PanelLayout.defaults() : layout.copy();
            LogicProgram nextProgram = program == null ? new LogicProgram() : program.copy();
            copy.validate();
            nextProgram.validate();
            this.layout = copy;
            this.program = nextProgram;
            this.engine.reset();
            markDirty();
            sync();
        }
    }

    /** Server side: clears the alarm and drops the redstone line. */
    public void clearAlarm() {
        this.alarmLevel = 0;
        this.alarmText = "";
        this.alarmUntil = 0L;
        this.soundTicks = 0;
        if (this.redstoneTicks > 0) {
            this.redstoneTicks = 0;
            notifyRedstone();
        }
        markDirty();
        sync();
    }

    /** Authoritative read of the core, server side. */
    public DFCSnapshot snapshot() {
        if (world == null) {
            return new DFCSnapshot();
        }
        DFCSnapshot snapshot = DFCReader.read(world, this.pos, corePos, meterPos, panelW, panelH);
        if (cameraEnabled && cameraPos != null) {
            TileEntity te = world.getTileEntity(cameraPos);
            if (te instanceof TileEntityMonitorCamera) {
                TileEntityMonitorCamera camera = (TileEntityMonitorCamera) te;
                TileEntityMonitorCamera.CameraView view = camera.capture(world);
                if (view != null) {
                    snapshot.coreType = "CAM:" + camera.getName();
                    snapshot.fuel1Name = camera.getChannel();
                    snapshot.fuel2Name = "ANGLE " + camera.getYaw() + "/" + camera.getPitch();
                }
            }
        }
        refinePrediction(snapshot);
        return snapshot;
    }

    /**
     * Estimates how long until the catalysts fail, from their actual damage
     * growth. The value is smoothed so a single noisy sample cannot flip the
     * countdown, and it simply stays absent while the damage is not moving.
     */
    private void refinePrediction(DFCSnapshot snapshot) {
        double damage = Math.max(snapshot.damageA, snapshot.damageB);
        long now = world.getTotalWorldTime();
        if (this.sampleTick >= 0L && now > this.sampleTick) {
            double delta = damage - this.sampleDamage;
            if (delta >= 0.0D) {
                double instant = delta / (double) (now - this.sampleTick);
                this.damageRate = this.damageRate <= 0.0D ? instant : this.damageRate * 0.75D + instant * 0.25D;
            }
            if (damage <= 0.01D) {
                this.damageRate = 0.0D;
            }
        }
        this.sampleTick = now;
        this.sampleDamage = damage;

        if (snapshot.predictionMode != 0 || !snapshot.hasCore || damage >= 100.0D || this.damageRate < 5.0E-4D) {
            return;
        }
        double seconds = (100.0D - damage) / this.damageRate / 20.0D;
        if (seconds > 1.0D && seconds < 21600.0D) {
            snapshot.predictionMode = 2;
            snapshot.predictedSeconds = (int) Math.ceil(seconds);
        }
    }

    @Override
    public void update() {
        if (world == null) {
            return;
        }
        if (world.isRemote) {
            clientTick();
            return;
        }
        tickAlarm();

        boolean displayDue = --this.displayTimer <= 0;
        boolean evalDue = --this.evalTimer <= 0;
        if (!displayDue && !evalDue) {
            return;
        }
        if (evalDue) {
            this.evalTimer = PanelEngine.EVAL_TICKS;
        }

        List<EntityPlayerMP> watchers = watchers();
        boolean needEval = !watchers.isEmpty() || this.program.needsBackgroundEval() || isAlarmActive();
        DFCSnapshot snapshot = null;

        if (evalDue && needEval) {
            snapshot = safeSnapshot();
            // A monitor with nothing bound has no feed: the "数据中断" trigger
            // is driven by coreFound going false, not by a reflection failure.
            this.engine.tick(this.program, snapshot, snapshot != null && snapshot.coreFound, this.sink);
            if (this.alarmEnded()) {
                this.alarmLevel = 0;
                this.alarmText = "";
                this.alarmUntil = 0L;
                sync();
            }
        }

        if (displayDue) {
            this.displayTimer = Math.max(2, HBMZZConfig.displayInterval);
            if (!watchers.isEmpty()) {
                if (snapshot == null) {
                    snapshot = safeSnapshot();
                }
                if (snapshot != null) {
                    for (EntityPlayerMP player : watchers) {
                        NetworkHandler.sendTo(player, new DFCDataMessage(this.pos, snapshot));
                    }
                }
            }
        }
    }

    /** True when the alarm ran out but the flag is still set; needs one sync. */
    private boolean alarmEnded() {
        return this.alarmUntil > 0L && this.world.getTotalWorldTime() > this.alarmUntil;
    }

    /** One reflection read, with the same guard the monitor always had. */
    private DFCSnapshot safeSnapshot() {
        try {
            return snapshot();
        } catch (Throwable throwable) {
            // This block reads a third party's internals through reflection; a
            // bad sample must never take the server down with it.
            if (!reportedFailure) {
                reportedFailure = true;
                HBMZZ.logger.error("DFC monitor at " + this.pos + " could not read its core", throwable);
            }
            return null;
        }
    }

    /**
     * Runs every server tick: keeps the beeper going while the alarm lasts and
     * drops the redstone line when its time is up.
     */
    private void tickAlarm() {
        long now = this.world.getTotalWorldTime();
        if (this.soundTicks > 0) {
            this.soundTicks--;
            if (this.soundTicks % 20 == 0) {
                playTone(this.soundPitch);
            }
        } else if (this.alarmLevel > 0 && now <= this.alarmUntil && now % 20L == 0L) {
            // A steady one-per-second pulse, pitched by severity. Not a looped
            // sound: a looping one survives the chunk unloading.
            playTone(this.alarmLevel >= 2 ? 1.15D : 0.72D);
        }
        if (this.redstoneTicks > 0) {
            this.redstoneTicks--;
            if (this.redstoneTicks == 0) {
                this.redstoneStrength = 0;
                notifyRedstone();
            }
        }
    }

    private void playTone(double pitch) {
        SoundEvent tone = this.alarmLevel >= 2 ? net.minecraft.init.SoundEvents.BLOCK_NOTE_BELL
                : net.minecraft.init.SoundEvents.BLOCK_NOTE_PLING;
        this.world.playSound(null, this.pos, tone, SoundCategory.BLOCKS, 1.0F, (float) pitch);
    }

    private void notifyRedstone() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyNeighborsOfStateChange(this.pos,
                    this.world.getBlockState(this.pos).getBlock(), false);
        }
    }

    /**
     * Where the rule engine's effects land.
     *
     * <p>Everything is applied here rather than inside the engine so the engine
     * stays a pure decision maker - which is what makes it possible to reason
     * about a program without a world in hand.</p>
     */
    private final PanelEngine.Effect sink = new PanelEngine.Effect() {

        @Override
        public void alarm(int level, String text, int ticks) {
            TileEntityDFCMonitor.this.alarmLevel = Math.max(1, Math.min(2, level));
            TileEntityDFCMonitor.this.alarmText = text == null ? "" : text;
            TileEntityDFCMonitor.this.alarmUntil = TileEntityDFCMonitor.this.world.getTotalWorldTime() + ticks;
            TileEntityDFCMonitor.this.soundTicks = ticks;
            TileEntityDFCMonitor.this.soundPitch = level >= 2 ? 1.15D : 0.72D;
            markDirty();
            sync();
        }

        @Override
        public void banner(String text, int ticks) {
            TileEntityDFCMonitor.this.alarmLevel = 0;
            TileEntityDFCMonitor.this.alarmText = text == null ? "" : text;
            TileEntityDFCMonitor.this.alarmUntil = TileEntityDFCMonitor.this.world.getTotalWorldTime() + ticks;
            markDirty();
            sync();
        }

        @Override
        public void sound(double pitch, int ticks) {
            TileEntityDFCMonitor.this.soundTicks = ticks;
            TileEntityDFCMonitor.this.soundPitch = pitch;
        }

        @Override
        public void page(int page) {
            setPage(page);
        }

        @Override
        public void log(String text) {
            // A panel has no chat window: the event is written to the banner
            // strip, and only when nothing more urgent is on it.
            if (TileEntityDFCMonitor.this.alarmLevel == 0 && !isAlarmActive()) {
                banner("\u25c7 " + text, 60);
            }
        }

        @Override
        public void redstone(int strength, int ticks) {
            TileEntityDFCMonitor.this.redstoneStrength = strength;
            TileEntityDFCMonitor.this.redstoneTicks = Math.max(1, ticks);
            notifyRedstone();
        }

        @Override
        public void clearAlarm() {
            TileEntityDFCMonitor.this.clearAlarm();
        }
    };

    /**
     * Client side: ask for a sample when the push feed has stopped.
     *
     * <p>The server pushes to everyone within {@code displayRange}, which is the
     * cheap path, but a screen that only updates while some server-side timer
     * happens to run is a screen that silently freezes. This pulls once a second
     * whenever the newest sample is older than three seconds, so a player
     * looking at a monitor always sees it come back.</p>
     */
    private void clientTick() {
        // Start the boot clock here rather than in the renderer. This is the
        // first client tick after the tile entity exists - which is exactly
        // "the screen was just placed" or "the save just finished loading" -
        // and it runs whether or not the panel is on screen. Starting it in the
        // renderer instead meant the terminal window, which does not render the
        // world, kept reading a boot stamp of 0 and sat on the first frame of
        // the start-up screen forever.
        if (this.powered && this.clientBootAt == 0L) {
            this.clientBootAt = System.currentTimeMillis();
        }
        if (--this.clientPollTimer > 0) {
            return;
        }
        this.clientPollTimer = 20;
        if (getClientDataAge() < 3000L) {
            return;
        }
        NetworkHandler.sendToServer(new DFCRequestMessage(this.pos));
    }

    /** Milliseconds since the newest sample arrived, or a large value if none has. */
    public long getClientDataAge() {
        if (this.clientDataAt == 0L) {
            return Long.MAX_VALUE / 4L;
        }
        return Math.max(0L, System.currentTimeMillis() - this.clientDataAt);
    }

    private List<EntityPlayerMP> watchers() {
        List<EntityPlayerMP> list = new ArrayList<>(2);
        double range = (double) HBMZZConfig.displayRange;
        double max = range * range;
        for (EntityPlayer player : this.world.playerEntities) {
            if (player instanceof EntityPlayerMP && player.getDistanceSqToCenter(this.pos) <= max) {
                list.add((EntityPlayerMP) player);
            }
        }
        return list;
    }

    private void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    // ---------------------------------------------------------------- client

    public void acceptData(DFCSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        long now = System.currentTimeMillis();
        this.clientData = snapshot;
        this.clientDataAt = now;
        if (snapshot.tick != this.historyTick) {
            long elapsed = this.historyTick == Long.MIN_VALUE ? 0L : snapshot.tick - this.historyTick;
            if (elapsed > 0L && !Double.isNaN(this.clientLastTemp)) {
                double instant = (snapshot.temperature - this.clientLastTemp) / (double) elapsed;
                // Smoothed: a single hot tick must not slam the badge around.
                this.clientTempRate = this.clientTempRate * 0.7D + instant * 0.3D;
            }
            this.clientLastTemp = snapshot.temperature;
            this.historyTick = snapshot.tick;
            this.tempHistory[this.historyIndex] = snapshot.temperature;
            this.energyHistory[this.historyIndex] = snapshot.containedEnergy;
            this.stabHistory[this.historyIndex] = snapshot.stabilization;
            this.historyIndex = (this.historyIndex + 1) % HISTORY;
            if (this.historySize < HISTORY) {
                this.historySize++;
            }
        }
        // The fault page is driven off the collapse edge: latch the moment it
        // starts, clear it the moment the core is calm again.
        if (snapshot.collapsing) {
            if (this.clientFaultAt == 0L) {
                this.clientFaultAt = now;
            }
        } else {
            this.clientFaultAt = 0L;
        }
    }

    public DFCSnapshot getClientData() {
        return this.clientData;
    }

    /** True when the feed stopped long enough that the screen should blank out. */
    public boolean isStale() {
        return this.clientData == null || System.currentTimeMillis() - this.clientDataAt > 4000L;
    }

    public int getHistorySize() {
        return this.historySize;
    }

    /** Oldest-to-newest sample {@code back} steps behind the newest one. */
    public double history(int channel, int back) {
        double[] values = channel == 0 ? this.tempHistory : channel == 1 ? this.energyHistory : this.stabHistory;
        int index = (this.historyIndex - 1 - back + HISTORY * 2) % HISTORY;
        return values[index];
    }

    // ------------------------------------------------------------------ nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if (corePos != null) {
            compound.setLong("corePos", corePos.toLong());
        }
        if (meterPos != null) {
            compound.setLong("meterPos", meterPos.toLong());
        }
        compound.setInteger("panelW", panelW);
        compound.setInteger("panelH", panelH);
        compound.setInteger("screenCols", screenCols);
        compound.setInteger("screenRows", screenRows);
        // Page and power have to travel with the block update tag. Without them
        // every sync (a page click, a size change) deserialized the client copy
        // back to page 0 / powered on, so the terminal looked like it ignored
        // the button that had just been pressed.
        compound.setInteger("page", page);
        compound.setBoolean("powered", powered);
        compound.setInteger("tilt", tilt);
        compound.setTag("panelLayout", this.layout.write());
        compound.setTag("panelLogic", this.program.write());
        compound.setInteger("alarmLevel", this.alarmLevel);
        compound.setString("alarmText", this.alarmText == null ? "" : this.alarmText);
        compound.setLong("alarmUntil", this.alarmUntil);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey("corePos")) {
            corePos = BlockPos.fromLong(compound.getLong("corePos"));
        }
        if (compound.hasKey("meterPos")) {
            meterPos = BlockPos.fromLong(compound.getLong("meterPos"));
        }
        panelW = HBMZZConfig.clampWidth(compound.getInteger("panelW") > 0
                ? compound.getInteger("panelW") : HBMZZConfig.defaultWidth);
        panelH = HBMZZConfig.clampHeight(compound.getInteger("panelH") > 0
                ? compound.getInteger("panelH") : HBMZZConfig.defaultHeight);
        screenCols = HBMZZConfig.clampCols(compound.getInteger("screenCols") > 0
                ? compound.getInteger("screenCols") : HBMZZConfig.defaultScreenCols);
        screenRows = HBMZZConfig.clampRows(compound.getInteger("screenRows") > 0
                ? compound.getInteger("screenRows") : HBMZZConfig.defaultScreenRows);
        tilt = ConsoleRig.clampTilt(compound.getInteger("tilt"));
        page = Math.max(0, Math.min(3, compound.getInteger("page")));
        powered = !compound.hasKey("powered") || compound.getBoolean("powered");
        if (compound.hasKey("panelLayout")) {
            this.layout = PanelLayout.read(compound.getCompoundTag("panelLayout"));
        }
        if (compound.hasKey("panelLogic")) {
            this.program = LogicProgram.read(compound.getCompoundTag("panelLogic"));
        }
        this.alarmLevel = compound.getInteger("alarmLevel");
        this.alarmText = compound.getString("alarmText");
        this.alarmUntil = compound.getLong("alarmUntil");
        // A save just finished loading: that is one of the three moments the
        // panel is supposed to boot, and "开机时" rules key off the same edge.
        this.engine.markBoot();
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 3, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        // The terminal window size is a per-viewer preference, but it lives in
        // the same NBT the server sends. Reading the packet wholesale reset the
        // window to the server's default on every sync, which is why resizing
        // never survived a page or size change.
        int localPanelW = this.panelW;
        int localPanelH = this.panelH;
        readFromNBT(packet.getNbtCompound());
        this.panelW = localPanelW;
        this.panelH = localPanelH;
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
