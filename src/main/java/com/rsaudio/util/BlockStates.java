package com.rsaudio.util;

import com.google.common.base.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Map;
import java.util.TreeMap;

/**
 * 方块状态 <-> 文本（Minecraft 侧）。
 * <p>
 * 结构文件里存文本形式的方块状态（{@code rsaudio:redstone_delayer[facing=north,level=3,powered=false]}），
 * 而不是数字 id/meta，这样<b>换存档、加删模组之后旧结构仍然看得懂</b>。
 * 纯文本处理在 {@link BlockStateText}（零 MC 依赖，可单测），这里只负责和 {@code Block}/{@code IBlockState} 对接。
 * <p>
 * ★ 1.12.2 的坑：{@code IProperty.parseValue(String)} 返回的是 <b>Guava Optional</b>，
 * <b>不抛异常</b>（不像 1.13+ 的 {@code parseValue}）。所以取值非法时要自己判断 Optional 是否为空，
 * 否则会把 IllegalStateException 抛到粘贴循环里。
 */
public final class BlockStates {

    private BlockStates() {
    }

    /** 编码成 {@code 注册名[属性名=值, ...]}（属性按键名升序，便于去重） */
    public static String encode(IBlockState state) {
        if (state == null) {
            return "minecraft:air";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.valueOf(state.getBlock().getRegistryName()));
        Map<String, String> props = new TreeMap<String, String>();
        // ★ 1.12.2 的方法名是 getPropertyKeys()（不是 1.13+ 的 getProperties()；
        //   1.12.2 的 getProperties() 返回的是 ImmutableMap<属性, 取值>，语义不一样）
        for (IProperty<?> property : state.getPropertyKeys()) {
            props.put(property.getName(), valueName(state, property));
        }
        String text = BlockStateText.join(props);
        if (!text.isEmpty()) {
            sb.append('[').append(text).append(']');
        }
        return sb.toString();
    }

    /**
     * 解析文本。找不到方块返回 {@code null}；
     * 属性名/取值对不上时<b>跳过该属性</b>（保留其余属性），不至于因为一个坏属性丢掉整块。
     */
    public static IBlockState decode(String text) {
        String name = BlockStateText.nameOf(text);
        if (name.isEmpty()) {
            return null;
        }
        Block block = Block.getBlockFromName(name);
        if (block == null) {
            return null;
        }
        IBlockState state = block.getDefaultState();
        for (Map.Entry<String, String> entry : BlockStateText.propsOf(text).entrySet()) {
            IProperty<?> property = block.getBlockState().getProperty(entry.getKey());
            if (property == null) {
                continue;
            }
            state = apply(state, property, entry.getValue());
        }
        return state;
    }

    /** 捕获通配类型用的小跳板（{@code IProperty<?>} 不能直接喂给泛型方法） */
    private static String valueName(IBlockState state, IProperty<?> property) {
        return valueNameTyped(state, property);
    }

    private static <T extends Comparable<T>> String valueNameTyped(IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }

    private static IBlockState apply(IBlockState state, IProperty<?> property, String valueText) {
        return applyTyped(state, property, valueText);
    }

    private static <T extends Comparable<T>> IBlockState applyTyped(IBlockState state, IProperty<T> property,
                                                                    String valueText) {
        Optional<T> parsed = property.parseValue(valueText);
        if (!parsed.isPresent()) {
            return state;
        }
        return state.withProperty(property, parsed.get());
    }
}
