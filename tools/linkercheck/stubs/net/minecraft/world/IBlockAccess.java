package net.minecraft.world;

/**
 * Minimal stand-in for the block-access view of a world.
 *
 * <p>Only needed because {@code CPMachines} names it in a reflective method
 * lookup - {@code BlockDummyable.findCore(IBlockAccess, int, int, int)} - and a
 * class literal is resolved when the holder class loads.</p>
 */
public interface IBlockAccess {
}
