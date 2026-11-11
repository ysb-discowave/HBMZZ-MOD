package com.rsaudio.redstone.asm;

/**
 * 极简红石计算机的 CPU 内核（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 为什么单独抽出来：这个类里全是"取值 → 算 → 跳转"的纯逻辑，跟世界、方块、渲染都无关。
 * 抽出来之后可以脱离游戏直接用 javac 编译 + 跑断言（见 tools/check_asm.py），
 * 时序类 bug 不用等到进游戏才发现。
 * <p>
 * 时间模型：世界每 tick 调一次 {@link #tick(Io, int)}，一次最多执行
 * {@code maxSteps} 条指令。这样即使玩家写了 {@code loop: JMP loop} 这种死循环，
 * 也只会吃掉每 tick 的配额，不会卡死服务端；同时不用 WAIT 的紧凑循环
 * 也能以"每 tick N 条"的速度推进。
 * <p>
 * 面编号直接等于 {@code EnumFacing.ordinal()}：0=down 1=up 2=north 3=south 4=west 5=east，
 * {@link #FACE_ALL}（6）表示六个面一起。
 */
public final class Cpu {

    public static final int REG_COUNT = 4;
    public static final int FACE_COUNT = 6;
    /** 六个面一起（SET all / WRI all） */
    public static final int FACE_ALL = 6;
    /** 红石强度上限 */
    public static final int MAX_OUTPUT = 15;
    /** 寄存器/立即数上限 */
    public static final int MAX_VALUE = Program.IMM_MASK;
    /** 每 tick 默认指令配额 */
    public static final int DEFAULT_STEPS_PER_TICK = 16;

    /** 每 tick 的结果状态 */
    public enum Status {
        /** 没在运行（已停或已结束） */
        IDLE,
        /** 跑了一段，配额用完了，下一 tick 接着跑 */
        RAN,
        /** 这一 tick 在等待（WAIT）里消耗掉了 */
        WAITED,
        /** 程序正常结束（HALT 或跑过了最后一条） */
        HALTED,
        /** 被玩家停了 */
        STOPPED,
        /** 运行期错误（坏操作码等） */
        ERROR
    }

    /** CPU 与外部世界的接口：读输入、播报文字。输出不用回调 —— 输出寄存器就在 CPU 里。 */
    public interface Io {

        /** 读某个面的红石输入强度（0~15） */
        int input(int face);

        /** 程序要求播报一段文字 */
        void message(String text);
    }

    /** 没有外部世界时用的空实现（单测用） */
    public static final class NullIo implements Io {

        public static final NullIo INSTANCE = new NullIo();

        @Override
        public int input(int face) {
            return 0;
        }

        @Override
        public void message(String text) {
        }
    }

    private final int[] regs = new int[REG_COUNT];
    private final int[] outputs = new int[FACE_COUNT];

    private Program program = Program.EMPTY;
    private int pc;
    private int waitTicks;
    private int programTicks;
    private int comparison;
    private boolean running;
    private boolean halted;
    private String error;
    private int errorLine;

    // ---------------------------------------------------------------- 装载与启停

    /** 换程序：会复位（寄存器和输出都清零） */
    public void setProgram(Program value) {
        this.program = value == null ? Program.EMPTY : value;
        reset();
    }

    public Program getProgram() {
        return program;
    }

    /** 复位到"刚开机"的状态，但不动程序本身 */
    public void reset() {
        for (int i = 0; i < REG_COUNT; i++) {
            regs[i] = 0;
        }
        for (int i = 0; i < FACE_COUNT; i++) {
            outputs[i] = 0;
        }
        pc = 0;
        waitTicks = 0;
        programTicks = 0;
        comparison = 0;
        running = false;
        halted = false;
        error = null;
        errorLine = 0;
    }

    /** 从头开始运行 */
    public void start() {
        reset();
        if (!program.isEmpty()) {
            running = true;
        } else {
            halted = true;
        }
    }

    /** 暂停（保留寄存器和输出，可以继续跑） */
    public void pause() {
        running = false;
        halted = false;
    }

    /** 继续跑（不复位） */
    public void resume() {
        if (!program.isEmpty()) {
            running = true;
            halted = false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isHalted() {
        return halted;
    }

    public boolean hasError() {
        return error != null;
    }

    public String getError() {
        return error;
    }

    public int getErrorLine() {
        return errorLine;
    }

    public int getPc() {
        return pc;
    }

    public int getProgramTicks() {
        return programTicks;
    }

    public int getRegister(int index) {
        return index < 0 || index >= REG_COUNT ? 0 : regs[index];
    }

    public int getOutput(int face) {
        return face < 0 || face >= FACE_COUNT ? 0 : outputs[face];
    }

    /** 有没有任何一面在输出（方块状态用它决定要不要点亮指示灯） */
    public boolean hasAnyOutput() {
        for (int i = 0; i < FACE_COUNT; i++) {
            if (outputs[i] > 0) {
                return true;
            }
        }
        return false;
    }

    /** 一次性清掉所有输出（停机、拆方块时用） */
    public void clearOutputs() {
        for (int i = 0; i < FACE_COUNT; i++) {
            outputs[i] = 0;
        }
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 推进一个世界 tick。
     *
     * @param io       外部世界接口（可为 null，等于没有任何输入）
     * @param maxSteps 这一 tick 最多执行多少条指令（<=0 用默认值）
     */
    public Status tick(Io io, int maxSteps) {
        if (!running) {
            return halted ? Status.HALTED : Status.STOPPED;
        }
        if (program.isEmpty()) {
            running = false;
            halted = true;
            return Status.HALTED;
        }
        programTicks++;
        int budget = maxSteps <= 0 ? DEFAULT_STEPS_PER_TICK : maxSteps;
        Io world = io == null ? NullIo.INSTANCE : io;
        for (int step = 0; step < budget; step++) {
            if (waitTicks > 0) {
                waitTicks--;
                return Status.WAITED;
            }
            Status status = execOne(world);
            if (status != Status.RAN) {
                return status;
            }
        }
        return Status.RAN;
    }

    /** 执行一条指令 */
    private Status execOne(Io io) {
        if (pc < 0 || pc >= program.count) {
            // 跑过了最后一条 = 程序结束（等价于结尾有一个隐式 HALT）
            running = false;
            halted = true;
            return Status.HALTED;
        }
        int base = pc * Program.STRIDE;
        int op = program.code[base + Program.OP];
        int a = program.code[base + Program.A];
        int b = program.code[base + Program.B];
        int line = program.code[base + Program.LINE];
        pc++;

        switch (op) {
            case Program.NOP:
                return Status.RAN;
            case Program.HALT:
                running = false;
                halted = true;
                return Status.HALTED;
            case Program.CLR:
                clearOutputs();
                return Status.RAN;
            case Program.MOV:
                regs[a & 3] = clamp(read(b));
                return Status.RAN;
            case Program.ADD:
                regs[a & 3] = clamp(regs[a & 3] + read(b));
                return Status.RAN;
            case Program.SUB:
                regs[a & 3] = clamp(regs[a & 3] - read(b));
                return Status.RAN;
            case Program.AND:
                regs[a & 3] = clamp(regs[a & 3] & read(b));
                return Status.RAN;
            case Program.OR:
                regs[a & 3] = clamp(regs[a & 3] | read(b));
                return Status.RAN;
            case Program.XOR:
                regs[a & 3] = clamp(regs[a & 3] ^ read(b));
                return Status.RAN;
            case Program.SHL:
                regs[a & 3] = clamp(regs[a & 3] << Math.min(16, Math.max(0, read(b))));
                return Status.RAN;
            case Program.SHR:
                regs[a & 3] = clamp(regs[a & 3] >> Math.min(16, Math.max(0, read(b))));
                return Status.RAN;
            case Program.CMP: {
                int x = regs[a & 3];
                int y = read(b);
                comparison = x < y ? -1 : (x > y ? 1 : 0);
                return Status.RAN;
            }
            case Program.JMP:
                pc = a;
                return Status.RAN;
            case Program.JE:
                if (comparison == 0) {
                    pc = a;
                }
                return Status.RAN;
            case Program.JNE:
                if (comparison != 0) {
                    pc = a;
                }
                return Status.RAN;
            case Program.JG:
                if (comparison > 0) {
                    pc = a;
                }
                return Status.RAN;
            case Program.JL:
                if (comparison < 0) {
                    pc = a;
                }
                return Status.RAN;
            case Program.OUT: {
                int value = Math.min(MAX_OUTPUT, Math.max(0, read(b)));
                if (a == FACE_ALL) {
                    for (int i = 0; i < FACE_COUNT; i++) {
                        outputs[i] = value;
                    }
                } else {
                    outputs[a & 7] = value;
                }
                return Status.RAN;
            }
            case Program.WRI: {
                int value;
                if (a == FACE_ALL) {
                    value = 0;
                    for (int i = 0; i < FACE_COUNT; i++) {
                        value = Math.max(value, io.input(i));
                    }
                } else {
                    value = io.input(a & 7);
                }
                regs[b & 3] = Math.min(MAX_OUTPUT, Math.max(0, value));
                return Status.RAN;
            }
            case Program.WAIT:
                waitTicks = Math.max(0, a);
                return waitTicks > 0 ? Status.WAITED : Status.RAN;
            case Program.TIME:
                regs[a & 3] = programTicks & MAX_VALUE;
                return Status.RAN;
            case Program.MSG:
                if (a >= 0 && a < program.strings.length) {
                    io.message(program.strings[a]);
                }
                return Status.RAN;
            default:
                error = "未知操作码 " + op + "（程序可能来自旧版本，试试重新点「运行」）";
                errorLine = line;
                running = false;
                halted = true;
                return Status.ERROR;
        }
    }

    /**
     * 读操作数：带 {@link Program#IMM_FLAG} 的是立即数，否则是寄存器号。
     * <p>
     * 判断依据是"操作数怎么编的"，不是"值多大"，所以寄存器里存 65535
     * 也不会被误当成立即数。
     */
    private int read(int operand) {
        if ((operand & Program.IMM_FLAG) != 0) {
            return operand & Program.IMM_MASK;
        }
        return regs[operand & 3];
    }

    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return value > MAX_VALUE ? MAX_VALUE : value;
    }
}
