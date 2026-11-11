package com.rsaudio.client;

/**
 * 客户端侧记住的"服务端有哪些结构文件"清单。
 * <p>
 * 结构文件在服务端的 config 目录里，客户端看不到（联机时更在别的机器上），
 * 所以加载器界面要显示清单就得靠服务端发过来。
 */
public final class ClientStructureList {

    private static String[] names = new String[0];

    private ClientStructureList() {
    }

    public static void apply(String[] value) {
        names = value == null ? new String[0] : value;
    }

    public static String[] get() {
        return names;
    }

    public static boolean isEmpty() {
        return names.length == 0;
    }
}
