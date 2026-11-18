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

package com.hbmzz.block;

import com.hbmzz.HBMZZ;
import com.hbmzz.tile.TileEntityNukeController;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

/**
 * 核弹信号控制器: the box on the wall that turns a lever into a warhead.
 *
 * <p>The only redstone-facing part of the whole system, and deliberately the
 * dumbest one. It has an input, a binding, and a lamp; the fuse, the yield and
 * the announcement belong to the device and to {@code NukeService}. A player who
 * wants the bomb to go off at a particular moment wires a button here, and a
 * player who wants it to go off when the reactor temperature crosses a line
 * wires a control panel here - the controller cannot tell the difference, which
 * is the point.</p>
 */
public class BlockNukeController extends BlockHorizontal implements ITileEntityProvider {

    /** Lit while the input is powered. Cosmetic, and the only local feedback. */
    public static final PropertyBool POWERED = PropertyBool.create("powered");

    public BlockNukeController() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "nuke_controller");
        setUnlocalizedName(HBMZZ.MODID + ".nuke_controller");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        setResistance(60.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(POWERED, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, POWERED);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(POWERED) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(POWERED, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNukeController();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityNukeController)) {
            return true;
        }
        TileEntityNukeController controller = (TileEntityNukeController) te;
        String binding = controller.hasNuke()
                ? controller.getNukePos().getX() + " " + controller.getNukePos().getY() + " "
                + controller.getNukePos().getZ() + " (DIM " + controller.getNukeDim() + ")"
                : new TextComponentTranslation("hbmzz.msg.ctrl_unbound").getUnformattedText();
        String result;
        switch (controller.getLastResult()) {
            case TileEntityNukeController.RESULT_FIRED:
                result = "FIRED";
                break;
            case TileEntityNukeController.RESULT_NO_DEVICE:
                result = "NO DEVICE";
                break;
            case TileEntityNukeController.RESULT_NO_CHUNK:
                result = "CHUNK NOT LOADED";
                break;
            default:
                result = "IDLE";
                break;
        }
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_status", binding, result));
        return true;
    }
}
