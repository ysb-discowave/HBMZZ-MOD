package net.minecraft.nbt;

import java.util.HashMap;
import java.util.Map;

public class NBTTagCompound extends NBTBase {

    private final Map<String, NBTBase> tags = new HashMap<>();

    public void setTag(String key, NBTBase value) {
        this.tags.put(key, value);
    }

    public boolean hasKey(String key) {
        return this.tags.containsKey(key);
    }

    public boolean hasKey(String key, int type) {
        NBTBase tag = this.tags.get(key);
        return tag != null && tag.getId() == type;
    }

    public NBTBase getTag(String key) {
        return this.tags.get(key);
    }

    public NBTTagList getTagList(String key, int type) {
        NBTBase tag = this.tags.get(key);
        if (tag instanceof NBTTagList) {
            return (NBTTagList) tag;
        }
        return new NBTTagList();
    }

    public NBTTagCompound getCompoundTag(String key) {
        NBTBase tag = this.tags.get(key);
        if (tag instanceof NBTTagCompound) {
            return (NBTTagCompound) tag;
        }
        return new NBTTagCompound();
    }

    @Override
    public byte getId() {
        return 10;
    }
}
