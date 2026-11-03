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

package com.hbmzz.tile;

import com.hbmzz.ctrl.CtrlEngine;
import com.hbmzz.ctrl.CtrlLink;
import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.CtrlRule;
import com.hbmzz.ctrl.CtrlWidget;
import com.hbmzz.ctrl.MachineBridge;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The machine control panel block entity: the panel, the six machine bindings,
 * and the half-second round that keeps both honest.
 *
 * <p>Server-authoritative, and not by accident. Every value an operator sets
 * ends up as an event in somebody's reactor, and every readout is a promise
 * about what that reactor is doing right now. If clients computed either, two
 * people standing at the same panel would see different numbers and could send
 * contradictory orders. So the server drives the round, and clients only paint
 * what they are told.</p>
 *
 * <p>The design (controls, links, rules) is saved with the world. The runtime
 * (last value, latched stop, rule cooldowns) is not: a panel that spent the
 * night with a rule cooling down should not come back believing it still is.</p>
 */
public class TileEntityCtrlPanel extends TileEntity implements ITickable {

    /** How far a machine may be from the panel and still be bindable. */
    public static final int LINK_RANGE = 24;

    private CtrlPanel panel = CtrlPanel.defaults();
    private int timer = CtrlEngine.TICK;
    /** Set when a link's cached keys/events need re-reading (bind, load). */
    private boolean linksDirty = true;
    /** Set when an operator turn should reach nearby clients on the next round. */
    private boolean pendingSync;

    public CtrlPanel getPanel() {
        return this.panel;
    }

    /** True when this panel has a rule that must run with nobody watching. */
    public boolean runsUnattended() {
        return this.panel.hasAutomation();
    }

    // ------------------------------------------------------------- design I/O

    /** Replaces the whole panel. Server side only; the packet handler validates. */
    public void setPanel(CtrlPanel next) {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        CtrlPanel copy = next == null ? new CtrlPanel() : next.copy();
        copy.validate();
        // Every link is re-checked against the world, because the design came
        // from a client. Range matters as much as "is it a machine": without
        // it, a modified client could point slot A at any reactor on the
        // server and operate it from here. An out-of-range slot is emptied
        // rather than deleted, so the controls pointed at it survive and start
        // working again the moment it is re-bound.
        for (int i = 0; i < CtrlPanel.LINKS; i++) {
            CtrlLink link = copy.links[i];
            if (link.isEmpty()) {
                continue;
            }
            boolean inRange = this.pos.distanceSq(link.pos) <= (double) LINK_RANGE * LINK_RANGE;
            if (!inRange || !MachineBridge.isControllable(this.world, link.pos)) {
                link.clear();
            } else {
                MachineBridge.describe(link, this.world);
            }
        }
        // Runtime values of operator controls survive a design save: rebuilding
        // a panel should not silently reset every knob to zero.
        for (int i = 0; i < Math.min(copy.widgets.size(), this.panel.widgets.size()); i++) {
            CtrlWidget fresh = copy.widgets.get(i);
            CtrlWidget old = this.panel.widgets.get(i);
            if (fresh.isOperator() && fresh.type == old.type) {
                fresh.value = old.value;
                fresh.latched = old.latched;
            }
        }
        this.panel = copy;
        this.linksDirty = true;
        markDirty();
        sync();
    }

    /**
     * Binds slot {@code slot} to the machine at {@code machinePos}, or clears it
     * when the position is null.
     *
     * @return false when the target is not a controllable machine in range
     */
    public boolean bind(int slot, BlockPos machinePos) {
        if (this.world == null || this.world.isRemote || slot < 0 || slot >= CtrlPanel.LINKS) {
            return false;
        }
        if (machinePos == null) {
            this.panel.forgetLink(slot);
            markDirty();
            sync();
            return true;
        }
        if (this.pos.distanceSq(machinePos) > (double) LINK_RANGE * LINK_RANGE) {
            return false;
        }
        if (!MachineBridge.isControllable(this.world, machinePos)) {
            return false;
        }
        CtrlLink link = this.panel.links[slot];
        link.set(machinePos, "");
        MachineBridge.describe(link, this.world);
        markDirty();
        sync();
        return true;
    }

    /** Operator input on one control. */
    public void operate(int index, double value) {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (CtrlEngine.operate(this.panel, this.world, index, value)) {
            markDirty();
            // Not synced here on purpose. A knob being dragged is sixty
            // packets a second, and answering each one with a full block
            // update would push the whole panel to every nearby client sixty
            // times a second. The dragging client already moved its own copy;
            // everyone else catches up on the next half-second round.
            this.pendingSync = true;
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (--this.timer > 0) {
            return;
        }
        this.timer = CtrlEngine.TICK;

        if (this.linksDirty) {
            this.linksDirty = false;
            for (CtrlLink link : this.panel.links) {
                if (!link.isEmpty()) {
                    MachineBridge.describe(link, this.world);
                }
            }
            markDirty();
        }

        boolean changed = CtrlEngine.refresh(this.panel, this.world, this.world.getTotalWorldTime());
        if (this.pendingSync) {
            this.pendingSync = false;
            changed = true;
        }
        if (changed) {
            markDirty();
            // Only pay for the block update when somebody is close enough to
            // read the screen; a panel ticking alone on a wall still runs its
            // rules, it just does not shout about the numbers.
            if (hasWatchers()) {
                sync();
            }
        }
    }

    private boolean hasWatchers() {
        for (EntityPlayer player : this.world.playerEntities) {
            if (player.getDistanceSqToCenter(this.pos) <= 48.0D * 48.0D) {
                return true;
            }
        }
        return false;
    }

    private void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setTag("ctrlPanel", this.panel.write());
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey("ctrlPanel")) {
            this.panel = CtrlPanel.read(compound.getCompoundTag("ctrlPanel"));
        }
        this.linksDirty = true;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    /** One-line description of a slot, for the editor's sidebar. */
    public String describeSlot(int slot) {
        if (slot < 0 || slot >= CtrlPanel.LINKS) {
            return "-";
        }
        CtrlLink link = this.panel.links[slot];
        if (link.isEmpty()) {
            return "未绑定";
        }
        String name = link.label.isEmpty() ? "机器" : link.label;
        return name + " @ " + link.pos.getX() + "," + link.pos.getY() + "," + link.pos.getZ();
    }

    /** Rules that currently point at a slot, used to warn before unbinding. */
    public int referencesTo(int slot) {
        int count = 0;
        for (CtrlWidget widget : this.panel.widgets) {
            if (widget.link == slot) {
                count++;
            }
        }
        for (CtrlRule rule : this.panel.rules) {
            if (rule.srcLink == slot) {
                count++;
            }
            if (rule.dstLink == slot) {
                count++;
            }
        }
        return count;
    }
}
