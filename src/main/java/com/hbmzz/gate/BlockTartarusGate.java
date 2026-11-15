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

package com.hbmzz.gate;

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
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * The Tartarus gate: the community edition's giant security door, laid flat.
 *
 * <h3>Not a new door</h3>
 * <p>This is the same door, rotated ninety degrees about a horizontal axis.
 * The wall version puts the door face on {@code north}, the door's top edge on
 * {@code up} and plain steel on the sides; this one puts the door face on
 * {@code up}, the top edge on {@code north} and the same steel on the sides.
 * The models in {@code assets/hbmzz/models/block/tartarus_gate*.json} are
 * exactly that re-assignment, and every texture they use is the door's own:
 * {@code rsaudio:blocks/door_front}, {@code door_side}, {@code door_top},
 * {@code door_bottom} and {@code door_panel}. Nothing was redrawn.</p>
 *
 * <h3>Twenty-six by twenty-six, one door</h3>
 * <p>The Transition Seal is the largest block in the mod: twenty-six cells
 * across and twenty-four high, a wall of {@code seal_hatch} plates with a
 * controller in the frame. Laid flat it becomes a twenty-six by twenty-six
 * floor plate - which is the only size that deserves the name, and the reason
 * this block is a 676-cell structure rather than a hatch. The cell at the
 * north-west corner carries the tile entity; the other 675 are
 * {@link BlockGatePart} cells. All of them carry the same {@code open} state,
 * so the door opens as one piece.</p>
 *
 * <h3>Open means gone</h3>
 * <p>When open, the cells render nothing at all. The wall door keeps its frame
 * visible because it retracts into a wall that is still there; a floor hatch
 * that kept a painted plate over the pit would be lying about the one thing the
 * player cares about. The leaves have retracted under the surrounding floor, so
 * what is left to see is the shaft.</p>
 */
public class BlockTartarusGate extends BlockHorizontal implements ITileEntityProvider {

    public static final PropertyBool OPEN = PropertyBool.create("open");

    /**
     * Cells per side. Fixed 26x26, the Transition Seal's own footprint. The
     * constant is the only place the size is written down: placement, teardown,
     * redstone and the item check all read it.
     */
    public static final int SIZE = 26;

    /** Reentrancy guard for taking the gate down cell by cell. */
    static boolean DISMANTLING = false;

    public BlockTartarusGate() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "tartarus_gate");
        setUnlocalizedName(HBMZZ.MODID + ".tartarus_gate");
        setCreativeTab(CreativeTabs.REDSTONE);
        // The wall door is hardness 50 / resistance 6000: it is meant to survive
        // a nuke. Laid flat it keeps the same numbers, because it is the same
        // door and not a decorative hatch.
        setHardness(50.0F);
        setResistance(6000.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(OPEN, Boolean.FALSE));
    }

    public static BlockPos cell(BlockPos origin, int dx, int dz) {
        return origin.add(dx, 0, dz);
    }

    private static IBlockState stateFor(EnumFacing facing, boolean open) {
        return ModContent.BLOCK_TARTARUS_GATE.getDefaultState()
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
        // Facing records which way the door was laid down. The door face is on
        // top either way; this is what tells two gates rotated ninety degrees
        // apart from each other.
        return stateFor(placer.getHorizontalFacing().getOpposite(), false);
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
        return new TileEntityTartarusGate();
    }

    // ------------------------------------------------------------- appearance

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {
        return !state.getValue(OPEN);
    }

    @Override
    @Nullable
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        // Closed: the door body, a full cell (the wall door's panels are full
        // blocks too). Open: nothing, so the player falls into the shaft.
        return state.getValue(OPEN) ? null : FULL_BLOCK_AABB;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return FULL_BLOCK_AABB;
    }

    /**
     * Selectable even when open.
     *
     * <p>An open door has no collision box, and the default ray trace only ever
     * hits one. That makes the door impossible to close by hand: every click
     * goes through the hole to whatever is at the bottom of the shaft. Keeping
     * a full-cube <em>selection</em> box while the collision box stays empty
     * gives the player something to aim at without giving them a floor to stand
     * on - the two boxes answer different questions, and this is the one the
     * crosshair asks.</p>
     */
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
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos cell = cell(pos, dx, dz);
                if (!world.isBlockLoaded(cell)) {
                    return false;
                }
                if (!world.getBlockState(cell).getBlock().isReplaceable(world, cell)) {
                    return false;
                }
            }
        }
        return true;
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
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos cell = cell(origin, dx, dz);
                if (!world.isBlockLoaded(cell)) {
                    continue;
                }
                Block existing = world.getBlockState(cell).getBlock();
                if (existing instanceof BlockGatePart) {
                    continue;
                }
                if (existing.isReplaceable(world, cell)) {
                    world.setBlockState(cell, ModContent.BLOCK_GATE_PART.getDefaultState()
                            .withProperty(BlockGatePart.FACING, facing)
                            .withProperty(BlockGatePart.OPEN, open), 3);
                }
            }
        }
    }

    // --------------------------------------------------------------- controls

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityTartarusGate && ((TileEntityTartarusGate) te).isPowered()) {
            if (!player.isSneaking()) {
                player.sendMessage(new TextComponentTranslation("hbmzz.msg.gate_powered"));
            }
            return true;
        }
        applyOpen(world, pos, !state.getValue(OPEN));
        return true;
    }

    /**
     * Sets every cell to the same state and makes the noise.
     *
     * <p>Done here rather than in the tile entity so that a redstone edge and a
     * click produce exactly the same thing: one door, one clunk.</p>
     */
    static void applyOpen(World world, BlockPos origin, boolean open) {
        IBlockState originState = world.getBlockState(origin);
        if (!(originState.getBlock() instanceof BlockTartarusGate)
                || originState.getValue(OPEN) == open) {
            return;
        }
        world.setBlockState(origin, stateFor(originState.getValue(FACING), open), 3);
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos cell = cell(origin, dx, dz);
                if (!world.isBlockLoaded(cell)) {
                    continue;
                }
                IBlockState partState = world.getBlockState(cell);
                if (partState.getBlock() instanceof BlockGatePart) {
                    world.setBlockState(cell, partState.withProperty(BlockGatePart.OPEN, open), 3);
                }
            }
        }
        world.playSound(null, origin, open
                        ? net.minecraft.init.SoundEvents.BLOCK_IRON_DOOR_OPEN
                        : net.minecraft.init.SoundEvents.BLOCK_IRON_DOOR_CLOSE,
                net.minecraft.util.SoundCategory.BLOCKS, 1.0F, 0.55F);
    }

    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block blockIn, BlockPos fromPos) {
        if (!world.isRemote) {
            updateSignal(world, pos);
        }
    }

    /** True when any cell of the door is powered; the door is one switch. */
    static boolean powered(World world, BlockPos origin) {
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                if (world.isBlockPowered(cell(origin, dx, dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The corner cell of whichever gate covers {@code pos}, or null. */
    @Nullable
    public static BlockPos findOrigin(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                BlockPos candidate = pos.add(-dx, 0, -dz);
                if (!world.isBlockLoaded(candidate)) {
                    continue;
                }
                if (world.getBlockState(candidate).getBlock() instanceof BlockTartarusGate) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // --------------------------------------------------------------- teardown

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !DISMANTLING) {
            // Vanilla has already spawned this block's drop by the time
            // breakBlock runs, so the corner must not ask for a second item.
            takeDown(world, pos, pos, false);
        }
        super.breakBlock(world, pos, state);
    }

    /**
     * Removes the whole door.
     *
     * @param dropAt   where the item appears; a cell 25 blocks away from the
     *                 one that was mined looks like the door simply vanished
     * @param dropItem true when the teardown started at a filler cell, which
     *                 has no item of its own and therefore has to hand the
     *                 player the corner block's item itself
     */
    static void takeDown(World world, BlockPos origin, BlockPos dropAt, boolean dropItem) {
        if (world.isRemote || DISMANTLING) {
            return;
        }
        DISMANTLING = true;
        try {
            for (int dx = 0; dx < SIZE; dx++) {
                for (int dz = 0; dz < SIZE; dz++) {
                    BlockPos cell = cell(origin, dx, dz);
                    if (!world.isBlockLoaded(cell)) {
                        continue;
                    }
                    Block block = world.getBlockState(cell).getBlock();
                    if (block instanceof BlockTartarusGate || block instanceof BlockGatePart) {
                        world.setBlockToAir(cell);
                    }
                }
            }
            if (dropItem) {
                BlockPos at = dropAt == null ? origin : dropAt;
                spawnAsEntity(world, at, new ItemStack(ModContent.BLOCK_TARTARUS_GATE));
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

    /** Redstone can reach any cell; every cell forwards to the corner. */
    static void updateSignal(World world, BlockPos origin) {
        TileEntity te = world.getTileEntity(origin);
        if (te instanceof TileEntityTartarusGate) {
            ((TileEntityTartarusGate) te).checkSignal(world, origin);
        }
    }
}
