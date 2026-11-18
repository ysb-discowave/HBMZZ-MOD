package com.rsaudio.network;

import com.rsaudio.client.ClientTrainState;
import com.rsaudio.train.TrainSpec;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：列车的读数快照。
 *
 * <h3>★ 为什么不走 DataWatcher</h3>
 * DataWatcher 的每个字段都有独立的 dirty 标记，<b>每 tick</b> 都会把脏字段发一遍。
 * 列车要同步的是"速度 / 相位 / 乘客数 / 故障"这一整组每秒都在变的量 ——
 * 用 DataWatcher 就等于每 tick 发好几个包。走自己的包可以按需节流
 * （实体侧 {@code SYNC_INTERVAL = 20}，一秒一次），人眼完全看不出区别。
 *
 * <h3>★ 为什么不带位置</h3>
 * 位置和朝向由实体自己的同步负责（服务端 {@code setPosition} +
 * {@code velocityChanged} 就会触发原版的实体位置包）。
 * 这个包<b>只带实体同步不带的东西</b>。两边各带一份的话，
 * 车会在"实体包的位置"和"这个包的位置"之间来回跳。
 *
 * <h3>★ 长度校验</h3>
 * {@code saveState()} 的槽位数由 {@link TrainSpec#STATE_SLOTS} 决定，
 * 且**会随版本变**。所以包体自带长度、客户端按长度读并做上界校验 ——
 * 老客户端连新服务端时最多读到一份被拒绝的状态，而不是解码崩溃。
 */
public class PacketTrainSync implements IMessage {

    private int entityId;
    private int[] state;

    public PacketTrainSync() {
    }

    public PacketTrainSync(int entityId, int[] state) {
        this.entityId = entityId;
        this.state = state;
    }

    public int getEntityId() {
        return entityId;
    }

    public int[] getState() {
        return state;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        int len = buf.readInt();
        // ★ 上界校验必须在分配数组之前做：一个坏包里的 len 可以让客户端 OOM
        if (len < 0 || len > TrainSpec.STATE_SLOTS) {
            this.state = null;
            return;
        }
        this.state = new int[len];
        for (int i = 0; i < len; i++) {
            this.state[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        if (state == null) {
            buf.writeInt(0);
            return;
        }
        buf.writeInt(state.length);
        for (int i = 0; i < state.length; i++) {
            buf.writeInt(state[i]);
        }
    }

    public static class Handler implements IMessageHandler<PacketTrainSync, IMessage> {

        /**
         * ★★★ 每台车<b>第一次</b>收到读数包时打一行日志（之后不再打）。
         * <p>
         * 为什么必须留这一行：车厢是<b>客户端</b>按读数包里的 {@code cars} 画出来的，
         * 包没到就一节都不画，而服务端那边一切正常、日志干净 —— 玩家能给出的描述只有
         * "没有车厢"。这不是靠读代码能分辨的故障：可能是包没发、可能是客户端没有这个实体、
         * 也可能是包到了但内核槽 12 真是 0。这一行配上
         * {@code TrainRenderer.diagnose()} 的 ②③ 两条出口，三种情况就各自有主了。
         * <p>
         * ★ 用"收到也算一次"记进 {@code SEEN}：只有真的收到过才会有一条记录，
         * 所以"日志里没有这一行"本身就等于"包没到"（不是"还没到时候"）。
         */
        private static final java.util.Set<Integer> SEEN = new java.util.HashSet<Integer>();

        @Override
        public IMessage onMessage(final PacketTrainSync message, MessageContext ctx) {
            // ★ 网络线程不能碰世界 —— 实体查找必须回主线程做
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = Minecraft.getMinecraft().world;
                    if (world == null || message.state == null) {
                        return;
                    }
                    Entity e = world.getEntityByID(message.entityId);
                    if (SEEN.add(Integer.valueOf(message.entityId))) {
                        com.rsaudio.RsAudio.log("[列车] 客户端收到读数包 #%d：cars=%d phase=%d"
                                        + "（客户端%s这个实体，%s）",
                                Integer.valueOf(message.entityId),
                                Integer.valueOf(ClientTrainState.cars(message.state)),
                                Integer.valueOf(ClientTrainState.phase(message.state)),
                                e == null ? "没找到" : "找到了",
                                e instanceof com.rsaudio.entity.EntityTrain ? "类型正确"
                                        : "类型不对，读数不会生效");
                    }
                    if (!(e instanceof com.rsaudio.entity.EntityTrain)) {
                        return;
                    }
                    // 客户端只把状态存起来给渲染器读，**不**跑内核
                    // （两边各跑一份内核会因扫描到的世界不同而分叉，表现为车在屏幕上抽搐）
                    ClientTrainState.put(message.entityId, message.state);
                }
            });
            return null;
        }
    }
}
