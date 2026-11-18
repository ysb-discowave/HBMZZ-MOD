package net.minecraft.nbt;

import java.util.ArrayList;
import java.util.List;

public class NBTTagList extends NBTBase {

    private final List<NBTBase> tags = new ArrayList<>();

    public void appendTag(NBTBase tag) {
        this.tags.add(tag);
    }

    public int tagCount() {
        return this.tags.size();
    }

    public NBTBase get(int index) {
        return this.tags.get(index);
    }

    @Override
    public byte getId() {
        return 9;
    }
}
