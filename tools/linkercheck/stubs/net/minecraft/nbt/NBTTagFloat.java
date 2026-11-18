package net.minecraft.nbt;

public class NBTTagFloat extends NBTBase {

    private final float value;

    public NBTTagFloat(float value) {
        this.value = value;
    }

    public float getFloat() {
        return this.value;
    }

    @Override
    public byte getId() {
        return 5;
    }
}
