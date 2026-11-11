package com.rsaudio;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置目录管理。
 * <p>
 * 音频文件放在 {@code config/rsaudio/audio/} 下，整合包导出时只要带上 config 文件夹即可。
 */
public class Config {

    private static File configDir = null;

    public static void init(File modConfigDirectory) {
        configDir = new File(modConfigDirectory, RsAudio.MODID);
        getAudioDir();
        getStructuresDir();
        getTtsDir();
        writeReadme();
    }

    public static File getConfigDir() {
        if (configDir == null) {
            configDir = new File("config", RsAudio.MODID).getAbsoluteFile();
        }
        return configDir;
    }

    /** 音频文件目录：config/rsaudio/audio */
    public static File getAudioDir() {
        File dir = new File(getConfigDir(), "audio");
        if (!dir.exists() && !dir.mkdirs()) {
            RsAudio.warn("无法创建音频目录: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 结构文件目录：config/rsaudio/structures
     * <p>
     * 结构魔杖保存出来的 {@code .nbt} 放在这里：跨存档可用、可以直接拷给别人。
     */
    public static File getStructuresDir() {
        File dir = new File(getConfigDir(), "structures");
        if (!dir.exists() && !dir.mkdirs()) {
            RsAudio.warn("无法创建结构目录: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * TTS 引擎与语音模型目录：{@code config/rsaudio/tts}
     * <p>
     * 里面放三样东西：引擎（{@code piper.exe} 或 {@code engine.txt}）、
     * 语音模型（{@code *.onnx} + 同名的 {@code *.onnx.json}）、
     * 以及自动生成的 {@code cache/} 合成缓存。
     * <p>
     * 放在 config 下而不是模组 jar 里，是因为语音模型有几十 MB —— 塞进 jar
     * 会让每次构建和分发都背上这个体积，而玩家只需要装一次。
     */
    public static File getTtsDir() {
        File dir = new File(getConfigDir(), "tts");
        if (!dir.exists() && !dir.mkdirs()) {
            RsAudio.warn("无法创建 TTS 目录: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static void writeReadme() {
        File readme = new File(getConfigDir(), "如何添加音乐.txt");
        if (readme.exists()) {
            return;
        }
        String text = ""
                + "红石音频 (Redstone Audio) 使用说明\r\n"
                + "=================================\r\n\r\n"
                + "1. 把你的 .ogg 音乐文件放进本目录下的 audio 文件夹：\r\n"
                + "     " + new File(getAudioDir(), "").getAbsolutePath() + "\r\n\r\n"
                + "   * 必须是 ogg 格式（后缀 .ogg），其它格式请先用转换软件转成 ogg。\r\n"
                + "   * 中文文件名、空格、括号都可以，模组会自动处理。\r\n"
                + "   * 导出整合包时把这个 config 文件夹一起打包即可，音乐不会丢。\r\n\r\n"
                + "2. 游戏里放好「音频播放器」方块，右键打开界面，选择要播放的音乐。\r\n\r\n"
                + "3. 给音频播放器通上红石信号（拉杆 / 红石粉 / 红石延迟器）即可播放；\r\n"
                + "   红石信号消失时音乐停止。\r\n\r\n"
                + "4. 新加了音乐文件后，在界面里点「重新扫描」按钮（或按 F3+T 重载资源）即可生效。\r\n\r\n"
                + "5. 「红石延迟器」用法和原版红石中继器一致：\r\n"
                + "   背面接收信号，延迟 N 刻后从正面输出；右键 = 延迟 +1 刻，潜行+右键 = 延迟 -1 刻。\r\n"
                + "   放置时正面(输出面)朝向你看向的方向。\r\n\r\n"
                + "6. 「结构魔杖」：手持<b>木斧</b>就是魔杖\r\n"
                + "   左键点方块 = 选第一个角；右键点方块 = 选第二个角；\r\n"
                + "   潜行 + 右键 = 打开魔杖界面（填结构名、保存选区、清空选区）。\r\n"
                + "   保存出来的文件在：" + new File(getStructuresDir(), "").getAbsolutePath() + "\r\n"
                + "   * 拿着木斧时左键不会挖掉方块（这就是魔杖的用法），换别的工具即可正常挖掘。\r\n"
                + "   * 结构存的是「注册名+属性」文本，换存档、加删模组之后旧结构仍然能看懂。\r\n\r\n"
                + "7. 「结构加载器」：右键打开界面，填结构名、调相对偏移，点「加载」\r\n"
                + "   就会把结构贴到加载器上方/旁边；给方块通红石信号（上升沿）也会加载一次。\r\n"
                + "   * 粘贴时先把目标长方体清成空气，再放方块 —— 和原样一致。\r\n"
                + "   * 方块实体（箱子里的东西、机器设置）会一起复制。\r\n"
                + "   * 大结构会分 tick 慢慢贴，不会一下子卡住服务器。\r\n\r\n"
                + "8. 「自动化红石信号输出计算机」：右键打开界面写程序（极简汇编），点「运行」\r\n"
                + "   常用指令：MOV/ADD/SUB/AND/OR/XOR/SHL/SHR/CMP、JMP/JE/JNE/JG/JL、\r\n"
                + "             SET 面,值 / CLR / WRI 面 / WAIT 刻 / MSG 文字 / TIME r / HALT\r\n"
                + "   六个面各自独立输出 0~15；计算机默认带一个「卖葱信号发生器」示例程序。\r\n\r\n"
                + "9. 「警报灯」：6 种颜色（红橙黄绿蓝紫），通红石信号就亮/爆闪。\r\n"
                + "   右键打开界面调爆闪频率、开关蜂鸣；潜行+右键直接快速开关蜂鸣。\r\n\r\n"
                + "10.「防核门」：放下方块后，手持木斧<b>潜行+右键</b>打开设置界面\r\n"
                + "   设置宽度/高度/朝向，点「建造」生成整片门板；红石信号开门，抗爆。\r\n"
                + "   开关门<b>只能用木斧</b>：手持木斧右键 = 开/关门（门板任意一格都行，空手不开门）。\r\n\r\n"
                + "11.「广播器」：右键打开界面，填三样东西 ——\r\n"
                + "     ・弹窗标题 / 弹窗内容：会在附近所有玩家的<b>右下角</b>弹出提示框；\r\n"
                + "     ・朗读文本：用 TTS 念出来（需要先装好语音引擎，见下面第 12 条）。\r\n"
                + "   另外可以调「范围」（多少格内的玩家能收到）和「弹窗停留时长」。\r\n"
                + "   通上红石信号（上升沿）也会自动广播一次，可以做成自动播报。\r\n\r\n"
                + "12.「广播器」的语音（TTS）怎么装：\r\n"
                + "   语音目录：" + new File(getTtsDir(), "").getAbsolutePath() + "\r\n"
                + "   用的是开源免费的 Piper（MIT 协议），只输出 wav，不需要额外装转码工具。\r\n"
                + "   装好之后把「引擎」和「语音模型」都放到上面这个目录里（两个都必备）：\r\n"
                + "     ・引擎：官方独立版的 piper.exe，直接丢进这个目录即可；\r\n"
                + "       如果用的是 pip install piper-tts，本目录里新建一个 engine.txt（UTF-8），\r\n"
                + "       每行写一个参数、带空格的路径不用加引号，例如：\r\n"
                + "           C:\\Python\\python.exe\r\n"
                + "           -m\r\n"
                + "           piper\r\n"
                + "       以 # 开头的行是注释。\r\n"
                + "     ・模型：zh_CN-huayan-medium.onnx 和 zh_CN-huayan-medium.onnx.json 两个文件，\r\n"
                + "       一起放进来（或放进 models/ 子目录）。缺了同名的 .json 引擎起不来。\r\n"
                + "   * 没装引擎也不会报错：弹窗照常显示，只是没有声音。\r\n"
                + "   * 界面上有个「本机试听」按钮，可以在游戏里直接验证你本机的引擎装好没有。\r\n"
                + "   * 合成一句话要十几秒（起进程 + 载入 60MB 模型是固定开销），所以：\r\n"
                + "     朗读文本尽量写短句（100 字以内）；而且一保存设置就会在后台先合成一遍，\r\n"
                + "     等你真的按下「广播」或红石触发时，通常已经缓存好了、几乎是即时的。\r\n"
                + "   * 想先在游戏外面体检一下，可以跑 rsaudio/tools/tts_selftest.py：\r\n"
                + "     它按游戏里同一套规则找引擎和模型，并真的合成一句样例检验成败。\r\n"
                + "   * 合成结果会缓存到 " + new File(getTtsDir(), "cache").getAbsolutePath() + "，\r\n"
                + "     同一句话第二次播是零成本的。缓存满了会自动淘汰最旧的。\r\n";
        try {
            Files.write(readme.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            RsAudio.warn("写入说明文件失败: " + e);
        }
    }
}
