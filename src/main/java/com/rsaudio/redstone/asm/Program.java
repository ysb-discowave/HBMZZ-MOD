package com.rsaudio.redstone.asm;

/**
 * 编译好的程序：定长指令数组（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 每条指令占 {@link #STRIDE} 个 int，字段含义见 {@link #OP}/{@link #A}/{@link #B}/{@link #LINE}。
 * 用定长数组而不是对象列表，是为了让"取指"变成几次数组下标运算 —— 这个 CPU 每 tick 都要跑，
 * 而且要被 GUI 每 5 tick 读一次状态，别在热路径上产生垃圾。
 * <p>
 * {@link #LINE} 存源码行号，出错时能直接告诉玩家"第 8 行有问题"，
 * 而不是甩一个 pc 值让人自己数。
 */
public final class Program {

    /** 每条指令占的 int 数 */
    public static final int STRIDE = 4;
    public static final int OP = 0;
    public static final int A = 1;
    public static final int B = 2;
    public static final int LINE = 3;

    /**
     * 操作数里的"立即数"标记。
     * <p>
     * 操作数复用同一个 int 槽：带这一位 = 立即数（低 16 位是值），不带 = 寄存器号。
     * 寄存器<b>值</b>里出现这一位不影响解析，因为判断依据是"操作数怎么编的"，
     * 不是"值是多少"。
     */
    public static final int IMM_FLAG = 0x10000;
    public static final int IMM_MASK = 0xFFFF;

    // ---------------------------------------------------------------- 操作码
    public static final int NOP = 0;
    /** MOV r, src */
    public static final int MOV = 1;
    public static final int ADD = 2;
    public static final int SUB = 3;
    public static final int AND = 4;
    public static final int OR = 5;
    public static final int XOR = 6;
    public static final int SHL = 7;
    public static final int SHR = 8;
    /** CMP r, src */
    public static final int CMP = 9;
    /** JMP 目标指令下标 */
    public static final int JMP = 10;
    public static final int JE = 11;
    public static final int JNE = 12;
    public static final int JG = 13;
    public static final int JL = 14;
    /** OUT 面, src —— 设置某个面的红石输出强度 */
    public static final int OUT = 15;
    /** WRI 面, r —— 读取某个面的红石输入强度到寄存器 */
    public static final int WRI = 16;
    /** WAIT 刻数 */
    public static final int WAIT = 17;
    public static final int HALT = 18;
    /** TIME r —— 程序已经运行的刻数 */
    public static final int TIME = 19;
    /** MSG 字符串表下标 */
    public static final int MSG = 20;
    /** 所有面的输出清零 */
    public static final int CLR = 21;
    public static final int OP_COUNT = 22;

    /** 指令字（长度 = {@link #count} * {@link #STRIDE}） */
    public final int[] code;
    /** 指令条数 */
    public final int count;
    /** MSG 用的字符串表 */
    public final String[] strings;
    /** 源码行数（含空行；仅用于界面显示） */
    public final int sourceLines;

    public Program(int[] code, int count, String[] strings, int sourceLines) {
        this.code = code == null ? new int[0] : code;
        this.count = Math.max(0, Math.min(count, this.code.length / STRIDE));
        this.strings = strings == null ? new String[0] : strings;
        this.sourceLines = sourceLines;
    }

    /** 空程序（没写任何东西）—— 合法，只是什么都不做 */
    public static final Program EMPTY = new Program(new int[0], 0, new String[0], 0);

    public boolean isEmpty() {
        return count == 0;
    }

    /** 取某条指令的源码行号（越界返回 0） */
    public int lineOf(int index) {
        if (index < 0 || index >= count) {
            return 0;
        }
        return code[index * STRIDE + LINE];
    }
}
