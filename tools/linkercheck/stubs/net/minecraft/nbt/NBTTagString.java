package net.minecraft.nbt;

public class NBTTagString extends NBTBase {

    private final String value;

    public NBTTagString(String value) {
        this.value = value;
    }

    public String getString() {
        return this.value;
    }

    @Override
    public byte getId() {
        return 8;
    }
}
