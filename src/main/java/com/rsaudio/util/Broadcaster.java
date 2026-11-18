package com.rsaudio.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

/**
 * 聊天栏播报（左下角）。
 * <p>
 * 两个方块都用它：音频播放器在开始播放时播报，红石延迟器在延迟触发输出时播报。
 * 只发给该位置附近的玩家（{@link #RANGE} 格，和声音的传播范围一致），
 * 这样不会把整服的人都刷屏。
 * <p>
 * 颜色用原版 {@link TextFormatting} 的 16 种颜色：1.12.2 的字体渲染器只支持这 16 种，
 * 所以"自定义颜色"就是在这 16 种里挑。颜色按 ordinal 存进 NBT，
 * 这里用 {@link #colorOf(int)} 做一次越界保护（旧存档/坏数据落到默认色，不会崩）。
 */
public final class Broadcaster {

    /** 播报范围（方块），与音频的播放范围保持一致 */
    public static final double RANGE = 128.0D;

    /** 原版 16 色的 RGB 值，顺序同 {@link TextFormatting} 的前 16 项（ordinal 0~15），界面上画色块用 */
    public static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };

    /** 可选颜色数量（原版前 16 项都是颜色，后面的是粗体/斜体之类的格式码） */
    public static final int COLOR_COUNT = 16;

    /** 默认色：黄色，在聊天栏里最显眼 */
    public static final int DEFAULT_COLOR = TextFormatting.YELLOW.ordinal();

    /** 广播文字的长度上限（防止有人用超长文本刷屏） */
    public static final int MAX_LENGTH = 160;

    private Broadcaster() {
    }

    /** 统一在这里裁剪文字，服务端/客户端两边行为一致 */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }

    /** 把存下来的序号收敛成合法的颜色（越界 = 默认色） */
    public static TextFormatting colorOf(int ordinal) {
        if (ordinal < 0 || ordinal >= COLOR_COUNT) {
            return TextFormatting.values()[DEFAULT_COLOR];
        }
        return TextFormatting.values()[ordinal];
    }

    /** 下一个颜色（界面上的"颜色"按钮循环用） */
    public static int nextColor(int ordinal) {
        int value = ordinal < 0 || ordinal >= COLOR_COUNT ? DEFAULT_COLOR : ordinal;
        return (value + 1) % COLOR_COUNT;
    }

    /**
     * 把文字发到该位置附近所有玩家的聊天栏。
     * <p>
     * 只在服务端调用；文字为空或全是空白就什么都不做（= 这个功能没开）。
     */
    public static void send(World world, BlockPos pos, String text, int colorOrdinal) {
        if (world == null || world.isRemote || pos == null) {
            return;
        }
        if (text == null) {
            return;
        }
        String message = text.trim();
        if (message.isEmpty()) {
            return;
        }

        ITextComponent component = new TextComponentString(message);
        component.setStyle(new Style().setColor(colorOf(colorOrdinal)));

        double limit = RANGE * RANGE;
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        for (EntityPlayer player : world.playerEntities) {
            if (player == null || player.isDead) {
                continue;
            }
            if (player.getDistanceSq(x, y, z) <= limit) {
                player.sendMessage(component);
            }
        }
    }
}
