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

package com.rsaudio.client;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * reasim 的"自动回声"：看一眼喇叭装在哪，再决定混响给多少。
 * <p>
 * 只用两件事：<b>六个方向能看多远</b>（空间感）和<b>周围有多少实心方块</b>（吸收面多少），
 * 外加一条"头顶能看见天 → 混响砍掉大半"。这三条加起来已经能区分
 * "贴脸砌在铁皮机房里"和"插在旷野上"，而真正的声学建模对 1.12.2 来说毫无必要 ——
 * 玩家要的是"这台机器在室内/室外听起来不一样"，不是脉冲响应测量。
 * <p>
 * 结果缓存 20 tick，调用点在客户端 tick 上（世界里读方块必须在客户端线程）。
 */
public final class ReasimEnvironment {

    /** 射线最长打多少格。16 格之外一律按"很开阔"算 */
    private static final int MAX_RAY = 16;
    /** 完全密闭时的上下限：就算砌死了也留一点余量，不然听起来像两块砖之间的缝 */
    private static final float ECHO_CAP = 0.95F;
    /** 露天时的乘数：旷野上确实有地面反射，但绝不该像室内那样糊 */
    private static final float OPEN_SKY_DAMP = 0.35F;

    private ReasimEnvironment() {
    }

    /**
     * 按周围环境算一个回声量。
     *
     * @return 0.0（极开阔）～ 0.95（密闭小房间）
     */
    public static float autoEcho(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos)) {
            return 0.30F;
        }
        int total = 0;
        for (EnumFacing facing : EnumFacing.values()) {
            total += freeDistance(world, pos, facing);
        }
        float average = total / (float) EnumFacing.values().length;
        float openness = clamp(average / MAX_RAY);
        float enclosure = 1.0F - openness;

        float solidRatio = solidRatio(world, pos, 4);
        // 0.4 是"一个正常房间里实心方块的占比"的经验中值，
        // 以它为基准上下浮动：墙越多越闷，越空越干。
        float echo = 0.85F * enclosure + 0.35F * (solidRatio - 0.40F);

        if (world.canSeeSky(pos.up(2))) {
            echo *= OPEN_SKY_DAMP;
        }
        if (echo < 0.0F) {
            return 0.0F;
        }
        return echo > ECHO_CAP ? ECHO_CAP : echo;
    }

    /** 从方块中心沿一个方向走，遇到实心方块就停，返回走了多少格 */
    private static int freeDistance(World world, BlockPos pos, EnumFacing facing) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(pos);
        for (int step = 1; step <= MAX_RAY; step++) {
            cursor.setPos(pos.getX() + facing.getFrontOffsetX() * step,
                    pos.getY() + facing.getFrontOffsetY() * step,
                    pos.getZ() + facing.getFrontOffsetZ() * step);
            if (!world.isBlockLoaded(cursor)) {
                return step;
            }
            IBlockState state = world.getBlockState(cursor);
            if (!state.getMaterial().isReplaceable()) {
                return step;
            }
        }
        return MAX_RAY;
    }

    /** 半径内的实心方块占比，按 2 格取样（精度对这件事完全够，省 8 倍开销） */
    private static float solidRatio(World world, BlockPos pos, int radius) {
        int solid = 0;
        int sampled = 0;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dy = -radius; dy <= radius; dy += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    BlockPos at = pos.add(dx, dy, dz);
                    if (!world.isBlockLoaded(at)) {
                        continue;
                    }
                    sampled++;
                    if (!world.getBlockState(at).getMaterial().isReplaceable()) {
                        solid++;
                    }
                }
            }
        }
        return sampled == 0 ? 0.4F : solid / (float) sampled;
    }

    private static float clamp(float value) {
        if (Float.isNaN(value)) {
            return 0.30F;
        }
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }
}
