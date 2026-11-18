package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.tile.TileBlastDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * 防爆窗帘 —— 作者（TinyOOOOOOOOOS）在防核门之后提的东西："防爆窗帘。"
 *
 * <p>用户补的规格：<b>整体黑色、表面印小型标识、用原版铁铲蹲下右键可自定义调整大小</b>。
 *
 * <h3>和防核门的关系</h3>
 * 同一套机构（{@link BlockBlastDoor}）的<b>第二种变体</b>，只换四样东西：
 * <ul>
 *   <li>门板方块 → {@link BlockBlastCurtainPanel}；</li>
 *   <li>材质 / 音效 → {@code Material.CLOTH} / {@code SoundType.CLOTH}；</li>
 *   <li>爆炸抗性 → {@link BlockBlastDoor#CURTAIN_BLAST_RESISTANCE}（1000）；</li>
 *   <li>外观 → 渲染器把门体画成带竖向褶皱的黑布 + 顶部罗马杆 + 可换的布面贴图。</li>
 * </ul>
 * 连续碰撞、门板生长回收、防夹、联锁、气闸、门禁、尺寸界面全部继承，一行都没重写。
 *
 * <h3>为什么是铁铲</h3>
 * 用户的规矩：<b>原版铁铲 + 潜行 + 右键 = 打开尺寸界面</b>。
 * 界面复用防核门那个（{@code GuiBlastDoor}，里面本来就有宽 / 高 的 +/− 和「建造」），
 * 所以"自定义调整大小"不需要另做一套 UI —— 改完尺寸点建造，帘子会按新尺寸重建。
 * <p>
 * ★ 铁铲潜行右键<b>默认到不了这里</b>：{@code Item.doesSneakBypassUse} 对工具是 false，
 * 原版 {@code processRightClickBlock} 在潜行时压根不调 {@code onBlockActivated}。
 * 让这条调用发生的是 {@code com.rsaudio.util.CurtainToolHandler} 里那条
 * {@code setUseBlock(ALLOW)} —— <b>两处是一对，删掉那边这里就是"潜行右键没反应"</b>。
 *
 * <h3>木斧也认（兼容，不是必需）</h3>
 * 帘子就是门，玩家手上多半先摸到木斧。木斧那一套（右键开关 / 潜行开界面）由父类处理，
 * 这里只在前面拦一道铁铲。
 */
public class BlockBlastCurtain extends BlockBlastDoor {

    public BlockBlastCurtain() {
        super("blast_curtain", true);
        // ★ 字面量留给 tools/regcheck（理由见 BlockBlastDoor 构造函数里那段）
        setRegistryName(RsAudio.MODID, "blast_curtain");
        setUnlocalizedName(RsAudio.MODID + ".blast_curtain");
    }

    /**
     * 手上是不是那把"窗帘专用工具"——<b>原版铁铲</b>。
     * <p>
     * 只认 {@link Items#IRON_SHOVEL}：用户明确说"使用原版铁铲"。
     * 金 / 石 / 木 / 钻石铲一概不认 —— 认多了等于没门槛，
     * 而"门槛"正是这个动作存在的意义（防止手滑改了整扇帘子的尺寸）。
     */
    public static boolean isCurtainTool(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        ItemStack stack = player.getHeldItemMainhand();
        return !stack.isEmpty() && stack.getItem() == Items.IRON_SHOVEL;
    }

    /**
     * 铁铲：潜行 = 尺寸界面，不潜行 = 开 / 关。
     * <p>
     * 除此之外一律交给父类（木斧的开关与设置界面、空手提示都在那里）。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (isCurtainTool(playerIn)) {
            if (playerIn.isSneaking()) {
                // 界面只在客户端，服务端这一份是空实现（和防核门同一条约定）
                if (worldIn.isRemote) {
                    RsAudio.proxy.openBlastDoorGui(pos);
                }
                return true;
            }
            if (!worldIn.isRemote) {
                TileBlastDoor tile = getTile(worldIn, pos);
                if (tile != null) {
                    tile.toggleDoor(playerIn);
                }
            }
            return true;
        }
        return super.onBlockActivated(worldIn, pos, state, playerIn, hand, facing, hitX, hitY, hitZ);
    }

    /**
     * 窗帘必须<b>挂在玻璃旁边</b>才能装 —— 用户："它必须检测到旁边有玻璃才能装上"。
     *
     * <h3>为什么放在"放下去之后"而不是 {@code canPlaceBlockAt}</h3>
     * {@code canPlaceBlockAt} 只回一个 true/false，客户端拿它决定"能不能放"，
     * 玩家看不到任何原因，只会觉得"这块地方不让放窗帘"。
     * 所以先让它放下去、再当场检查：不合格就把方块撤掉、物品原样还回去、并说清楚为什么。
     * <p>
     * 检测范围取 <b>3×3×3</b>（含斜角）而不只是贴着的六面：
     * 玩家装窗帘时通常是"先砌好一面玻璃墙，再把帘子贴着墙放"，
     * 站在窗台边、帘子挪半格是很常见的操作 —— 只认六面会让你明明贴着玻璃却装不上。
     */
    @Override
    public void onBlockPlacedBy(World worldIn, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(worldIn, pos, state, placer, stack);
        if (worldIn.isRemote || hasGlassNearby(worldIn, pos)) {
            return;
        }
        worldIn.setBlockToAir(pos);
        if (placer instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) placer;
            ItemStack back = new ItemStack(this);
            if (!player.inventory.addItemStackToInventory(back)) {
                player.dropItem(back, false);
            }
            player.sendStatusMessage(new TextComponentString(
                    "窗帘要挂在玻璃旁边：3×3×3 范围内要有玻璃方块"), true);
        }
    }

    /** 3×3×3 范围内有没有玻璃（原版玻璃 / 玻璃板 / 任何 Material.GLASS 的方块） */
    private static boolean hasGlassNearby(World world, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (isGlass(world.getBlockState(pos.add(dx, dy, dz)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isGlass(IBlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        if (block == net.minecraft.init.Blocks.GLASS
                || block == net.minecraft.init.Blocks.STAINED_GLASS
                || block == net.minecraft.init.Blocks.GLASS_PANE
                || block == net.minecraft.init.Blocks.STAINED_GLASS_PANE) {
            return true;
        }
        // ★ 兜底认材质：别的模组的玻璃（含染色玻璃、强化玻璃）大多也是 Material.GLASS，
        //   只认原版那四张脸的话，玩家用模组玻璃砌的窗就永远挂不上帘子。
        return state.getMaterial() == net.minecraft.block.material.Material.GLASS;
    }

    /**
     * 窗帘<b>不复用</b>防核门那几条物品说明：
     * 其中"木斧右键开关门"对窗帘是错的（钥匙是铁铲）。
     * 与其让玩家读到一堆不适用的字，不如给窗帘三条自己的。
     */
    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.curtain.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.curtain.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.curtain.3"));
    }
}
