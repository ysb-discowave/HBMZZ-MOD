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

package com.hbmzz.vault;

import com.hbmzz.HBMZZ;
import com.hbmzz.ModContent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
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
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * The bank vault: a tall, giant security door set into a wall.
 *
 * <h3>Twenty-six tall, not wide</h3>
 * <p>Like the {@code tartarus_gate}, this is one object made of many cells -
 * but standing up instead of lying flat. The footprint is {@link #W} wide and
 * {@link #H} high. The cell at the lower-left corner carries the
 * {@link TileEntityVault}; the other cells are {@link BlockVaultPart}. All of
 * them share one {@code open} state, so the leaf swings as one piece.</p>
 *
 * <h3>The blocks only stand in the way</h3>
 * <p>Every cell renders nothing: the vault is drawn entirely by the corner's
 * tile entity renderer (the frame, the swinging leaf, the turning handle and
 * the live status plate). The block itself contributes nothing but a collision
 * and a selection box - a solid one when the door is shut (so the leaf actually
 * keeps people out) and none when it is open (so the hole you see is a hole you
 * can walk through). Keeping the visuals out of the block model is what lets the
 * leaf interpolate open and shut smoothly: a block model is baked once, a TESR
 * redraws it every frame.</p>
 *
 * <h3>Not a manual door</h3>
 * <p>Right-clicking it does nothing - there is no readout and no switch here.
 * The door opens and closes on the <em>facility state</em>: the tile entity
 * watches the save-wide facility code and swings the leaf open whenever the
 * facility is at or past the door's "open condition". The axe is the only thing
 * that touches this door by hand, and only to re-tune that condition.</p>
 */
public class BlockVault extends BlockHorizontal implements ITileEntityProvider {

    public static final PropertyBool OPEN = PropertyBool.create("open");

    /** Cells across the leaf, measured along the door's right direction. */
    public static final int W = 5;
    /** Cells from sill to lintel. */
    public static final int H = 6;

    /** Reentrancy guard for taking the vault down cell by cell. */
    static boolean DISMANTLING = false;

    public BlockVault() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "vault_door");
        setUnlocalizedName(HBMZZ.MODID + ".vault_door");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(50.0F);
        setResistance(6000.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(OPEN, Boolean.FALSE));
    }

    /** The cell at column {@code dx} (along the door's right) and row {@code dy} (up). */
    public static BlockPos cell(BlockPos origin, EnumFacing facing, int dx, int dy) {
        EnumFacing right = facing.rotateY();
        return origin.offset(right, dx).up(dy);
    }

    private static IBlockState stateFor(EnumFacing facing, boolean open) {
        return ModContent.BLOCK_VAULT_DOOR.getDefaultState()
                .withProperty(FACING, facing)
                .withProperty(OPEN, open);
    }

    // ------------------------------------------------------------------ state

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, OPEN);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        // A wall door faces the player. If they hit a side face, face that face's
        // opposite; if they hit the top, fall back to the player's own heading.
        EnumFacing out;
        if (facing.getAxis() == EnumFacing.Axis.Y) {
            out = placer == null ? EnumFacing.NORTH : placer.getHorizontalFacing().getOpposite();
        } else {
            out = facing.getOpposite();
        }
        return stateFor(out, false);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(OPEN) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return stateFor(EnumFacing.getHorizontal(meta & 3), (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityVault();
    }

    // ------------------------------------------------------------- appearance
    // The leaf is drawn by the renderer; the block only decides whether it
    // blocks and can be picked.

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {
        return false;
    }

    @Override
    @Nullable
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        // Shut: the leaf is solid and stops anyone from walking through the hole.
        // Open: nothing, so the gap the renderer shows is a gap you can pass.
        return state.getValue(OPEN) ? null : FULL_BLOCK_AABB;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return FULL_BLOCK_AABB;
    }

    /** Selectable whether open or shut: the crosshair aims at the whole cell. */
    @Override
    public net.minecraft.util.math.RayTraceResult collisionRayTrace(
            IBlockState state, World world, BlockPos pos,
            net.minecraft.util.math.Vec3d start, net.minecraft.util.math.Vec3d end) {
        return rayTrace(pos, start, end, FULL_BLOCK_AABB);
    }

    // ------------------------------------------------------------- placement

    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        if (!super.canPlaceBlockAt(world, pos)) {
            return false;
        }
        EnumFacing facing = placerFacing(world, pos);
        for (int dx = 0; dx < W; dx++) {
            for (int dy = 0; dy < H; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                BlockPos c = cell(pos, facing, dx, dy);
                if (!world.isBlockLoaded(c)) {
                    return false;
                }
                if (!world.getBlockState(c).getBlock().isReplaceable(world, c)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Facing the item will land with; used only for the footprint check. */
    private static EnumFacing placerFacing(World world, BlockPos pos) {
        return EnumFacing.NORTH;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (!world.isRemote) {
            fill(world, pos, state);
        }
    }

    private static void fill(World world, BlockPos origin, IBlockState originState) {
        EnumFacing facing = originState.getValue(FACING);
        boolean open = originState.getValue(OPEN);
        for (int dx = 0; dx < W; dx++) {
            for (int dy = 0; dy < H; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                BlockPos c = cell(origin, facing, dx, dy);
                if (!world.isBlockLoaded(c)) {
                    continue;
                }
                Block existing = world.getBlockState(c).getBlock();
                if (existing instanceof BlockVaultPart) {
                    continue;
                }
                if (existing.isReplaceable(world, c)) {
                    world.setBlockState(c, ModContent.BLOCK_VAULT_PART.getDefaultState()
                            .withProperty(BlockVaultPart.FACING, facing)
                            .withProperty(BlockVaultPart.OPEN, open), 3);
                }
            }
        }
    }

    // --------------------------------------------------------------- controls

    /**
     * A plain right-click does nothing to this door.
     *
     * <p>Deliberately a no-op: the leaf is driven by the facility state, and the
     * only hand gesture that touches it is the axe (handled in
     * {@link VaultAxeHandler}), which re-tunes the open condition. Giving a bare
     * click an open/close here would make the facility-state control cosmetic,
     * which is the exact failure this design is meant to avoid.</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        return false;
    }

    /**
     * Sets the corner and every leaf cell to the same state and clanks.
     *
     * <p>One place for both the facility-state change and the teardown so the
     * leaf always moves as a single object with a single sound.</p>
     */
    static void applyOpen(World world, BlockPos origin, EnumFacing facing, boolean open) {
        IBlockState originState = world.getBlockState(origin);
        if (!(originState.getBlock() instanceof BlockVault)
                || originState.getValue(OPEN) == open) {
            return;
        }
        world.setBlockState(origin, stateFor(facing, open), 3);
        for (int dx = 0; dx < W; dx++) {
            for (int dy = 0; dy < H; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                BlockPos c = cell(origin, facing, dx, dy);
                if (!world.isBlockLoaded(c)) {
                    continue;
                }
                IBlockState partState = world.getBlockState(c);
                if (partState.getBlock() instanceof BlockVaultPart) {
                    world.setBlockState(c, partState.withProperty(BlockVaultPart.OPEN, open), 3);
                }
            }
        }
        world.playSound(null, origin, open
                        ? net.minecraft.init.SoundEvents.BLOCK_IRON_DOOR_OPEN
                        : net.minecraft.init.SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                net.minecraft.util.SoundCategory.BLOCKS, 1.0F, 0.5F);
    }

    /** The corner cell of whichever vault covers {@code pos}, or null. */
    @Nullable
    public static BlockPos findOrigin(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        for (EnumFacing facing : EnumFacing.values()) {
            if (facing.getAxis() == EnumFacing.Axis.Y) {
                continue;
            }
            EnumFacing right = facing.rotateY();
            int rx = right.getFrontOffsetX();
            int rz = right.getFrontOffsetZ();
            for (int dx = 0; dx < W; dx++) {
                for (int dy = 0; dy < H; dy++) {
                    BlockPos candidate = pos.add(-rx * dx, -dy, -rz * dx);
                    if (!world.isBlockLoaded(candidate)) {
                        continue;
                    }
                    if (world.getBlockState(candidate).getBlock() instanceof BlockVault) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** The door's facing, read from its corner cell. */
    @Nullable
    public static EnumFacing facingOf(World world, BlockPos origin) {
        if (world == null || origin == null) {
            return null;
        }
        IBlockState state = world.getBlockState(origin);
        if (!(state.getBlock() instanceof BlockVault)) {
            return null;
        }
        return state.getValue(FACING);
    }

    // --------------------------------------------------------------- teardown

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !DISMANTLING) {
            takeDown(world, pos, facingOf(world, pos), pos, false);
        }
        super.breakBlock(world, pos, state);
    }

    static void takeDown(World world, BlockPos origin, EnumFacing facing, BlockPos dropAt, boolean dropItem) {
        if (world.isRemote || DISMANTLING || facing == null) {
            return;
        }
        DISMANTLING = true;
        try {
            for (int dx = 0; dx < W; dx++) {
                for (int dy = 0; dy < H; dy++) {
                    BlockPos c = cell(origin, facing, dx, dy);
                    if (!world.isBlockLoaded(c)) {
                        continue;
                    }
                    Block block = world.getBlockState(c).getBlock();
                    if (block instanceof BlockVault || block instanceof BlockVaultPart) {
                        world.setBlockToAir(c);
                    }
                }
            }
            if (dropItem) {
                BlockPos at = dropAt == null ? origin : dropAt;
                spawnAsEntity(world, at, new ItemStack(ModContent.BLOCK_VAULT_DOOR));
            }
        } finally {
            DISMANTLING = false;
        }
    }

    @Override
    public int quantityDropped(Random random) {
        return 1;
    }

    @Override
    public int damageDropped(IBlockState state) {
        return 0;
    }
}
