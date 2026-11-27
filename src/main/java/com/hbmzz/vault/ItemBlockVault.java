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

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

/**
 * The vault door in item form, so a refused placement can name the cell in the way.
 *
 * <p>{@code canPlaceBlockAt} returning false is silent, and this door refuses in
 * a situation players will walk into constantly: it needs a five-by-six hole,
 * and the natural first attempt is to click the middle of a wall that is too
 * thin. "It just does not place" reads as a broken item; "the cell at
 * 12, 64, -3 is in the way" reads as an instruction.</p>
 */
public class ItemBlockVault extends ItemBlock {

    public ItemBlockVault(Block block) {
        super(block);
        setMaxStackSize(1);
        setMaxDamage(0);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            BlockPos target = world.getBlockState(pos).getBlock().isReplaceable(world, pos)
                    ? pos : pos.offset(facing);
            if (!getBlock().canPlaceBlockAt(world, target)) {
                BlockPos blocked = firstBlocked(world, target);
                if (blocked != null) {
                    player.sendMessage(new TextComponentTranslation("hbmzz.msg.vault_blocked",
                            blocked.getX(), blocked.getY(), blocked.getZ()));
                }
                return EnumActionResult.FAIL;
            }
        }
        return super.onItemUse(player, world, pos, hand, facing, hitX, hitY, hitZ);
    }

    private static BlockPos firstBlocked(World world, BlockPos origin) {
        // Must use the same facing the footprint check uses (BlockVault.placerFacing,
        // which is NORTH), or the "in the way" cell it names will not match the cell
        // that actually blocked placement.
        EnumFacing facing = EnumFacing.NORTH;
        for (int dx = 0; dx < BlockVault.W; dx++) {
            for (int dy = 0; dy < BlockVault.H; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                BlockPos cell = BlockVault.cell(origin, facing, dx, dy);
                if (!world.isBlockLoaded(cell)) {
                    return cell;
                }
                if (!world.getBlockState(cell).getBlock().isReplaceable(world, cell)) {
                    return cell;
                }
            }
        }
        return null;
    }
}
