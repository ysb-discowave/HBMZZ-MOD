package com.rsaudio.entity;

import com.rsaudio.RsAudio;
import com.rsaudio.truck.TruckCore;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import io.netty.buffer.ByteBuf;

/**
 * 货运卡车实体 —— 本模组的第二个自定义实体（第一个是 {@link EntityTrain}）。
 *
 * <h3>★ 和列车同一套分工：实体只是"世界 ↔ 内核"的胶水</h3>
 * 行驶状态机在零依赖内核 {@link TruckCore} 里（{@code tools/check_truck_core.py}
 * 拿真 javac 单测的就是它）。这个类只做四件事：
 * <ol>
 *   <li><b>喂障碍</b>：每 tick 看车头前 / 车尾后各两格有没有实心方块，
 *       {@code core.setBlocked(...)}；</li>
 *   <li><b>喂 tick</b>：{@code core.tick()} 返回本 tick 位移，沿方向搬进 posX/posZ；</li>
 *   <li><b>报事件</b>：内核吐出的事件键（start / stop / reverse / blocked / still_blocked）
 *       转成玩家看得懂的中文提示；</li>
 *   <li><b>存读档</b>：{@code core.saveState()} 那份 int[3] 原样塞进 NBT。</li>
 * </ol>
 *
 * <h3>★ 位置/朝向同步走<b>原版实体同步</b>，不自己发包</h3>
 * 列车车厢是"车头拖着的纯渲染附件"，所以要自定义快照包（PacketTrainSync）。
 * 卡车是<b>单车</b>：整台车就是一个实体，原版 EntityTrackerMP 每 3 tick 推一次
 * 位置+朝向、客户端按 partialTicks 插值 —— 够了。
 * 唯一不走原版的是"顶灯亮不亮"（运行状态）：它不是位置，DataWatcher 一个字节
 * 正好（只在变化的那一次发包，不像每 tick 的读数）。
 *
 * <h3>★ 交互</h3>
 * 右键 = 点火 / 熄火；<b>潜行</b>右键 = 调头（180°）。
 * 都不让玩家上车 —— 这是台机器，不是坐骑。
 */
public class EntityFreightTruck extends Entity implements IEntityAdditionalSpawnData {

    /**
     * DataWatcher 槽位：0=熄火 1=行驶。客户端渲染器（{@code FreightTruckRenderer}）
     * 读它画顶灯。
     * <p>
     * ★ 本环境的 datasync 是新式 API：字段是 {@code dataManager}（不是 dataWatcher），
     *   注册/读/写都走 {@link DataParameter}（不是老的 int 下标 + setByte/getByte），
     *   字节序列化器取自 {@link DataSerializers#BYTE}。写成旧 API 会直接编译失败。
     */
    public static final DataParameter<Byte> STATE = DataSerializers.BYTE.createKey(0);

    /** 内核。服务端权威；客户端那份只用于渲染（不在客户端 tick） */
    private final TruckCore core = new TruckCore();

    /** 第一次进世界时是否完成初始化 */
    private boolean spawnInit = false;

    /**
     * 这台车<b>为什么</b>消失。{@code null} = 没走任何已知移除路径（本身就是线索）。
     * 和 EntityTrain 的 removalReason 同一条纪律。
     */
    private String removalReason = null;

    /** 非玩家伤害只报一次（着火时每 20 刻打一次，不设闸门就是刷屏） */
    private boolean envHitLogged = false;

    public EntityFreightTruck(World worldIn) {
        super(worldIn);
        // 碰撞箱跟车体几何对齐：车宽 1.4、车高 1.55（见 FreightTruckRenderer 的几何）。
        // ★ 车长 2.3 盖不进正方形碰撞箱（setSize 只有宽/高两个参数）——
        //   纵向超出由 getRenderBoundingBox 兜（视锥剔除按它做），
        //   碰撞箱只负责"横着别穿进车里"。
        setSize(1.4F, 1.55F);
        // 故意不写 isImmuneToFire 之类标志位：免疫收在 attackEntityFrom 一个判据里
        // （见那边的长注释 —— 和列车是同一课）。
        this.stepHeight = 0.0F;
        this.noClip = false;
    }

    @Override
    protected void entityInit() {
        this.dataManager.register(STATE, Byte.valueOf((byte) 0));
    }

    // ================================================================ tick

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) {
            // 客户端不跑内核：位置/朝向由原版实体同步推过来，渲染器插值。
            // 和 EntityTrain 同一个理由：两边各跑一份状态机只会逐渐分叉（车抽搐）。
            this.prevPosX = this.posX;
            this.prevPosY = this.posY;
            this.prevPosZ = this.posZ;
            return;
        }

        if (!spawnInit) {
            spawnInit = true;
        }

        // ---- 服务端：喂障碍 -> 喂 tick -> 搬坐标 -> 同步顶灯 -> 报事件
        core.setBlocked(isBlockedAhead());
        double dist = core.tick();
        if (dist > 0.0D) {
            double dx = TruckCore.dirX(core.getDirection()) * dist;
            double dz = TruckCore.dirZ(core.getDirection()) * dist;
            this.posX += dx;
            this.posZ += dz;
        }
        this.rotationYaw = TruckCore.yawOfDir(core.getDirection());
        this.dataManager.set(STATE,
                (byte) (core.getState() == TruckCore.STATE_RUNNING ? 1 : 0));
        String ev = core.drainEvent();
        if (ev != null) {
            onEvent(ev);
        }
    }

    /**
     * 前方（和后方，倒车时它就是"前方"）有没有实心方块。
     * <p>
     * ★ 四个采样点：中心线前后各 0.9 / 1.3 格（车体纵向 -1.15..+1.15，
     * 四个点正好罩住整台车的头尾）。倒车时"前面"变成车尾，
     * 只查正向会在倒车时"穿墙"。
     * ★ 未加载的区块不算障碍：读方块会强制加载区块，不能在实体 tick 里做。
     */
    private boolean isBlockedAhead() {
        double dx = TruckCore.dirX(core.getDirection());
        double dz = TruckCore.dirZ(core.getDirection());
        for (double f : new double[]{0.9D, 1.3D, -0.9D, -1.3D}) {
            int bx = (int) Math.floor(posX + dx * f);
            int by = (int) Math.floor(posY + 0.5D);
            int bz = (int) Math.floor(posZ + dz * f);
            BlockPos bpos = new BlockPos(bx, by, bz);
            // ★ isBlockLoaded 只收 BlockPos（没有 int,int,int 重载）；未加载的格不算障碍
            if (!world.isBlockLoaded(bpos)) {
                continue;
            }
            if (world.getBlockState(bpos)
                    .getMaterial().blocksMovement()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 内核事件的出口。
     * <p>
     * ★ 分工：start / stop / reverse / still_blocked 是"某个人刚按下的按钮"，
     * 那个人在 {@link #processInitialInteract} 里已经拿到聊天回执了，这里只留服务端日志；
     * blocked 是"车自己停下来"（没有按钮按的人），才向附近的玩家广播 ——
     * 不广播的话玩家只会看到"车不动了"，猜不到是前面有墙。
     */
    private void onEvent(String key) {
        if ("blocked".equals(key)) {
            notifyNearby(I18n.translateToLocal("chat.rsaudio.truck.blocked"));
        }
        RsAudio.log("[卡车] 事件 %s @ %s", key, where());
    }

    // ================================================================ 交互

    /**
     * 右键 = 点火 / 熄火；潜行右键 = 调头。
     */
    @Override
    public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) {
            return true;
        }
        if (player.isSneaking()) {
            core.reverse();
            player.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocal("chat.rsaudio.truck.reverse")), true);
            RsAudio.log("[卡车] %s 把 %s 的货运卡车调头（现朝 %d）",
                    player.getName(), where(), Integer.valueOf(core.getDirection()));
            return true;
        }
        if (core.getState() == TruckCore.STATE_IDLE) {
            // ★ 现场重查一次障碍：内核的 blocked 是上一个 tick 的，
            //   玩家这一秒可能刚把墙拆了（或刚砌上）—— 用旧值会给出错误回执
            boolean blockedNow = isBlockedAhead();
            core.setBlocked(blockedNow);
            core.start();
            String key = core.drainEvent();   // "start" 或 "still_blocked"
            player.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocal("chat.rsaudio.truck." + key)), true);
            RsAudio.log("[卡车] %s 在 %s 点火（%s）", player.getName(), where(), key);
        } else {
            core.stop();
            player.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocal("chat.rsaudio.truck.stop")), true);
            RsAudio.log("[卡车] %s 在 %s 让货运卡车熄火", player.getName(), where());
        }
        return true;
    }

    // ================================================================ 移动 / 伤害

    @Override
    public void move(MoverType type, double x, double y, double z) {
        // 什么都不做：位置完全由内核决定（和 EntityTrain 同一纪律）
    }

    /** 不会被推走、不会被水流冲 —— 它是一台车 */
    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    /**
     * 不载客：canPassengerSteer 与列车同理 ——
     * 返回 true 会让原版把玩家视线当方向盘，下一 tick 内核又拧回来，高频抖动。
     */
    @Override
    public boolean canPassengerSteer() {
        return false;
    }

    /**
     * 被攻击 —— <b>只有玩家的攻击</b>能拆掉这台车。
     * <p>
     * ★★★ 和 EntityTrain 的 attackEntityFrom 是同一个判据、同一课：
     * 原版自己就会打实体（着火每 20 刻 1 点、岩浆 4 点、附近任何爆炸），
     * "谁打我我都 setDead()" 的后果是"一台没被碰过的车在几秒内静默消失"，
     * 而且日志里一个字都没有。所以：非玩家伤害一律拒绝并<b>留痕</b>。
     */
    @Override
    public boolean attackEntityFrom(net.minecraft.util.DamageSource source, float amount) {
        if (world.isRemote || isEntityInvulnerable(source)) {
            return false;
        }
        EntityPlayer attacker = null;
        Entity trueSrc = source.getTrueSource();
        if (trueSrc instanceof EntityPlayer) {
            attacker = (EntityPlayer) trueSrc;
        } else if (trueSrc == null && source.getImmediateSource() instanceof EntityPlayer) {
            attacker = (EntityPlayer) source.getImmediateSource();
        }
        if (attacker == null) {
            if (!envHitLogged) {
                envHitLogged = true;
                RsAudio.log("[卡车] 吃到非玩家伤害（%s，%.1f 点）@ %s —— 已拒绝"
                                + "（只有玩家能拆车，机器不吃环境伤害）",
                        source.getDamageType(), Float.valueOf(amount), where());
            }
            return false;
        }
        removalReason = "被玩家 " + attacker.getName() + " 拆除";
        RsAudio.log("[卡车] 被玩家 %s 拆除 @ %s（伤害源 %s，已跑 %d 刻）",
                attacker.getName(), where(), source.getDamageType(),
                Integer.valueOf(ticksExisted));
        notifyNearby(I18n.translateToLocal("chat.rsaudio.truck.removed"));
        setDead();
        return true;
    }

    @Override
    public void setDead() {
        if (!world.isRemote && !isDead) {
            // ★ 消失必须留痕且能指到成因。removalReason == null 意味着走了
            //   预期之外的移除路径 —— 那句话本身就是线索（/kill、别的模组…）。
            RsAudio.log("[卡车] 消失 @ %s（%s；已跑 %d 刻）", where(),
                    removalReason == null ? "★ 没走任何已知移除路径（可疑）" : removalReason,
                    Integer.valueOf(ticksExisted));
            if (removalReason == null) {
                notifyNearby(I18n.translateToLocal("chat.rsaudio.truck.removed"));
            }
        }
        super.setDead();
    }

    /**
     * 掉出世界（posY < -64 时原版调它，默认 setDead）。
     * 对卡车这是"不该发生"：y 从不被改（它只在 XZ 平面走）。真发生了说明坐标坏了
     * —— 按原版静默删掉的话连"坏坐标是多少"都没了，所以拦下并拉回 y=0。
     */
    @Override
    protected void outOfWorld() {
        RsAudio.log("[卡车] 掉出世界（坐标 %.3f,%.3f,%.3f）—— 已拦下，拉回 y=0",
                Double.valueOf(posX), Double.valueOf(posY), Double.valueOf(posZ));
        setPosition(posX, 0.0D, posZ);
    }

    /** 日志用的坐标串。★ 项目约定：日志必带坐标 */
    private String where() {
        return String.format("%.1f,%.1f,%.1f", Double.valueOf(posX), Double.valueOf(posY),
                Double.valueOf(posZ));
    }

    /**
     * 向附近玩家广播一句提示（只广播，不发声音）。
     * 24 格 = 点火/调头那个玩家自己 + 附近能看见这台车的人（和列车的 64 格
     * 广播圈不同：列车是"全园区通报"，卡车只是台停在场院里的车）。
     */
    private void notifyNearby(String text) {
        java.util.List<EntityPlayer> players = world.playerEntities;
        for (EntityPlayer pl : players) {
            if (pl.getDistanceSq(this) <= 24.0D * 24.0D) {
                pl.sendMessage(new TextComponentString(text));
            }
        }
    }

    // ================================================================ 渲染包围盒

    /**
     * ★★★ 必须罩住<b>整台车</b>，不能只用碰撞箱那 1.4×1.55。
     * 车长 2.3 格（车头+货台），碰撞箱只有 1.4 —— 视锥剔除是整实体一起的，
     * 盒子只罩碰撞箱的话，"车头出视锥 → 整车被剔除"（列车车厢那次是同一个坑：
     * 症状被描述成"根本没有车厢"）。
     * <p>
     * 车头朝向 = (-sin yaw, cos yaw)（和 TrainRenderer 的 -yaw 同一套符号），
     * 车头在 +1.3 格处、车尾在 -1.3 格处，头尾各并一个盒子再 grow 半格。
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        AxisAlignedBB box = super.getRenderBoundingBox();
        double rad = Math.toRadians(this.rotationYaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        return box.union(box.offset(fx * 1.3D, 0.0D, fz * 1.3D))
                .union(box.offset(-fx * 1.3D, 0.0D, -fz * 1.3D)).grow(0.5D);
    }

    // ================================================================ NBT

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        if (compound.hasKey("TruckState")) {
            core.loadState(compound.getIntArray("TruckState"));
            spawnInit = true;     // 读档之后不要当"新车"处理
        }
        // 障碍信息不在 NBT 里 —— 它是<b>世界的</b>状态，每 tick 现查就有。
        // 存进去会出现"存档里的障碍和现在的世界对不上"（和列车轨道图同一条纪律）。
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setIntArray("TruckState", core.saveState());
    }

    // ================================================================ 生成数据

    /**
     * ★ 实现 IEntityAdditionalSpawnData 不是为了多发数据，而是为了走那条
     * "实体出现在客户端之前就把位置定好"的生成路径 —— 不实现的话新车会先
     * 闪现在 (0,0,0) 再跳过来（EntityTrain 同一条注释）。
     */
    @Override
    public void writeSpawnData(ByteBuf buffer) {
        int[] st = core.saveState();
        buffer.writeInt(st.length);
        for (int i = 0; i < st.length; i++) {
            buffer.writeInt(st[i]);
        }
    }

    @Override
    public void readSpawnData(ByteBuf additionalData) {
        int len = additionalData.readInt();
        if (len < 0 || len > TruckCore.STATE_SLOTS) {
            return;
        }
        int[] st = new int[len];
        for (int i = 0; i < len; i++) {
            st[i] = additionalData.readInt();
        }
        core.loadState(st);
    }

    // ================================================================ 给外部用

    public TruckCore getCore() {
        return core;
    }
}
