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

package com.hbmzz.common;

import com.hbmzz.block.BlockVideoBroadcaster;
import com.hbmzz.block.BlockVideoScreen;
import net.minecraft.block.Block;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 蹲下右键：让这两块方块在被潜行点击时仍然收到点击。
 *
 * <h3>The bug</h3>
 * <p>Vanilla's right-click path asks the block only when the player is not
 * sneaking, or the held stack "bypasses" sneaking
 * ({@code PlayerControllerMP.processRightClickBlock}, and the same test again in
 * {@code PlayerInteractionManager} on the server):</p>
 *
 * <pre>
 *   if (!player.isSneaking() || bypass || event.getUseBlock() == ALLOW)
 *       block.onBlockActivated(...)
 * </pre>
 *
 * <p>An empty hand bypasses, so 蹲下右键空手 worked - and holding <em>anything at
 * all</em> silently did nothing, on both sides. That is why the settings window
 * felt like it "sometimes does not recognise the click": the player was holding a
 * pickaxe. There is nothing to fix inside the block, because the block was never
 * called.</p>
 *
 * <h3>The fix</h3>
 * <p>Forge fires {@link PlayerInteractEvent.RightClickBlock} before that test and
 * lets a handler answer {@code setUseBlock(ALLOW)}, which is exactly the supported
 * way to say "this block gets the click even while sneaking". Registered on both
 * sides from {@code HBMZZ.preInit}, because the server runs the same test and a
 * client-only answer would open a window whose packets the server ignored.</p>
 *
 * <p>Scoped to the two video blocks on purpose: applying it to every block in the
 * game would break the vanilla rule that sneaking means "use the item, not the
 * block" - which players rely on to place blocks against chests.</p>
 */
public final class VideoClickHandler {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntityPlayer() == null || !event.getEntityPlayer().isSneaking()) {
            return;
        }
        if (event.getWorld() == null) {
            return;
        }
        Block block = event.getWorld().getBlockState(event.getPos()).getBlock();
        if (block instanceof BlockVideoBroadcaster || block instanceof BlockVideoScreen) {
            event.setUseBlock(Event.Result.ALLOW);
        }
    }
}
