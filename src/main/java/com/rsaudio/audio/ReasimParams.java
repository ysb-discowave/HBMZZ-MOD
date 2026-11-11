/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.rsaudio.audio;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * reasim（扬声器真实度模拟）的一组参数。
 * <p>
 * 这个类刻意<b>不引用任何 Minecraft 类型</b>：它同时被方块实体（服务端权威）、
 * 网络包、图形界面的滑条和客户端的 DSP 线程使用，而这四者分属不同线程。
 * 全部字段都是基本类型 + 一个 {@link #copy()}，跨线程只传副本，谁也不持有别人的状态。
 * <p>
 * <b>为什么参数存在方块实体上而不是客户端本地：</b>播放器是"给一片区域放音"的机器，
 * 音色属于这台机器，不属于某个听的人。挂在方块实体上，多人服务器里所有人听到的
 * 是同一种破喇叭，而且重进世界还在。
 */
public final class ReasimParams {

    // ---- 调节范围（界面滑条、网络包校验、DSP 内部钳制都用这同一组常量）----

    /** 音量下限：完全静音（但电流声仍然会漏一点，真实的功放就是这样） */
    public static final float VOLUME_MIN = 0.0F;
    /** 音量上限 200% —— 再高只是在模拟削波，交给失真滑条去做 */
    public static final float VOLUME_MAX = 2.0F;
    public static final float VOLUME_DEFAULT = 1.0F;

    /** 回声湿声比例下限 */
    public static final float ECHO_MIN = 0.0F;
    /** 回声湿声比例上限：1.0 时混响几乎盖过原声，再高就听不清词了 */
    public static final float ECHO_MAX = 1.0F;
    public static final float ECHO_DEFAULT = 0.30F;

    public static final float DISTORTION_MIN = 0.0F;
    public static final float DISTORTION_MAX = 1.0F;
    public static final float DISTORTION_DEFAULT = 0.0F;

    /** 电流声（50Hz 工频哼声 + 谐波嗡鸣） */
    public static final float HISS_MIN = 0.0F;
    public static final float HISS_MAX = 1.0F;
    public static final float HISS_DEFAULT = 0.18F;

    /** 沙沙声（带限白噪 + 稀疏爆裂），也就是收音机没台时的那个声音 */
    public static final float STATIC_MIN = 0.0F;
    public static final float STATIC_MAX = 1.0F;
    public static final float STATIC_DEFAULT = 0.10F;

    /** 总开关 */
    public boolean enabled = false;
    /** 增益，1.0 = 原音量 */
    public float volume = VOLUME_DEFAULT;
    /** 回声湿声比例 */
    public float echo = ECHO_DEFAULT;
    /** 回声是否交给周围环境自动决定（开着时 {@link #echo} 只是界面显示值） */
    public boolean echoAuto = false;
    /** 失真量 */
    public float distortion = DISTORTION_DEFAULT;
    /** 电流声 */
    public float hiss = HISS_DEFAULT;
    /** 沙沙声 */
    public float staticNoise = STATIC_DEFAULT;

    public ReasimParams() {
    }

    /** 深拷贝：跨线程/跨包只传副本 */
    public ReasimParams copy() {
        ReasimParams out = new ReasimParams();
        out.enabled = this.enabled;
        out.volume = this.volume;
        out.echo = this.echo;
        out.echoAuto = this.echoAuto;
        out.distortion = this.distortion;
        out.hiss = this.hiss;
        out.staticNoise = this.staticNoise;
        return out;
    }

    /** 把所有字段钳到合法范围。反序列化和网络包都必须先过这一遍 */
    public ReasimParams clamp() {
        this.volume = clamp(this.volume, VOLUME_MIN, VOLUME_MAX);
        this.echo = clamp(this.echo, ECHO_MIN, ECHO_MAX);
        this.distortion = clamp(this.distortion, DISTORTION_MIN, DISTORTION_MAX);
        this.hiss = clamp(this.hiss, HISS_MIN, HISS_MAX);
        this.staticNoise = clamp(this.staticNoise, STATIC_MIN, STATIC_MAX);
        return this;
    }

    /** 两个参数组是否等价（界面用来判断"要不要发包"） */
    public boolean sameAs(ReasimParams other) {
        if (other == null) {
            return false;
        }
        return this.enabled == other.enabled
                && this.echoAuto == other.echoAuto
                && Math.abs(this.volume - other.volume) < 1.0E-4F
                && Math.abs(this.echo - other.echo) < 1.0E-4F
                && Math.abs(this.distortion - other.distortion) < 1.0E-4F
                && Math.abs(this.hiss - other.hiss) < 1.0E-4F
                && Math.abs(this.staticNoise - other.staticNoise) < 1.0E-4F;
    }

    // ------------------------------------------------------------------ 序列化

    /**
     * 写进网络包 / NBT 共用的紧凑布局：一个标志位 + 五个 float。
     * 不用把字段名写进包里，这样以后调整取值范围不会破坏旧存档。
     */
    public void write(DataOutput out) throws IOException {
        int flags = 0;
        if (this.enabled) {
            flags |= 1;
        }
        if (this.echoAuto) {
            flags |= 2;
        }
        out.writeByte(flags);
        out.writeFloat(this.volume);
        out.writeFloat(this.echo);
        out.writeFloat(this.distortion);
        out.writeFloat(this.hiss);
        out.writeFloat(this.staticNoise);
    }

    public static ReasimParams read(DataInput in) throws IOException {
        ReasimParams out = new ReasimParams();
        int flags = in.readByte();
        out.enabled = (flags & 1) != 0;
        out.echoAuto = (flags & 2) != 0;
        out.volume = in.readFloat();
        out.echo = in.readFloat();
        out.distortion = in.readFloat();
        out.hiss = in.readFloat();
        out.staticNoise = in.readFloat();
        return out.clamp();
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        // NaN 从这里混进来过：滑条宽度为 0 时做过除法，一路写进 NBT 之后
        // 整个 DSP 链的输出全变成 NaN，表现是"开了 reasim 之后彻底没声"。
        return Float.isNaN(value) ? min : value;
    }

    @Override
    public String toString() {
        return String.format("reasim[%s vol=%.2f echo=%.2f%s dist=%.2f hiss=%.2f static=%.2f]",
                enabled ? "on" : "off", volume, echo, echoAuto ? "(auto)" : "",
                distortion, hiss, staticNoise);
    }
}
