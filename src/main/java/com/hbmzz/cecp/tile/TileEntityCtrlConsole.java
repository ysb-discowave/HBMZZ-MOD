/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.tile;

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.ControlEventSystem;
import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.TerminalConsoleHost;
import com.hbmzz.cecp.block.CtrlConsoleType;
import com.hbmzz.cecp.controls.addon.TerminalControl;
import com.hbmzz.cecp.net.CPPackets;
import com.hbmzz.cecp.net.ControlPanelUpdatePacket;
import com.hbmzz.ctrl.TerminalHost;
import com.hbmzz.ctrl.TerminalShell;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.ItemStackHandler;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Server-authoritative state of one console.
 *
 * <p>Ported from NTM: Cursed Edition ({@code com.hbm.tileentity.machine.TileEntityControlPanel})
 * with the OpenComputers component and the NTM GUI-provider plumbing stripped.
 * Everything that makes the panel behave - the per-control node systems, the
 * redstone sampling/broadcast pair, the slope transform used for rendering and
 * picking, and the delta packets - is unchanged.</p>
 */
public class TileEntityCtrlConsole extends TileEntity implements ITickable, com.hbmzz.cecp.IControllable {

    public ItemStackHandler inventory;
    public ControlPanel panel;
    public CtrlConsoleType panelType = CtrlConsoleType.CUSTOM_PANEL;
    private AxisAlignedBB bb;

    private final int[] redstoneInputPower = new int[EnumFacing.VALUES.length];
    private final int[] redstoneInputWeak = new int[EnumFacing.VALUES.length];
    private final int[] redstoneInputStrong = new int[EnumFacing.VALUES.length];
    private final int[] redstoneOutputWeak = new int[EnumFacing.VALUES.length];
    private final int[] redstoneOutputStrong = new int[EnumFacing.VALUES.length];
    private final int[] redstoneOutputWeakPending = new int[EnumFacing.VALUES.length];
    private final int[] redstoneOutputStrongPending = new int[EnumFacing.VALUES.length];
    private final Deque<RedstoneInputSnapshot> pendingRedstoneInputSnapshots = new ArrayDeque<>();
    private RedstoneInputSnapshot activeRedstoneInputSnapshot;
    private int redstoneOutputCollectionDepth = 0;

    /** Commands a second, across the whole console. Same budget as the panel. */
    private static final int TERMINAL_BURST = 10;
    private long terminalSecond = -1L;
    private int terminalUsed;

    public TileEntityCtrlConsole() {
        this.inventory = new ItemStackHandler(1) {
            @Override
            protected void onContentsChanged(int slot) {
                markDirty();
            }
        };
        this.panel = new ControlPanel(this, 0.25F, (float) Math.toRadians(20), 0, 0, 0.25F, 0);
        Arrays.fill(this.redstoneInputPower, -1);
        Arrays.fill(this.redstoneInputWeak, -1);
        Arrays.fill(this.redstoneInputStrong, -1);
    }

    @Override
    public void onLoad() {
        if (this.world == null) {
            return;
        }
        if (this.world.isRemote) {
            loadClient();
        } else {
            for (Control c : this.panel.controls) {
                for (BlockPos b : c.taggedLinks.values()) {
                    ControlEventSystem.get(this.world).subscribeTo(this, b);
                }
            }
            reinitializePanelState();
            if (hasAnyRedstoneOutput()) {
                notifyRedstoneNeighbors();
            }
        }
    }

    @SideOnly(Side.CLIENT)
    public void updateTransform() {
        Matrix4f mat = new Matrix4f();

        int meta = getBlockMetadata();
        boolean isDown = ((meta >> 2) & 1) == 1;
        boolean isUp = ((meta >> 3) & 1) == 1;

        if (isUp) {
            mat.translate(new Vector3f(0.5F, this.panel.height, 0.5F));
            rotateByMetadata(mat, meta);
            mat.rotate(-this.panel.angle, new Vector3f(0, 0, 1));
            mat.rotate((float) Math.toRadians(90), new Vector3f(0, 1, 0));
        } else if (isDown) {
            mat.translate(new Vector3f(0.5F, 1 - this.panel.height, 0.5F));
            rotateByMetadata(mat, meta);
            mat.rotate((float) Math.toRadians(180), new Vector3f(1, 0, 0));
            mat.rotate(-this.panel.angle, new Vector3f(0, 0, 1));
            mat.rotate((float) Math.toRadians(90), new Vector3f(0, 1, 0));
        } else {
            mat.translate(new Vector3f(0.5F, 0, 0.5F));
            rotateByMetadata(mat, meta);
            mat.rotate((float) Math.toRadians(-90), new Vector3f(1, 0, 0));
            mat.rotate((float) Math.toRadians(-90), new Vector3f(0, 0, 1));
            mat.translate(new Vector3f(0, this.panel.height - 0.5F, 0.5F));
            mat.rotate((float) Math.toRadians(-180), new Vector3f(0, 1, 0));
            mat.rotate(this.panel.angle, new Vector3f(1, 0, 0));
        }

        mat.scale(new Vector3f(0.1F, 0.1F, 0.1F));
        mat.translate(new Vector3f(0.5F, 0, 0.5F));
        this.panel.setTransform(mat);
    }

    private void rotateByMetadata(Matrix4f mat, int meta) {
        switch ((meta & 3) + 2) {
            case 4:
                mat.rotate((float) Math.toRadians(180), new Vector3f(0, 1, 0));
                break;
            case 2:
                mat.rotate((float) Math.toRadians(90), new Vector3f(0, 1, 0));
                break;
            case 3:
                mat.rotate((float) Math.toRadians(270), new Vector3f(0, 1, 0));
                break;
            default:
                break;
        }
    }

    @SideOnly(Side.CLIENT)
    public void loadClient() {
        updateTransform();
    }

    @Override
    public void update() {
        if (this.world == null) {
            return;
        }
        if (!this.world.isRemote) {
            dispatchPendingRedstoneInputEvents(false);
        }
        this.panel.update();
        if (!this.panel.changedVars.isEmpty()) {
            markDirty();
            CPPackets.createSendToAllTrackingThreadedPacket(
                    new ControlPanelUpdatePacket(this.pos, new ArrayList<>(this.panel.changedVars)),
                    new TargetPoint(this.world.provider.getDimension(), this.pos.getX(), this.pos.getY(),
                            this.pos.getZ(), 1));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        compound.setTag("panel", this.panel.writeToNBT(new NBTTagCompound()));
        NBTTagCompound weakOutput = new NBTTagCompound();
        NBTTagCompound strongOutput = new NBTTagCompound();
        for (EnumFacing facing : EnumFacing.VALUES) {
            weakOutput.setInteger(facing.getName(), this.redstoneOutputWeak[facing.getIndex()]);
            strongOutput.setInteger(facing.getName(), this.redstoneOutputStrong[facing.getIndex()]);
        }
        compound.setTag("redstoneWeakOut", weakOutput);
        compound.setTag("redstoneStrongOut", strongOutput);
        return super.writeToNBT(compound);
    }

    @Override
    protected void setWorldCreate(World worldIn) {
        this.world = worldIn;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        if (this.panel == null) {
            this.panel = new ControlPanel(this, 0.25F, (float) Math.toRadians(20), 0, 0, 0.25F, 0);
        }
        this.panel.readFromNBT(compound.getCompoundTag("panel"));
        NBTTagCompound weakOutput = compound.getCompoundTag("redstoneWeakOut");
        NBTTagCompound strongOutput = compound.getCompoundTag("redstoneStrongOut");
        for (EnumFacing facing : EnumFacing.VALUES) {
            this.redstoneOutputWeak[facing.getIndex()] = clampRedstoneStrength(weakOutput.getInteger(facing.getName()));
            this.redstoneOutputStrong[facing.getIndex()] = clampRedstoneStrength(strongOutput.getInteger(facing.getName()));
        }
        super.readFromNBT(compound);
    }

    public void receiveEvent(BlockPos from, ControlEvent e) {
        this.panel.receiveEvent(from, e);
    }

    public List<String> getInEvents() {
        return Arrays.asList("tick");
    }

    public BlockPos getControlPos() {
        return getPos();
    }

    public World getControlWorld() {
        return getWorld();
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, -1, getUpdateTag());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    /** Called by the console block / packet for every edit the player makes. */
    public void receiveControl(EntityPlayerMP player, NBTTagCompound data) {
        if (data.hasKey("full_set")) {
            markDirty();
            for (Control c : this.panel.controls) {
                for (BlockPos b : c.taggedLinks.values()) {
                    ControlEventSystem.get(this.world).unsubscribeFrom(this, b);
                }
            }
            this.panel.readFromNBT(data);
            for (Control c : this.panel.controls) {
                for (BlockPos b : c.taggedLinks.values()) {
                    ControlEventSystem.get(this.world).subscribeTo(this, b);
                }
            }
            reinitializePanelState();
            CPPackets.createSendToAllTrackingThreadedPacket(
                    new ControlPanelUpdatePacket(this.pos, data),
                    new TargetPoint(this.world.provider.getDimension(), this.pos.getX(), this.pos.getY(),
                            this.pos.getZ(), 1));
        } else if (data.hasKey("click_control")) {
            int idx = data.getInteger("click_control");
            if (idx < 0 || idx >= this.panel.controls.size()) {
                return;
            }
            ControlEvent evt = ControlEvent.readFromNBT(data);
            this.panel.receiveDirectEvent(this.panel.controls.get(idx), evt);
        }
    }

    /**
     * Runs one terminal command against this console's own panel copy.
     *
     * <p>Server only, and reached only from {@code TerminalInputMessage}: the
     * client sends the text, never a machine. Everything the command is allowed
     * to touch is decided here, against the panel the server has - which is the
     * same authority a player has when they turn a knob on the same board.</p>
     */
    public TerminalShell.Result runTerminal(int controlIndex, String line, TerminalShell.Sink sink) {
        TerminalShell.Result result = new TerminalShell.Result();
        if (this.world == null || this.world.isRemote || sink == null) {
            return result;
        }
        if (this.panel == null || controlIndex < 0 || controlIndex >= this.panel.controls.size()) {
            sink.err("这个终端已经不在了。");
            return result;
        }
        Control control = this.panel.controls.get(controlIndex);
        if (!(control instanceof TerminalControl)) {
            sink.err("这个控件不是终端。");
            return result;
        }
        long second = this.world.getTotalWorldTime() / 20L;
        if (second != this.terminalSecond) {
            this.terminalSecond = second;
            this.terminalUsed = 0;
        }
        if (++this.terminalUsed > TERMINAL_BURST) {
            sink.err("命令太频繁了，等一秒再来。");
            return result;
        }
        result = TerminalShell.execute(new TerminalConsoleHost((TerminalControl) control, this.world), line, sink);
        if (result.dirty) {
            markDirty();
            // The target is part of the control's saved variables, so the other
            // viewers of this console should see the selection move too.
            NBTTagCompound data = new NBTTagCompound();
            this.panel.writeToNBT(data);
            CPPackets.createSendToAllTrackingThreadedPacket(
                    new ControlPanelUpdatePacket(this.pos, data),
                    new TargetPoint(this.world.provider.getDimension(), this.pos.getX(), this.pos.getY(),
                            this.pos.getZ(), 1));
        }
        return result;
    }

    /**
     * The terminal view of control {@code controlIndex}, or null when that
     * control is not a terminal.
     *
     * <p>Used by the reply packet to describe the panel back to the window -
     * labels, target, and the vocabulary Tab needs - so the client never has to
     * guess what a machine accepts.</p>
     */
    public TerminalHost terminalHost(int controlIndex) {
        if (this.panel == null || controlIndex < 0 || controlIndex >= this.panel.controls.size()) {
            return null;
        }
        Control control = this.panel.controls.get(controlIndex);
        if (!(control instanceof TerminalControl)) {
            return null;
        }
        return new TerminalConsoleHost((TerminalControl) control, this.world);
    }

    /** Which slot the terminal at {@code controlIndex} points at, or -1. */
    public int terminalTarget(int controlIndex) {
        TerminalHost host = terminalHost(controlIndex);
        return host == null ? -1 : host.target();
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

    public void captureRedstoneInputChanges() {
        captureRedstoneInputChanges(false);
    }

    private void captureRedstoneInputChanges(boolean forceEventDispatch) {
        if (this.world == null || this.world.isRemote) {
            return;
        }

        int[] nextPower = Arrays.copyOf(this.redstoneInputPower, this.redstoneInputPower.length);
        int[] nextWeak = Arrays.copyOf(this.redstoneInputWeak, this.redstoneInputWeak.length);
        int[] nextStrong = Arrays.copyOf(this.redstoneInputStrong, this.redstoneInputStrong.length);
        List<EnumFacing> changedFaces = new ArrayList<>();
        for (EnumFacing facing : EnumFacing.VALUES) {
            int idx = facing.getIndex();
            BlockPos neighborPos = getPos().offset(facing);
            IBlockState neighborState = this.world.getBlockState(neighborPos);
            int effectivePower = this.world.getRedstonePower(neighborPos, facing);
            int weakPower = neighborState.getWeakPower(this.world, neighborPos, facing);
            int strongPower = neighborState.getStrongPower(this.world, neighborPos, facing);

            boolean changed = effectivePower != this.redstoneInputPower[idx]
                    || weakPower != this.redstoneInputWeak[idx]
                    || strongPower != this.redstoneInputStrong[idx];
            nextPower[idx] = effectivePower;
            nextWeak[idx] = weakPower;
            nextStrong[idx] = strongPower;
            if (changed || forceEventDispatch) {
                changedFaces.add(facing);
            }
        }

        System.arraycopy(nextPower, 0, this.redstoneInputPower, 0, this.redstoneInputPower.length);
        System.arraycopy(nextWeak, 0, this.redstoneInputWeak, 0, this.redstoneInputWeak.length);
        System.arraycopy(nextStrong, 0, this.redstoneInputStrong, 0, this.redstoneInputStrong.length);
        for (EnumFacing facing : changedFaces) {
            this.pendingRedstoneInputSnapshots.addLast(
                    new RedstoneInputSnapshot(facing, nextPower, nextWeak, nextStrong));
        }
    }

    private static ControlEvent createRedstoneInputEvent(EnumFacing facing, int effectivePower, int weakPower,
                                                         int strongPower) {
        return ControlEvent.newEvent("redstone_input")
                .setVar("facing", facing)
                .setVar("power", effectivePower)
                .setVar("weak", weakPower)
                .setVar("strong", strongPower)
                .setVar("isPowered", effectivePower > 0)
                .setVar("isWeaklyPowered", weakPower > 0)
                .setVar("isStronglyPowered", strongPower > 0);
    }

    private void dispatchPendingRedstoneInputEvents(boolean clearOutputsFirst) {
        if (this.world == null || this.world.isRemote || this.pendingRedstoneInputSnapshots.isEmpty()) {
            return;
        }
        beginRedstoneOutputCollection(clearOutputsFirst);
        try {
            BlockPos controlPos = getPos();
            while (!this.pendingRedstoneInputSnapshots.isEmpty()) {
                this.activeRedstoneInputSnapshot = this.pendingRedstoneInputSnapshots.removeFirst();
                this.panel.receiveEvent(controlPos, this.activeRedstoneInputSnapshot.toControlEvent());
            }
        } finally {
            this.activeRedstoneInputSnapshot = null;
            finishRedstoneOutputCollection();
        }
    }

    private void reinitializePanelState() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        this.pendingRedstoneInputSnapshots.clear();
        beginRedstoneOutputCollection(true);
        try {
            for (Control control : this.panel.controls) {
                control.receiveEvent(ControlEvent.newEvent("initialize"));
            }
            captureRedstoneInputChanges(true);
            dispatchPendingRedstoneInputEvents(false);
        } finally {
            finishRedstoneOutputCollection();
        }
    }

    public int getWeakRedstoneOutput(EnumFacing facing) {
        return this.redstoneOutputWeak[facing.getIndex()];
    }

    public int getStrongRedstoneOutput(EnumFacing facing) {
        return this.redstoneOutputStrong[facing.getIndex()];
    }

    private void notifyRedstoneNeighbors() {
        this.world.notifyNeighborsOfStateChange(this.pos, getBlockType(), true);
        for (EnumFacing facing : EnumFacing.VALUES) {
            this.world.notifyNeighborsOfStateChange(this.pos.offset(facing), getBlockType(), true);
        }
    }

    private boolean hasAnyRedstoneOutput() {
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (this.redstoneOutputWeak[facing.getIndex()] > 0 || this.redstoneOutputStrong[facing.getIndex()] > 0) {
                return true;
            }
        }
        return false;
    }

    public void beginRedstoneOutputCollection() {
        beginRedstoneOutputCollection(false);
    }

    public void beginRedstoneOutputCollection(boolean clearPending) {
        if (this.redstoneOutputCollectionDepth++ == 0) {
            if (clearPending) {
                Arrays.fill(this.redstoneOutputWeakPending, 0);
                Arrays.fill(this.redstoneOutputStrongPending, 0);
            } else {
                System.arraycopy(this.redstoneOutputWeak, 0, this.redstoneOutputWeakPending, 0,
                        this.redstoneOutputWeak.length);
                System.arraycopy(this.redstoneOutputStrong, 0, this.redstoneOutputStrongPending, 0,
                        this.redstoneOutputStrong.length);
            }
        }
    }

    public void finishRedstoneOutputCollection() {
        if (this.redstoneOutputCollectionDepth == 0) {
            return;
        }
        if (--this.redstoneOutputCollectionDepth == 0) {
            applyRedstoneOutputs(this.redstoneOutputWeakPending, this.redstoneOutputStrongPending);
        }
    }

    public int getRedstoneInputPower(EnumFacing facing) {
        if (this.activeRedstoneInputSnapshot != null) {
            return this.activeRedstoneInputSnapshot.getPower(facing);
        }
        return this.redstoneInputPower[facing.getIndex()] < 0 ? 0 : this.redstoneInputPower[facing.getIndex()];
    }

    public int getRedstoneInputWeak(EnumFacing facing) {
        if (this.activeRedstoneInputSnapshot != null) {
            return this.activeRedstoneInputSnapshot.getWeak(facing);
        }
        return this.redstoneInputWeak[facing.getIndex()] < 0 ? 0 : this.redstoneInputWeak[facing.getIndex()];
    }

    public int getRedstoneInputStrong(EnumFacing facing) {
        if (this.activeRedstoneInputSnapshot != null) {
            return this.activeRedstoneInputSnapshot.getStrong(facing);
        }
        return this.redstoneInputStrong[facing.getIndex()] < 0 ? 0 : this.redstoneInputStrong[facing.getIndex()];
    }

    public void setWeakRedstoneOutput(EnumFacing facing, int strength) {
        int idx = facing.getIndex();
        int clamped = clampRedstoneStrength(strength);
        if (this.redstoneOutputCollectionDepth > 0) {
            this.redstoneOutputWeakPending[idx] = clamped;
            return;
        }
        int[] weakOutputs = Arrays.copyOf(this.redstoneOutputWeak, this.redstoneOutputWeak.length);
        weakOutputs[idx] = clamped;
        applyRedstoneOutputs(weakOutputs, this.redstoneOutputStrong);
    }

    public void setStrongRedstoneOutput(EnumFacing facing, int strength) {
        int idx = facing.getIndex();
        int clamped = clampRedstoneStrength(strength);
        if (this.redstoneOutputCollectionDepth > 0) {
            this.redstoneOutputStrongPending[idx] = clamped;
            return;
        }
        int[] strongOutputs = Arrays.copyOf(this.redstoneOutputStrong, this.redstoneOutputStrong.length);
        strongOutputs[idx] = clamped;
        applyRedstoneOutputs(this.redstoneOutputWeak, strongOutputs);
    }

    private void applyRedstoneOutputs(int[] weakOutputs, int[] strongOutputs) {
        boolean changed = false;
        for (EnumFacing facing : EnumFacing.VALUES) {
            int idx = facing.getIndex();
            int weak = clampRedstoneStrength(weakOutputs[idx]);
            int strong = clampRedstoneStrength(strongOutputs[idx]);
            if (this.redstoneOutputWeak[idx] != weak) {
                this.redstoneOutputWeak[idx] = weak;
                changed = true;
            }
            if (this.redstoneOutputStrong[idx] != strong) {
                this.redstoneOutputStrong[idx] = strong;
                changed = true;
            }
        }
        if (changed) {
            markDirty();
            notifyRedstoneNeighbors();
        }
    }

    private static int clampRedstoneStrength(int strength) {
        return Math.max(0, Math.min(15, strength));
    }

    public float[] getBox() {
        float baseSizeX = 1 - (this.panel.b_off + this.panel.d_off);
        float baseSizeY = 1 - (this.panel.a_off + this.panel.c_off);

        double base_hyp = 1 / Math.cos(Math.abs(this.panel.angle));
        double panel_hyp = baseSizeY / Math.cos(Math.abs(this.panel.angle));

        float box_width = 10;
        float box_height = (float) (base_hyp * 10);
        float minX = (-box_width / 2) + (this.panel.d_off * box_width);
        float minY = (-box_height / 2) + (this.panel.a_off * box_height);

        return new float[]{minX, minY, minX + baseSizeX * 10, (float) (minY + panel_hyp * 10)};
    }

    public AxisAlignedBB getBoundingBox(boolean isUp, boolean isDown, EnumFacing facing) {
        AxisAlignedBB defAABB;
        float height1 = ControlPanel.getSlopeHeightFromZ(1 - this.panel.c_off, this.panel.height, -this.panel.angle);
        float height0 = ControlPanel.getSlopeHeightFromZ(this.panel.a_off, this.panel.height, -this.panel.angle);

        if (isUp) {
            defAABB = new AxisAlignedBB(this.panel.d_off, 0, this.panel.a_off, 1 - this.panel.b_off,
                    Math.max(height0, height1), 1 - this.panel.c_off);
        } else if (isDown) {
            defAABB = new AxisAlignedBB(1 - this.panel.d_off, 1, this.panel.a_off, this.panel.b_off,
                    1 - Math.max(height0, height1), 1 - this.panel.c_off);
        } else {
            defAABB = new AxisAlignedBB(this.panel.d_off, 1 - this.panel.a_off, 0, 1 - this.panel.b_off,
                    this.panel.c_off, Math.max(height0, height1));
        }
        return rotateAABB(defAABB, facing);
    }

    public static AxisAlignedBB rotateAABB(AxisAlignedBB box, EnumFacing facing) {
        switch (facing) {
            case NORTH:
                return new AxisAlignedBB(1 - box.minX, box.minY, 1 - box.maxZ, 1 - box.maxX, box.maxY, 1 - box.minZ);
            case EAST:
                return new AxisAlignedBB(box.minZ, box.minY, 1 - box.minX, box.maxZ, box.maxY, 1 - box.maxX);
            case WEST:
                return new AxisAlignedBB(1 - box.minZ, box.minY, box.minX, 1 - box.maxZ, box.maxY, box.maxX);
            case SOUTH:
            default:
                return box;
        }
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        if (this.bb == null) {
            this.bb = new AxisAlignedBB(this.pos.getX(), this.pos.getY(), this.pos.getZ(),
                    this.pos.getX() + 1, this.pos.getY() + 1, this.pos.getZ() + 1);
        }
        return this.bb;
    }

    private static final class RedstoneInputSnapshot {
        private final EnumFacing changedFace;
        private final int[] powerByFace;
        private final int[] weakByFace;
        private final int[] strongByFace;

        private RedstoneInputSnapshot(EnumFacing changedFace, int[] powerByFace, int[] weakByFace, int[] strongByFace) {
            this.changedFace = changedFace;
            this.powerByFace = Arrays.copyOf(powerByFace, powerByFace.length);
            this.weakByFace = Arrays.copyOf(weakByFace, weakByFace.length);
            this.strongByFace = Arrays.copyOf(strongByFace, strongByFace.length);
        }

        private int getPower(EnumFacing facing) {
            return Math.max(0, this.powerByFace[facing.getIndex()]);
        }

        private int getWeak(EnumFacing facing) {
            return Math.max(0, this.weakByFace[facing.getIndex()]);
        }

        private int getStrong(EnumFacing facing) {
            return Math.max(0, this.strongByFace[facing.getIndex()]);
        }

        private ControlEvent toControlEvent() {
            return createRedstoneInputEvent(this.changedFace, getPower(this.changedFace),
                    getWeak(this.changedFace), getStrong(this.changedFace));
        }
    }
}
