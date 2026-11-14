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

package com.hbmzz.alert;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The facility code, on its way to a client.
 *
 * <p>Sent to everybody on every change, and again by each display to whoever is
 * close enough to read it. The first half is what makes the screens agree: a
 * player standing in front of a display in a different dimension is not in
 * anybody's radius, and without the broadcast their screen would keep showing
 * GREEN until they walked back to the machine that changed it.</p>
 *
 * <p>The whole record travels - code, since, source, and the last few events -
 * not a delta. It is about a hundred bytes and it means a client that missed
 * three updates is instantly correct instead of being three events behind
 * forever.</p>
 */
public class AlertSyncMessage implements IMessage {

    /** Matches {@link FacilityState#HISTORY}; the display draws at most five rows. */
    private static final int MAX_HISTORY = 6;

    private int code;
    private long since;
    private String source = "";
    private String actor = "";
    private long nowTick;

    private boolean hasBlast;
    private long blastTick;
    private int blastX;
    private int blastY;
    private int blastZ;
    private int blastYield;

    private final List<Integer> historyCode = new ArrayList<Integer>();
    private final List<Long> historyTick = new ArrayList<Long>();
    private final List<String> historySource = new ArrayList<String>();

    public AlertSyncMessage() {
    }

    public AlertSyncMessage(FacilityState state, long nowTick) {
        this.code = state.codeId();
        this.since = state.since();
        this.source = state.source();
        this.actor = state.actor();
        this.nowTick = nowTick;
        this.hasBlast = state.hasBlast();
        this.blastTick = state.blastTick();
        this.blastX = state.blastX();
        this.blastY = state.blastY();
        this.blastZ = state.blastZ();
        this.blastYield = state.blastYield();
        for (FacilityState.Entry entry : state.history()) {
            if (this.historySource.size() >= MAX_HISTORY) {
                break;
            }
            this.historyCode.add(entry.code);
            this.historyTick.add(entry.tick);
            this.historySource.add(entry.source);
        }
    }

    public int code() {
        return this.code;
    }

    public long since() {
        return this.since;
    }

    public String source() {
        return this.source;
    }

    public String actor() {
        return this.actor;
    }

    public long nowTick() {
        return this.nowTick;
    }

    public boolean hasBlast() {
        return this.hasBlast;
    }

    public long blastTick() {
        return this.blastTick;
    }

    public int blastX() {
        return this.blastX;
    }

    public int blastY() {
        return this.blastY;
    }

    public int blastZ() {
        return this.blastZ;
    }

    public int blastYield() {
        return this.blastYield;
    }

    public int historySize() {
        return this.historySource.size();
    }

    public int historyCode(int index) {
        return this.historyCode.get(index);
    }

    public long historyTick(int index) {
        return this.historyTick.get(index);
    }

    public String historySource(int index) {
        return this.historySource.get(index);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.code = buf.readByte();
        this.since = buf.readLong();
        this.source = ByteBufUtils.readUTF8String(buf);
        this.actor = ByteBufUtils.readUTF8String(buf);
        this.nowTick = buf.readLong();
        this.hasBlast = buf.readBoolean();
        this.blastTick = buf.readLong();
        this.blastX = buf.readInt();
        this.blastY = buf.readInt();
        this.blastZ = buf.readInt();
        this.blastYield = buf.readInt();
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count; i++) {
            this.historyCode.add((int) buf.readByte());
            this.historyTick.add(buf.readLong());
            this.historySource.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(this.code);
        buf.writeLong(this.since);
        ByteBufUtils.writeUTF8String(buf, clip(this.source, 48));
        ByteBufUtils.writeUTF8String(buf, clip(this.actor, 32));
        buf.writeLong(this.nowTick);
        buf.writeBoolean(this.hasBlast);
        buf.writeLong(this.blastTick);
        buf.writeInt(this.blastX);
        buf.writeInt(this.blastY);
        buf.writeInt(this.blastZ);
        buf.writeInt(this.blastYield);
        int count = Math.min(this.historySource.size(), MAX_HISTORY);
        buf.writeByte(count);
        for (int i = 0; i < count; i++) {
            buf.writeByte(this.historyCode.get(i));
            buf.writeLong(this.historyTick.get(i));
            ByteBufUtils.writeUTF8String(buf, clip(this.historySource.get(i), 48));
        }
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * Client-only delivery, isolated behind a reflective call so this handler
     * stays loadable on a dedicated server, where the discriminator still has to
     * be registered.
     */
    public static class Handler implements IMessageHandler<AlertSyncMessage, IMessage> {

        @Override
        public IMessage onMessage(AlertSyncMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientPacketBridge")
                            .getMethod("onAlertSync", AlertSyncMessage.class)
                            .invoke(null, message);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
