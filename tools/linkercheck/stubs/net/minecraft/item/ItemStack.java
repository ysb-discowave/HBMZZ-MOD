package net.minecraft.item;

import net.minecraft.nbt.NBTTagCompound;

public class ItemStack {

    public static final ItemStack EMPTY = new ItemStack();

    private final Item item;
    private NBTTagCompound tag;

    public ItemStack() {
        this.item = null;
    }

    public ItemStack(Item item) {
        this.item = item;
    }

    public boolean isEmpty() {
        return this.item == null;
    }

    public Item getItem() {
        return this.item;
    }

    public NBTTagCompound getTagCompound() {
        return this.tag;
    }

    public void setTagCompound(NBTTagCompound tag) {
        this.tag = tag;
    }

    public String getDisplayName() {
        return "item";
    }
}
