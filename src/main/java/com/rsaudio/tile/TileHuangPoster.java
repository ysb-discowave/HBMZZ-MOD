package com.rsaudio.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/**
 * 纪念挂画的方块实体 —— 只存一个东西：当前是第几页（哪张画面）。
 *
 * <h3>为什么页码不能放方块状态</h3>
 * 最初页码是 blockstate 里的 VARIANT（4 页 × 4 朝向 = 16 种，正好塞满 1.12.2
 * 的 4 位 meta）。现在画面加到 11 张，11 × 4 = 44 种状态，meta 装不下 ——
 * 再往 blockstate 里加就会在启动时 ArrayIndexOutOfBounds（本项目在巨型管道
 * 上吃过一次）。所以页码挪进这里，跟着 NBT 存档 + getUpdateTag 同步。
 *
 * <h3>画面怎么画</h3>
 * 单块时靠方块模型贴图（poster_pageN，静态、可靠）；多块拼成大图时由
 * HuangPosterWorldRenderer 用高分辨率原图画一张覆盖整片区域的大四边形。
 * 页码就是从这里读的。
 */
public class TileHuangPoster extends TileEntity {

    /** 当前第几页，0..BlockHuangPoster.PAGES-1 */
    private int page;

    public int getPage() {
        return page;
    }

    /** 只接受合法页码，越界会自动折回 0（存档被外部改过时兜底） */
    public void setPage(int p) {
        int max = com.rsaudio.block.BlockHuangPoster.PAGES - 1;
        this.page = (p < 0 || p > max) ? 0 : p;
    }

    /** 翻到下一页，返回新的页码 */
    public int nextPage() {
        setPage(page + 1 >= com.rsaudio.block.BlockHuangPoster.PAGES ? 0 : page + 1);
        return page;
    }

    /** 服务端改完页码后调这个，把新页码推给所有正在看这块画的客户端 */
    public void syncToClient() {
        if (world != null && !world.isRemote) {
            net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.page = compound.hasKey("PosterPage") ? compound.getInteger("PosterPage") : 0;
        int max = com.rsaudio.block.BlockHuangPoster.PAGES - 1;
        if (this.page < 0 || this.page > max) {
            this.page = 0;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("PosterPage", this.page);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("PosterPage", this.page);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.page = tag.hasKey("PosterPage") ? tag.getInteger("PosterPage") : 0;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }
}
