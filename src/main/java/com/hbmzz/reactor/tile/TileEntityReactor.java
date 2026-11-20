/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3.
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

import com.hbmzz.reactor.ReactorInput;
import com.hbmzz.reactor.ReactorOutput;
import com.hbmzz.reactor.ReactorPhase;
import com.hbmzz.reactor.ReactorState;
import com.hbmzz.reactor.api.SimpleReactor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The in-world shell around a {@link SimpleReactor}.
 *
 * <p>This class does no physics. It translates redstone into a
 * {@link ReactorInput}, lets the reactor advance one tick, and translates the
 * {@link ReactorOutput} back into redstone strength. All the decisions live in
 * the pure package - exactly the split used by the DFC blast sensor, where the
 * server owns the one authoritative reading and the client only sees a bool.</p>
 *
 * <h3>Redstone contract</h3>
 * <ul>
 *   <li>Back face (opposite the panel): <b>control rods</b>, 0..15 mapped to
 *       0..100 insertion. 0 = rods out, 15 = rods fully in.</li>
 *   <li>Left face: <b>coolant valve</b>, 0..15 mapped to 0..1 flow.</li>
 *   <li>Right face: <b>start / scram</b>. A rising edge starts the core; a
 *       signal held high for two ticks requests a scram. This is polled, not
 *       event-driven, for the same reason as the nuke controller: a block
 *       loaded into an already-powered position must still see the edge.</li>
 *   <li>Front face: <b>output</b>, 0..15 proportional to delivered power.</li>
 * </ul>
 *
 * <h3>What is persisted</h3>
 * <p>Only the {@link ReactorState} (phase, flux, heat, ticks, lifetime output)
 * and the rod/coolant set points. The output is recomputed on load, never
 * saved - a stale output in a save file is a lie the player will act on.</p>
 */
public class TileEntityReactor extends TileEntity implements ITickable {

    private static final String NBT_STATE = "state";
    private static final String NBT_PHASE = "phase";
    private static final String NBT_FLUX = "flux";
    private static final String NBT_HEAT = "heat";
    private static final String NBT_TICKS = "ticks";
    private static final String NBT_PRODUCED = "produced";

    private final SimpleReactor reactor = new SimpleReactor(null);
    private boolean lastCommand;
    private int commandHighTicks;
    private int lastOutputLevel = -1;

    public SimpleReactor reactor() {
        return this.reactor;
    }

    public ReactorState state() {
        return this.reactor.state();
    }

    public ReactorOutput output() {
        return this.reactor.output();
    }

    /** Human-readable status for the GUI and the WAILA-style tooltip. */
    public String statusKey() {
        ReactorPhase p = this.reactor.state().phase;
        return p.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        ReactorInput in = readRedstone();
        this.reactor.tick(in);
        publishOutput();
    }

    /**
     * Sample the three input faces. The panel block stores its facing in the
     * block state; here we only need the horizontal direction to know which
     * neighbour is which.
     */
    private ReactorInput readRedstone() {
        int rods = 15;
        int coolant = 0;
        boolean command = false;
        IBlockState state = this.world.getBlockState(this.pos);
        net.minecraft.util.EnumFacing facing = state.getValue(
                com.hbmzz.reactor.block.BlockReactor.FACING);
        if (facing.getAxis().isHorizontal()) {
            net.minecraft.util.EnumFacing back = facing.getOpposite();
            net.minecraft.util.EnumFacing left = facing.rotateYCCW();
            net.minecraft.util.EnumFacing right = facing.rotateY();
            // Read each face from the direction the signal arrives. Using
            // getRedstonePowerFromNeighbors here would merge three independent
            // lines into one maximum and wire the core to the wrong control.
            rods = Math.max(this.world.getRedstonePower(this.pos.offset(back), back),
                    this.world.getRedstonePower(this.pos.offset(back), back.getOpposite()));
            coolant = this.world.getRedstonePower(this.pos.offset(left), left);
            command = this.world.getRedstonePower(this.pos.offset(right), right) > 0;
        }

        // Edge logic on the command line: one tick high = start, sustained high
        // = scram. A level that never changes does neither on its own.
        boolean start = command && !this.lastCommand;
        if (command) {
            this.commandHighTicks++;
        } else {
            this.commandHighTicks = 0;
        }
        boolean scram = this.commandHighTicks >= 2;
        this.lastCommand = command;

        boolean fuel = true; // the core is the fuel; a spent core is handled by the phase
        double rodInsertion = 100.0D * rods / 15.0D;
        double flow = coolant / 15.0D;
        return new ReactorInput(rodInsertion, flow, fuel, start, scram);
    }

    /** Push delivered power out of the front face and sync on change only. */
    private void publishOutput() {
        int level = this.reactor.output().redstoneLevel();
        if (level == this.lastOutputLevel) {
            return;
        }
        this.lastOutputLevel = level;
        // Tell the neighbours on the front face to re-sample our power. The
        // block reports strength through getWeakPower, so this one notification
        // is all a comparator or repeater needs to see the new level.
        IBlockState state = this.world.getBlockState(this.pos);
        net.minecraft.util.EnumFacing facing = state.getValue(
                com.hbmzz.reactor.block.BlockReactor.FACING);
        this.world.notifyNeighborsOfStateChange(this.pos.offset(facing),
                this.world.getBlockState(this.pos.offset(facing)).getBlock(), false);
        markDirty();
        sync();
    }

    // =================================================================== NBT

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
            ReactorState saved = new ReactorState(phase,
                    tag.getDouble(NBT_FLUX), tag.getDouble(NBT_HEAT),
                    tag.getInteger(NBT_TICKS), tag.getDouble(NBT_PRODUCED));
            this.reactor.restore(saved);
        }
        this.lastOutputLevel = -1;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        NBTTagCompound tag = new NBTTagCompound();
        ReactorState s = this.reactor.state();
        tag.setString(NBT_PHASE, s.phase.name());
        tag.setDouble(NBT_FLUX, s.flux);
        tag.setDouble(NBT_HEAT, s.heat);
        tag.setInteger(NBT_TICKS, s.ticks);
        tag.setDouble(NBT_PRODUCED, s.produced);
        compound.setTag(NBT_STATE, tag);
        return compound;
    }

    // ================================================================ sync

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("level", this.reactor.output().redstoneLevel());
        tag.setString("phase", this.reactor.state().phase.name());
        return new SPacketUpdateTileEntity(this.pos, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        // The client only needs the two displayed values, never the physics.
        this.lastOutputLevel = pkt.getNbtCompound().getInteger("level");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    private void sync() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        IBlockState state = this.world.getBlockState(this.pos);
        this.world.notifyBlockUpdate(this.pos, state, state, 3);
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
