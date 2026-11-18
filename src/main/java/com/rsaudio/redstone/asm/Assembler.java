package com.rsaudio.redstone.asm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简汇编器：把文本程序编译成 {@link Program}（<b>零 Minecraft 依赖</b>）。
 *
 * <h3>语法</h3>
 * <pre>
 *   ; 分号或 # 开头是注释（引号里的 ; # 不算注释）
 *   loop:                     ; 标签，可以单独一行，也可以和指令同行
 *     WRI north               ; A = 北面的红石输入强度（0~15）
 *     CMP A, 0
 *     JE  off
 *     SET all, 15             ; 六个面都输出 15
 *     MSG "卖葱信号"
 *     WAIT 10                 ; 等 10 刻（约 0.5 秒）
 *     JMP loop
 *   off:
 *     CLR
 *     WAIT 10
 *     JMP loop
 * </pre>
 *
 * <h3>指令表</h3>
 * <pre>
 *   MOV r, src      寄存器 = 值             ADD/SUB/AND/OR/XOR/SHL/SHR r, src
 *   CMP r, src      比较（给 JE/JNE/JG/JL 用）
 *   JMP 标签        无条件跳转               JE/JNE/JG/JL 标签   条件跳转
 *   SET 面, src     设置该面输出强度(0~15)   CLR                所有面清零
 *   WRI 面[, r]     读该面输入强度（默认进 A）TIME r             已运行刻数
 *   WAIT 刻数       等待                     MSG 文字           播报到聊天栏
 *   HALT            停机                     NOP                空操作
 * </pre>
 *
 * <p>寄存器：{@code A B C D}。面：{@code down up north south west east} 或 {@code all}（全部），
 * 也可以写 0~6 的数字（顺序同上，6 = 全部）。
 * <p>值是 0~65535 的十进制立即数。
 */
public final class Assembler {

    /** 指令条数上限：防止有人贴进来一个几万行的程序拖死服务端 */
    public static final int MAX_INSTRUCTIONS = 4096;
    /** 源码行数上限 */
    public static final int MAX_SOURCE_LINES = 8000;
    /** 单条 MSG 文字长度上限 */
    public static final int MAX_STRING_LENGTH = 160;
    /** MSG 字符串表上限 */
    public static final int MAX_STRINGS = 64;

    /** 六个面的名字，下标 == {@code EnumFacing.ordinal()}：down up north south west east */
    public static final String[] FACE_NAMES = {"down", "up", "north", "south", "west", "east"};
    /** "全部面" 的编号（写给 OUT/WRI 用） */
    public static final int FACE_ALL = 6;

    /** 标签还没解析出来时占位用的哨兵值 */
    private static final int LABEL_PENDING = Integer.MIN_VALUE;

    /**
     * 默认程序：一个"卖葱信号发生器"。
     * <p>
     * 北面接拉杆/红石；有信号时每 20 刻发一次 10 刻的满强度脉冲（另外五个面也会同时输出），
     * 并在聊天栏播报「卖葱信号」；信号消失后播报「停止卖葱」。
     * <p>
     * 播报做了去重（同一句话只在变化时播一次），所以这个循环不会刷屏。
     */
    public static final String DEFAULT_SOURCE = String.join("\n",
            "; ===== 卖葱信号发生器 =====",
            "; 北面读输入；有信号时每 1 秒发一次 10 刻脉冲",
            "; 寄存器 A B C D，面：down up north south west east / all",
            "loop:",
            "  WRI north        ; A = 北面输入强度",
            "  CMP A, 0",
            "  JE  idle",
            "  SET all, 15      ; 六个面全输出 15",
            "  MSG \"卖葱信号\"",
            "  WAIT 10",
            "  CLR",
            "  WAIT 10",
            "  JMP loop",
            "idle:",
            "  CLR",
            "  MSG \"停止卖葱\"",
            "  WAIT 10",
            "  JMP loop");

    private Assembler() {
    }

    /** 编译结果：要么 program 可用，要么 error/errorLine 有值 */
    public static final class Result {

        public final Program program;
        public final String error;
        public final int errorLine;

        Result(Program program, String error, int errorLine) {
            this.program = program;
            this.error = error;
            this.errorLine = errorLine;
        }

        public boolean isOk() {
            return error == null;
        }
    }

    /** 待回填的标签引用 */
    private static final class Pending {
        final int instruction;
        final int slot;
        final String label;
        final int line;

        Pending(int instruction, int slot, String label, int line) {
            this.instruction = instruction;
            this.slot = slot;
            this.label = label;
            this.line = line;
        }
    }

    // ---------------------------------------------------------------- 主流程

    public static Result assemble(String source) {
        if (source == null || source.trim().isEmpty()) {
            return new Result(Program.EMPTY, null, 0);
        }
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = normalized.split("\n", -1);
        if (rawLines.length > MAX_SOURCE_LINES) {
            return error("程序太长：最多 " + MAX_SOURCE_LINES + " 行", rawLines.length);
        }

        List<int[]> out = new ArrayList<int[]>();
        List<String> strings = new ArrayList<String>();
        Map<String, Integer> labels = new HashMap<String, Integer>();
        List<Pending> pending = new ArrayList<Pending>();

        for (int i = 0; i < rawLines.length; i++) {
            int lineNo = i + 1;
            String line = stripComment(rawLines[i]).trim();
            if (line.isEmpty()) {
                continue;
            }

            // ---- 先摘标签：允许 "loop:" 单独一行，也允许 "loop: JMP loop" 这种紧凑写法
            while (true) {
                int colon = labelColon(line);
                if (colon < 0) {
                    break;
                }
                String name = line.substring(0, colon).trim();
                if (!isIdentifier(name)) {
                    return error("标签名不合法：" + name, lineNo);
                }
                if (labels.containsKey(name)) {
                    return error("标签重复定义：" + name, lineNo);
                }
                labels.put(name, out.size());
                line = line.substring(colon + 1).trim();
                if (line.isEmpty()) {
                    break;
                }
            }
            if (line.isEmpty()) {
                continue;
            }

            if (out.size() >= MAX_INSTRUCTIONS) {
                return error("指令太多：最多 " + MAX_INSTRUCTIONS + " 条", lineNo);
            }

            // ---- 指令
            int space = firstSpace(line);
            String mnemonic = (space < 0 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT);
            String rest = space < 0 ? "" : line.substring(space + 1).trim();
            int op = opcodeOf(mnemonic);
            if (op < 0) {
                return error("未知指令：" + mnemonic, lineNo);
            }

            int[] record = new int[]{op, 0, 0, lineNo};

            switch (op) {
                case Program.NOP:
                case Program.HALT:
                case Program.CLR: {
                    if (!operands(rest).isEmpty()) {
                        return error(mnemonic + " 不需要操作数", lineNo);
                    }
                    break;
                }
                case Program.MOV:
                case Program.ADD:
                case Program.SUB:
                case Program.AND:
                case Program.OR:
                case Program.XOR:
                case Program.SHL:
                case Program.SHR:
                case Program.CMP: {
                    List<String> args = operands(rest);
                    if (args.size() != 2) {
                        return error(mnemonic + " 需要两个操作数：" + mnemonic + " 寄存器, 值/寄存器", lineNo);
                    }
                    int reg = register(args.get(0));
                    if (reg < 0) {
                        return error("第一个操作数必须是寄存器（A/B/C/D）：" + args.get(0), lineNo);
                    }
                    int src = value(args.get(1));
                    if (src == Integer.MIN_VALUE) {
                        return error("第二个操作数必须是寄存器或 0~65535 的数字：" + args.get(1), lineNo);
                    }
                    record[Program.A] = reg;
                    record[Program.B] = src;
                    break;
                }
                case Program.JMP:
                case Program.JE:
                case Program.JNE:
                case Program.JG:
                case Program.JL: {
                    List<String> args = operands(rest);
                    if (args.size() != 1) {
                        return error(mnemonic + " 需要一个标签名", lineNo);
                    }
                    String label = args.get(0);
                    if (!isIdentifier(label)) {
                        return error("标签名不合法：" + label, lineNo);
                    }
                    record[Program.A] = LABEL_PENDING;
                    pending.add(new Pending(out.size(), Program.A, label, lineNo));
                    break;
                }
                case Program.OUT: {
                    List<String> args = operands(rest);
                    if (args.size() != 2) {
                        return error("SET 需要两个操作数：SET 面, 值/寄存器", lineNo);
                    }
                    int face = face(args.get(0));
                    if (face < 0) {
                        return error("面名不合法（down/up/north/south/west/east/all 或 0~6）：" + args.get(0), lineNo);
                    }
                    int src = value(args.get(1));
                    if (src == Integer.MIN_VALUE) {
                        return error("输出值必须是寄存器或 0~65535 的数字：" + args.get(1), lineNo);
                    }
                    record[Program.A] = face;
                    record[Program.B] = src;
                    break;
                }
                case Program.WRI: {
                    // WRI 面        -> 读进 A（最常用的写法，允许省略寄存器）
                    // WRI 面, 寄存器 -> 读进指定寄存器
                    List<String> args = operands(rest);
                    if (args.size() != 1 && args.size() != 2) {
                        return error("WRI 需要一个面，可选一个寄存器：WRI 面[, 寄存器]", lineNo);
                    }
                    int face = face(args.get(0));
                    if (face < 0) {
                        return error("面名不合法（down/up/north/south/west/east/all 或 0~6）：" + args.get(0), lineNo);
                    }
                    int reg = args.size() == 1 ? 0 : register(args.get(1));
                    if (reg < 0) {
                        return error("第二个操作数必须是寄存器（A/B/C/D）：" + args.get(1), lineNo);
                    }
                    record[Program.A] = face;
                    record[Program.B] = reg;
                    break;
                }
                case Program.TIME: {
                    List<String> args = operands(rest);
                    if (args.size() != 1) {
                        return error("TIME 需要一个寄存器", lineNo);
                    }
                    int reg = register(args.get(0));
                    if (reg < 0) {
                        return error("TIME 的操作数必须是寄存器（A/B/C/D）：" + args.get(0), lineNo);
                    }
                    record[Program.A] = reg;
                    break;
                }
                case Program.WAIT: {
                    List<String> args = operands(rest);
                    if (args.size() != 1) {
                        return error("WAIT 需要一个刻数，例如 WAIT 20", lineNo);
                    }
                    int ticks = immediate(args.get(0));
                    if (ticks == Integer.MIN_VALUE) {
                        return error("WAIT 的刻数必须是 0~65535 的数字：" + args.get(0), lineNo);
                    }
                    record[Program.A] = ticks;
                    break;
                }
                case Program.MSG: {
                    String text = unquote(rest);
                    if (text.isEmpty()) {
                        return error("MSG 需要一段文字", lineNo);
                    }
                    if (strings.size() >= MAX_STRINGS) {
                        return error("MSG 文字太多：最多 " + MAX_STRINGS + " 条", lineNo);
                    }
                    String clipped = text.length() > MAX_STRING_LENGTH
                            ? text.substring(0, MAX_STRING_LENGTH) : text;
                    int index = strings.indexOf(clipped);
                    if (index < 0) {
                        index = strings.size();
                        strings.add(clipped);
                    }
                    record[Program.A] = index;
                    break;
                }
                default:
                    return error("暂不支持的指令：" + mnemonic, lineNo);
            }

            out.add(record);
        }

        // ---- 回填标签
        for (Pending item : pending) {
            Integer target = labels.get(item.label);
            if (target == null) {
                return error("找不到标签：" + item.label, item.line);
            }
            out.get(item.instruction)[item.slot] = target;
        }

        int[] code = new int[out.size() * Program.STRIDE];
        for (int i = 0; i < out.size(); i++) {
            System.arraycopy(out.get(i), 0, code, i * Program.STRIDE, Program.STRIDE);
        }
        return new Result(new Program(code, out.size(),
                strings.toArray(new String[strings.size()]), rawLines.length), null, 0);
    }

    private static Result error(String message, int line) {
        return new Result(null, message, line);
    }

    // ---------------------------------------------------------------- 词法

    /**
     * 去掉注释。分号/井号在<b>双引号里</b>不算注释，这样 {@code MSG "a;b"} 能原样播报。
     */
    public static String stripComment(String line) {
        if (line == null) {
            return "";
        }
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == ';' || c == '#')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** 行首标签的冒号位置（"loop:" -> 4）；不是标签返回 -1 */
    private static int labelColon(String line) {
        int i = 0;
        while (i < line.length() && isIdentifierChar(line.charAt(i))) {
            i++;
        }
        if (i > 0 && i < line.length() && line.charAt(i) == ':') {
            return i;
        }
        return -1;
    }

    private static int firstSpace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 逗号和空白都当分隔符，所以 SET all,15 / SET all 15 都能写 */
    private static List<String> operands(String rest) {
        List<String> list = new ArrayList<String>();
        if (rest == null || rest.isEmpty()) {
            return list;
        }
        for (String token : rest.split("[,\\s]+")) {
            String piece = token.trim();
            if (!piece.isEmpty()) {
                list.add(piece);
            }
        }
        return list;
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !isIdentifierChar(value.charAt(0))) {
            return false;
        }
        if (value.charAt(0) >= '0' && value.charAt(0) <= '9') {
            return false;                    // 标签不能以数字开头，免得和立即数撞
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isIdentifierChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '.';
    }

    /** 拆掉 MSG 的两端引号（没有引号就原样返回并 trim） */
    public static String unquote(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /** 寄存器 A/B/C/D -> 0~3；不是寄存器返回 -1 */
    private static int register(String token) {
        if (token == null || token.length() != 1) {
            return -1;
        }
        char c = Character.toUpperCase(token.charAt(0));
        if (c >= 'A' && c < 'A' + 4) {
            return c - 'A';
        }
        return -1;
    }

    /** 操作数 -> 编码（寄存器号，或 IMM_FLAG|立即数）；非法返回 Integer.MIN_VALUE */
    private static int value(String token) {
        int reg = register(token);
        if (reg >= 0) {
            return reg;
        }
        int imm = immediate(token);
        if (imm == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return Program.IMM_FLAG | imm;
    }

    /** 纯数字 -> 0~65535；否则 Integer.MIN_VALUE */
    private static int immediate(String token) {
        if (token == null || token.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return Integer.MIN_VALUE;
            }
        }
        try {
            long v = Long.parseLong(token);
            if (v < 0 || v > Program.IMM_MASK) {
                return Integer.MIN_VALUE;
            }
            return (int) v;
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /** 面名 -> 0~6；非法返回 -1 */
    private static int face(String token) {
        if (token == null || token.isEmpty()) {
            return -1;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if ("all".equals(lower) || "*".equals(lower)) {
            return FACE_ALL;
        }
        for (int i = 0; i < FACE_NAMES.length; i++) {
            if (FACE_NAMES[i].equals(lower)) {
                return i;
            }
        }
        int n = immediate(token);
        if (n >= 0 && n <= FACE_ALL) {
            return n;
        }
        return -1;
    }

    private static int opcodeOf(String mnemonic) {
        if ("NOP".equals(mnemonic)) return Program.NOP;
        if ("MOV".equals(mnemonic)) return Program.MOV;
        if ("ADD".equals(mnemonic)) return Program.ADD;
        if ("SUB".equals(mnemonic)) return Program.SUB;
        if ("AND".equals(mnemonic)) return Program.AND;
        if ("OR".equals(mnemonic)) return Program.OR;
        if ("XOR".equals(mnemonic)) return Program.XOR;
        if ("SHL".equals(mnemonic)) return Program.SHL;
        if ("SHR".equals(mnemonic)) return Program.SHR;
        if ("CMP".equals(mnemonic)) return Program.CMP;
        if ("JMP".equals(mnemonic)) return Program.JMP;
        if ("JE".equals(mnemonic)) return Program.JE;
        if ("JNE".equals(mnemonic)) return Program.JNE;
        if ("JG".equals(mnemonic)) return Program.JG;
        if ("JL".equals(mnemonic)) return Program.JL;
        // SET 和 OUT 是同一个东西：SET 更贴近"设红石强度"的直觉
        if ("OUT".equals(mnemonic) || "SET".equals(mnemonic)) return Program.OUT;
        if ("WRI".equals(mnemonic) || "IN".equals(mnemonic)) return Program.WRI;
        if ("WAIT".equals(mnemonic) || "SLEEP".equals(mnemonic)) return Program.WAIT;
        if ("HALT".equals(mnemonic) || "STOP".equals(mnemonic)) return Program.HALT;
        if ("TIME".equals(mnemonic)) return Program.TIME;
        if ("MSG".equals(mnemonic) || "SAY".equals(mnemonic)) return Program.MSG;
        if ("CLR".equals(mnemonic) || "CLEAR".equals(mnemonic)) return Program.CLR;
        return -1;
    }
}
