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

import com.hbmzz.HBMZZConfig;
import com.hbmzz.alert.AlertCode;
import com.hbmzz.alert.NukeService;
import com.hbmzz.block.BlockCodeBroadcaster;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The block that puts a code on every screen in the facility.
 *
 * <p>One preset per block, editable by right-clicking it: an operator wiring a
 * fire alarm does not want a menu, they want to click the box until it says RED
 * and then wire the lever. Sneak-right-clicking steps backwards, so overshooting
 * by one does not mean cycling through seven more.</p>
 *
 * <h3>Why the preset is not the current state</h3>
 * <p>These two things look alike and must not be merged. The preset is what this
 * box will shout when it is triggered - it is furniture, it persists, and it can
 * sit there for weeks. The facility code is what every screen currently shows -
 * it is one record for the save, and it changes when anything, including a
 * warhead, decides it should. A broadcaster whose preset is OMEGA still has to
 * show GREEN on its own lamp while the facility is fine.</p>
 */
public class TileEntityCodeBroadcaster extends TileEntity implements ITickable {

    /** Ships set to YELLOW: the first thing anybody wires up is a small warning. */
    private int preset = AlertCode.YELLOW.id();
    private boolean lastPowered;
    /** Counts broadcasts, so the player can see that the clock actually fired. */
    private int broadcasts;

    // ---- custom state mode ---------------------------------------------
    // A broadcaster can shout something that is not one of the eight standard
    // codes: a level (0..7) plus its own word, colour and text. The preset above
    // is still what it would shout if the custom mode is switched off; the two
    // never blend, so a box that "says GREEN" has to mean GREEN either way.
    private boolean customActive;
    private int customLevel = AlertCode.YELLOW.id();
    private String customLabel = "";
    private int customRgb;
    private String customZhTitle = "";
    private String customEnTitle = "";
    private String customZhDetail = "";
    private String customEnDetail = "";

    public AlertCode getPreset() {
        return AlertCode.byId(this.preset);
    }

    public boolean isCustomActive() {
        return this.customActive;
    }

    public int getCustomLevel() {
        return this.customLevel & 0x0F;
    }

    public String getCustomLabel() {
        return this.customLabel;
    }

    /** Packed 0xRRGGBB. */
    public int getCustomRgb() {
        return this.customRgb & 0xFFFFFF;
    }

    public String getCustomZhTitle() {
        return this.customZhTitle;
    }

    public String getCustomEnTitle() {
        return this.customEnTitle;
    }

    public String getCustomZhDetail() {
        return this.customZhDetail;
    }

    public String getCustomEnDetail() {
        return this.customEnDetail;
    }

    /**
     * Replaces the whole custom payload and marks the block dirty.
     *
     * @return false when the level is outside 0..7; the block then keeps its
     * previous settings rather than half-applying a broken one
     */
    public boolean applyCustom(boolean active, int level, String label, int rgb,
                               String zhTitle, String enTitle, String zhDetail, String enDetail) {
        if (level < 0 || level > 7) {
            return false;
        }
        this.customActive = active;
        this.customLevel = level & 0x0F;
        this.customLabel = clean(label, 24);
        this.customRgb = rgb & 0xFFFFFF;
        this.customZhTitle = clean(zhTitle, 32);
        this.customEnTitle = clean(enTitle, 48);
        this.customZhDetail = clean(zhDetail, 80);
        this.customEnDetail = clean(enDetail, 96);
        markDirty();
        sync();
        return true;
    }

    private static String clean(String value, int limit) {
        String text = value == null ? "" : value.trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public void setPreset(AlertCode code) {
        if (code == null) {
            return;
        }
        this.preset = code.id();
        markDirty();
        sync();
    }

    /** Steps the preset. {@code forward == false} walks back down the ladder. */
    public AlertCode step(boolean forward) {
        AlertCode next = forward ? getPreset().next() : getPreset().previous();
        setPreset(next);
        return next;
    }

    public int getBroadcasts() {
        return this.broadcasts;
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if ((this.world.getTotalWorldTime() & 1L) != 0L) {
            return;
        }
        boolean powered = this.world.isBlockPowered(this.pos);
        if (powered && !this.lastPowered) {
            fire();
        }
        this.lastPowered = powered;
        setPoweredState(powered);
    }

    private void fire() {
        this.broadcasts++;
        String where = "来自 " + this.pos.getX() + " " + this.pos.getY() + " " + this.pos.getZ();
        if (this.customActive) {
            // A custom code carries its own colour and words; the klaxon still
            // follows the level, not the label, so a quiet custom GREEN stays
            // quiet and a custom RED still screams.
            NukeService.raiseCustom(this.world, this.customLevel, this.customLabel, this.customRgb,
                    this.customZhTitle, this.customEnTitle, this.customZhDetail, this.customEnDetail,
                    "代码广播器", "", where);
            boolean alarm = this.customLevel >= AlertCode.RED.id();
            if (HBMZZConfig.alertKlaxon) {
                NukeService.klaxon(this.world, net.minecraft.init.SoundEvents.BLOCK_NOTE_BELL,
                        alarm ? 2.0F : 0.8F, alarm ? 0.7F : 1.2F);
            }
            markDirty();
            return;
        }
        AlertCode code = getPreset();
        NukeService.raise(this.world, code, "代码广播器", "", where);
        if (HBMZZConfig.alertKlaxon) {
            NukeService.klaxon(this.world, net.minecraft.init.SoundEvents.BLOCK_NOTE_BELL,
                    code.isAlarm() ? 2.0F : 0.8F, code.isAlarm() ? 0.7F : 1.2F);
        }
        markDirty();
    }

    private void setPoweredState(boolean powered) {
        IBlockState state = this.world.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockCodeBroadcaster
                && state.getValue(BlockCodeBroadcaster.POWERED) != powered) {
            this.world.setBlockState(this.pos, state.withProperty(BlockCodeBroadcaster.POWERED, powered), 3);
        }
    }

    private void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setByte("preset", (byte) this.preset);
        compound.setInteger("broadcasts", this.broadcasts);
        compound.setBoolean("lastPowered", this.lastPowered);
        compound.setBoolean("customActive", this.customActive);
        compound.setByte("customLevel", (byte) this.customLevel);
        compound.setString("customLabel", this.customLabel);
        compound.setInteger("customRgb", this.customRgb);
        compound.setString("customZhTitle", this.customZhTitle);
        compound.setString("customEnTitle", this.customEnTitle);
        compound.setString("customZhDetail", this.customZhDetail);
        compound.setString("customEnDetail", this.customEnDetail);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.preset = compound.hasKey("preset") ? compound.getByte("preset") : AlertCode.YELLOW.id();
        this.broadcasts = compound.getInteger("broadcasts");
        this.lastPowered = compound.getBoolean("lastPowered");
        this.customActive = compound.getBoolean("customActive");
        this.customLevel = compound.hasKey("customLevel")
                ? (compound.getByte("customLevel") & 0x0F) : this.customLevel;
        this.customLabel = compound.getString("customLabel");
        this.customRgb = compound.getInteger("customRgb") & 0xFFFFFF;
        this.customZhTitle = compound.getString("customZhTitle");
        this.customEnTitle = compound.getString("customEnTitle");
        this.customZhDetail = compound.getString("customZhDetail");
        this.customEnDetail = compound.getString("customEnDetail");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 3, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
