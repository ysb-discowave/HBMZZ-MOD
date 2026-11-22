/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import com.hbmzz.HBMZZ;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * The AI control node block. A compact wall/rack unit that watches one bound
 * machine and speaks a facility-wide alarm through the TTS pipeline when the
 * AI service flags its readings as abnormal.
 *
 * <p>Interaction:</p>
 * <ul>
 *   <li>Right-click (empty hand): print the node's status, bound machine and
 *       last verdict to chat.</li>
 *   <li>Right-click with a wooden axe: toggle armed on/off - the same field
 *       tool convention the rest of the mod uses.</li>
 *   <li>Sneak + right-click with a wooden axe: toggle reasim voice routing.</li>
 * </ul>
 *
 * <p>Configuration of the AI endpoint and machine binding is done with the
 * {@code /ai} command (see {@code CommandAI}), because those values need to be
 * set from a distance and printed back verbatim - a GUI cannot beat a command
 * line for an operator wiring up an HTTP service.</p>
 */
public final class BlockAIControlNode extends BlockHorizontal implements ITileEntityProvider {

    /** Lamp colour state: false = idle/normal, true = anomaly. */
    public static final PropertyBool ALERT = PropertyBool.create("alert");

    public BlockAIControlNode() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "ai_control_node");
        setUnlocalizedName(HBMZZ.MODID + ".ai_control_node");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        setResistance(12.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(ALERT, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, ALERT);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(ALERT)) {
            meta |= 4;
        }
        return meta;
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(ALERT, (meta & 4) != 0);
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityAIControlNode();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityAIControlNode)) {
            return true;
        }
        TileEntityAIControlNode node = (TileEntityAIControlNode) te;
        ItemStack held = player.getHeldItem(hand);

        // Wooden axe = field tool, consistent with the blast door / broadcaster.
        if (!held.isEmpty() && held.getItem() == Items.WOODEN_AXE) {
            if (player.isSneaking()) {
                node.setReasim(!node.isReasim());
                player.sendMessage(new TextComponentString(
                        "AI 语音 reasim 广播音色: " + (node.isReasim() ? "开" : "关")));
            } else {
                node.setArmed(!node.isArmed());
                player.sendMessage(new TextComponentString(
                        "AI 控制节点: " + (node.isArmed() ? "已启用" : "已停用")));
            }
            return true;
        }

        // Empty hand: read status out.
        String bound = node.getBound() == null
                ? "无"
                : node.getBound().getX() + "," + node.getBound().getY() + ","
                        + node.getBound().getZ() + " (dim " + node.getBoundDim() + ")";
        player.sendMessage(new TextComponentString(
                "AI 节点 @" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                        + " | 状态=" + node.getLastLevel()
                        + " | 绑定=" + bound
                        + " | " + node.getLastStatus()
                        + " | reasim=" + (node.isReasim() ? "开" : "关")));
        return true;
    }

    /** Called from the tile entity's sync path to reflect verdicts in the lamp. */
    public static void updateAlertState(World world, BlockPos pos, boolean alert) {
        if (world == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockAIControlNode && state.getValue(ALERT) != alert) {
            world.setBlockState(pos, state.withProperty(ALERT, alert), 3);
        }
    }
}
