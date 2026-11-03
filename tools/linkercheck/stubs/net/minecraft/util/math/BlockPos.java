package net.minecraft.util.math;

/** Minimal stand-in for 1.12.2 BlockPos: same packing, same equality. */
public class BlockPos {

    /** Same singleton 1.12.2 exposes; Control's link map uses it as its default. */
    public static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    private final int x;
    private final int y;
    private final int z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getZ() {
        return this.z;
    }

    public long toLong() {
        return ((long) this.x & 0x3FFFFFFL) << 38
                | ((long) this.y & 0xFFFL) << 26
                | ((long) this.z & 0x3FFFFFFL);
    }

    public static BlockPos fromLong(long packed) {
        int x = (int) (packed << 0 >> 38);
        int y = (int) (packed << 26 >> 52);
        int z = (int) (packed << 38 >> 38);
        return new BlockPos(x, y, z);
    }

    public BlockPos toImmutable() {
        return this;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockPos)) {
            return false;
        }
        BlockPos pos = (BlockPos) other;
        return this.x == pos.x && this.y == pos.y && this.z == pos.z;
    }

    @Override
    public int hashCode() {
        return (this.y + this.z * 31) * 31 + this.x;
    }

    @Override
    public String toString() {
        return this.x + ", " + this.y + ", " + this.z;
    }
}
