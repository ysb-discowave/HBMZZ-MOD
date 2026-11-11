package com.rsaudio.client;

import com.rsaudio.network.PacketProgramSync;
import net.minecraft.util.math.BlockPos;

/**
 * 客户端侧记住的红石计算机状态（界面画实时面板用）。
 * <p>
 * 服务端每 5 tick 推一次，这里存最近的一份。跟着 {@link BlockPos} 一起核对：
 * 只有"正在看的这台机器"的数据才会被采纳，免得串台。
 */
public final class ClientComputerState {

    private static BlockPos pos;
    private static String source = "";
    private static boolean running;
    private static int errorLine;
    private static String error = "";
    private static int pc;
    private static int[] regs = new int[4];
    private static int[] outputs = new int[6];
    private static int ticks;
    private static String message = "";
    /** 本地是否已经收到过这台机器的一次同步 */
    private static boolean valid;

    private ClientComputerState() {
    }

    public static void apply(PacketProgramSync packet) {
        if (packet == null) {
            return;
        }
        pos = packet.getPos();
        source = packet.getSource();
        running = packet.isRunning();
        errorLine = packet.getErrorLine();
        error = packet.getError();
        pc = packet.getPc();
        ticks = packet.getTicks();
        message = packet.getMessage();
        regs = new int[4];
        for (int i = 0; i < 4; i++) {
            regs[i] = packet.getReg(i);
        }
        outputs = new int[6];
        for (int i = 0; i < 6; i++) {
            outputs[i] = packet.getOutput(i);
        }
        valid = true;
    }

    /** 换了一台机器（打开另一台机器的界面）就作废，免得显示上一台的数据 */
    public static void expect(BlockPos target) {
        if (pos == null || target == null || !pos.equals(target)) {
            valid = false;
            pos = target;
        }
    }

    public static boolean isValid() {
        return valid;
    }

    public static String getSource() {
        return source;
    }

    public static boolean isRunning() {
        return running;
    }

    public static String getError() {
        return error;
    }

    public static int getErrorLine() {
        return errorLine;
    }

    public static int getPc() {
        return pc;
    }

    public static int getReg(int index) {
        return index >= 0 && index < regs.length ? regs[index] : 0;
    }

    public static int getOutput(int face) {
        return face >= 0 && face < outputs.length ? outputs[face] : 0;
    }

    public static int getTicks() {
        return ticks;
    }

    public static String getMessage() {
        return message;
    }
}
