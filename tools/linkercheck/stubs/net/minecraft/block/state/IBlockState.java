package net.minecraft.block.state;

import net.minecraft.block.Block;

/** Minimal stand-in for a block state: {@code getBlock()} is all that is asked. */
public class IBlockState {

    public Block getBlock() {
        return null;
    }
}
