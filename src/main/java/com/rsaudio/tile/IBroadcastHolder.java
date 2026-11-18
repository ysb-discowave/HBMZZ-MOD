package com.rsaudio.tile;

/**
 * 支持"聊天栏播报"的方块实体。
 * <p>
 * 音频播放器与红石延迟器都实现它，这样 {@code PacketSetBroadcast} /
 * {@code PacketBroadcastSync} 两条网络包不用 instanceof 分两种类型写两遍。
 */
public interface IBroadcastHolder {

    /** 广播文字（空字符串 = 关闭播报） */
    String getBroadcastText();

    /** 广播颜色：原版 {@code TextFormatting} 的 ordinal（0~15） */
    int getBroadcastColor();

    /** 服务端设置（存档 + 同步给客户端） */
    void setBroadcast(String text, int color);

    /** 客户端收到同步包时使用：只改值，不回发任何东西 */
    void applySyncedBroadcast(String text, int color);
}
