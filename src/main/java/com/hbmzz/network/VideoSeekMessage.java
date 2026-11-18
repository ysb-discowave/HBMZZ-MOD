/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the
 * Free Software Foundation, version 3.
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

import com.hbmzz.block.BlockVideoScreen;
import com.hbmzz.tile.TileEntityVideoScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * A jump on one wall's timeline: seek to a time, pause / resume, or rewind.
 *
 * <h3>Why this travels at all</h3>
 * <p>The playhead is world state, not a client preference: if the seek lived in
 * the window, the player who pressed it would jump to 0:00 while everybody else
 * kept watching 0:23, and the next tick would drag them back. So the request goes
 * to the server, the server moves the wall's timeline, and every client - the one
 * that asked included - reads the new position out of the block. Single player is
 * the same code path with the integrated server in the middle, which is exactly
 * why the two modes cannot drift apart.</p>
 *
 * <h3>Distance</h3>
 * <p>Sixty-four blocks, the same reach the remote uses to connect a wall. A film
 * is often driven from a booth or the back of a hall rather than from arm's
 * length, and unlike the size window this action does not place or remove any
 * blocks - it moves a clock - so the generous limit costs nothing.</p>
 */
public class VideoSeekMessage implements IMessage {

    /** Jump to {@link #millis}. */
    public static final byte ACTION_SEEK = 0;
    /** Freeze or resume at the current position. */
    public static final byte ACTION_TOGGLE_PAUSE = 1;
    /** Back to the first frame, playing. */
    public static final byte ACTION_RESTART = 2;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte action = ACTION_SEEK;
    private int millis;

    public VideoSeekMessage() {
    }

    public VideoSeekMessage(BlockPos pos, byte action, int millis) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.action = action;
        this.millis = millis;
    }

    public static VideoSeekMessage seek(BlockPos pos, int millis) {
        return new VideoSeekMessage(pos, ACTION_SEEK, millis);
    }

    public static VideoSeekMessage togglePause(BlockPos pos) {
        return new VideoSeekMessage(pos, ACTION_TOGGLE_PAUSE, 0);
    }

    public static VideoSeekMessage restart(BlockPos pos) {
        return new VideoSeekMessage(pos, ACTION_RESTART, 0);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.millis = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeByte(this.action);
        buf.writeInt(this.millis);
    }

    public static class Handler implements IMessageHandler<VideoSeekMessage, IMessage> {

        /** How far away the wall may be and still be driven from here. */
        private static final double REACH = 64.0D;

        @Override
        public IMessage onMessage(VideoSeekMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP sender = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final byte action = message.action;
            final int millis = message.millis;
            sender.mcServer.addScheduledTask(() -> {
                if (sender.getDistanceSqToCenter(pos) > REACH * REACH) {
                    return;
                }
                BlockPos origin = resolve(sender, pos);
                if (origin == null) {
                    return;
                }
                TileEntity te = sender.world.getTileEntity(origin);
                if (!(te instanceof TileEntityVideoScreen)) {
                    return;
                }
                TileEntityVideoScreen screen = (TileEntityVideoScreen) te;
                switch (action) {
                    case ACTION_TOGGLE_PAUSE:
                        screen.setPaused(!screen.isPaused());
                        break;
                    case ACTION_RESTART:
                        screen.restart();
                        break;
                    case ACTION_SEEK:
                    default:
                        // The block clamps into 0..MAX_PROGRESS_MS; every client
                        // then clamps again against the clip it actually has.
                        screen.seekTo(millis);
                        break;
                }
                // Echo the authoritative state straight back to whoever asked.
                // The chunk-map update reaches them too, but it is routed by
                // "who is watching that chunk" and arrives a tick or two later;
                // in that window the window is drawing a playhead the server has
                // already replaced, which is the "拖动后弹回原位" report. This
                // packet closes that window to one frame.
                if (screen.getUpdatePacket() != null) {
                    sender.connection.sendPacket(screen.getUpdatePacket());
                }
                sender.sendStatusMessage(new TextComponentString(describe(screen)), true);
            });
            return null;
        }

        /**
         * The controller cell of the wall the player clicked.
         *
         * <p>Any cell is a legal target - the picture covers all of them - so a
         * part cell is walked back to its controller. A cell whose controller is
         * gone resolves to nothing and the packet is dropped, which is the right
         * answer for a wall that is no longer a wall.</p>
         */
        private static BlockPos resolve(EntityPlayerMP sender, BlockPos pos) {
            if (pos == null || !sender.world.isBlockLoaded(pos)) {
                return null;
            }
            IBlockState state = sender.world.getBlockState(pos);
            if (!(state.getBlock() instanceof BlockVideoScreen)) {
                return null;
            }
            return BlockVideoScreen.originOf(sender.world, pos);
        }
    }

    /** One line for the action bar: position, length and what the clock is doing. */
    public static String describe(TileEntityVideoScreen screen) {
        String state;
        if (!screen.isPlaying()) {
            state = "拉杆未开";
        } else if (screen.isPaused()) {
            state = "已暂停";
        } else {
            state = "播放中";
        }
        int ms = screen.positionMs();
        return "大屏 " + formatClock(ms) + " · " + state;
    }

    /**
     * {@code m:ss.d} - minutes, seconds, tenths.
     *
     * <p>Seconds alone are unreadable past a two-minute film and a full timestamp
     * is noise for an eight-second one, so the minutes are only printed when there
     * are any.</p>
     */
    public static String formatClock(int millis) {
        int ms = Math.max(0, millis);
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        int tenths = (ms % 1000) / 100;
        if (minutes > 0) {
            return minutes + ":" + (seconds < 10 ? "0" : "") + seconds + "." + tenths;
        }
        return seconds + "." + tenths + "s";
    }
}
