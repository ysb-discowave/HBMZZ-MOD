package net.minecraft.nbt;

public class NBTTagInt extends NBTBase {

    private final int value;

    public NBTTagInt(int value) {
        this.value = value;
    }

    public int getInt() {
        return this.value;
    }

    @Override
    public byte getId() {
        return 3;
    }
}
