package com.rsaudio.tile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 监控摄像头的方块实体。
 *
 * <h3>它只记参数，不记画面</h3>
 * 画面是<b>每个客户端自己渲染</b>出来的（见 {@code com.rsaudio.client.CameraFeedRenderer}）：
 * 摄像头没有"拍到的图像"这种东西，它只是一个"看的方向 + 一个频道号"。
 * 让服务端去渲染画面再逐帧发给客户端是行不通的 —— 服务端根本没有 OpenGL 上下文。
 * <p>
 * 所以这里的字段全部是"配置"，客户端拿 {@code getUpdateTag} 同步一份就够了，
 * 不需要任何每 tick 的网络流量。
 *
 * <h3>频道</h3>
 * 频道号把"摄像头"和"监控控制台"解耦：控制台只认频道，不认具体哪个摄像头。
 * 同一个频道可以挂多个摄像头，控制台显示<b>离自己最近的那个</b>。
 * 这样布线时不用一根根去"配对"，也就不需要什么绑定/解绑的操作。
 *
 * <h3>朝向 = 基础朝向 + 云台偏移</h3>
 * "贴哪个面"在放置时定死（{@code FACING}），但这只给了 6 个离散方向，
 * 挂歪一点就什么都拍不到。所以再叠一层<b>云台偏移</b>：
 * {@link #getYawOffset()} / {@link #getPitchOffset()}，各限制在 ±180 / ±90 度。
 * <p>
 * 最终视线 = 把基础 {@code FACING} 换算成 yaw/pitch，再加上这两个偏移
 * （见 {@code com.rsaudio.client.CameraFeedRenderer}）。偏移量存在方块实体上、
 * 跟着 {@code getUpdateTag} 同步，所以<b>服务端和所有客户端算出来的视线是同一个</b>
 * —— "画面里有谁"的判定（服务端无关、纯客户端算）不会和真实画面跑偏。
 */
public class TileMonitorCamera extends TileEntity {

    /** 频道号范围：1..16（用 1 起头是为了界面上好念，"1 号频道"） */
    public static final int CHANNEL_MIN = 1;
    public static final int CHANNEL_MAX = 16;
    public static final int DEFAULT_CHANNEL = 1;

    /** 视野角度范围（度）。默认比玩家视角略窄，像监控镜头 */
    public static final int FOV_MIN = 30;
    public static final int FOV_MAX = 110;
    public static final int FOV_DEFAULT = 70;

    /**
     * 云台偏移的取值规则<b>全部委托给</b> {@link com.rsaudio.util.Gimbal}。
     * <p>
     * ★ 刻意不在这里另写一套常数和夹取逻辑：范围 / 步长 / 归一化规则一旦有两份，
     * 迟早会出现"界面按 ▶ 转到 195°、服务端存成 -165°、渲染又各算各的"这种三方差一。
     * {@code Gimbal} 那一份是能被 {@code tools/check_gimbal_core.py} 真实跑起来验证的，
     * 所以权威版本放在那边，这里只做转发（方块实体是双端共用的，不能直接引用客户端类，
     * 但 {@code Gimbal} 是纯数学、两边都能用）。
     */
    public static final int YAW_MIN = com.rsaudio.util.Gimbal.YAW_MIN;
    public static final int YAW_MAX = com.rsaudio.util.Gimbal.YAW_MAX;
    /** 界面上按一下转多少度 */
    public static final int YAW_STEP = com.rsaudio.util.Gimbal.YAW_STEP;
    public static final int YAW_DEFAULT = com.rsaudio.util.Gimbal.YAW_DEFAULT;

    /** 云台俯仰偏移范围（度）。<b>正数 = 往下压</b>（和原版 pitch 同号）。 */
    public static final int PITCH_MIN = com.rsaudio.util.Gimbal.PITCH_MIN;
    public static final int PITCH_MAX = com.rsaudio.util.Gimbal.PITCH_MAX;
    public static final int PITCH_STEP = com.rsaudio.util.Gimbal.PITCH_STEP;
    public static final int PITCH_DEFAULT = com.rsaudio.util.Gimbal.PITCH_DEFAULT;

    public static final int LABEL_MAX_LENGTH = 24;

    private int channel = DEFAULT_CHANNEL;
    private int fov = FOV_DEFAULT;
    /** 云台水平偏移（度），见 {@link #YAW_MIN}。0 = 正对 FACING 的方向 */
    private int yawOffset = YAW_DEFAULT;
    /** 云台俯仰偏移（度），见 {@link #PITCH_MIN}。0 = 不抬不压 */
    private int pitchOffset = PITCH_DEFAULT;
    private boolean enabled = true;
    /** 给玩家看的名字，例如"三号气闸北侧"。空字符串 = 界面上显示坐标 */
    private String label = "";
    /** 放置者的名字（纯粹为了界面上好认，空 = 未知） */
    private String owner = "";

    // ---------------------------------------------------------------- 取值

    public int getChannel() {
        return channel;
    }

    public int getFov() {
        return fov;
    }

    /** 云台水平偏移（度），-180..180 */
    public int getYawOffset() {
        return yawOffset;
    }

    /** 云台俯仰偏移（度），-90..90。正数往下压 */
    public int getPitchOffset() {
        return pitchOffset;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getLabel() {
        return label == null ? "" : label;
    }

    public String getOwner() {
        return owner == null ? "" : owner;
    }

    /**
     * 界面上显示的标题：没起名字就退回坐标.
     * <p>
     * ★ 名字不能叫 {@code getDisplayName()} —— {@link TileEntity} 已经有一个
     * {@code ITextComponent getDisplayName()}（给原版"方块名"用的），
     * 同名不同返回值无法重载，javac 直接报
     * "return type String is not compatible with ITextComponent"。
     * 这个坑很隐蔽：写的时候看起来只是"一个普通的 getter"。
     */
    public String labelOrPos() {
        String name = getLabel();
        if (!name.isEmpty()) {
            return name;
        }
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public static int clampChannel(int value) {
        if (value < CHANNEL_MIN) {
            return CHANNEL_MIN;
        }
        return value > CHANNEL_MAX ? CHANNEL_MAX : value;
    }

    public static int clampFov(int value) {
        if (value < FOV_MIN) {
            return FOV_MIN;
        }
        return value > FOV_MAX ? FOV_MAX : value;
    }

    /**
     * ★ 归一化用的是"对 360 取模"，不是 {@code min/max} 夹取：那样连按 ▶ 到 195°
     * 会被夹成 180°、再按还是 180°，看起来像"卡住了转不过去"。
     * 规则和原因见 {@link com.rsaudio.util.Gimbal#clampYaw(int)}。
     */
    public static int clampYaw(int value) {
        return com.rsaudio.util.Gimbal.clampYaw(value);
    }

    /** 夹到 [-90, 90]。必须"夹"而不是"取模"，原因见 {@link com.rsaudio.util.Gimbal#clampPitch(int)} */
    public static int clampPitch(int value) {
        return com.rsaudio.util.Gimbal.clampPitch(value);
    }

    // ---------------------------------------------------------------- 设置

    public void setChannel(int value) {
        int clamped = clampChannel(value);
        if (channel == clamped) {
            return;
        }
        channel = clamped;
        markDirty();
        syncToClient();
    }

    public void setFov(int value) {
        int clamped = clampFov(value);
        if (fov == clamped) {
            return;
        }
        fov = clamped;
        markDirty();
        syncToClient();
    }

    public void setYawOffset(int value) {
        int clamped = clampYaw(value);
        if (yawOffset == clamped) {
            return;
        }
        yawOffset = clamped;
        markDirty();
        syncToClient();
    }

    public void setPitchOffset(int value) {
        int clamped = clampPitch(value);
        if (pitchOffset == clamped) {
            return;
        }
        pitchOffset = clamped;
        markDirty();
        syncToClient();
    }

    /** 云台归零（回到"正对 FACING"的样子） */
    public void resetGimbal() {
        setYawOffset(YAW_DEFAULT);
        setPitchOffset(PITCH_DEFAULT);
    }

    public void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        markDirty();
        syncToClient();
    }

    public void setLabel(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > LABEL_MAX_LENGTH) {
            trimmed = trimmed.substring(0, LABEL_MAX_LENGTH);
        }
        if (trimmed.equals(label)) {
            return;
        }
        label = trimmed;
        markDirty();
        syncToClient();
    }

    public void setOwner(String value) {
        this.owner = value == null ? "" : value;
        markDirty();
    }

    // ---------------------------------------------------------------- 同步

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.channel = compound.hasKey("Channel") ? clampChannel(compound.getInteger("Channel")) : DEFAULT_CHANNEL;
        this.fov = compound.hasKey("Fov") ? clampFov(compound.getInteger("Fov")) : FOV_DEFAULT;
        this.yawOffset = compound.hasKey("Yaw") ? clampYaw(compound.getInteger("Yaw")) : YAW_DEFAULT;
        this.pitchOffset = compound.hasKey("Pitch") ? clampPitch(compound.getInteger("Pitch")) : PITCH_DEFAULT;
        this.enabled = !compound.hasKey("Enabled") || compound.getBoolean("Enabled");
        this.label = compound.getString("Label");
        this.owner = compound.getString("Owner");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Channel", channel);
        compound.setInteger("Fov", fov);
        compound.setInteger("Yaw", yawOffset);
        compound.setInteger("Pitch", pitchOffset);
        compound.setBoolean("Enabled", enabled);
        compound.setString("Label", getLabel());
        compound.setString("Owner", getOwner());
        return compound;
    }

    /**
     * ★ 这几个字段必须写进 updateTag，否则界面上改完频道、客户端那边要等到
     * 区块重新加载才会看到新值（表现为"设了没反应"）。
     * <p>
     * 云台偏移尤其不能漏：画面渲染和"画面里有谁"的判定都在客户端，
     * 漏同步就会出现"服务端记着转了 15°，客户端还按老方向画"——
     * 界面上看着转了、画面纹丝不动。
     */
    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Channel", channel);
        tag.setInteger("Fov", fov);
        tag.setInteger("Yaw", yawOffset);
        tag.setInteger("Pitch", pitchOffset);
        tag.setBoolean("Enabled", enabled);
        tag.setString("Label", getLabel());
        tag.setString("Owner", getOwner());
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.channel = tag.hasKey("Channel") ? clampChannel(tag.getInteger("Channel")) : DEFAULT_CHANNEL;
        this.fov = tag.hasKey("Fov") ? clampFov(tag.getInteger("Fov")) : FOV_DEFAULT;
        this.yawOffset = tag.hasKey("Yaw") ? clampYaw(tag.getInteger("Yaw")) : YAW_DEFAULT;
        this.pitchOffset = tag.hasKey("Pitch") ? clampPitch(tag.getInteger("Pitch")) : PITCH_DEFAULT;
        this.enabled = !tag.hasKey("Enabled") || tag.getBoolean("Enabled");
        this.label = tag.getString("Label");
        this.owner = tag.getString("Owner");
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
        return oldState.getBlock() != newState.getBlock();
    }

    /**
     * 方块被拆 / 区块卸载时，把这块摄像头占用的离屏 FBO 还给显卡。
     * <p>
     * ★ 这里走 {@code proxy} 而不是直接 {@code import} 客户端的类：
     * 方块实体是双端共用的，一旦在<b>源码里</b>直接引用客户端的类，
     * 专用服务器在链接这个类时就会去解析 {@code net.minecraft.client.*}，
     * 轻则日志刷屏、重则直接崩服。代理的基类版本是空实现，
     * 所以服务端这条路径上根本不会碰到客户端类。
     */
    @Override
    public void invalidate() {
        super.invalidate();
        if (world != null && world.isRemote) {
            com.rsaudio.RsAudio.proxy.releaseCameraFeed(pos);
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (world != null && world.isRemote) {
            com.rsaudio.RsAudio.proxy.releaseCameraFeed(pos);
        }
    }
}
