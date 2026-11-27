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

package com.hbmzz.vault;

import com.hbmzz.alert.AlertCode;
import com.rsaudio.util.WandHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The one hand gesture that touches the vault door by hand.
 *
 * <p>The door opens and closes on the facility state; nothing else reaches it.
 * This is that nothing. Holding a wooden axe (the mod's wand - see
 * {@link WandHandler#isWand}) and right-clicking the door walks its open
 * condition through the eight facility codes:</p>
 *
 * <ul>
 *   <li>plain right-click: raise the condition - the door opens only on a worse
 *       state, so the leaf is harder to open;</li>
 *   <li>sneak + right-click: lower it - the door opens on a calmer state.</li>
 * </ul>
 *
 * <p>It wraps around, so either gesture always does something. The condition
 * lives in the corner's tile entity and is written to its NBT, so it survives a
 * save. {@link WandHandler} is told to leave this door alone, which is what
 * keeps the wand's "pick a corner" from eating the same right-click.</p>
 */
public final class VaultAxeHandler {

    private VaultAxeHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (event.getHand() != EnumHand.MAIN_HAND || !WandHandler.isWand(player)) {
            return;
        }
        World world = player.world;
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos pos = event.getPos();
        Block block = world.getBlockState(pos).getBlock();
        if (!(block instanceof BlockVault) && !(block instanceof BlockVaultPart)) {
            return;
        }
        // Click a leaf cell: walk back to the corner; click the corner: it is the origin.
        BlockPos origin = block instanceof BlockVault ? pos : BlockVault.findOrigin(world, pos);
        if (origin == null) {
            return;
        }
        TileEntity te = world.getTileEntity(origin);
        if (!(te instanceof TileEntityVault)) {
            return;
        }
        TileEntityVault vault = (TileEntityVault) te;
        int next = player.isSneaking() ? vault.getOpenCondition() - 1 : vault.getOpenCondition() + 1;
        vault.setOpenCondition(next);
        // Re-evaluate against the live facility code right now instead of waiting
        // for the next one-second poll, so the leaf reacts to the new condition.
        vault.check(world, origin);
        te.markDirty();
        AlertCode condition = AlertCode.byId(vault.getOpenCondition());
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.vault_condition",
                condition.label()));
    }
}
