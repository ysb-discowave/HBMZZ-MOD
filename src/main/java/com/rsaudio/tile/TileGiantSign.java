package com.rsaudio.tile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 巨大标志牌的内容：三行文字 + 一个字色。
 *
 * <h3>为什么这些字段全部走 {@code getUpdateTag} 而不是自定义同步包</h3>
 * 它们都是"配置"：写一次、之后只有渲染读，没有任何每 tick 变化。
 * 这正是原版 {@code SPacketUpdateTileEntity} 的适用场景（告示牌就是这么做的），
 * 所以不需要一个专门的下行包 —— 上行改完（{@code PacketGiantSignSet}）之后，
 * 服务端 {@code markDirty()} + {@code notifyBlockUpdate} 就会把
 * {@link #getUpdateTag()} 推给所有正在看这块牌子的客户端。
 * <p>
 * ★ 反过来，如果这几个字段漏写进 {@code getUpdateTag}，症状是
 * "在界面上改完、字没变"（要等区块重载才显示）——
 * {@code TileMonitorCamera} 那边记过同一笔，那边还加了 {@code handleUpdateTag}
 * 显式覆盖，这里照做。
 *
 * <h3>★ 文本必须净化</h3>
 * 玩家能往这里写任意字符串。不清洗的话至少有两个坑：
 * <ul>
 *   <li>{@code §} 是原版的格式控制符 —— 玩家可以塞 {@code §k}（乱码）或者
 *       用它对不齐的字号做文章；更糟的是它会被原版渲染器当成控制符吃进状态机。</li>
 *   <li>换行符会把一行拆成两行，绕开"三行"的排版假设。</li>
 * </ul>
 * 所以 {@link #sanitize(String)} 统一：去掉 {@code §}、去掉控制字符、按长度截断。
 * 清洗放在<b>写入侧</b>（不只是渲染侧），这样 NBT 里存的、网络上传的、
 * 渲染用的永远是同一份干净文本 —— 三处各自清洗是最容易漏的那种设计。
 */
public class TileGiantSign extends TileEntity {

    /** 行数 */
    public static final int LINE_COUNT = 3;

    /** 每行最长字符数。太长会自动缩小字号（见 {@code GiantSignRenderer}），这里只挡"离谱" */
    public static final int MAX_LINE_LENGTH = 20;

    /** 字色种数 */
    public static final int COLOR_COUNT = 8;

    /**
     * 字色名。★ 必须和两块地方逐字一致：
     * ① {@code lang} 里的 {@code tile.rsaudio.giant_sign.color.<名字>}；
     * ② {@code gen_resources.py} 生成贴图/模型时用的同一份列表。
     * {@code verify_jar} 的 §[2e] 会从本类的常量池里抽字面量再和 lang 双向比对
     * （"清单式"检查漏项是静默的，所以这里必须从源码抽，不能手写）。
     */
    public static final String[] COLOR_NAMES = {
            "white", "amber", "red", "green", "cyan", "blue", "violet", "yellow",
    };

    /** 与 {@link #COLOR_NAMES} 一一对应的实际颜色（0xRRGGBB） */
    private static final int[] COLOR_RGB = {
            0xFFFFFF, 0xFFB43C, 0xFF4A4A, 0x5CFF7A, 0x4AE0FF, 0x5C8CFF, 0xC07AFF, 0xFFE95C,
    };

    public static final int DEFAULT_COLOR = 0;

    private final String[] lines = new String[LINE_COUNT];
    private int colorIndex = DEFAULT_COLOR;

    public TileGiantSign() {
        for (int i = 0; i < LINE_COUNT; i++) {
            lines[i] = "";
        }
    }

    // ------------------------------------------------------------------ 取值

    public String getLine(int index) {
        return index >= 0 && index < LINE_COUNT ? lines[index] : "";
    }

    public int getColorIndex() {
        return colorIndex;
    }

    /** 这一块牌子上有没有字（渲染器用它决定要不要画） */
    public boolean hasAnyText() {
        for (String line : lines) {
            if (line != null && !line.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 字号自适应时要用的最长行宽（按<b>字符数</b>估，真正的像素宽度在渲染器里算） */
    public int longestLineLength() {
        int best = 0;
        for (String line : lines) {
            if (line != null && line.length() > best) {
                best = line.length();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 写值

    /**
     * 一次性写入三行 + 颜色（界面按"应用"时走这条）。
     *
     * @return 内容是否真的变了（没变就不 markDirty / 不推同步，省一次区块更新）
     */
    public boolean setContent(String[] newLines, int newColor) {
        boolean changed = false;
        for (int i = 0; i < LINE_COUNT; i++) {
            String value = sanitize(newLines != null && i < newLines.length ? newLines[i] : "");
            if (!value.equals(lines[i])) {
                lines[i] = value;
                changed = true;
            }
        }
        int clamped = clampColor(newColor);
        if (clamped != colorIndex) {
            colorIndex = clamped;
            changed = true;
        }
        if (changed) {
            markDirty();
            syncToClient();
        }
        return changed;
    }

    /** 服务端把最新内容推给正在看的人（右键开界面时顺手调一次，见 BlockGiantSign） */
    public void pushToClients() {
        syncToClient();
    }

    // ------------------------------------------------------------------ 净化与钳位

    /**
     * 清洗一行文本：去 {@code §}、去所有控制字符、去首尾空白、按长度截断。
     * <p>
     * ★ 长度截断按<b>码点</b>算不到（1.12.2 的 {@code String.length()} 是 char 数），
     * 但这里够用：超长本来就会被渲染器缩到看不清，截断只是防"有人塞十万字符"
     * 让 NBT 和网络包爆掉。用 char 数截断最多把一个代理对（emoji）劈成两半 ——
     * 那两个半字符渲染出来只是乱码方块，不会崩，可以接受。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < MAX_LINE_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7') {
                continue;                       // 原版格式控制符：一律不给用
            }
            if (c < 0x20 || c == 0x7f) {
                continue;                       // 控制字符（含换行/制表）
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    public static int clampColor(int value) {
        if (value < 0) {
            return 0;
        }
        return value >= COLOR_COUNT ? COLOR_COUNT - 1 : value;
    }

    /** 颜色名，越界兜底到第一项（不做异常，渲染路径上不该有抛点） */
    public static String colorName(int index) {
        return COLOR_NAMES[clampColor(index)];
    }

    /** 实际 RGB，越界兜底到第一项 */
    public static int colorRgb(int index) {
        return COLOR_RGB[clampColor(index)];
    }

    // ------------------------------------------------------------------ 同步

    /**
     * 把方块实体数据推给正在看这块牌子的客户端。
     * <p>
     * 用 {@code notifyBlockUpdate} 而不是自己发包：原版会顺着这条路
     * 把 {@link #getUpdateTag()} 装进 {@code SPacketUpdateTileEntity} 发出去。
     */
    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        for (int i = 0; i < LINE_COUNT; i++) {
            // ★ 读档也要过一遍 sanitize：NBT 是外部可改的（玩家能拿编辑器改存档），
            //   不能让"只在写入侧清洗"变成一条可绕过的规则。
            lines[i] = sanitize(compound.getString("Line" + i));
        }
        this.colorIndex = clampColor(compound.hasKey("Color") ? compound.getInteger("Color") : DEFAULT_COLOR);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        for (int i = 0; i < LINE_COUNT; i++) {
            compound.setString("Line" + i, getLine(i));
        }
        compound.setInteger("Color", colorIndex);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        for (int i = 0; i < LINE_COUNT; i++) {
            tag.setString("Line" + i, getLine(i));
        }
        tag.setInteger("Color", colorIndex);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        for (int i = 0; i < LINE_COUNT; i++) {
            lines[i] = sanitize(tag.getString("Line" + i));
        }
        this.colorIndex = clampColor(tag.hasKey("Color") ? tag.getInteger("Color") : DEFAULT_COLOR);
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        // 切换 LIT 会 setBlockState，状态变了但方块没变 —— 内容必须留着
        return oldState.getBlock() != newState.getBlock();
    }
}
