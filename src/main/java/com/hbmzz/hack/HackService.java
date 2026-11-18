/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.hack;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The command language behind the hacker tablet, and the one place where a
 * value is written straight into another mod's tile entity.
 *
 * <h3>What makes this different from {@code MachineBridge}</h3>
 * <p>The control panel talks to machines through their own contract:
 * {@code IControllable.receiveEvent} with a named event, and the machine decides
 * what that means. A control rod moves because the machine agreed to move it.
 * This class does not ask. It walks the tile entity's declared fields, picks the
 * one whose name matches, and assigns to it - so a reactor's {@code heat} changes
 * whether or not any control rod moved, and whether or not the machine has an
 * event for it. That is exactly the "bypass the rods" the feature is about, and
 * it is also exactly why every write here is fenced.</p>
 *
 * <h3>The fences</h3>
 * <ul>
 *   <li><b>Owner.</b> Only tile entities from {@code com.hbm} (the base mod) and
 *       this project's own packages may be written. A generic "write any field on
 *       any object in the world" tool would be a griefing primitive against
 *       every other mod installed, and no story about a hacked reactor needs it.</li>
 *   <li><b>Shape.</b> Only primitive numeric and boolean fields. Object fields
 *       are refused, because assigning a wrong-typed object into a live tile
 *       entity is how you get a crash two ticks later inside somebody else's
 *       code, with our stack frame long gone.</li>
 *   <li><b>Kind.</b> {@code static} and {@code final} are refused. A static field
 *       is shared by every instance in the world - one command would rewrite
 *       every reactor on the server - and a final field is either a constant or
 *       something the JIT has already folded.</li>
 *   <li><b>Range.</b> NaN and infinity never reach a field.</li>
 * </ul>
 *
 * <p>A refused write is reported as a refusal. It is never silently dropped,
 * because "the command did nothing and said nothing" is indistinguishable from
 * a broken tablet.</p>
 */
public final class HackService {

    // Output tones. The tablet maps these to colours; nothing else reads them.
    public static final int TONE_TEXT = 0;
    public static final int TONE_OK = 1;
    public static final int TONE_ERR = 2;
    public static final int TONE_WARN = 3;
    public static final int TONE_DIM = 4;
    public static final int TONE_HEAD = 5;

    /** Longest single command the tablet will send. */
    public static final int MAX_LINE = 120;

    /** How many fields {@code field} will list before it truncates. */
    private static final int FIELD_LIST_LIMIT = 24;

    /**
     * Spoken names mapped to the real field names they stand for, in the order
     * they should be tried.
     *
     * <p>This exists because the feature is described in terms of "temperature"
     * and "flux", and the field behind those words is whatever the machine
     * happened to call it. NTM's DFC stores heat in {@code heat}; an RBMK column
     * stores it in {@code heat} too but its neutron count in {@code flux}. Rather
     * than hard-coding per machine, each alias carries a candidate list and the
     * first one that exists wins. A machine nobody has seen before still works
     * through {@code set <exactFieldName>}.</p>
     */
    private static final Map<String, String[]> ALIASES = new LinkedHashMap<String, String[]>();

    static {
        ALIASES.put("temp", new String[]{"heat", "temperature", "coreHeat", "coreTemperature", "temp"});
        ALIASES.put("temperature", ALIASES.get("temp"));
        ALIASES.put("heat", ALIASES.get("temp"));
        ALIASES.put("flux", new String[]{"flux", "neutronFlux", "fluxFast", "fluxSlow", "neutrons"});
        ALIASES.put("neutron", ALIASES.get("flux"));
        ALIASES.put("rod", new String[]{"controlRodLevel", "rodLevel", "level", "insertion",
                "controlLevel", "rodInsertion"});
        ALIASES.put("rods", ALIASES.get("rod"));
        ALIASES.put("steam", new String[]{"reasimSteam", "steam", "steamAmount",
                "bufferSteam", "steamBuffer"});
        ALIASES.put("power", new String[]{"power", "energy", "storedEnergy", "bufferPower",
                "energyStored"});
    }

    /** One line of output. */
    public static final class Line {

        public final int tone;
        public final String text;

        public Line(int tone, String text) {
            this.tone = tone;
            this.text = text == null ? "" : text;
        }
    }

    /** Everything the tablet needs to redraw itself after a command. */
    public static final class Reply {

        public final List<Line> lines = new ArrayList<Line>();
        public int state = HackState.NORMAL;
        public int ticksLeft;
        public boolean hasTarget;
        public int targetX;
        public int targetY;
        public int targetZ;
        public String targetLabel = "";
        /** True when the command changed nothing and the tablet should beep. */
        public boolean rejected;

        /**
         * Public because the packet that carries a failure back lives in another
         * package: a command handler that cannot build a reply must still be
         * able to say so.
         */
        public void add(int tone, String text) {
            this.lines.add(new Line(tone, text));
        }
    }

    private HackService() {
    }

    // ------------------------------------------------------------------- entry

    /**
     * Runs one line. Called on the server thread with the player already
     * resolved; everything below assumes a real, online operator.
     */
    public static Reply run(EntityPlayerMP player, String raw) {
        Reply reply = new Reply();
        if (player == null) {
            return reply;
        }
        World world = player.world;
        String line = raw == null ? "" : raw.trim();
        if (line.length() > MAX_LINE) {
            line = line.substring(0, MAX_LINE);
        }
        HackRegistry.prune(world, player.getPosition());

        // An empty line is the window asking "what is my state?" - the same
        // convention the panel terminal uses. It must not print anything, or
        // every reopen would scroll the log by itself.
        if (line.isEmpty()) {
            fillStatus(reply, player);
            return reply;
        }

        // A disabled tablet still opens, still reports its target and still
        // answers the state poll: only the commands that could change the world
        // are refused. Turning it off should look like a locked device, not
        // like a broken item.
        if (!HBMZZConfig.hackEnabled) {
            reply.rejected = true;
            reply.add(TONE_ERR, "该设备已被服务器禁用。");
            fillStatus(reply, player);
            return reply;
        }

        String[] parts = line.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String arg1 = parts.length > 1 ? parts[1] : "";
        String arg2 = parts.length > 2 ? parts[2] : "";

        // While the signal is cut there is no link to write through. Only the
        // commands that do not need one - the recovery verb, the help page, the
        // coordinate readout - still answer. Without this a player could keep
        // poking a core whose telemetry they had already blinded, which is both
        // incoherent and a way to arm a meltdown with no screen to see it on.
        if (HackRegistry.isCut(world) && !survivesCut(verb)) {
            reply.rejected = true;
            reply.add(TONE_ERR, "信号已中断，只能使用 restore。");
            fillStatus(reply, player);
            return reply;
        }

        if ("help".equals(verb) || "?".equals(verb)) {
            help(reply);
        } else if ("pos".equals(verb) || "where".equals(verb)) {
            reply.add(TONE_OK, "POS " + player.getPosition().getX() + " "
                    + player.getPosition().getY() + " " + player.getPosition().getZ());
            reply.add(TONE_DIM, "DIM " + world.provider.getDimension()
                    + "  (" + world.provider.getDimensionType().getName() + ")");
            reply.add(TONE_DIM, "把上面三个数字填给 target。");
        } else if ("target".equals(verb) || "aim".equals(verb)) {
            target(reply, player, parts, arg1);
        } else if ("field".equals(verb) || "fields".equals(verb) || "ls".equals(verb)) {
            fields(reply, player);
        } else if ("read".equals(verb) || "get".equals(verb)) {
            read(reply, player, arg1);
        } else if ("set".equals(verb)) {
            set(reply, player, arg1, arg2);
        } else if ("cut".equals(verb) || "sever".equals(verb)) {
            cut(reply, player);
        } else if ("restore".equals(verb) || "reset".equals(verb)) {
            restore(reply, player);
        } else if (ALIASES.containsKey(verb)) {
            // temp 5000 / flux 1200 / rod 30 - the short form.
            set(reply, player, verb, arg1);
        } else {
            reply.rejected = true;
            reply.add(TONE_ERR, "未知指令: " + parts[0]);
            reply.add(TONE_DIM, "输入 help 查看可用指令。");
        }

        fillStatus(reply, player);
        return reply;
    }

    /**
     * Commands that still make sense with the signal cut.
     *
     * <p>Only the one that lifts it. The cut page draws no log, so even a
     * read-only command would execute into a screen that cannot show its
     * answer - and "the command worked but you cannot see it" is worse than a
     * flat refusal. The cut also expires on its own, so a player who does not
     * know the verb is never stuck.</p>
     */
    private static boolean survivesCut(String verb) {
        return "restore".equals(verb) || "reset".equals(verb);
    }

    private static void fillStatus(Reply reply, EntityPlayerMP player) {
        World world = player.world;
        HackRegistry.Aim aim = HackRegistry.aimOf(player.getUniqueID());
        if (aim != null && aim.dimension == world.provider.getDimension()) {
            reply.hasTarget = true;
            reply.targetX = aim.pos.getX();
            reply.targetY = aim.pos.getY();
            reply.targetZ = aim.pos.getZ();
            TileEntity te = world.isBlockLoaded(aim.pos) ? world.getTileEntity(aim.pos) : null;
            reply.targetLabel = label(te, aim.pos);
        } else if (aim != null) {
            reply.hasTarget = true;
            reply.targetX = aim.pos.getX();
            reply.targetY = aim.pos.getY();
            reply.targetZ = aim.pos.getZ();
            reply.targetLabel = "维度 " + aim.dimension + "（不在当前世界）";
        }
        reply.state = HackRegistry.stateFor(world, aim == null ? null : aim.pos);
        reply.ticksLeft = HackRegistry.ticksLeft(world, aim == null ? null : aim.pos);
    }

    // ------------------------------------------------------------------ target

    private static void target(Reply reply, EntityPlayerMP player, String[] parts, String arg1) {
        if ("clear".equalsIgnoreCase(arg1) || "none".equalsIgnoreCase(arg1)) {
            HackRegistry.clearAim(player.getUniqueID());
            reply.add(TONE_OK, "目标已解除。");
            return;
        }
        if (parts.length < 4) {
            reply.rejected = true;
            reply.add(TONE_ERR, "用法: target <x> <y> <z>");
            reply.add(TONE_DIM, "可以写 ~ ~ ~ 表示脚下，~2 表示偏移两格。");
            return;
        }
        Integer x = coordinate(parts[1], player.getPosition().getX());
        Integer y = coordinate(parts[2], player.getPosition().getY());
        Integer z = coordinate(parts[3], player.getPosition().getZ());
        if (x == null || y == null || z == null) {
            reply.rejected = true;
            reply.add(TONE_ERR, "坐标无法解析: " + parts[1] + " " + parts[2] + " " + parts[3]);
            return;
        }
        BlockPos pos = new BlockPos(x.intValue(), y.intValue(), z.intValue());
        World world = player.world;
        if (!world.isBlockLoaded(pos)) {
            reply.rejected = true;
            reply.add(TONE_ERR, "该区块未加载: " + x + " " + y + " " + z);
            reply.add(TONE_DIM, "走过去把它加载，或者换一个坐标。");
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te == null) {
            reply.rejected = true;
            reply.add(TONE_ERR, x + " " + y + " " + z + " 没有方块实体。");
            return;
        }
        if (!ownerAllowed(te)) {
            reply.rejected = true;
            reply.add(TONE_ERR, "目标不可入侵: " + te.getClass().getSimpleName());
            reply.add(TONE_DIM, "只接受 HBM 与本模组的机器。");
            return;
        }
        HackRegistry.aim(player.getUniqueID(), world.provider.getDimension(), pos);
        reply.add(TONE_OK, "TARGET " + x + " " + y + " " + z);
        reply.add(TONE_DIM, label(te, pos));
        int writable = writableFields(te).size();
        reply.add(TONE_DIM, "可写字段 " + writable + " 个，输入 field 查看。");
        // Whether anything is actually watching this machine decides whether the
        // write will be *noticed*, and that is not something the operator can
        // see from here. Saying it up front turns a silent "nothing happened"
        // into a fact they can act on - bind a screen, or aim at the core.
        int watching = monitorsWatching(world, pos);
        reply.add(watching > 0 ? TONE_WARN : TONE_DIM,
                watching > 0
                        ? "已发现 " + watching + " 台监测屏在监视它，写入会触发断链。"
                        : "没有监测屏在监视这个坐标，写入不会触发预警。");
    }

    /**
     * How many monitors have this exact position bound as their core.
     *
     * <p>Copies the list before walking it: {@code loadedTileEntityList} is the
     * live list the chunk system mutates, and a block that unloads mid-scan
     * would otherwise throw a {@code ConcurrentModificationException} out of a
     * command handler.</p>
     */
    private static int monitorsWatching(World world, BlockPos pos) {
        int count = 0;
        List<TileEntity> all;
        try {
            all = new ArrayList<TileEntity>(world.loadedTileEntityList);
        } catch (Throwable throwable) {
            return 0;
        }
        for (TileEntity te : all) {
            if (te instanceof com.hbmzz.tile.TileEntityDFCMonitor) {
                BlockPos core = ((com.hbmzz.tile.TileEntityDFCMonitor) te).getCorePos();
                if (pos.equals(core)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** {@code 12}, {@code ~}, {@code ~-3}. Returns null when it is not a number. */
    private static Integer coordinate(String token, int origin) {
        try {
            if (token.startsWith("~")) {
                if (token.length() == 1) {
                    return Integer.valueOf(origin);
                }
                return Integer.valueOf(origin + (int) Math.round(Double.parseDouble(token.substring(1))));
            }
            return Integer.valueOf((int) Math.round(Double.parseDouble(token)));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    // ------------------------------------------------------------------ fields

    private static void fields(Reply reply, EntityPlayerMP player) {
        TileEntity te = aimedTile(reply, player);
        if (te == null) {
            return;
        }
        List<Field> list = writableFields(te);
        if (list.isEmpty()) {
            reply.add(TONE_WARN, "这台机器没有可写的数值字段。");
            return;
        }
        reply.add(TONE_HEAD, "-- " + label(te, te.getPos()) + " --");
        int shown = 0;
        for (Field field : list) {
            if (shown++ >= FIELD_LIST_LIMIT) {
                reply.add(TONE_DIM, "... 还有 " + (list.size() - FIELD_LIST_LIMIT) + " 个字段");
                break;
            }
            reply.add(TONE_TEXT, field.getName() + " = " + valueOf(te, field)
                    + "  [" + typeName(field.getType()) + "]");
        }
    }

    private static void read(Reply reply, EntityPlayerMP player, String name) {
        if (name.isEmpty()) {
            reply.rejected = true;
            reply.add(TONE_ERR, "用法: read <字段名>");
            return;
        }
        TileEntity te = aimedTile(reply, player);
        if (te == null) {
            return;
        }
        Field field = resolve(te, name);
        if (field == null) {
            reply.rejected = true;
            reply.add(TONE_ERR, "没有字段 " + name + "。");
            suggest(reply, te, name);
            return;
        }
        reply.add(TONE_OK, field.getName() + " = " + valueOf(te, field));
    }

    private static void set(Reply reply, EntityPlayerMP player, String name, String rawValue) {
        if (name.isEmpty() || rawValue.isEmpty()) {
            reply.rejected = true;
            reply.add(TONE_ERR, "用法: set <字段名> <数值>");
            return;
        }
        TileEntity te = aimedTile(reply, player);
        if (te == null) {
            return;
        }
        double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (NumberFormatException notANumber) {
            // Booleans are worth accepting: plenty of machines gate a pump or a
            // valve on one, and "true" is what a player will type.
            String v = rawValue.toLowerCase(Locale.ROOT);
            if ("true".equals(v) || "on".equals(v) || "yes".equals(v)) {
                value = 1.0D;
            } else if ("false".equals(v) || "off".equals(v) || "no".equals(v)) {
                value = 0.0D;
            } else {
                reply.rejected = true;
                reply.add(TONE_ERR, "不是数值: " + rawValue);
                return;
            }
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            reply.rejected = true;
            reply.add(TONE_ERR, "拒绝写入 NaN / Infinity。");
            return;
        }
        Field field = resolve(te, name);
        if (field == null) {
            reply.rejected = true;
            reply.add(TONE_ERR, "没有字段 " + name + "。");
            suggest(reply, te, name);
            return;
        }
        if (!writable(field)) {
            reply.rejected = true;
            reply.add(TONE_ERR, "字段 " + field.getName() + " 不可写（"
                    + (Modifier.isStatic(field.getModifiers()) ? "static" : "final") + "）。");
            return;
        }
        String before = valueOf(te, field);
        try {
            assign(te, field, value);
        } catch (Throwable throwable) {
            reply.rejected = true;
            reply.add(TONE_ERR, "写入失败: " + throwable.getClass().getSimpleName());
            HBMZZ.logger.warn("hacker tablet could not write " + field.getName()
                    + " on " + te.getClass().getName(), throwable);
            return;
        }
        // The write landed. Tell the world so the block's own sync packet does
        // not push a stale copy back over it, then light up every monitor that
        // watches this position.
        te.markDirty();
        World world = player.world;
        world.notifyBlockUpdate(te.getPos(), world.getBlockState(te.getPos()),
                world.getBlockState(te.getPos()), 3);
        HackRegistry.touch(world, te.getPos());

        reply.add(TONE_OK, field.getName() + ": " + before + " -> " + valueOf(te, field));
        reply.add(TONE_WARN, "已绕过控制棒，直接写入。监控屏将在 "
                + (HackState.disconnectTicks / 20) + " 秒内失去链路。");
    }

    // ------------------------------------------------------------------- verbs

    private static void cut(Reply reply, EntityPlayerMP player) {
        HackRegistry.cut(player.world);
        reply.add(TONE_ERR, "!! 堆芯信号已中断。");
        reply.add(TONE_ERR, "全部监控屏将显示 ERROR#！，持续 "
                + (HackState.cutTicks / 20) + " 秒。");
        reply.add(TONE_DIM, "输入 restore 立即恢复。");
    }

    private static void restore(Reply reply, EntityPlayerMP player) {
        HackRegistry.restore(player.world);
        reply.add(TONE_OK, "链路已恢复，本维度入侵标记已清除。");
    }

    private static void help(Reply reply) {
        reply.add(TONE_HEAD, "-- BLACKICE 指令表 --");
        reply.add(TONE_TEXT, "pos                    报告当前坐标");
        reply.add(TONE_TEXT, "target <x> <y> <z>     锁定机器（支持 ~）");
        reply.add(TONE_TEXT, "target clear           解除锁定");
        reply.add(TONE_TEXT, "field                  列出可写字段");
        reply.add(TONE_TEXT, "read <字段>            读一个字段");
        reply.add(TONE_TEXT, "set <字段> <值>        直接写入");
        reply.add(TONE_TEXT, "temp / flux / rod <值> 常用字段的别名");
        reply.add(TONE_TEXT, "cut                    中断堆芯信号");
        reply.add(TONE_TEXT, "restore                恢复链路");
        reply.add(TONE_DIM, "字段名不分大小写；可按名字模糊建议。");
    }

    // ------------------------------------------------------------------ target io

    /** The aim, or null after writing a reason into the reply. */
    private static TileEntity aimedTile(Reply reply, EntityPlayerMP player) {
        HackRegistry.Aim aim = HackRegistry.aimOf(player.getUniqueID());
        if (aim == null) {
            reply.rejected = true;
            reply.add(TONE_ERR, "还没有锁定目标。");
            reply.add(TONE_DIM, "先站到机器旁边输入 pos，再 target <x> <y> <z>。");
            return null;
        }
        World world = player.world;
        if (aim.dimension != world.provider.getDimension()) {
            reply.rejected = true;
            reply.add(TONE_ERR, "目标在维度 " + aim.dimension + "，你不在那儿。");
            return null;
        }
        if (!world.isBlockLoaded(aim.pos)) {
            reply.rejected = true;
            reply.add(TONE_ERR, "目标区块已卸载。");
            return null;
        }
        TileEntity te = world.getTileEntity(aim.pos);
        if (te == null) {
            reply.rejected = true;
            reply.add(TONE_ERR, "目标方块已经不在了。");
            return null;
        }
        if (!ownerAllowed(te)) {
            reply.rejected = true;
            reply.add(TONE_ERR, "目标已不是可入侵的机器。");
            return null;
        }
        return te;
    }

    // ---------------------------------------------------------------- reflection

    /**
     * Only the base mod and our own machines.
     *
     * <p>Note the trailing dots: {@code com.hbm.} does not match
     * {@code com.hbmzz.}, and neither matches an unrelated package that merely
     * starts with the same letters. This is a security boundary, not a
     * convenience, so it is written to be unambiguous rather than short.</p>
     */
    private static boolean ownerAllowed(TileEntity te) {
        String name = te.getClass().getName();
        return name.startsWith("com.hbm.") || name.startsWith("com.hbmzz.")
                || name.startsWith("com.rsaudio.");
    }

    /** Every field of a tile entity this tool is willing to assign to. */
    private static List<Field> writableFields(TileEntity te) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> type = te.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            Field[] declared;
            try {
                declared = type.getDeclaredFields();
            } catch (Throwable throwable) {
                // A malformed or partially loaded class: skip that layer rather
                // than losing the whole list.
                continue;
            }
            for (Field field : declared) {
                if (writable(field)) {
                    out.add(field);
                }
            }
        }
        return out;
    }

    private static boolean writable(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
            return false;
        }
        if (field.isSynthetic()) {
            return false;
        }
        Class<?> type = field.getType();
        return type == double.class || type == float.class || type == int.class
                || type == long.class || type == short.class || type == byte.class
                || type == boolean.class;
    }

    /**
     * Finds the field a name refers to: exact (case-insensitive) first, then the
     * alias table, then nothing.
     *
     * <p>Case-insensitive matching is not cosmetic. NTM's fields are camelCase
     * and a player typing at a terminal writes {@code maxheat}; refusing that
     * would make the tool feel broken when it is merely pedantic.</p>
     */
    private static Field resolve(TileEntity te, String name) {
        Field exact = findField(te.getClass(), name);
        if (exact != null) {
            return exact;
        }
        String[] candidates = ALIASES.get(name.toLowerCase(Locale.ROOT));
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            Field field = findField(te.getClass(), candidate);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> type = start; type != null && type != Object.class; type = type.getSuperclass()) {
            Field[] declared;
            try {
                declared = type.getDeclaredFields();
            } catch (Throwable throwable) {
                continue;
            }
            for (Field field : declared) {
                if (field.getName().equalsIgnoreCase(name)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static void assign(TileEntity te, Field field, double value) throws IllegalAccessException {
        field.setAccessible(true);
        Class<?> type = field.getType();
        if (type == double.class) {
            field.setDouble(te, value);
        } else if (type == float.class) {
            field.setFloat(te, (float) value);
        } else if (type == int.class) {
            field.setInt(te, (int) Math.round(value));
        } else if (type == long.class) {
            field.setLong(te, Math.round(value));
        } else if (type == short.class) {
            field.setShort(te, (short) Math.round(value));
        } else if (type == byte.class) {
            field.setByte(te, (byte) Math.round(value));
        } else if (type == boolean.class) {
            field.setBoolean(te, value != 0.0D);
        }
    }

    private static String valueOf(TileEntity te, Field field) {
        try {
            field.setAccessible(true);
            Object value = field.get(te);
            if (value instanceof Double || value instanceof Float) {
                return String.format(Locale.ROOT, "%.3f",
                        Double.valueOf(((Number) value).doubleValue()));
            }
            return String.valueOf(value);
        } catch (Throwable throwable) {
            return "?";
        }
    }

    private static String typeName(Class<?> type) {
        if (type == double.class || type == Double.class) {
            return "double";
        }
        if (type == float.class || type == Float.class) {
            return "float";
        }
        if (type == long.class || type == Long.class) {
            return "long";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "bool";
        }
        if (type == int.class || type == Integer.class) {
            return "int";
        }
        return type.getSimpleName();
    }

    /** "没有字段 x" plus the three closest names, so a typo is recoverable. */
    private static void suggest(Reply reply, TileEntity te, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<String>();
        for (Field field : writableFields(te)) {
            names.add(field.getName());
        }
        Collections.sort(names, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(distance(a.toLowerCase(Locale.ROOT), lower),
                        distance(b.toLowerCase(Locale.ROOT), lower));
            }
        });
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(3, names.size()); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(names.get(i));
        }
        if (builder.length() > 0) {
            reply.add(TONE_DIM, "最接近的: " + builder);
        }
    }

    /** Levenshtein, two rows. Small strings, called only on a failed lookup. */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    /** What the tablet shows on its target line. */
    private static String label(TileEntity te, BlockPos pos) {
        if (te == null) {
            return "--";
        }
        String name = null;
        try {
            name = te.getBlockType().getLocalizedName();
        } catch (Throwable ignored) {
            // A tile entity whose block was removed mid-tick: fall through to
            // the class name rather than throwing inside a command.
        }
        if (name == null || name.isEmpty() || name.startsWith("tile.")) {
            name = te.getClass().getSimpleName();
        }
        return name + "  @" + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
