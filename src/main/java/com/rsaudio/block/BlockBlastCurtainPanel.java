package com.rsaudio.block;

import com.rsaudio.RsAudio;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;

/**
 * 防爆窗帘的<b>门板</b>：由 {@link BlockBlastCurtain} 控制器生成，玩家拿不到它的物品。
 * <p>
 * 和防核门板完全同源（连方块状态都是同一对静态属性），只换三样：
 * 注册名 {@code blast_curtain_panel}、材质 {@link Material#CLOTH}、
 * 音效 {@link SoundType#CLOTH}，抗性降到 {@link BlockBlastDoor#CURTAIN_BLAST_RESISTANCE}。
 * <p>
 * ★ 门面是一整扇，玩家分不出哪一格是控制器，所以门板自己也要认那把"窗帘工具"
 * （见 {@link BlockBlastCurtain#isCurtainTool}）：铁铲潜行右键 = 尺寸界面、铁铲右键 = 开关。
 * 木斧那套照旧由父类处理。
 */
public class BlockBlastCurtainPanel extends BlockBlastDoorPanel {

    public BlockBlastCurtainPanel() {
        super("blast_curtain_panel", Material.CLOTH, SoundType.CLOTH,
                8.0F, BlockBlastDoor.CURTAIN_BLAST_RESISTANCE);
        // ★ 字面量留给 tools/regcheck（理由见 BlockBlastDoor 构造函数里那段）
        setRegistryName(RsAudio.MODID, "blast_curtain_panel");
        setUnlocalizedName(RsAudio.MODID + ".blast_curtain_panel");
    }

    @Override
    public boolean onBlockActivated(net.minecraft.world.World worldIn, net.minecraft.util.math.BlockPos pos,
                                    net.minecraft.block.state.IBlockState state,
                                    net.minecraft.entity.player.EntityPlayer playerIn,
                                    net.minecraft.util.EnumHand hand, net.minecraft.util.EnumFacing facing,
                                    float hitX, float hitY, float hitZ) {
        if (BlockBlastCurtain.isCurtainTool(playerIn)) {
            // ★ 用 findController 现场扫，<b>不能用</b> TileBlastDoor.ownerOf（归属表）：
            //   归属表只在服务端登记，客户端查它恒为 null —— 拿它开界面就是"潜行右键没反应"。
            net.minecraft.util.math.BlockPos controller = BlockBlastDoor.findController(worldIn, pos);
            if (controller == null) {
                return false;                 // 找不到主人（帘子正在拆？）—— 当普通方块处理
            }
            if (playerIn.isSneaking()) {
                if (worldIn.isRemote) {
                    com.rsaudio.RsAudio.proxy.openBlastDoorGui(controller);
                }
                return true;
            }
            if (!worldIn.isRemote) {
                com.rsaudio.tile.TileBlastDoor tile = BlockBlastDoor.getTile(worldIn, controller);
                if (tile != null) {
                    tile.toggleDoor(playerIn);
                }
            }
            return true;
        }
        return super.onBlockActivated(worldIn, pos, state, playerIn, hand, facing, hitX, hitY, hitZ);
    }
}
