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

package com.hbmzz.video;

import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.VideoBroadcastMessage;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * The firing end: one string in, a full screen out.
 *
 * <p>Kept apart from the tile entity because two things call it - the redstone
 * edge and the window's 测试广播 button - and they must take the identical path.
 * A test that skips half the code is how "it works in the editor" bugs are born.</p>
 *
 * <p>The server never touches pixels. It resolves names to players, sends one
 * small packet each, and records a sentence saying what happened. Whether the
 * file exists is decided on the receiving client, because that is where the
 * frames are read from.</p>
 */
public final class VideoBroadcast {

    private VideoBroadcast() {
    }

    /**
     * Sends {@code video} to every player the selector names.
     *
     * @return a one-line report for the tile entity (and the operator's HUD)
     */
    public static String fire(World world, BlockPos pos, String video, String selector) {
        if (video == null || video.isEmpty()) {
            return "未设置视频文件";
        }
        if (!VideoLibrary.isValidName(video)) {
            return "视频名不合法：" + video;
        }
        VideoTargets.Result result = VideoTargets.resolve(world, pos, selector);
        if (!result.ok()) {
            return result.error;
        }
        List<EntityPlayer> players = result.players;
        VideoBroadcastMessage message = new VideoBroadcastMessage(video);
        int sent = 0;
        for (EntityPlayer player : players) {
            // Fake players answer instanceof EntityPlayer too; they have no
            // connection to push a window through.
            if (player instanceof EntityPlayerMP) {
                NetworkHandler.sendTo((EntityPlayerMP) player, message);
                sent++;
            }
        }
        if (sent == 0) {
            return "没有可以接收的玩家";
        }
        String note = "";
        if (!VideoLibrary.names().isEmpty() && !VideoLibrary.exists(video)) {
            // Only worth saying when this machine actually has a shelf of its
            // own; a plain dedicated server legitimately has no videos at all.
            note = "（本机视频目录里没有这个文件）";
        }
        return "已向 " + sent + " 名玩家广播 " + video + note;
    }
}
