package com.rsaudio.structure;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每个玩家的"结构魔杖"选区（两个角）。
 * <p>
 * 只放在服务端内存里，<b>不落盘</b>：选区是临时的操作状态，重启后重新框一下就行；
 * 真正要留下来的东西是"保存出来的结构文件"。
 * <p>
 * 玩家断开连接时由 {@code WandHandler} 调 {@link #remove} 清掉，免得长期占内存。
 */
public final class StructureSelection {

    private static final Map<UUID, StructureSelection> SELECTIONS = new HashMap<UUID, StructureSelection>();

    private BlockPos pos1;
    private BlockPos pos2;
    /** 界面里填的结构名（保存时用） */
    private String name = "";

    private StructureSelection() {
    }

    public static StructureSelection of(UUID playerId) {
        if (playerId == null) {
            return new StructureSelection();
        }
        StructureSelection selection = SELECTIONS.get(playerId);
        if (selection == null) {
            selection = new StructureSelection();
            SELECTIONS.put(playerId, selection);
        }
        return selection;
    }

    public static void remove(UUID playerId) {
        if (playerId != null) {
            SELECTIONS.remove(playerId);
        }
    }

    public static void clearAll() {
        SELECTIONS.clear();
    }

    // ---------------------------------------------------------------- 选区

    public BlockPos getPos1() {
        return pos1;
    }

    public BlockPos getPos2() {
        return pos2;
    }

    public void setPos1(BlockPos pos) {
        this.pos1 = pos == null ? null : pos.toImmutable();
    }

    public void setPos2(BlockPos pos) {
        this.pos2 = pos == null ? null : pos.toImmutable();
    }

    public void clearCorners() {
        this.pos1 = null;
        this.pos2 = null;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value == null ? "" : value;
    }

    /** 两个角都选好了没 */
    public boolean isComplete() {
        return pos1 != null && pos2 != null;
    }

    public boolean isEmpty() {
        return pos1 == null && pos2 == null;
    }

    public BlockPos min() {
        if (pos1 == null || pos2 == null) {
            return pos1 == null ? pos2 : pos1;
        }
        return new BlockPos(Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ()));
    }

    public BlockPos max() {
        if (pos1 == null || pos2 == null) {
            return pos1 == null ? pos2 : pos1;
        }
        return new BlockPos(Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ()));
    }

    public int sizeX() {
        return isComplete() ? Math.abs(pos1.getX() - pos2.getX()) + 1 : 0;
    }

    public int sizeY() {
        return isComplete() ? Math.abs(pos1.getY() - pos2.getY()) + 1 : 0;
    }

    public int sizeZ() {
        return isComplete() ? Math.abs(pos1.getZ() - pos2.getZ()) + 1 : 0;
    }

    public int volume() {
        return isComplete() ? sizeX() * sizeY() * sizeZ() : 0;
    }

    /** 界面上显示的一行描述，例如 {@code 12×5×3 · 180 格} */
    public String describe() {
        if (!isComplete()) {
            if (pos1 == null && pos2 == null) {
                return "未选择";
            }
            BlockPos only = pos1 == null ? pos2 : pos1;
            return String.format(java.util.Locale.ROOT, "(%d, %d, %d) · 还差一个角",
                    only.getX(), only.getY(), only.getZ());
        }
        return String.format(java.util.Locale.ROOT, "%d×%d×%d · %d 格", sizeX(), sizeY(), sizeZ(), volume());
    }
}
