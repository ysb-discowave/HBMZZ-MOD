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

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.alert.NukeService;
import com.hbmzz.alert.NukeSnapshot;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.NukeDataMessage;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen that watches one bomb.
 *
 * <p>Same shape as the DFC monitor, for the same reason: the server samples,
 * the sample travels to whoever can read the screen, and the client paints the
 * last one it received. There is no per-frame work on the server and no polling
 * from the client, and a screen in a dimension with nobody in it costs one
 * early-out per display interval.</p>
 *
 * <p>A monitor with nothing bound is not an error state. It paints the same
 * frame the DFC monitor does - "no device", with the binding instructions - because
 * a player who just placed the block needs to know what to do next, and a blank
 * screen says nothing.</p>
 */
public class TileEntityNukeMonitor extends TileEntityPanelScreen {

    private BlockPos nukePos;
    private int nukeDim;

    private int displayTimer = HBMZZConfig.displayInterval;

    /** Client-side copy of the newest sample; never serialized. */
    private NukeSnapshot clientData;
    private long clientDataAt;
    private boolean reportedFailure;

    public BlockPos getNukePos() {
        return this.nukePos;
    }

    public int getNukeDim() {
        return this.nukeDim;
    }

    public boolean hasNuke() {
        return this.nukePos != null;
    }

    public void bind(BlockPos pos, int dim) {
        this.nukePos = pos;
        this.nukeDim = dim;
        markDirty();
        sync();
    }

    public void unbind() {
        this.nukePos = null;
        this.nukeDim = 0;
        markDirty();
        sync();
    }

    /**
     * One sample of the bound device.
     *
     * <p>Read server-side and turned into a flat value object immediately: the
     * tile entity of the device may be in another dimension, may be invalidated
     * between two lines, and must not be handed to a packet. Copying the numbers
     * here is what makes the screen independent of the bomb's lifetime.</p>
     */
    public NukeSnapshot snapshot() {
        NukeSnapshot sample = new NukeSnapshot();
        if (this.world == null) {
            return sample;
        }
        sample.nowTick = this.world.getTotalWorldTime();
        if (this.nukePos != null) {
            sample.x = this.nukePos.getX();
            sample.y = this.nukePos.getY();
            sample.z = this.nukePos.getZ();
            sample.dim = this.nukeDim;
        }
        TileEntityNuke device = NukeService.find(this.world, this.nukePos, this.nukeDim);
        if (device == null) {
            return sample;
        }
        sample.hasDevice = true;
        sample.state = device.isCountingDown() ? NukeSnapshot.STATE_COUNTDOWN : NukeSnapshot.STATE_IDLE;
        sample.yield = device.getYield();
        sample.fuse = device.getFuseTicks();
        sample.countdown = device.getCountdown();
        if (device.getControllerPos() != null) {
            sample.hasController = true;
            sample.cx = device.getControllerPos().getX();
            sample.cy = device.getControllerPos().getY();
            sample.cz = device.getControllerPos().getZ();
            sample.cdim = device.getControllerDim();
        }
        return sample;
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (--this.displayTimer > 0) {
            return;
        }
        this.displayTimer = Math.max(2, HBMZZConfig.displayInterval);
        List<EntityPlayerMP> watchers = watchers();
        if (watchers.isEmpty()) {
            return;
        }
        NukeSnapshot sample;
        try {
            sample = snapshot();
        } catch (Throwable throwable) {
            if (!this.reportedFailure) {
                this.reportedFailure = true;
                HBMZZ.logger.error("Nuke monitor at " + this.pos + " could not read its device", throwable);
            }
            return;
        }
        for (EntityPlayerMP player : watchers) {
            NetworkHandler.sendTo(player, new NukeDataMessage(this.pos, sample));
        }
    }

    private List<EntityPlayerMP> watchers() {
        List<EntityPlayerMP> list = new ArrayList<EntityPlayerMP>(2);
        double range = (double) HBMZZConfig.displayRange;
        double max = range * range;
        for (EntityPlayer player : this.world.playerEntities) {
            if (player instanceof EntityPlayerMP && player.getDistanceSqToCenter(this.pos) <= max) {
                list.add((EntityPlayerMP) player);
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- client

    public void accept(NukeSnapshot sample) {
        if (sample == null) {
            return;
        }
        this.clientData = sample;
        this.clientDataAt = System.currentTimeMillis();
    }

    public NukeSnapshot getClientData() {
        return this.clientData;
    }

    /** True when no sample has arrived recently enough to trust the screen. */
    public boolean isStale() {
        return this.clientData == null || System.currentTimeMillis() - this.clientDataAt > 4000L;
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if (this.nukePos != null) {
            compound.setLong("nukePos", this.nukePos.toLong());
            compound.setInteger("nukeDim", this.nukeDim);
        }
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.nukePos = compound.hasKey("nukePos") ? BlockPos.fromLong(compound.getLong("nukePos")) : null;
        this.nukeDim = compound.getInteger("nukeDim");
    }

    // 更新包、倾角同步与渲染包围盒都在 TileEntityPanelScreen 里：倾角是这台机器的属性，
    // 不是这一块屏独有的东西，两边各写一份迟早会有一边忘了跟着改。
}
