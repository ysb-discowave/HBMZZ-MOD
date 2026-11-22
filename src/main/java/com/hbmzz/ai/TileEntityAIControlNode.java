/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The AI control node: polls a bound machine, asks an AI service whether the
 * readings are abnormal, and raises a facility-wide voice alarm when they are.
 *
 * <p>The per-node state is small and entirely persistent: which machine is
 * bound (position + dimension), whether the node is armed, and the last known
 * status for the status lamp. The AI endpoint / key / model are global
 * ({@link AiConfig}) so an operator configures the service once, not once per
 * block. Thresholds are deliberately left to the AI: the node ships a snapshot
 * of every numeric reading and trusts the verdict, which is what lets the same
 * block watch a DFC core, a reactor or any generic machine.</p>
 *
 * <p><b>Threading:</b> the only asynchronous part is the HTTP call in
 * {@link AiClient}, whose callback is delivered back on the server thread. The
 * tick never blocks on the network; it just fires the next request once the
 * previous one has returned (guarded by {@code inFlight}).</p>
 */
public class TileEntityAIControlNode extends TileEntity implements ITickable {

    /** Bound machine, or null when unbound. */
    private BlockPos bound;
    private int boundDim;
    /** Per-block enable switch, independent of the global AiConfig.enabled. */
    private boolean armed = true;
    /** Route anomaly voice through the reasim loudspeaker DSP. */
    private boolean reasim = true;
    /** Last verdict level, for the block-state lamp and status queries. */
    private String lastLevel = "IDLE";
    /** Human-readable last result, shown by /ai status and right-click. */
    private String lastStatus = "未绑定机器";

    private boolean inFlight;
    private boolean firstTick = true;
    private int pollCounter;

    // ------------------------------------------------------------------ accessors

    public BlockPos getBound() {
        return bound;
    }

    public int getBoundDim() {
        return boundDim;
    }

    public boolean isArmed() {
        return armed;
    }

    public boolean isReasim() {
        return reasim;
    }

    public String getLastLevel() {
        return lastLevel;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void bind(BlockPos pos, int dim) {
        this.bound = pos == null ? null : pos.toImmutable();
        this.boundDim = dim;
        setStatus("已绑定 " + (pos == null ? "无" : coord(pos)) + " (dim " + dim + ")");
        markDirty();
        sync();
    }

    public void setArmed(boolean value) {
        this.armed = value;
        setStatus(value ? "已启用" : "已停用");
        markDirty();
        sync();
    }

    public void setReasim(boolean value) {
        this.reasim = value;
        markDirty();
        sync();
    }

    private void setStatus(String text) {
        this.lastStatus = text == null ? "" : text;
        if (this.lastStatus.length() > 120) {
            this.lastStatus = this.lastStatus.substring(0, 120);
        }
    }

    private static String coord(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        if (firstTick) {
            firstTick = false;
            // Align the lamp with loaded state without firing a request on load.
            return;
        }
        if (!AiConfig.enabled || !armed || bound == null || inFlight) {
            return;
        }
        if (++pollCounter < AiConfig.clampPollSeconds(AiConfig.pollSeconds) * 20) {
            return;
        }
        pollCounter = 0;

        // Only poll when the bound machine is in the same dimension and loaded.
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        World targetWorld = world.provider.getDimension() == boundDim
                ? world
                : (server == null ? null : server.getWorld(boundDim));
        if (targetWorld == null || !targetWorld.isBlockLoaded(bound)) {
            setStatus("绑定机器所在区块未加载");
            return;
        }

        final AiMachineProbe.Snapshot snapshot =
                AiMachineProbe.sample(targetWorld, bound, com.hbmzz.aegis.AegisConfig.scanRadius);
        if (!snapshot.ok) {
            setStatus("采样失败: " + snapshot.error);
            return;
        }

        inFlight = true;
        AiClient.analyze(snapshot, AiConfig.endpoint, AiConfig.apiKey, AiConfig.model,
                AiConfig.httpTimeoutMs, new AiClient.Callback() {
                    @Override
                    public void onVerdict(AiMachineProbe.Snapshot snap, AiClient.Verdict verdict) {
                        inFlight = false;
                        lastLevel = verdict.abnormal
                                ? (verdict.level == null || verdict.level.isEmpty() ? "ANOMALY" : verdict.level)
                                : "NORMAL";
                        setStatus((verdict.abnormal ? "异常: " : "正常: ")
                                + (verdict.reason == null ? verdict.message : verdict.reason));
                        markDirty();
                        sync();
                        if (verdict.abnormal) {
                            AiBroadcast.announce(pos, verdict, reasim);
                            AiAutopilot.onVerdict(world, pos, verdict);
                        }
                    }

                    @Override
                    public void onError(AiMachineProbe.Snapshot snap, String error) {
                        inFlight = false;
                        lastLevel = "ERROR";
                        setStatus("AI 请求失败: " + error);
                        markDirty();
                        sync();
                    }
                });
    }

    /** Manual single-shot analysis from /ai test. */
    public void testNow() {
        if (world == null || world.isRemote) {
            return;
        }
        if (bound == null) {
            setStatus("未绑定机器，无法测试");
            markDirty();
            sync();
            return;
        }
        AiMachineProbe.Snapshot snapshot =
                AiMachineProbe.sample(world, bound, com.hbmzz.aegis.AegisConfig.scanRadius);
        if (!snapshot.ok) {
            setStatus("采样失败: " + snapshot.error);
            markDirty();
            sync();
            return;
        }
        setStatus("测试中...");
        markDirty();
        sync();
        AiClient.analyze(snapshot, AiConfig.endpoint, AiConfig.apiKey, AiConfig.model,
                AiConfig.httpTimeoutMs, new AiClient.Callback() {
                    @Override
                    public void onVerdict(AiMachineProbe.Snapshot snap, AiClient.Verdict verdict) {
                        lastLevel = verdict.abnormal ? "ANOMALY" : "NORMAL";
                        setStatus("测试" + (verdict.abnormal ? "异常" : "正常") + ": "
                                + (verdict.reason == null ? verdict.message : verdict.reason));
                        markDirty();
                        sync();
                        if (verdict.abnormal) {
                            AiBroadcast.announce(pos, verdict, reasim);
                            AiAutopilot.onVerdict(world, pos, verdict);
                        }
                    }

                    @Override
                    public void onError(AiMachineProbe.Snapshot snap, String error) {
                        lastLevel = "ERROR";
                        setStatus("测试请求失败: " + error);
                        markDirty();
                        sync();
                    }
                });
    }

    private void sync() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if (bound != null) {
            compound.setLong("bound", bound.toLong());
            compound.setInteger("boundDim", boundDim);
            compound.setBoolean("hasBound", true);
        } else {
            compound.setBoolean("hasBound", false);
        }
        compound.setBoolean("armed", armed);
        compound.setBoolean("reasim", reasim);
        compound.setString("level", lastLevel);
        compound.setString("status", lastStatus);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.bound = compound.getBoolean("hasBound")
                ? BlockPos.fromLong(compound.getLong("bound")) : null;
        this.boundDim = compound.getInteger("boundDim");
        this.armed = compound.hasKey("armed") ? compound.getBoolean("armed") : true;
        this.reasim = compound.hasKey("reasim") ? compound.getBoolean("reasim") : true;
        this.lastLevel = compound.getString("level");
        if (lastLevel.isEmpty()) {
            lastLevel = "IDLE";
        }
        this.lastStatus = compound.getString("status");
        if (lastStatus.isEmpty()) {
            lastStatus = bound == null ? "未绑定机器" : "就绪";
        }
        this.firstTick = true;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}
