package com.rsaudio.client;

import net.minecraft.util.math.BlockPos;

/**
 * 客户端侧记住的魔杖选区（真值在服务端，这里只是给界面显示用）。
 * <p>
 * 单独放一个类而不是塞进界面里：选区会因为玩家左右键而变，
 * 界面可能还没打开；等界面打开时直接读这里就行。
 */
public final class ClientWandState {

    private static boolean hasPos1;
    private static boolean hasPos2;
    private static BlockPos pos1;
    private static BlockPos pos2;
    private static String name = "";
    private static String message = "";
    /** 最近一次操作的时间戳，界面用来把提示文字淡出 */
    private static long messageTime;

    private ClientWandState() {
    }

    /** 收到服务端同步包时调用 */
    public static void apply(boolean first, long packed1, boolean second, long packed2,
                             String selectionName, String text) {
        hasPos1 = first;
        pos1 = first ? BlockPos.fromLong(packed1) : null;
        hasPos2 = second;
        pos2 = second ? BlockPos.fromLong(packed2) : null;
        name = selectionName == null ? "" : selectionName;
        if (text != null && !text.isEmpty()) {
            message = text;
            messageTime = System.currentTimeMillis();
        }
    }

    public static boolean hasPos1() {
        return hasPos1;
    }

    public static boolean hasPos2() {
        return hasPos2;
    }

    public static BlockPos getPos1() {
        return pos1;
    }

    public static BlockPos getPos2() {
        return pos2;
    }

    public static String getName() {
        return name;
    }

    public static String getMessage() {
        return message;
    }

    public static boolean isMessageFresh() {
        return System.currentTimeMillis() - messageTime < 6000L;
    }

    /** 界面里显示选区尺寸用；和 {@code StructureSelection#describe} 保持一致 */
    public static String describe() {
        if (!hasPos1 && !hasPos2) {
            return "未选择";
        }
        if (!hasPos1 || !hasPos2) {
            BlockPos only = hasPos1 ? pos1 : pos2;
            return String.format(java.util.Locale.ROOT, "(%d, %d, %d) · 还差一个角",
                    only.getX(), only.getY(), only.getZ());
        }
        int sx = Math.abs(pos1.getX() - pos2.getX()) + 1;
        int sy = Math.abs(pos1.getY() - pos2.getY()) + 1;
        int sz = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
        return String.format(java.util.Locale.ROOT, "%d×%d×%d · %d 格", sx, sy, sz, sx * sy * sz);
    }
}
