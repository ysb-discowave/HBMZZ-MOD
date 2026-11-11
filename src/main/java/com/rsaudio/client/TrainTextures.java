package com.rsaudio.client;

/**
 * 列车渲染器直接引用的贴图名，集中在这里。
 *
 * <h3>为什么要有这么一个类</h3>
 * {@link TrainRenderer} 用 {@code getAtlasSprite(name)} 拿贴图，而这个调用
 * <b>不在任何模型 JSON 里</b> —— 于是 {@code verify_jar.py} 那套
 * "从模型反推贴图"的检查<b>完全看不见它们</b>。塔塔鲁斯就因此少打包过贴图，
 * 进游戏是紫黑块。现在体检脚本会直接扫 {@code .class} 常量池里的贴图名
 * （见 {@code verify_jar.py} 的 {@code EXPECT_TESR_TEXTURES} 与 §[8]）。
 * <p>
 * 常量池里出现的是<b>字面量</b>，所以这些必须写成编译期内联的形式 ——
 * 不能是运行时算出来的字符串。
 *
 * <h3>★ 每个名字必须带 {@code blocks/} 前缀</h3>
 * 写成 {@code rsaudio:xxx} 会静默返回 {@code missingno}（紫黑块），
 * 不报错、不刷日志。这是本项目踩过两次的坑。
 */
public final class TrainTextures {

    private TrainTextures() {
    }

    /** 车身侧板：深绿涂装 + 车窗带 + 腰线 */
    public static final String BODY_SIDE = "rsaudio:blocks/train_body_side";
    /** 车顶：浅灰弧面 + 通风口 */
    public static final String BODY_ROOF = "rsaudio:blocks/train_body_roof";
    /** 车头正面：驾驶室风挡 + 前照灯 */
    public static final String BODY_NOSE = "rsaudio:blocks/train_body_nose";
    /** 车厢端面：贯通道 + 缓冲器 */
    public static final String BODY_END = "rsaudio:blocks/train_body_end";
    /** 转向架 / 车底机械件 */
    public static final String BOGIE = "rsaudio:blocks/train_bogie";
    /** 车门（开合时单独画的那两片） */
    public static final String DOOR = "rsaudio:blocks/train_door";
}
