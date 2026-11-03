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

package com.hbmzz.cecp.block;

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.compat.CPEnv;
import com.hbmzz.cecp.net.NBTControlPacket;
import com.hbmzz.cecp.net.CPPackets;
import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.item.ItemMonitorLinker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.Mirror;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Random;

/**
 * The console block players place in the world.
 *
 * <p>Ported from NTM: Cursed Edition ({@code com.hbm.blocks.generic.BlockControlPanel}).
 * Behaviour is unchanged: right-clicking a control fires {@code ctrl_press} at
 * it, holding a screwdriver opens the layout editor, and the block
 * itself can drive redstone from the panel's {@code redstone_output} nodes.</p>
 *
 * <p>Cursed Edition only accepts its own two screwdrivers, which live in a
 * different jar; {@link #isConfigTool(ItemStack)} matches them by registry name
 * so the same tool works here, and the mod's own monitor link cable is accepted
 * as well for worlds without NTM.</p>
 */
public class BlockCtrlConsole extends BlockContainer {

    public static final PropertyBool UP = PropertyBool.create("up");
    public static final PropertyBool DOWN = PropertyBool.create("down");
    public static final PropertyDirection FACING = PropertyDirection.create("facing", EnumFacing.Plane.HORIZONTAL);

    public BlockCtrlConsole() {
        super(Material.IRON);
        setUnlocalizedName("hbmzz.ctrl_console");
        setRegistryName("hbmzz", "ctrl_console");
        setHardness(4.0F);
        setResistance(12.0F);
        setCreativeTab(net.minecraft.creativetab.CreativeTabs.REDSTONE);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(UP, Boolean.FALSE)
                .withProperty(DOWN, Boolean.FALSE)
                .withProperty(FACING, EnumFacing.NORTH));
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        // The tile entity builds its own ControlPanel in the constructor, the
        // same way Cursed Edition's TileEntityControlPanel does.
        return new TileEntityCtrlConsole();
    }

    /**
     * Whether a stack counts as the console's configuration tool.
     *
     * <p>Cursed Edition opens the editor with its own screwdriver
     * ({@code ModItems.screwdriver} / {@code screwdriver_desh}). This mod is a
     * separate jar and must keep working with or without NTM installed, so the
     * check is by registry name instead of by class: any item whose path
     * contains {@code screwdriver} counts, which covers {@code hbm:screwdriver},
     * {@code hbm:screwdriver_desh} and other mods' screwdrivers. The monitor
     * link cable keeps working as before, so a world without NTM still has a
     * usable tool.</p>
     */
    public static boolean isConfigTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item instanceof ItemMonitorLinker) {
            return true;
        }
        ResourceLocation reg = item.getRegistryName();
        // 1.12.2 names these getResourcePath/getResourceDomain (the short
        // getPath()/getNamespace() pair is 1.13+).
        String path = reg == null ? "" : reg.getResourcePath().toLowerCase(Locale.ROOT);
        String name = item.getUnlocalizedName() == null ? "" : item.getUnlocalizedName().toLowerCase(Locale.ROOT);
        return path.contains("screwdriver") || name.contains("screwdriver");
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack held = playerIn.getHeldItem(hand);
        boolean configuring = isConfigTool(held);
        if (!configuring && held.isEmpty()) {
            // Vanilla only reaches the off-hand pass if the main-hand pass did
            // not consume the click, so a screwdriver held off-hand with an
            // empty main hand has to be recognised here or the editor stays shut.
            ItemStack other = playerIn.getHeldItem(hand == EnumHand.MAIN_HAND ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND);
            configuring = isConfigTool(other);
        }
        // Holding a block must place it against the panel, not get swallowed by
        // the "always consume the click" behaviour below. Answered on both sides
        // so the client's prediction matches what the server does.
        if (!configuring && !held.isEmpty() && held.getItem() instanceof ItemBlock) {
            return false;
        }
        if (!worldIn.isRemote) {
            // Without this, aiming at empty board space would open the editor
            // every time and no control could ever be pressed.
            if (configuring) {
                FMLNetworkHandler.openGui(playerIn, com.hbmzz.HBMZZ.instance, 0, worldIn,
                        pos.getX(), pos.getY(), pos.getZ());
            }
        } else if (!configuring) {
            TileEntity te = worldIn.getTileEntity(pos);
            if (!(te instanceof TileEntityCtrlConsole)) {
                return true;
            }
            TileEntityCtrlConsole console = (TileEntityCtrlConsole) te;
            Control ctrl = console.panel.getSelectedControl(playerIn.getPositionEyes(1.0F), playerIn.getLook(1.0F));
            if (ctrl != null) {
                ControlEvent evt = ControlEvent.newEvent("ctrl_press");
                evt.setVar("isSneaking", new DataValueFloat(playerIn.isSneaking()));
                NBTTagCompound dat = evt.writeToNBT(new NBTTagCompound());
                dat.setInteger("click_control", console.panel.controls.indexOf(ctrl));
                CPPackets.createSendToServerThreadedPacket(new NBTControlPacket(dat, pos));
            }
        }
        return true;
    }

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public int getWeakPower(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
        TileEntity te = blockAccess.getTileEntity(pos);
        if (te instanceof TileEntityCtrlConsole) {
            return ((TileEntityCtrlConsole) te).getWeakRedstoneOutput(side);
        }
        return 0;
    }

    @Override
    public int getStrongPower(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
        TileEntity te = blockAccess.getTileEntity(pos);
        if (te instanceof TileEntityCtrlConsole) {
            return ((TileEntityCtrlConsole) te).getStrongRedstoneOutput(side);
        }
        return 0;
    }

    @Override
    public boolean getWeakChanges(IBlockAccess world, BlockPos pos) {
        return true;
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos) {
        super.neighborChanged(state, worldIn, pos, blockIn, fromPos);
        refreshRedstoneState(worldIn, pos);
    }

    @Override
    public void onNeighborChange(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        super.onNeighborChange(world, pos, neighbor);
        if (world instanceof World) {
            refreshRedstoneState((World) world, pos);
        }
    }

    private static void refreshRedstoneState(World worldIn, BlockPos pos) {
        if (!worldIn.isRemote) {
            TileEntity te = worldIn.getTileEntity(pos);
            if (te instanceof TileEntityCtrlConsole) {
                ((TileEntityCtrlConsole) te).captureRedstoneInputChanges();
            }
        }
    }

    @Nonnull
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        TileEntity te = source.getTileEntity(pos);
        if (te instanceof TileEntityCtrlConsole) {
            AxisAlignedBB ret = ((TileEntityCtrlConsole) te)
                    .getBoundingBox(state.getValue(UP), state.getValue(DOWN), state.getValue(FACING));
            if (ret != null) {
                return ret;
            }
        }
        return super.getBoundingBox(state, source, pos);
    }

    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess worldIn, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        return this.getDefaultState()
                .withProperty(FACING, placer.getHorizontalFacing().getOpposite())
                .withProperty(UP, facing.getIndex() == 1)
                .withProperty(DOWN, facing.getIndex() == 0);
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public Item getItemDropped(IBlockState state, Random rand, int fortune) {
        return Item.getItemFromBlock(this);
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isBlockNormalCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isNormalCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {
        return false;
    }

    @Override
    public boolean shouldSideBeRendered(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
        return false;
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, UP, DOWN, FACING);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int up = state.getValue(UP) ? 1 : 0;
        int down = state.getValue(DOWN) ? 1 : 0;
        int facing = state.getValue(FACING).getIndex();
        return (up << 3) | (down << 2) | (facing - 2);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState()
                .withProperty(UP, ((meta >> 3) & 1) > 0)
                .withProperty(DOWN, ((meta >> 2) & 1) > 0)
                .withProperty(FACING, EnumFacing.getFront((meta & 3) + 2));
    }

    @Override
    public IBlockState withRotation(IBlockState state, Rotation rot) {
        return state.withProperty(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public IBlockState withMirror(IBlockState state, Mirror mirrorIn) {
        return state.withRotation(mirrorIn.toRotation(state.getValue(FACING)));
    }
}
