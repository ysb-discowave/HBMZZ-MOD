package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileTartarus;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 塔塔鲁斯的<b>控制器</b>（圆形巨型活板门的中央枢纽，也是这一格的方块实体所在）。
 *
 * <h3>这是什么</h3>
 * 一扇<b>圆形、巨型、向下缓慢开合</b>的舱盖门（活板门）：空手右键 = 手动开 / 关；
 * 手持木斧<b>潜行 + 右键</b> = 打开设置界面（调半径、建造、拆除）。
 * 建造后在控制器周围按 {@code 半径²} 生成整片圆形舱盖格。
 *
 * <h3>为什么是隐形方块</h3>
 * 控制器和舱盖格都用 {@code INVISIBLE} 渲染类型：世界上看不见方块本体，
 * 真正那块巨大的圆形金属舱盖盘 + 中心枢纽，全部由 {@code TartarusRenderer}
 * （挂在控制器方块实体上）画出来，并且能平滑地"向下沉"—— 普通方块模型做不到位移动画。
 *
 * <h3>抗爆</h3>
 * 爆炸抗性 {@code 6000}（黑曜石是 1200），扛得住核爆，但仍可正常挖掉，不留残骸。
 */
public class BlockTartarus extends Block implements ITileEntityProvider {

    /** 是否已开（开着就可通过，舱盖沉进竖井） */
    public static final PropertyBool OPEN = PropertyBool.create("open");

    public static final float BLAST_RESISTANCE = 6000.0F;

    public BlockTartarus() {
        super(net.minecraft.block.material.Material.IRON);
        setRegistryName(RsAudio.MODID, "tartarus");
        setUnlocalizedName(RsAudio.MODID + ".tartarus");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(50.0F);
        setResistance(BLAST_RESISTANCE);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setDefaultState(this.blockState.getBaseState().withProperty(OPEN, Boolean.FALSE));
    }

    public static IBlockState stateFor(boolean open) {
        return com.rsaudio.ModBlocks.TARTARUS.getDefaultState().withProperty(OPEN, open);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, OPEN);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(OPEN, (meta & 1) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(OPEN) ? 1 : 0;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return state.getValue(OPEN) ? NULL_AABB : FULL_BLOCK_AABB;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileTartarus();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileTartarus();
    }

    public static TileTartarus getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileTartarus ? (TileTartarus) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 手持木斧：交给魔杖（选角 / 开设置界面），这里不处理
        ItemStack held = playerIn.getHeldItemMainhand();
        if (!held.isEmpty() && held.getItem() == Items.WOODEN_AXE) {
            return false;
        }
        if (!worldIn.isRemote) {
            TileTartarus tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.toggleOpen(playerIn);
            }
        }
        return true;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            TileTartarus tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.removeHatch(false);
            }
        }
        super.breakBlock(worldIn, pos, state);
    }
}
