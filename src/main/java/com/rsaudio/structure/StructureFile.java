package com.rsaudio.structure;

import com.rsaudio.util.BlockStates;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个结构（一块区域的方块 + 方块实体）。
 *
 * <h3>文件格式（gzip 过的 NBT）</h3>
 * <pre>
 *   Version  int      格式版本
 *   Name     string   结构名
 *   Author   string   作者
 *   Size     int[3]   尺寸 x y z
 *   Palette  list&lt;string&gt;  调色板（非空气方块状态的文本，去重存放）
 *   Blocks   list&lt;compound&gt; { pos: int[3], state: int(调色板下标) }
 *   Tiles    list&lt;compound&gt; 方块实体的 writeToNBT（箱子里的东西、我们的机器设置都会一起复制）
 * </pre>
 * <b>只存非空气方块</b>：粘贴时先把整个长方体清成空气，再放非空气方块，
 * 结果和原样一致，文件却能小很多（中空建筑不会把内部空气也写进去）。
 * <p>
 * 存的是"注册名 + 属性"文本而不是数字 id/meta：换存档、加删模组之后旧结构仍然能看懂。
 * 缺方块/缺属性时不会崩，只会跳过并计数（{@link #getDecodeFailures()}）。
 */
public final class StructureFile {

    public static final int VERSION = 1;
    /** 单边最大 64 格 */
    public static final int MAX_AXIS = 64;
    /** 最多 64³ = 262144 个方块 */
    public static final int MAX_VOLUME = MAX_AXIS * MAX_AXIS * MAX_AXIS;
    /** 最多保存 4096 个方块实体 */
    public static final int MAX_TILES = 4096;

    public int sizeX = 1;
    public int sizeY = 1;
    public int sizeZ = 1;
    public String name = "";
    public String author = "";

    /** 调色板：非空气方块状态的文本，下标就是 Blocks 里 state 的值 */
    public final List<String> palette = new ArrayList<String>();
    private final Map<String, Integer> paletteIndex = new HashMap<String, Integer>();

    /** 非空气方块（相对坐标 + 调色板下标） */
    public int[] xs = new int[0];
    public int[] ys = new int[0];
    public int[] zs = new int[0];
    public int[] states = new int[0];
    public int blockCount = 0;

    /** 方块实体（{@code TileEntity#writeToNBT} 的结果，含 x/y/z，粘贴时会被改写成新位置） */
    public final List<NBTTagCompound> tiles = new ArrayList<NBTTagCompound>();

    /** 解码缓存（粘贴时按需填充） */
    private IBlockState[] decoded;
    private int decodeFailures;

    // ---------------------------------------------------------------- 采集

    /** 采集结果：要么 file 可用，要么 error 有值 */
    public static final class Capture {

        public final StructureFile file;
        public final String error;
        /** 区域体积（含空气） */
        public final int volume;
        /** 非空气方块数 */
        public final int blocks;
        /** 没存下的方块实体数（超出上限时） */
        public final int skippedTiles;

        Capture(StructureFile file, String error, int volume, int blocks, int skippedTiles) {
            this.file = file;
            this.error = error;
            this.volume = volume;
            this.blocks = blocks;
            this.skippedTiles = skippedTiles;
        }

        public boolean isOk() {
            return error == null;
        }
    }

    /**
     * 采集两个角之间的区域。
     * <p>
     * 两个角的先后顺序无所谓，内部会自己取 min/max。
     */
    public static Capture capture(World world, BlockPos a, BlockPos b, String name, String author) {
        if (world == null || a == null || b == null) {
            return new Capture(null, "选区不完整", 0, 0, 0);
        }
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int sx = Math.abs(a.getX() - b.getX()) + 1;
        int sy = Math.abs(a.getY() - b.getY()) + 1;
        int sz = Math.abs(a.getZ() - b.getZ()) + 1;

        if (sx > MAX_AXIS || sy > MAX_AXIS || sz > MAX_AXIS) {
            return new Capture(null, "尺寸超过上限（单边最多 " + MAX_AXIS + " 格）："
                    + sx + "×" + sy + "×" + sz, 0, 0, 0);
        }
        long volume = (long) sx * sy * sz;
        if (volume > MAX_VOLUME) {
            return new Capture(null, "体积超过上限（最多 " + MAX_VOLUME + " 格）", 0, 0, 0);
        }

        StructureFile file = new StructureFile();
        file.sizeX = sx;
        file.sizeY = sy;
        file.sizeZ = sz;
        file.name = name == null ? "" : name;
        file.author = author == null ? "" : author;

        int cap = (int) volume;
        int[] xs = new int[cap];
        int[] ys = new int[cap];
        int[] zs = new int[cap];
        int[] states = new int[cap];
        int count = 0;
        int skippedTiles = 0;

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);
                    IBlockState state = world.getBlockState(pos);
                    if (state.getBlock() == Blocks.AIR) {
                        continue;
                    }
                    String text = BlockStates.encode(state);
                    Integer index = file.paletteIndex.get(text);
                    if (index == null) {
                        index = file.palette.size();
                        file.palette.add(text);
                        file.paletteIndex.put(text, index);
                    }
                    xs[count] = x;
                    ys[count] = y;
                    zs[count] = z;
                    states[count] = index;
                    count++;

                    // 方块实体（箱子里的东西、机器设置）一起存
                    TileEntity tile = world.getTileEntity(pos);
                    if (tile != null) {
                        if (file.tiles.size() < MAX_TILES) {
                            try {
                                NBTTagCompound tag = tile.writeToNBT(new NBTTagCompound());
                                // x/y/z = 绝对坐标（TileEntity#readFromNBT 会用它设自己的位置）
                                tag.setInteger("x", minX + x);
                                tag.setInteger("y", minY + y);
                                tag.setInteger("z", minZ + z);
                                // ★ 另外存一份相对坐标：粘贴时要靠它算出"新位置在哪"
                                tag.setInteger("rx", x);
                                tag.setInteger("ry", y);
                                tag.setInteger("rz", z);
                                file.tiles.add(tag);
                            } catch (Throwable ignored) {
                                // 某些模组的方块实体 writeToNBT 会抛异常，跳过这一个，别毁掉整个采集
                            }
                        } else {
                            skippedTiles++;
                        }
                    }
                }
            }
        }

        file.xs = Arrays.copyOf(xs, count);
        file.ys = Arrays.copyOf(ys, count);
        file.zs = Arrays.copyOf(zs, count);
        file.states = Arrays.copyOf(states, count);
        file.blockCount = count;
        return new Capture(file, null, (int) volume, count, skippedTiles);
    }

    // ---------------------------------------------------------------- 查询

    public int volume() {
        return sizeX * sizeY * sizeZ;
    }

    public String dimensions() {
        return sizeX + "×" + sizeY + "×" + sizeZ;
    }

    /** 体素下标 -> 相对坐标（x 变化最快，其次 z，最后 y） */
    public BlockPos voxelOffset(int index) {
        int x = sizeX == 0 ? 0 : index % sizeX;
        int rest = sizeX == 0 ? 0 : index / sizeX;
        int z = sizeZ == 0 ? 0 : rest % sizeZ;
        int y = sizeZ == 0 ? 0 : rest / sizeZ;
        return new BlockPos(x, y, z);
    }

    /** 第 index 个非空气方块的相对坐标 */
    public BlockPos blockOffset(int index) {
        if (index < 0 || index >= blockCount) {
            return BlockPos.ORIGIN;
        }
        return new BlockPos(xs[index], ys[index], zs[index]);
    }

    /**
     * 第 index 个非空气方块的状态（带缓存 + 容错）。
     * <p>
     * 解析不出来（方块被删了/属性名变了）返回 {@code null}，并累加失败计数。
     */
    public IBlockState stateAt(int index) {
        if (index < 0 || index >= blockCount) {
            return null;
        }
        if (decoded == null) {
            decoded = new IBlockState[blockCount];
        }
        if (decoded[index] == null) {
            int paletteSlot = states[index];
            String text = paletteSlot >= 0 && paletteSlot < palette.size() ? palette.get(paletteSlot) : null;
            IBlockState state = text == null ? null : BlockStates.decode(text);
            if (state == null) {
                decodeFailures++;
                // 用空气占位：同一个坏方块别反复解析
                decoded[index] = Blocks.AIR.getDefaultState();
                return null;
            }
            decoded[index] = state;
        }
        return decoded[index] == Blocks.AIR.getDefaultState() ? null : decoded[index];
    }

    public int getDecodeFailures() {
        return decodeFailures;
    }

    // ---------------------------------------------------------------- 存档

    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", VERSION);
        tag.setString("Name", name == null ? "" : name);
        tag.setString("Author", author == null ? "" : author);
        tag.setIntArray("Size", new int[]{sizeX, sizeY, sizeZ});

        NBTTagList paletteList = new NBTTagList();
        for (String entry : palette) {
            paletteList.appendTag(new NBTTagString(entry));
        }
        tag.setTag("Palette", paletteList);

        NBTTagList blockList = new NBTTagList();
        for (int i = 0; i < blockCount; i++) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setIntArray("pos", new int[]{xs[i], ys[i], zs[i]});
            entry.setInteger("state", states[i]);
            blockList.appendTag(entry);
        }
        tag.setTag("Blocks", blockList);

        NBTTagList tileList = new NBTTagList();
        for (NBTTagCompound tile : tiles) {
            tileList.appendTag(tile.copy());
        }
        tag.setTag("Tiles", tileList);
        return tag;
    }

    /** 解析；坏数据返回 null（不抛异常，别让一个坏文件崩掉服务端） */
    public static StructureFile fromNBT(NBTTagCompound tag) {
        if (tag == null) {
            return null;
        }
        StructureFile file = new StructureFile();
        int[] size = tag.getIntArray("Size");
        if (size.length != 3) {
            return null;
        }
        file.sizeX = Math.max(1, size[0]);
        file.sizeY = Math.max(1, size[1]);
        file.sizeZ = Math.max(1, size[2]);
        if (file.sizeX > MAX_AXIS || file.sizeY > MAX_AXIS || file.sizeZ > MAX_AXIS) {
            return null;
        }
        file.name = tag.getString("Name");
        file.author = tag.getString("Author");

        NBTTagList paletteList = tag.getTagList("Palette", 8);      // 8 = String
        for (int i = 0; i < paletteList.tagCount(); i++) {
            file.palette.add(paletteList.getStringTagAt(i));
        }

        NBTTagList blockList = tag.getTagList("Blocks", 10);        // 10 = Compound
        int count = blockList.tagCount();
        if (count > MAX_VOLUME) {
            return null;
        }
        file.xs = new int[count];
        file.ys = new int[count];
        file.zs = new int[count];
        file.states = new int[count];
        int valid = 0;
        for (int i = 0; i < count; i++) {
            NBTTagCompound entry = blockList.getCompoundTagAt(i);
            int[] pos = entry.getIntArray("pos");
            if (pos.length != 3) {
                continue;
            }
            file.xs[valid] = pos[0];
            file.ys[valid] = pos[1];
            file.zs[valid] = pos[2];
            file.states[valid] = entry.getInteger("state");
            valid++;
        }
        if (valid != count) {
            file.xs = Arrays.copyOf(file.xs, valid);
            file.ys = Arrays.copyOf(file.ys, valid);
            file.zs = Arrays.copyOf(file.zs, valid);
            file.states = Arrays.copyOf(file.states, valid);
        }
        file.blockCount = valid;

        NBTTagList tileList = tag.getTagList("Tiles", 10);
        for (int i = 0; i < tileList.tagCount() && i < MAX_TILES; i++) {
            file.tiles.add(tileList.getCompoundTagAt(i));
        }
        return file;
    }
}
