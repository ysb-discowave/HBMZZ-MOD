package com.rsaudio.util;

import java.util.Map;
import java.util.TreeMap;

/**
 * 方块状态字符串的纯文本处理（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 结构文件里存的是文本形式的方块状态，形如：
 * <pre>
 *   minecraft:stone
 *   minecraft:stone[variant=granite]
 *   rsaudio:redstone_delayer[facing=north,level=3,powered=false]
 * </pre>
 * 存成文本而不是数字 id / meta，是为了让结构文件<b>跨存档、跨模组版本</b>仍然能看懂
 * （用 meta 存的话，一旦方块注册顺序变了，旧结构就会整体错位）。
 * <p>
 * 这个类只负责"字符串层面"的解析与规范化，不碰 {@code Block}/{@code IBlockState}
 * （那部分在 {@link BlockStates}），这样它可以被单独编译测试，也能在服务端/客户端
 * 之外的地方用。
 * <p>
 * 规范化（{@link #normalize}）会把属性按键名升序重排，这样"同样的状态"永远得到
 * 同样的文本 —— 结构文件的调色板才能靠字符串去重。
 */
public final class BlockStateText {

    private BlockStateText() {
    }

    /** 取名字部分："a:b[c=d]" -> "a:b"；没有属性时原样返回（已 trim） */
    public static String nameOf(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        int bracket = value.indexOf('[');
        if (bracket < 0) {
            return value;
        }
        return value.substring(0, bracket).trim();
    }

    /** 取属性部分："a:b[c=d,e=f]" -> "c=d,e=f"；没有属性时返回空串 */
    public static String propsText(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        int open = value.indexOf('[');
        if (open < 0) {
            return "";
        }
        int close = value.lastIndexOf(']');
        if (close <= open) {
            return "";
        }
        return value.substring(open + 1, close).trim();
    }

    /**
     * 解析属性，返回<b>按键名升序</b>排好的映射。
     * <p>
     * 用 {@link TreeMap} 而不是 HashMap：结构文件要靠这个顺序保证稳定。
     * 空片段、没有 {@code =} 的片段会被忽略（宽容解析，坏文件不至于让加载直接失败）。
     */
    public static Map<String, String> propsOf(String text) {
        Map<String, String> map = new TreeMap<String, String>();
        String props = propsText(text);
        if (props.isEmpty()) {
            return map;
        }
        for (String part : props.split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int eq = piece.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = piece.substring(0, eq).trim();
            String value = piece.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                map.put(key, value);
            }
        }
        return map;
    }

    /** 把属性映射拼回 "k=v,k=v"（调用方保证是升序 Map，或者这里再排一次） */
    public static String join(Map<String, String> props) {
        if (props == null || props.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = props instanceof TreeMap
                ? props : new TreeMap<String, String>(props);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * 规范化：名字 + "[" + 升序属性 + "]"，没有属性就只留名字。
     * <p>
     * {@code "A:B[z=1,a=2]"} -> {@code "A:B[a=2,z=1"]}。
     */
    public static String normalize(String text) {
        String name = nameOf(text);
        String props = join(propsOf(text));
        if (props.isEmpty()) {
            return name;
        }
        return name + "[" + props + "]";
    }

    /** 名字部分是不是一个合法的注册名（{@code 命名空间:路径} 或裸路径） */
    public static boolean isWellFormed(String text) {
        String name = nameOf(text);
        if (name.isEmpty()) {
            return false;
        }
        String path = name;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            String ns = name.substring(0, colon);
            path = name.substring(colon + 1);
            if (!isIdentifierPart(ns)) {
                return false;
            }
        }
        return isIdentifierPart(path) && path.indexOf(':') < 0;
    }

    /** 允许的字符：字母、数字、下划线、点、横线、斜杠（原版注册名就这些） */
    private static boolean isIdentifierPart(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
