package net.minecraft.nbt;

public class NBTTagLong extends NBTBase {

    private final long value;

    public NBTTagLong(long value) {
        this.value = value;
    }

    public long getLong() {
        return this.value;
    }

    @Override
    public byte getId() {
        return 4;
    }
}
