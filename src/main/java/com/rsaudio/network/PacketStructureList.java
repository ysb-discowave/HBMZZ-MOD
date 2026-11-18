package com.rsaudio.network;

import com.rsaudio.structure.StructureStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;

/**
 * 服务端 -> 客户端：可用的结构名清单（给加载器界面显示）。
 */
public class PacketStructureList implements IMessage {

    /** 清单上限：防止 config 里堆了几千个文件时把包撑爆 */
    public static final int MAX_ENTRIES = 200;

    private String[] names = new String[0];

    public PacketStructureList() {
    }

    public PacketStructureList(List<String> source) {
        if (source == null) {
            return;
        }
        int count = Math.min(source.size(), MAX_ENTRIES);
        this.names = new String[count];
        for (int i = 0; i < count; i++) {
            String entry = source.get(i);
            this.names[i] = entry == null ? "" : entry;
        }
    }

    public String[] getNames() {
        return names;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = Math.max(0, Math.min(buf.readInt(), MAX_ENTRIES));
        this.names = new String[count];
        for (int i = 0; i < count; i++) {
            this.names[i] = ByteBufUtils.readUTF8String(buf);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(names.length);
        for (String name : names) {
            ByteBufUtils.writeUTF8String(buf, name);
        }
    }

    public static class Handler implements IMessageHandler<PacketStructureList, IMessage> {

        @Override
        public IMessage onMessage(final PacketStructureList message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    com.rsaudio.client.ClientStructureList.apply(message.names);
                }
            });
            return null;
        }
    }

    /** 便捷方法：清单是不是空的（界面据此显示"还没保存过结构"） */
    public boolean isEmpty() {
        return names.length == 0;
    }

    /** 服务端本地生成一份清单（单机时也走同一条路，保证两端行为一致） */
    public static PacketStructureList fromDisk() {
        return new PacketStructureList(StructureStorage.listNames());
    }
}
