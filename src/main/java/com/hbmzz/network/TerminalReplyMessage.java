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

package com.hbmzz.network;

import com.hbmzz.ctrl.TerminalHost;
import com.hbmzz.ctrl.TerminalShell;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The answer to one typed line: the text to append, what each line is (output,
 * error, hint...), the machine the terminal is now pointing at, and the
 * vocabulary completion needs for the next keystroke.
 *
 * <p>Lines and kinds travel as two flat strings rather than a list of objects:
 * a reply is at most forty short lines, and one length-prefixed blob is both
 * smaller and impossible to desynchronise. Kinds are one character per line,
 * index for index, so the two cannot drift out of step.</p>
 *
 * <p>The slot rides along because selecting a machine changes the panel - the
 * server syncs the block as well - but the operator's own window should not
 * have to wait for that update to show the right target.</p>
 *
 * <p>The four trailing lists exist for the same reason {@code TermCmd} exists:
 * Tab must answer in zero milliseconds. The server is the only side that can
 * ask a machine what it accepts, so it ships the answer with every reply and
 * the window completes locally against it.</p>
 */
public class TerminalReplyMessage implements IMessage {

    /** Labels, keys, events and variables each travel as newline-joined text. */
    private static final int MAX_SLOTS = 8;
    private static final int MAX_WORDS = 32;
    private static final int MAX_VARS = 16;
    private static final int MAX_WORD = 32;

    private int kind;
    private BlockPos pos = BlockPos.ORIGIN;
    private int index = -1;
    private int slot = -1;
    private boolean clear;
    private String kinds = "";
    private String text = "";
    private String labels = "";
    private String names = "";
    private String keys = "";
    private String events = "";
    private String vars = "";

    public TerminalReplyMessage() {
    }

    public TerminalReplyMessage(int kind, BlockPos pos, int index, int slot, boolean clear,
                                TerminalShell.Sink sink, TerminalHost host) {
        this.kind = kind;
        this.pos = pos;
        this.index = index;
        this.slot = slot;
        this.clear = clear;
        StringBuilder body = new StringBuilder();
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < sink.lines.size(); i++) {
            if (i > 0) {
                body.append('\n');
            }
            body.append(sink.lines.get(i));
            tags.append((char) ('0' + Math.max(0, Math.min(9, sink.kinds.get(i).intValue()))));
        }
        this.text = body.toString();
        this.kinds = tags.toString();

        if (host != null) {
            List<String> slotLabels = new ArrayList<String>();
            List<String> slotNames = new ArrayList<String>();
            for (int i = 0; i < host.slots() && i < MAX_SLOTS; i++) {
                slotLabels.add(host.label(i));
                slotNames.add(host.name(i));
            }
            this.labels = join(slotLabels, MAX_SLOTS);
            // Names travel separately from labels: a label is short because it
            // is also what the operator types ("RBMK"), a name is what the
            // header shows ("RBMK 反应堆 · 12 部件"). Before grouping they were
            // the same string, so the window used the label for both.
            this.names = join(slotNames, MAX_SLOTS);

            int target = host.target();
            if (target >= 0 && target < host.slots()) {
                this.keys = join(host.keys(target), MAX_WORDS);
                this.events = join(host.events(target), MAX_WORDS);
                List<String> pairs = new ArrayList<String>();
                for (Map.Entry<String, String> entry : host.vars(target).entrySet()) {
                    if (entry.getValue() == null || entry.getValue().isEmpty()) {
                        continue;
                    }
                    pairs.add(entry.getKey() + " " + entry.getValue());
                }
                this.vars = join(pairs, MAX_VARS);
            }
        }
    }

    /** Joins up to {@code limit} entries, each clipped so one bad tag cannot bloat the packet. */
    private static String join(List<String> parts, int limit) {
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (String part : parts) {
            if (used >= limit) {
                break;
            }
            String value = part == null ? "" : part;
            if (value.length() > MAX_WORD) {
                value = value.substring(0, MAX_WORD);
            }
            if (used > 0) {
                out.append('\n');
            }
            out.append(value);
            used++;
        }
        return out.toString();
    }

    public static List<String> split(String text, int limit) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String part : text.split("\n", -1)) {
            if (out.size() >= limit) {
                break;
            }
            out.add(part);
        }
        return out;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.kind = buf.readByte();
        this.pos = BlockPos.fromLong(buf.readLong());
        this.index = buf.readShort();
        this.slot = buf.readByte();
        this.clear = buf.readBoolean();
        this.kinds = TerminalInputMessage.readString(buf, TerminalShell.MAX_REPLY);
        this.text = TerminalInputMessage.readString(buf, TerminalShell.MAX_REPLY * 256);
        this.labels = TerminalInputMessage.readString(buf, MAX_SLOTS * MAX_WORD);
        this.names = TerminalInputMessage.readString(buf, MAX_SLOTS * MAX_WORD);
        this.keys = TerminalInputMessage.readString(buf, MAX_WORDS * MAX_WORD);
        this.events = TerminalInputMessage.readString(buf, MAX_WORDS * MAX_WORD);
        this.vars = TerminalInputMessage.readString(buf, MAX_VARS * MAX_WORD * 2);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(this.kind);
        buf.writeLong(this.pos.toLong());
        buf.writeShort(this.index);
        buf.writeByte(this.slot);
        buf.writeBoolean(this.clear);
        TerminalInputMessage.writeString(buf, this.kinds, TerminalShell.MAX_REPLY);
        TerminalInputMessage.writeString(buf, this.text, TerminalShell.MAX_REPLY * 256);
        TerminalInputMessage.writeString(buf, this.labels, MAX_SLOTS * MAX_WORD);
        TerminalInputMessage.writeString(buf, this.names, MAX_SLOTS * MAX_WORD);
        TerminalInputMessage.writeString(buf, this.keys, MAX_WORDS * MAX_WORD);
        TerminalInputMessage.writeString(buf, this.events, MAX_WORDS * MAX_WORD);
        TerminalInputMessage.writeString(buf, this.vars, MAX_VARS * MAX_WORD * 2);
    }

    /**
     * Registered for the client, but the handler class must still load on a
     * dedicated server, so the window is reached reflectively - the same
     * arrangement {@link DFCDataMessage} uses.
     */
    public static class Handler implements IMessageHandler<TerminalReplyMessage, IMessage> {

        @Override
        public IMessage onMessage(TerminalReplyMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientTerminalBridge")
                            .getMethod("onReply", int.class, BlockPos.class, int.class, int.class,
                                    boolean.class, String.class, String.class,
                                    String.class, String.class, String.class, String.class,
                                    String.class)
                            .invoke(null, Integer.valueOf(message.kind), message.pos,
                                    Integer.valueOf(message.index), Integer.valueOf(message.slot),
                                    Boolean.valueOf(message.clear), message.kinds, message.text,
                                    message.labels, message.names, message.keys,
                                    message.events, message.vars);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
