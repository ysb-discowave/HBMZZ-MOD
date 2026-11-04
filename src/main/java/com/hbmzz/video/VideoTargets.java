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

import net.minecraft.command.CommandException;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the operator's target string into a list of players.
 *
 * <p>This is vanilla's {@link EntitySelector}, not a lookalike, so every
 * argument a command block understands works here unchanged: {@code @a},
 * {@code @p}, {@code @r}, {@code @a[r=32]}, {@code @a[x=..,y=..,z=..,dx=..]},
 * {@code @a[team=red]}, {@code @p[score_kills_min=10]} and so on. Re-implementing
 * that parser would only create a second, slightly different dialect.</p>
 *
 * <p>The block stands in as the command sender. It reports command level 2,
 * which is exactly the authority a command block has and is the only reason
 * selectors resolve at all - {@code matchEntities} refuses to run for a sender
 * that cannot use level-2 commands. Two consequences follow, and both are
 * documented rather than papered over:</p>
 * <ul>
 *   <li>{@code @s} fails. It means "the entity that ran this", and a block is
 *       not an entity. It is not silently rewritten to {@code @p}, because on a
 *       server with two players standing nearby that would broadcast to the
 *       wrong screen.</li>
 *   <li>Coordinates and radii are measured from the block, not from a player,
 *       which is what makes {@code @a[x=..]} and {@code @a[r=..]} mean
 *       something sensible here.</li>
 * </ul>
 *
 * <p>A token that is not a selector is treated as a player name, which vanilla
 * command blocks only accept for the sender itself. That is a small, deliberate
 * superset: typing {@code Steve} into the window should work.</p>
 */
public final class VideoTargets {

    private VideoTargets() {
    }

    /** A resolution attempt: either players, or one sentence saying why not. */
    public static final class Result {

        public final List<EntityPlayer> players;
        public final String error;

        private Result(List<EntityPlayer> players, String error) {
            this.players = players;
            this.error = error;
        }

        public boolean ok() {
            return this.error == null && !this.players.isEmpty();
        }
    }

    public static Result resolve(World world, BlockPos pos, String token) {
        String query = token == null ? "" : token.trim();
        if (query.isEmpty()) {
            return fail("没有设置广播目标");
        }
        if (query.length() > 128) {
            return fail("选择器过长（上限 128 字符）");
        }
        if (world == null || pos == null) {
            return fail("方块位置无效");
        }
        try {
            List<EntityPlayer> found;
            if (EntitySelector.isSelector(query)) {
                found = new ArrayList<>(EntitySelector.matchEntities(
                        new BlockSender(world, pos), query, EntityPlayer.class));
            } else {
                found = byName(world, query);
                if (found.isEmpty()) {
                    return fail("找不到名为 " + query + " 的玩家（要选多人请用 @a 之类的选择器）");
                }
            }
            if (found.isEmpty()) {
                return fail("选择器 " + query + " 没有匹配到玩家");
            }
            return new Result(found, null);
        } catch (CommandException e) {
            return fail("选择器 " + query + " 无效：" + e.getMessage());
        } catch (NullPointerException e) {
            // EntitySelector needs MinecraftServer.worlds for a bare "@a"; a
            // multiplayer client has no integrated server to hand it. The
            // server side always does, so this only ever shows up in the
            // window's local preview.
            return fail("本条选择器只能在服务端解析（带方括号参数的选择器可以预览）");
        } catch (Throwable t) {
            return fail("选择器解析失败：" + t);
        }
    }

    /** Plain player name, with no selector syntax. */
    private static List<EntityPlayer> byName(World world, String name) {
        MinecraftServer server = world.getMinecraftServer();
        if (server != null && server.getPlayerList() != null) {
            EntityPlayer player = server.getPlayerList().getPlayerByUsername(name);
            if (player != null) {
                return Collections.singletonList(player);
            }
        }
        EntityPlayer local = world.getPlayerEntityByName(name);
        return local == null ? Collections.<EntityPlayer>emptyList()
                : Collections.singletonList(local);
    }

    private static Result fail(String message) {
        return new Result(Collections.<EntityPlayer>emptyList(), message);
    }

    /**
     * The block posing as a command sender.
     *
     * <p>Level 2 is granted unconditionally: the block has no notion of who
     * owns it, so the permission boundary is "whoever may use this block", which
     * is what {@code videoOperatorOnly} in the config narrows down to operators
     * for servers that want it.</p>
     */
    public static final class BlockSender implements ICommandSender {

        private final World world;
        private final BlockPos pos;

        public BlockSender(World world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }

        @Override
        public String getName() {
            return "视频广播器";
        }

        @Override
        public boolean canUseCommand(int permLevel, String commandName) {
            return true;
        }

        @Override
        public BlockPos getPosition() {
            return this.pos;
        }

        @Override
        public Vec3d getPositionVector() {
            return new Vec3d(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D);
        }

        @Override
        public World getEntityWorld() {
            return this.world;
        }

        @Override
        public MinecraftServer getServer() {
            return this.world.getMinecraftServer();
        }
    }
}
