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
import com.hbmzz.alert.AlertSync;
import com.hbmzz.alert.FacilityState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;

import java.util.List;

/**
 * One of the wall displays that show the facility code.
 *
 * <p>Holds no state worth saving. Everything it draws comes from the single
 * facility record, pushed to it by whoever is close enough to read it - which is
 * what makes the requirement "every display agrees" true by construction rather
 * than by discipline. Two displays can never disagree, because neither of them
 * remembers anything: they each hold a copy of the same record, and the copy is
 * overwritten wholesale on every packet.</p>
 *
 * <p>This matters at the moment it is hardest to test: when the code changes in
 * a dimension nobody is watching. The broadcast from
 * {@link AlertSync#push} updates every client that is connected, so a display
 * becomes correct the first time a player sees it again, with no event of its
 * own required.</p>
 */
public class TileEntityAlertDisplay extends TileEntity implements ITickable {

    private int refresh = 2;
    /** Last code/tick pushed from here, so a change can be sent immediately. */
    private int lastCode = -1;
    private long lastSince = -1L;

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        FacilityState state = FacilityState.get(this.world);
        if (state == null) {
            return;
        }
        boolean changed = state.codeId() != this.lastCode || state.since() != this.lastSince;
        if (!changed && --this.refresh > 0) {
            return;
        }
        this.refresh = Math.max(5, HBMZZConfig.alertRefreshTicks);
        this.lastCode = state.codeId();
        this.lastSince = state.since();
        List<EntityPlayer> players = this.world.playerEntities;
        double range = (double) HBMZZConfig.displayRange;
        double max = range * range;
        for (EntityPlayer player : players) {
            if (player instanceof EntityPlayerMP && player.getDistanceSqToCenter(this.pos) <= max) {
                AlertSync.to((EntityPlayerMP) player, this.world, state);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
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
    public boolean shouldRefresh(World world, net.minecraft.util.math.BlockPos pos,
                                 net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
