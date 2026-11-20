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

package com.hbmzz.reactor.block;

import com.hbmzz.HBMZZ;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * One of the four functional component blocks the player lays down around a
 * reactor core.
 *
 * <p>None of these is a machine. It has no tile entity, no state and no
 * behaviour of its own: it is a marker. The core block scans its neighbourhood,
 * counts how many of each kind it finds, and that count is the only thing the
 * physics ever sees. An emitter is "more fuel", an absorber is "more cooling",
 * a stabiliser is "more temperature feedback" and a control rod is "a throttle
 * whose depth the redstone beside it sets". All of that decision lives in the
 * core's tile entity and the pure {@code com.hbmzz.reactor} package; this class
 * only knows how to sit in the ground and drop itself when broken.</p>
 *
 * <p>It is a plain full cube with one texture per face taken from an existing,
 * already-shipping PNG. No new art, so a component can never arrive
 * purple-and-black in a pack that has not been rebuilt.</p>
 */
public class BlockReactorComponent extends Block {

    /** What a placed block represents to the core's scan. */
    public enum ComponentType {
        /** More fuel: raises rated output and the heat each unit of flux makes. */
        EMITTER("reactor_emitter", "reactor_emitter", 4.0F, 2.0F),
        /** More cooling: widens how much a full-open coolant loop can carry. */
        ABSORBER("reactor_absorber", "reactor_absorber", 4.0F, 2.0F),
        /** More temperature feedback: the coefficient that stops a hot core running away. */
        STABILISER("reactor_stabiliser", "reactor_stabiliser", 4.0F, 2.0F),
        /** A throttle: its redstone power is how deep it is inserted. */
        CONTROL_ROD("reactor_control_rod", "reactor_control_rod", 4.0F, 2.0F);

        private final String registrySuffix;
        private final String texture;
        private final float hardness;
        private final float resistance;

        ComponentType(String registrySuffix, String texture, float hardness, float resistance) {
            this.registrySuffix = registrySuffix;
            this.texture = texture;
            this.hardness = hardness;
            this.resistance = resistance;
        }
    }

    private final ComponentType type;

    public BlockReactorComponent(ComponentType type) {
        super(Material.IRON);
        this.type = type;
        setRegistryName(HBMZZ.MODID, type.registrySuffix);
        setUnlocalizedName(HBMZZ.MODID + "." + type.registrySuffix);
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(type.hardness);
        setResistance(type.resistance);
    }

    /** Components are solid, lightless, full cubes; rotation is irrelevant. */
    @Override
    public boolean isFullCube(net.minecraft.block.state.IBlockState state) {
        return true;
    }

    @Override
    public boolean isOpaqueCube(net.minecraft.block.state.IBlockState state) {
        return true;
    }

    @Override
    public boolean isNormalCube(net.minecraft.block.state.IBlockState state) {
        return true;
    }

    @Override
    public boolean isNormalCube(net.minecraft.block.state.IBlockState state,
                                net.minecraft.world.IBlockAccess world, BlockPos pos) {
        return true;
    }

    @Override
    public int getLightValue(net.minecraft.block.state.IBlockState state) {
        // Emitters glow faintly - they are the source. The rest stay dark.
        return this.type == ComponentType.EMITTER ? 7 : 0;
    }

    @Override
    public Block rotate(net.minecraft.world.IBlockAccess world, BlockPos pos,
                        net.minecraft.block.state.IBlockState state, BlockRotation rot) {
        return state.getBlock();
    }

    @Override
    public boolean canProvidePower(net.minecraft.block.state.IBlockState state) {
        return false;
    }

    public ComponentType type() {
        return this.type;
    }

    private void setHardestLevel() {
        // nothing special; kept as a named hook so future material tweaks stay local
    }
}
