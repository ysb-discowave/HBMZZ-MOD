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

/**
 * reasim 的信号链：把一路 16 位 PCM 变成"一只装在铁皮箱里的破喇叭"发出来的声音。
 * <p>
 * 这里刻意<b>不引用任何 Minecraft 类型</b>，所以它可以脱离游戏跑单测
 * （{@code tools/reasimcheck}：喂正弦波进来看频谱和峰值，验证每一级都真的生效）。
 * <p>
 * 处理顺序是有讲究的，不能随便换：
 * <ol>
 *     <li><b>增益</b> —— 功放旋钮，最先。</li>
 *     <li><b>失真</b> —— 喇叭过载。必须在噪声之前：真实的破喇叭是"信号被削平了"，
 *         而不是"先沙沙再削"。这里同时做两件事：软削波（tanh）和降位深（抖动的量化），
 *         后者是廉价 DAC 的量化噪声，两者叠加才是"廉价"而不是"重金属"。</li>
 *     <li><b>电流声 / 沙沙声</b> —— 加在失真之后。功放底噪不会跟着音量旋钮走，
 *         所以它<b>不乘</b>增益，这也是"音量拧到 0 还有一点嗡嗡声"的来源。</li>
 *     <li><b>回声</b> —— 房间。放在最后，因为房间混的是"喇叭已经放出来的声音"。
 *         反馈回路里带一级低通，否则重复几次会越积越亮、最后变成哨叫。</li>
 *     <li><b>软限幅</b> —— 只削掉超过 ±0.95 的峰，避免上面几级叠加后爆掉。</li>
 * </ol>
 * 所有滤波器状态都是每实例私有的，一个实例只能被一个线程使用（{@code ReasimSession}
 * 的推流线程）。
 */
public final class ReasimDsp {

    /** 回声延迟上限：0.4 秒。做成一间大厅也够用了，再长就是"山谷"而不是"房间" */
    public static final int MAX_DELAY_MS = 400;

    /** 回声最短/最长延迟（毫秒）。湿声越大，延迟越长 —— 混响越多的房间通常也越大 */
    private static final float DELAY_MIN_MS = 90.0F;
    private static final float DELAY_SPAN_MS = 190.0F;

    private final int sampleRate;
    private final int channels;
    private final int frameBytes;

    /** 回声延迟线，按帧交错的浮点采样 */
    private final float[] delayBuffer;
    private int delayFrames;
    private int writeIndex;

    // ---- 当前参数（由 configure 算好，process 里只用不换算）----
    private float gain = 1.0F;
    private float wet = 0.0F;
    private float feedback = 0.0F;
    private float drive = 1.0F;
    private float invShapeDrive = 1.0F;
    private float quantLevels = 0.0F;
    private float humLevel = 0.0F;
    private float staticLevel = 0.0F;
    private boolean active = false;

    // ---- 滤波器状态 ----
    /** 每帧复用的中转数组：一个 44.1kHz 的流每秒四万多帧，在这里 new 一次就够多了 */
    private final float[] frameBuffer;
    private final float[] lpState;
    private final float[] dcState;
    private final float[] statState;
    private double humPhase;
    private long rng = 0x2545F4914F6CDD1DL;

    public ReasimDsp(int sampleRate, int channels) {
        this.sampleRate = sampleRate <= 0 ? 44100 : sampleRate;
        this.channels = channels <= 0 ? 2 : channels;
        this.frameBytes = this.channels * 2;
        int maxFrames = this.sampleRate * MAX_DELAY_MS / 1000;
        this.delayBuffer = new float[maxFrames * this.channels];
        this.frameBuffer = new float[this.channels];
        this.lpState = new float[this.channels];
        this.dcState = new float[this.channels];
        this.statState = new float[this.channels];
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    /** 这个实例是否处于"要处理"的状态；false 时 {@link #process} 直接原样拷贝 */
    public boolean isActive() {
        return active;
    }

    /**
     * 按参数重新配置。
     *
     * @param params       参数组（调用方负责先 clamp）
     * @param resolvedEcho 实际生效的回声量：手动模式就是 {@code params.echo}，
     *                     自动模式是 {@code ReasimEnvironment} 按房间算出来的值
     */
    public void configure(ReasimParams params, float resolvedEcho) {
        if (params == null || !params.enabled) {
            this.active = false;
            return;
        }
        this.active = true;
        this.gain = params.volume;

        float echoAmount = clamp(resolvedEcho, 0.0F, 1.0F);
        // 湿声为 0 时彻底旁路，省下的 CPU 比"处理一遍再乘 0"更值
        this.wet = echoAmount;
        // 反馈跟着湿声走：0.20 起步（一次可见的拍回声），最高 0.65
        // （再高就是指数增长的无限回响了，会盖住一切）
        this.feedback = echoAmount <= 0.001F ? 0.0F : 0.20F + 0.45F * echoAmount;
        float delayMs = DELAY_MIN_MS + DELAY_SPAN_MS * echoAmount;
        int frames = Math.round(delayMs * this.sampleRate / 1000.0F);
        this.delayFrames = Math.max(1, Math.min(frames, this.delayBuffer.length / this.channels - 1));

        // 失真：drive 从 1（不削）到 25（几乎方波）。归一化除数是 drive 自己
        // 经过同一条曲线后的值，这样拧滑条时响度不会跟着一起飙。
        float d = clamp(params.distortion, 0.0F, 1.0F);
        this.drive = 1.0F + 24.0F * d;
        this.invShapeDrive = 1.0F / shape(this.drive);
        // 位深从 16 位一路掉到 6 位。低于 6 位就只剩噪声了，听不出是音乐。
        this.quantLevels = d <= 0.001F ? 0.0F : Math.round(Math.pow(2.0F, 16.0F - 10.0F * d)) / 2.0F;

        this.humLevel = clamp(params.hiss, 0.0F, 1.0F) * 0.06F;
        this.staticLevel = clamp(params.staticNoise, 0.0F, 1.0F);
    }

    /** 清空所有滤波器状态（换歌 / 重新开始循环时用，避免上一首的尾音串进来） */
    public void reset() {
        java.util.Arrays.fill(delayBuffer, 0.0F);
        java.util.Arrays.fill(lpState, 0.0F);
        java.util.Arrays.fill(dcState, 0.0F);
        java.util.Arrays.fill(statState, 0.0F);
        writeIndex = 0;
        humPhase = 0.0D;
    }

    /**
     * 就地处理一段 16 位小端 PCM。长度不是整帧时，尾部原样保留。
     */
    public void process(byte[] buffer, int offset, int length) {
        if (!active) {
            return;
        }
        int frames = length / frameBytes;
        int index = offset;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                int lo = buffer[index] & 0xFF;
                int hi = buffer[index + 1];
                frameBuffer[c] = ((short) ((hi << 8) | lo)) / 32768.0F;
                index += 2;
            }
            processFrame(frameBuffer);
            int write = index - frameBytes;
            for (int c = 0; c < channels; c++) {
                int value = Math.round(frameBuffer[c] * 32767.0F);
                if (value > 32767) {
                    value = 32767;
                } else if (value < -32768) {
                    value = -32768;
                }
                buffer[write] = (byte) (value & 0xFF);
                buffer[write + 1] = (byte) ((value >> 8) & 0xFF);
                write += 2;
            }
        }
    }

    // ------------------------------------------------------------------ 单帧

    private void processFrame(float[] frame) {
        // 1. 功放增益
        if (gain != 1.0F) {
            for (int c = 0; c < channels; c++) {
                frame[c] *= gain;
            }
        }

        // 2. 失真：软削波 + 降位深
        if (drive > 1.001F) {
            for (int c = 0; c < channels; c++) {
                float x = frame[c];
                float shaped = shape(x * drive) * invShapeDrive;
                // 廉价 DAC 的量化台阶。+0.5 再取整实现四舍五入，
                // 用 truncation 会让小信号整体偏负，听起来是"发闷"而不是"发脏"。
                if (quantLevels > 0.0F) {
                    shaped = Math.round(shaped * quantLevels) / quantLevels;
                }
                // 一阶高通去直流：削波是不对称的，会往信号里灌一个直流分量，
                // 不滤掉的话低音单元会被顶出去，听感是"没低音了"。
                float hp = shaped - dcState[c];
                dcState[c] = shaped - hp * 0.9995F;
                frame[c] = hp;
            }
        }

        // 3. 电流声：50Hz 工频 + 二次、三次谐波。铜线在磁场里就是这个调子。
        if (humLevel > 0.0F) {
            double t = humPhase;
            float hum = (float) (0.62D * Math.sin(t)
                    + 0.26D * Math.sin(2.0D * t + 0.7D)
                    + 0.12D * Math.sin(3.0D * t + 1.9D));
            float value = hum * humLevel;
            for (int c = 0; c < channels; c++) {
                frame[c] += value;
            }
            humPhase += 2.0D * Math.PI * 50.0D / (double) sampleRate;
            if (humPhase > 2.0D * Math.PI) {
                humPhase -= 2.0D * Math.PI;
            }
        }

        // 4. 沙沙声：带限白噪（一阶低通压掉刺耳的高频） + 稀疏爆裂
        if (staticLevel > 0.001F) {
            for (int c = 0; c < channels; c++) {
                float white = nextNoise();
                statState[c] += 0.35F * (white - statState[c]);
                float value = statState[c] * staticLevel * 0.05F;
                // 爆裂：每样本约 0.12% 概率来一个尖脉冲，听起来像接触不良的电位器
                if ((nextBits(12) & 0xFFF) < (int) (staticLevel * 5.0F)) {
                    value += (nextNoise() * 0.5F) * staticLevel;
                }
                frame[c] += value;
            }
        }

        // 5. 回声：读出延迟线 → 混进干声 → 带阻尼地写回
        if (wet > 0.001F && feedback > 0.0F) {
            int readIndex = writeIndex - delayFrames;
            if (readIndex < 0) {
                readIndex += delayBuffer.length / channels;
            }
            for (int c = 0; c < channels; c++) {
                float dry = frame[c];
                float delayed = delayBuffer[readIndex * channels + c];
                // 反馈回路里的低通：每次重复都更闷一点，这是真实房间的吸收，
                // 也是防止高频在回路里累积到哨叫的唯一手段。
                lpState[c] += 0.32F * (delayed - lpState[c]);
                delayBuffer[writeIndex * channels + c] = dry + lpState[c] * feedback;
                frame[c] = dry + delayed * wet;
            }
            writeIndex = (writeIndex + 1) % (delayBuffer.length / channels);
        }

        // 6. 软限幅
        for (int c = 0; c < channels; c++) {
            frame[c] = limit(frame[c]);
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * tanh 的 Padé 近似。比 {@code Math.tanh} 快一个数量级，
     * 在 |x| &lt; 3 的范围内误差 &lt; 0.02，对失真来说完全够用（反正已经削平了）。
     */
    private static float shape(float x) {
        if (x > 3.0F) {
            return 1.0F;
        }
        if (x < -3.0F) {
            return -1.0F;
        }
        float x2 = x * x;
        return x * (27.0F + x2) / (27.0F + 9.0F * x2);
    }

    /** 只在肩部（±0.95 以上）才压缩，中间完全线性 —— 保留动态，只削峰 */
    private static float limit(float x) {
        if (x > 0.95F) {
            return 0.95F + 0.05F * shape((x - 0.95F) / 0.05F);
        }
        if (x < -0.95F) {
            return -0.95F - 0.05F * shape((-x - 0.95F) / 0.05F);
        }
        return x;
    }

    /** xorshift64*：比 Random 快，也不需要同步 */
    private float nextNoise() {
        long x = rng;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rng = x;
        long v = x * 0x2545F4914F6CDD1DL;
        // 取高 24 位映射到 [-1, 1)
        return ((v >>> 40) & 0xFFFFFF) / 8388608.0F - 1.0F;
    }

    private int nextBits(int bits) {
        long x = rng;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rng = x;
        return (int) (x >>> (64 - bits));
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return value < min ? min : (value > max ? max : value);
    }
}
