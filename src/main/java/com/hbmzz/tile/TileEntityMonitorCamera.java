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

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class TileEntityMonitorCamera extends TileEntity {

    public static final class CameraView {
        public final int width;
        public final int height;
        public final int[] pixels;
        public final String name;
        public final String channel;
        public final int yaw;
        public final int pitch;

        public CameraView(int width, int height, int[] pixels, String name, String channel, int yaw, int pitch) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
            this.name = name;
            this.channel = channel;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private String name = "CAM-01";
    private String channel = "CH-01";
    private int yaw;
    private int pitch;
    /** In blocks; the view cone is about 10x6. */
    private int range = 12;

    /**
     * Client-side copy of the newest frame.
     *
     * <p>{@link #capture} walks 160 rays of up to {@code range * 6} samples
     * each, and both the wall renderer and the window ask for a frame once per
     * rendered frame - so an uncached capture is ~11k block lookups at 60 Hz,
     * on the render thread. A tenth of a second of staleness is invisible on a
     * security feed and costs 10 captures a second instead of 60.</p>
     */
    private CameraView cachedView;
    private long cachedAt;

    private static final long VIEW_CACHE_MS = 100L;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.trim().isEmpty() ? "CAM-01" : name.trim();
        markDirty();
        sync();
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel == null || channel.trim().isEmpty() ? "CH-01" : channel.trim();
        markDirty();
        sync();
    }

    public int getYaw() {
        return yaw;
    }

    public void setYaw(int yaw) {
        this.yaw = MathHelper.wrapDegrees(yaw);
        markDirty();
        sync();
    }

    public int getPitch() {
        return pitch;
    }

    public void setPitch(int pitch) {
        this.pitch = MathHelper.clamp(pitch, -90, 90);
        markDirty();
        sync();
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        this.range = Math.max(4, range);
        markDirty();
        sync();
    }

    public CameraView capture(World world) {
        if (world == null) {
            return null;
        }
        final int width = 16;
        final int height = 10;
        int[] pixels = new int[width * height];

        double yawRad = Math.toRadians(this.yaw);
        double pitchRad = Math.toRadians(this.pitch);
        Vec3d origin = new Vec3d(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D);
        Vec3d forward = new Vec3d(Math.cos(yawRad) * Math.cos(pitchRad), Math.sin(pitchRad),
                Math.sin(yawRad) * Math.cos(pitchRad));
        Vec3d right = new Vec3d(-Math.sin(yawRad), 0.0D, Math.cos(yawRad));
        Vec3d up = new Vec3d(0.0D, 1.0D, 0.0D);

        double halfW = 2.3D;
        double halfH = 1.5D;
        for (int y = 0; y < height; y++) {
            double v = ((double) y / (double) (height - 1) - 0.5D) * 2.0D;
            for (int x = 0; x < width; x++) {
                double u = ((double) x / (double) (width - 1) - 0.5D) * 2.0D;
                Vec3d target = origin
                        .add(forward.scale(this.range))
                        .add(right.scale(u * halfW))
                        .add(up.scale(v * halfH));

                int color = sampleColor(world, origin, target);
                pixels[y * width + x] = color;
            }
        }
        return new CameraView(width, height, pixels, this.name, this.channel, this.yaw, this.pitch);
    }

    /**
     * The frame to draw, reused for {@link #VIEW_CACHE_MS}.
     *
     * <p>Callers are per-frame code - the wall renderer and the monitor window -
     * so this is the entry point they should use; {@link #capture} stays public
     * for the one-shot case (a screenshot, a test).</p>
     */
    public CameraView view(World world) {
        long now = System.currentTimeMillis();
        if (this.cachedView != null && now - this.cachedAt < VIEW_CACHE_MS) {
            return this.cachedView;
        }
        this.cachedView = capture(world);
        this.cachedAt = now;
        return this.cachedView;
    }

    private int sampleColor(World world, Vec3d origin, Vec3d target) {
        Vec3d step = target.subtract(origin);
        double length = step.lengthVector();
        if (length <= 0.0D) {
            return 0xFF101010;
        }

        int steps = Math.max(6, (int) (length * 6.0D));
        double xStep = step.x / steps;
        double yStep = step.y / steps;
        double zStep = step.z / steps;
        int mixed = 0xFF0B1015;
        int alpha = 0xFF;

        for (int i = 0; i <= steps; i++) {
            int px = (int) Math.floor(origin.x + xStep * i);
            int py = (int) Math.floor(origin.y + yStep * i);
            int pz = (int) Math.floor(origin.z + zStep * i);
            BlockPos pos = new BlockPos(px, py, pz);
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (block == null || state.getMaterial().isLiquid() || state.getMaterial().isReplaceable()) {
                continue;
            }
            MapColor mapColor = state.getMapColor(world, pos);
            int c = mapColor != null ? mapColor.colorValue : 0xFF939393;
            alpha = 0xFF;
            mixed = (0xFF000000 | c) & 0xFFFFFFFF;
            break;
        }
        return (alpha << 24) | (mixed & 0x00FFFFFF);
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
        compound.setString("camName", name);
        compound.setString("camChannel", channel);
        compound.setInteger("camYaw", yaw);
        compound.setInteger("camPitch", pitch);
        compound.setInteger("camRange", range);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        // New aim or new name, new picture: drop the cached frame so the screen
        // does not keep showing the old direction for a tenth of a second.
        this.cachedView = null;
        name = compound.getString("camName");
        channel = compound.getString("camChannel");
        if (name == null || name.trim().isEmpty()) {
            name = "CAM-01";
        }
        if (channel == null || channel.trim().isEmpty()) {
            channel = "CH-01";
        }
        yaw = compound.getInteger("camYaw");
        pitch = compound.getInteger("camPitch");
        range = compound.getInteger("camRange");
        if (range < 4) {
            range = 12;
        }
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
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }
}
