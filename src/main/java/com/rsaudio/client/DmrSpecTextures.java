package com.rsaudio.client;

/**
 * TESR 直接引用的贴图名，集中在这里。
 *
 * <h3>为什么要有这么一个类</h3>
 * TESR 用 {@code getAtlasSprite(name)} 拿贴图，而这个调用<b>不在任何模型 JSON 里</b> ——
 * 于是 {@code verify_jar.py} 那套"从模型反推贴图"的检查<b>完全看不见它们</b>。
 * 曾经塔塔鲁斯就因此少打包过贴图，进游戏是紫黑块。
 * <p>
 * 现在体检脚本会直接扫 {@code .class} 常量池里的贴图名（见 {@code verify_jar.py} 的
 * {@code EXPECT_TESR_TEXTURES} 与 §[7]/§[8]）。但常量池里出现的是<b>字面量</b>，
 * 所以这里必须写成字面量拼接被编译器内联的形式 —— 不能是运行时算出来的字符串。
 * <p>
 * ★ 每个名字<b>必须带 {@code blocks/} 前缀</b>。写成 {@code rsaudio:xxx}
 * 会静默返回 {@code missingno}（紫黑块），不报错、不刷日志。
 */
public final class DmrSpecTextures {

    private DmrSpecTextures() {
    }

    /** 堆芯等离子体的表面颗粒贴图 */
    public static final String CORE = "rsaudio:blocks/dmr_core_plasma";
    /** 腔体内壁贴图（渲染器画约束环背后的暗背景用） */
    public static final String CHAMBER = "rsaudio:blocks/dmr_chamber_wall";
}
