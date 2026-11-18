package net.minecraft.world;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Minimal stand-in for a world.
 *
 * <p>Only the three questions the control panel asks of a world are answered
 * here: is this position loaded, what tile entity sits there, and what block
 * state sits there. Subclasses override them to count calls or to hand back a
 * fake machine, which is how the offline checks observe the panel reaching -
 * or failing to reach - a target.</p>
 */
public class World {

    public boolean isRemote;

    public boolean isBlockLoaded(BlockPos pos) {
        return true;
    }

    public TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    public IBlockState getBlockState(BlockPos pos) {
        return null;
    }
}
