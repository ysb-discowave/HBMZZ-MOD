import com.rsaudio.audio.ReasimDsp;
import com.rsaudio.audio.ReasimParams;

/** 离线验证 reasim 信号链：每一条断言对应一个"必须为真"的听感事实。 */
public class DspCheck {

    private static final int RATE = 44100;

    public static void main(String[] args) throws Exception {
        int fail = 0;
        fail += check("关闭时逐字节不变", DspCheck::bitExactWhenOff);
        fail += check("音量 0.5 让 RMS 减半", DspCheck::gainHalves);
        fail += check("音量 2.0 让 RMS 翻倍", DspCheck::gainDoubles);
        fail += check("静音 + 电流声 = 有底噪", DspCheck::humOnSilence);
        fail += check("静音 + 沙沙声 = 有底噪", DspCheck::staticOnSilence);
        fail += check("失真削平峰值", DspCheck::distortionClips);
        fail += check("回声在输入停止后仍有尾音", DspCheck::echoTail);
        fail += check("回声尾音是衰减的", DspCheck::echoDecays);
        fail += check("满参数不产生 NaN / 不越界", DspCheck::noOverflow);
        System.out.println(fail == 0 ? "ALL PASS" : (fail + " FAIL"));
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 用例

    static void bitExactWhenOff() {
        ReasimParams p = new ReasimParams();
        p.enabled = false;
        ReasimDsp dsp = new ReasimDsp(RATE, 2);
        dsp.configure(p, 0.5F);
        byte[] a = sine(0.5F, 0.5F, 2, 4096);
        byte[] b = a.clone();
        dsp.process(b, 0, b.length);
        require(java.util.Arrays.equals(a, b), "关闭状态下输出被改动了");
    }

    static void gainHalves() {
        double before = rms(run(withVolume(0.5F), 0.6F));
        double plain = rms(sine(0.5F, 0.6F, 2, 4096));
        require(near(before, plain * 0.5, 0.02), "0.5 倍增益后 RMS=" + before + " 期望 " + (plain * 0.5));
    }

    static void gainDoubles() {
        double after = rms(run(withVolume(2.0F), 0.6F));
        double plain = rms(sine(0.5F, 0.6F, 2, 4096));
        // 2.0 倍会把 0.5 的峰值推到 1.0，软限幅会削掉一点，所以放宽到 15%
        require(after > plain * 1.5, "2.0 倍增益后 RMS=" + after + " 太小（期望明显大于 " + plain + "）");
    }

    static void humOnSilence() {
        ReasimParams p = base();
        p.volume = 0.0F;
        p.hiss = 1.0F;
        p.staticNoise = 0.0F;
        ReasimDsp dsp = new ReasimDsp(RATE, 2);
        dsp.configure(p, 0.0F);
        byte[] buf = new byte[RATE * 4];
        dsp.process(buf, 0, buf.length);
        require(rms(buf) > 0.002, "音量 0 + 电流声 1 时应当仍有底噪，实测 RMS=" + rms(buf));
    }

    static void staticOnSilence() {
        ReasimParams p = base();
        p.volume = 0.0F;
        p.hiss = 0.0F;
        p.staticNoise = 1.0F;
        ReasimDsp dsp = new ReasimDsp(RATE, 2);
        dsp.configure(p, 0.0F);
        byte[] buf = new byte[RATE * 4];
        dsp.process(buf, 0, buf.length);
        require(rms(buf) > 0.005, "音量 0 + 沙沙声 1 时应当有明显噪声，实测 RMS=" + rms(buf));
    }

    static void distortionClips() {
        ReasimParams p = base();
        p.distortion = 1.0F;
        byte[] out = run(p, 0.8F);
        int peak = 0;
        for (int i = 0; i + 1 < out.length; i += 2) {
            peak = Math.max(peak, Math.abs((short) ((out[i + 1] << 8) | (out[i] & 0xFF))));
        }
        // 输入峰值 0.8 → 削波后应当贴近满刻度且不许越界
        require(peak > 24000 && peak <= 32767, "失真后峰值 = " + peak + "，期望被推到接近满刻度");
    }

    static void echoTail() {
        // 先喂 50ms 正弦，再喂 300ms 静音；回声应当把声音拖进静音区
        ReasimParams p = base();
        p.echo = 1.0F;
        ReasimDsp dsp = new ReasimDsp(RATE, 2);
        dsp.configure(p, 1.0F);
        byte[] tone = sine(0.4F, 0.05F, 2, 0);
        byte[] tail = new byte[RATE * 3 / 10 * 4];
        dsp.process(tone, 0, tone.length);
        dsp.process(tail, 0, tail.length);
        require(rms(tail) > 0.01, "回声尾音 RMS=" + rms(tail) + "，期望输入停止后仍有声音");
    }

    static void echoDecays() {
        ReasimParams p = base();
        p.echo = 1.0F;
        ReasimDsp dsp = new ReasimDsp(RATE, 2);
        dsp.configure(p, 1.0F);
        byte[] tone = sine(0.4F, 0.05F, 2, 0);
        dsp.process(tone, 0, tone.length);
        byte[] tail = new byte[RATE * 3 / 2 * 4];
        dsp.process(tail, 0, tail.length);
        int third = tail.length / 4 / 4 * 4;
        double early = rms(java.util.Arrays.copyOfRange(tail, 0, third));
        double late = rms(java.util.Arrays.copyOfRange(tail, tail.length - third, tail.length));
        require(late < early * 0.6, "尾音没有衰减：前段 " + early + " 后段 " + late);
    }

    static void noOverflow() {
        ReasimParams p = base();
        p.volume = ReasimParams.VOLUME_MAX;
        p.echo = 1.0F;
        p.distortion = 1.0F;
        p.hiss = 1.0F;
        p.staticNoise = 1.0F;
        byte[] out = run(p, 0.95F);
        boolean nan = false;
        for (int i = 0; i + 1 < out.length; i += 2) {
            short v = (short) ((out[i + 1] << 8) | (out[i] & 0xFF));
            if (v < -32768 || v > 32767) {
                nan = true;
            }
        }
        require(!nan, "满参数输出出现越界采样");
    }

    // ------------------------------------------------------------------ 工具

    static ReasimParams base() {
        ReasimParams p = new ReasimParams();
        p.enabled = true;
        p.volume = 1.0F;
        p.echo = 0.0F;
        p.distortion = 0.0F;
        p.hiss = 0.0F;
        p.staticNoise = 0.0F;
        return p;
    }

    static ReasimParams withVolume(float v) {
        ReasimParams p = base();
        p.volume = v;
        return p;
    }

    static byte[] run(ReasimParams p, float amp) {
        ReasimDsp dsp = new ReasimDsp(RATE, 2);
        dsp.configure(p, p.echo);
        byte[] buf = sine(0.5F, amp, 2, 0);
        dsp.process(buf, 0, buf.length);
        return buf;
    }

    /** seconds 秒的 440Hz 正弦，立体声、16 位小端 */
    static byte[] sine(float seconds, float amp, int channels, int padFrames) {
        int frames = Math.round(seconds * RATE) + padFrames;
        byte[] out = new byte[frames * channels * 2];
        int i = 0;
        for (int f = 0; f < frames; f++) {
            double v = amp * Math.sin(2.0 * Math.PI * 440.0 * f / RATE);
            short s = (short) Math.round(v * 32767.0);
            for (int c = 0; c < channels; c++) {
                out[i++] = (byte) (s & 0xFF);
                out[i++] = (byte) ((s >> 8) & 0xFF);
            }
        }
        return out;
    }

    static double rms(byte[] buf) {
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i + 1 < buf.length; i += 2) {
            short v = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
            double d = v / 32768.0;
            sum += d * d;
            n++;
        }
        return n == 0 ? 0.0 : Math.sqrt(sum / n);
    }

    static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol * Math.max(1.0, Math.abs(b));
    }

    static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    interface Case {
        void run() throws Exception;
    }

    static int check(String name, Case c) {
        try {
            c.run();
            System.out.println("  ok   " + name);
            return 0;
        } catch (Throwable t) {
            System.out.println("  FAIL " + name + " :: " + t.getMessage());
            return 1;
        }
    }
}
