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

package com.hbmzz.blast;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.tile.TileEntityDfcBlastSensor;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * DFC 爆炸传感器：DFC 一炸，它开始输出红石。
 *
 * <h3>它解决的问题</h3>
 * <p>DFC 的熔毁不是「冒烟然后停机」，是<strong>一次性把半个基地从存档里抹掉</strong>。
 * 在这个方块出现之前，所有「事故后自动动作」都缺一个起点：备用电站不知道要启动，
 * 防爆门不知道要关，警报不知道要停——因为没有任何东西告诉它们「刚刚出事了」。</p>
 *
 * <h3>为什么它自己不判断爆炸</h3>
 * <p>判定全部在服务端的 {@link DfcBlastWatch} 里做一次，方块只读那条记录。所以
 * 装十块和装一块的代价几乎一样，而且十块说的是同一句话：同一个维度、同一次爆炸、
 * 同一个剩余时间。各自判断的实现会在区块没加载时各错过一次，然后彼此不同步。</p>
 *
 * <h3>为什么是满方块而不是吊装屏</h3>
 * <p>它不是给人看的，是给导线看的。做成 2×2 的屏没有意义：信号只需要两根线，
 * 而设备越小越容易塞进墙里、塞进防爆门旁边的检修槽里。正面那块镜头只是为了让
 * 人一眼看出「这台是传感器」以及「它现在有没有在响」。</p>
 */
public class BlockDfcBlastSensor extends BlockHorizontal implements ITileEntityProvider {

    /** 有没有输出信号。只用于换模型，红石强度是连续的 0..15，见方块实体。 */
    public static final PropertyBool ACTIVE = PropertyBool.create("active");

    public BlockDfcBlastSensor() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "dfc_blast_sensor");
        setUnlocalizedName(HBMZZ.MODID + ".dfc_blast_sensor");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        // 比普通机器结实的多：这东西的用处就是在事故里活下来把信号送出去，
        // 一块被自己那次爆炸顺手掀掉的传感器等于没装。当然，正对着爆心的
        // 位置仍然会被抹掉——所以它该装在爆炸半径之外。
        setResistance(90.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(ACTIVE, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, ACTIVE);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(ACTIVE) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(ACTIVE, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityDfcBlastSensor();
    }

    // ----------------------------------------------------------------- power

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public int getWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return strengthAt(world, pos);
    }

    /**
     * 同时给强充能。
     *
     * <p>只给弱充能的话，红石粉能点亮，但直接贴在方块另一侧的中继器、活板门、
     * 活塞都读不到——接线的人得先铺一格粉。传感器本来就该「贴哪面哪面通」，
     * 所以两种都答。</p>
     */
    @Override
    public int getStrongPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return strengthAt(world, pos);
    }

    private static int strengthAt(IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityDfcBlastSensor) {
            return ((TileEntityDfcBlastSensor) te).getStrength();
        }
        return 0;
    }

    // ------------------------------------------------------------ interaction

    /**
     * 右键只报读数，不改任何东西。
     *
     * <p>它没有可调的东西——延迟、强度、半径都在配置里，因为它们是<strong>一次事故
     * 里全厂共用的常数</strong>，不是每块设备各自的偏好。给一块传感器加个菜单，
     * 结果只会是同一个基地里的传感器行为不一致。</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityDfcBlastSensor)) {
            return true;
        }
        TileEntityDfcBlastSensor sensor = (TileEntityDfcBlastSensor) te;
        DfcBlastWatch.Blast blast = DfcBlastWatch.last(world);
        if (blast == null) {
            player.sendMessage(HBMZZConfig.dfcSensorRange > 0
                    ? new TextComponentTranslation("hbmzz.msg.dfc_sensor_idle",
                            String.valueOf(HBMZZConfig.dfcSensorRange))
                    : new TextComponentTranslation("hbmzz.msg.dfc_sensor_idle_any"));
            return true;
        }
        long age = world.getTotalWorldTime() - blast.tick;
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.dfc_sensor",
                String.valueOf(sensor.getStrength()),
                String.valueOf(Math.round(blast.distanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))),
                String.valueOf(age / 20.0D),
                blast.source));
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (!world.isRemote) {
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }
}
