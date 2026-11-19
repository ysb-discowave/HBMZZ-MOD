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

package com.hbmzz.reactor.tile;

import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.ControlEventSystem;
import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.reactor.ReactorCoreMath;
import com.hbmzz.reactor.ReactorInput;
import com.hbmzz.reactor.ReactorMachine;
import com.hbmzz.reactor.ReactorOutput;
import com.hbmzz.reactor.ReactorPhase;
import com.hbmzz.reactor.ReactorSpec;
import com.hbmzz.reactor.ReactorState;
import com.hbmzz.reactor.block.BlockReactorCore;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The authoritative tile entity of the multi-block reactor core.
 *
 * <p>This class does no physics. Every tick it (1) scans the cluster around the
 * core and counts emitters, absorbers and stabilisers, (2) reads the redstone
 * depth of every control rod, (3) folds both into a {@link ReactorSpec} and a
 * {@link ReactorInput} via {@link ReactorCoreMath}, (4) hands them to
 * {@link ReactorMachine} for exactly one tick, and (5) reports the result out of
 * the front face and to the control panel. The decisions - whether a hot core
 * pushes back on the chain reaction or runs away - stay in the pure package.</p>
 *
 * <h3>Why the neighbours, not the faces</h3>
 * <p>Unlike the single-block reactor, which drives rods/coolant/start off three
 * redstone faces, the multi-block rig is a cluster the player lays down by hand.
 * The components <em>are</em> the inputs: an emitter raises rated output, an
 * absorber opens the coolant loop a notch, a stabiliser buys temperature
 * feedback, and a control rod throttles the reaction by its redstone depth. The
 * core just counts what the player built and lets the physics decide the rest.</p>
 *
 * <h3>Control-panel surface</h3>
 * <p>The machine control panel addresses this tile as an
 * {@link IControllable}. It can read {@code reactor_status} out events and query
 * {@code flux/heat/power/phase} directly, and it can drive three in-events:
 * {@code reactor_start} (latch a start for one tick), {@code reactor_scram}
 * (latch a scram), and {@code reactor_set_rod} (set a panel rod depth 0..100 that
 * overrides the redstone rods for as long as it stays set).</p>
 *
 * <h3>What is persisted</h3>
 * <p>Only the {@link ReactorState} and the panel rod set point. Component counts
 * and rod depths are re-scanned on load - a stale rig description in a save file
 * would be a lie the player would act on. The panel rod is saved so a console
 * wired to a throttle does not drop to "wide open" on world reload.</p>
 */
public class TileEntityReactorCore extends TileEntity implements ITickable, IControllable {

    private static final String NBT_STATE = "state";
    private static final String NBT_PHASE = "phase";
    private static final String NBT_FLUX = "flux";
    private static final String NBT_HEAT = "heat";
    private static final String NBT_TICKS = "ticks";
    private static final String NBT_PRODUCED = "produced";
    private static final String NBT_PANEL_ROD = "panelRod";

    /** Registry names of the four component blocks, counted or read around the core. */
    private static final String NAME_EMITTER = "hbmzz:reactor_emitter";
    private static final String NAME_ABSORBER = "hbmzz:reactor_absorber";
    private static final String NAME_STABILISER = "hbmzz:reactor_stabiliser";
    private static final String NAME_CONTROL_ROD = "hbmzz:reactor_control_rod";

    private ReactorState state = ReactorState.COLD;
    private ReactorOutput lastOutput;
    private int lastOutputLevel = -1;

    /**
     * Values recomputed each tick from the current rig, cached so a control-panel
     * {@link #getQueryData()} reads them back without triggering another scan.
     */
    private double lastRod = 0.0D;
    private double lastCoolant = 0.0D;
    private double lastRated = ReactorSpec.DEFAULT.ratedOutput;

    /** Panel-set rod depth 0..100; -1 means "follow the redstone rods". */
    private int panelRod = -1;
    /** One-tick latches driven by the control panel. */
    private boolean panelStart;
    private boolean panelScram;
    /** Redstone edge-detection for the back-face start / right-face scram. */
    private boolean lastBackPowered;
    private int rightHighTicks;

    // ------------------------------------------------------------------ queries

    public ReactorState state() {
        return this.state;
    }

    public ReactorOutput output() {
        return this.lastOutput;
    }

    public double flux() {
        return this.state.flux;
    }

    public double heat() {
        return this.state.heat;
    }

    public double power() {
        return this.lastOutput == null ? 0.0D : this.lastOutput.power;
    }

    /** Human-readable status for chat and the WAILA-style tooltip. */
    public String statusKey() {
        return this.state.phase.name().toLowerCase(Locale.ROOT);
    }

    /** Redstone strength off the front face, 0..15, proportional to power. */
    public int redstoneOutput() {
        return this.lastOutputLevel < 0 ? 0 : this.lastOutputLevel;
    }

    // -------------------------------------------------------------------- tick

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        Rig rig = scanRig();
        ReactorSpec spec = ReactorCoreMath.specFor(rig.emitters, rig.absorbers, rig.stabilisers);
        ReactorInput in = buildInput(rig);
        // Cache the rig-derived readings for the control panel; these are the
        // values a node graph or query wants to see, and they are exactly what
        // went into this tick's step.
        this.lastRated = spec.ratedOutput;
        this.lastCoolant = ReactorCoreMath.coolantFrom(rig.absorbers);
        this.lastRod = this.panelRod >= 0 ? this.panelRod
                : ReactorCoreMath.rodFrom(rig.rodDepths);
        this.state = ReactorMachine.step(this.state, in, spec);
        this.lastOutput = ReactorMachine.observe(this.state, in, spec);
        consumeLatches();
        publishOutput(spec);
    }

    /** One pass over the cluster: how many of each component, and the rod depths. */
    private Rig scanRig() {
        int emitters = 0;
        int absorbers = 0;
        int stabilisers = 0;
        int[] rodDepths = new int[0];
        List<Integer> rods = new ArrayList<>();

        EnumFacing facing = facing();
        for (int dx = -ReactorCoreMath.RADIUS; dx <= ReactorCoreMath.RADIUS; dx++) {
            for (int dy = -ReactorCoreMath.RADIUS; dy <= ReactorCoreMath.RADIUS; dy++) {
                for (int dz = -ReactorCoreMath.RADIUS; dz <= ReactorCoreMath.RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos p = this.pos.offset(dx, dy, dz);
                    IBlockState s = this.world.getBlockState(p);
                    Block b = s.getBlock();
                    if (b == null) {
                        continue;
                    }
                    String name = b.getRegistryName() == null ? "" : b.getRegistryName().toString();
                    switch (name) {
                        case NAME_EMITTER:
                            emitters++;
                            break;
                        case NAME_ABSORBER:
                            absorbers++;
                            break;
                        case NAME_STABILISER:
                            stabilisers++;
                            break;
                        case NAME_CONTROL_ROD:
                            // The rod's insertion depth is the redstone power arriving
                            // at it from any side - the strongest neighbour drives it.
                            // A rod with nothing wired to it reads 0 (fully withdrawn).
                            int depth = this.world.getRedstonePowerFromNeighbors(p);
                            rods.add(depth);
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        rodDepths = new int[rods.size()];
        for (int i = 0; i < rods.size(); i++) {
            rodDepths[i] = rods.get(i);
        }
        return new Rig(emitters, absorbers, stabilisers, rodDepths, facing);
    }

    /** Translate the rig plus the panel/redstone latches into one tick of input. */
    private ReactorInput buildInput(Rig rig) {
        double rod;
        if (this.panelRod >= 0) {
            // A panel-set throttle outranks the redstone rods until it is reset.
            rod = this.panelRod;
        } else {
            rod = ReactorCoreMath.rodFrom(rig.rodDepths);
        }
        double coolant = ReactorCoreMath.coolantFrom(rig.absorbers);
        boolean start = this.panelStart || redstoneStart(rig.facing);
        boolean scram = this.panelScram || redstoneScram(rig.facing);
        boolean fuel = this.state.phase != ReactorPhase.SPENT;
        return new ReactorInput(rod, coolant, fuel, start, scram);
    }

    /** Back face (opposite the front): a rising edge requests a start. */
    private boolean redstoneStart(EnumFacing facing) {
        EnumFacing back = facing.getOpposite();
        BlockPos p = this.pos.offset(back);
        boolean powered = Math.max(
                this.world.getRedstonePower(p, back),
                this.world.getRedstonePower(p, back.getOpposite())) > 0;
        boolean start = powered && !this.lastBackPowered;
        this.lastBackPowered = powered;
        return start;
    }

    /** Right face: held high for two ticks requests a scram. */
    private boolean redstoneScram(EnumFacing facing) {
        EnumFacing right = facing.rotateY();
        int p = this.world.getRedstonePower(this.pos.offset(right), right);
        if (p > 0) {
            this.rightHighTicks++;
        } else {
            this.rightHighTicks = 0;
        }
        return this.rightHighTicks >= 2;
    }

    private void consumeLatches() {
        this.panelStart = false;
        this.panelScram = false;
    }

    /** Push power out the front face; sync only when the strength actually moves. */
    private void publishOutput(ReactorSpec spec) {
        int level = levelFor(this.lastOutput.power, spec.ratedOutput);
        if (level == this.lastOutputLevel) {
            return;
        }
        this.lastOutputLevel = level;
        IBlockState state = this.world.getBlockState(this.pos);
        EnumFacing facing = state.getValue(BlockReactorCore.FACING);
        this.world.notifyNeighborsOfStateChange(this.pos.offset(facing),
                this.world.getBlockState(this.pos.offset(facing)).getBlock(), false);
        markDirty();
        sync();
    }

    private static int levelFor(double power, double rated) {
        if (rated <= 0.0D) {
            return 0;
        }
        int v = (int) Math.round(15.0D * (power / rated));
        if (v < 0) {
            return 0;
        }
        return v > 15 ? 15 : v;
    }

    private EnumFacing facing() {
        IBlockState s = this.world.getBlockState(this.pos);
        EnumFacing f = s.getValue(BlockReactorCore.FACING);
        return f != null ? f : EnumFacing.NORTH;
    }

    // ------------------------------------------------------------------- control

    @Override
    public List<String> getInEvents() {
        return Collections.unmodifiableList(Arrays.asList(
                "reactor_start", "reactor_scram", "reactor_set_rod"));
    }

    @Override
    public List<String> getOutEvents() {
        return Collections.unmodifiableList(Arrays.asList("reactor_status"));
    }

    @Override
    public Map<String, DataValue> getQueryData() {
        Map<String, DataValue> out = new LinkedHashMap<>();
        out.put("phase", new DataValueString(statusKey()));
        out.put("flux", new DataValueFloat((float) this.state.flux));
        out.put("heat", new DataValueFloat((float) this.state.heat));
        out.put("power", new DataValueFloat((float) power()));
        out.put("rod", new DataValueFloat((float) currentRod()));
        out.put("coolant", new DataValueFloat((float) this.lastCoolant));
        out.put("rated", new DataValueFloat((float) this.lastRated));
        out.put("scramming", new DataValueFloat(this.lastOutput != null && this.lastOutput.scramming ? 1F : 0F));
        return out;
    }

    private double currentRod() {
        // The cached reading is what this core actually ran with this tick; no need
        // to walk the rig again just to report it.
        return this.lastRod;
    }

    @Override
    public void receiveEvent(BlockPos from, ControlEvent evt) {
        if (evt == null || this.world == null || this.world.isRemote) {
            return;
        }
        String name = evt.name;
        if (name.equals("reactor_start")) {
            this.panelStart = true;
        } else if (name.equals("reactor_scram")) {
            this.panelScram = true;
        } else if (name.equals("reactor_set_rod")) {
            DataValue v = evt.vars.get("level");
            if (v != null) {
                double rod = v.getNumber();
                if (Double.isNaN(rod) || rod < 0.0D) {
                    rod = 0.0D;
                }
                this.panelRod = rod > 100.0D ? 100 : (int) Math.round(rod);
            } else if (evt.vars.containsKey("state")) {
                // Toggled off: fall back to the redstone rods.
                this.panelRod = -1;
            }
        }
        // Report back on the out event so a node aimed at this core sees it moved.
        ControlEventSystem.get(this.world)
                .broadcastToSubscribed(this, ControlEvent.newEvent("reactor_status")
                        .setVar("flux", (float) this.state.flux)
                        .setVar("heat", (float) this.state.heat)
                        .setVar("power", (float) power())
                        .setVar("phase", statusKey()));
    }

    @Override
    public BlockPos getControlPos() {
        return this.pos;
    }

    @Override
    public World getControlWorld() {
        return this.world;
    }

    @Override
    public void validate() {
        super.validate();
        if (this.world != null) {
            ControlEventSystem.get(this.world).addControllable(this);
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (this.world != null) {
            ControlEventSystem.get(this.world).removeControllable(this);
        }
    }

    // ----------------------------------------------------------------------- NBT

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey(NBT_STATE)) {
            NBTTagCompound tag = compound.getCompoundTag(NBT_STATE);
            ReactorPhase phase;
            try {
                phase = ReactorPhase.valueOf(tag.getString(NBT_PHASE));
            } catch (IllegalArgumentException e) {
                phase = ReactorPhase.OFFLINE;
            }
            this.state = new ReactorState(phase,
                    tag.getDouble(NBT_FLUX), tag.getDouble(NBT_HEAT),
                    tag.getInteger(NBT_TICKS), tag.getDouble(NBT_PRODUCED));
        }
        this.panelRod = compound.hasKey(NBT_PANEL_ROD) ? compound.getInteger(NBT_PANEL_ROD) : -1;
        this.lastOutputLevel = -1;
        this.lastOutput = ReactorMachine.observe(this.state, null, ReactorCoreMath.specFor(0, 0, 0));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(NBT_PHASE, this.state.phase.name());
        tag.setDouble(NBT_FLUX, this.state.flux);
        tag.setDouble(NBT_HEAT, this.state.heat);
        tag.setInteger(NBT_TICKS, this.state.ticks);
        tag.setDouble(NBT_PRODUCED, this.state.produced);
        compound.setTag(NBT_STATE, tag);
        if (this.panelRod >= 0) {
            compound.setInteger(NBT_PANEL_ROD, this.panelRod);
        }
        return compound;
    }

    // ---------------------------------------------------------------------- sync

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("level", this.lastOutputLevel);
        tag.setString("phase", this.state.phase.name());
        tag.setDouble("flux", this.state.flux);
        tag.setDouble("heat", this.state.heat);
        return new SPacketUpdateTileEntity(this.pos, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        // The client only needs the displayed values, never the physics.
        NBTTagCompound tag = pkt.getNbtCompound();
        this.lastOutputLevel = tag.getInteger("level");
        double flux = tag.hasKey("flux") ? tag.getDouble("flux") : 0.0D;
        double heat = tag.hasKey("heat") ? tag.getDouble("heat") : 0.0D;
        ReactorPhase phase;
        try {
            phase = ReactorPhase.valueOf(tag.getString("phase"));
        } catch (IllegalArgumentException e) {
            phase = ReactorPhase.OFFLINE;
        }
        this.state = this.state.with(phase, flux, heat, this.state.ticks, this.state.produced);
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    private void sync() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        IBlockState state = this.world.getBlockState(this.pos);
        this.world.notifyBlockUpdate(this.pos, state, state, 3);
    }

    /** One scan's worth of the rig, kept as a value so buildInput stays readable. */
    private static final class Rig {
        final int emitters;
        final int absorbers;
        final int stabilisers;
        final int[] rodDepths;
        final EnumFacing facing;

        Rig(int emitters, int absorbers, int stabilisers, int[] rodDepths, EnumFacing facing) {
            this.emitters = emitters;
            this.absorbers = absorbers;
            this.stabilisers = stabilisers;
            this.rodDepths = rodDepths;
            this.facing = facing;
        }
    }
}
