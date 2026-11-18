package com.rsaudio.formats.play;

import java.io.IOException;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

/**
 * Minimal, thread-safe 16-bit PCM playback kernel for a Minecraft 1.12.2 side mod.
 *
 * <p>Only depends on {@code java.*} and {@code javax.sound.*}: the player never decodes anything,
 * it just consumes a byte stream with a known {@link AudioFormat} and pushes it to a
 * {@link SourceDataLine}. The decoding layer (any format to 16-bit signed little endian PCM) is
 * supplied by the caller.</p>
 *
 * <h3>Threading</h3>
 * One daemon writer thread per session. {@link #stop()} only flips a volatile flag and calls
 * {@code flush()}/{@code stop()} on the line, so the calling thread (a game tick) never waits for
 * the audio device; the writer thread notices the flag and closes the line. {@link #setGain(float)}
 * and {@link #setPan(float)} are plain volatile writes - the writer thread applies them on its next
 * chunk (about 30 ms), so no native call happens on the game thread.
 *
 * <h3>Gain and pan</h3>
 * Overall gain is applied twice, on purpose: as a dB value on {@code MASTER_GAIN} (clamped to the
 * control range) and as a multiplier on the samples. The sample multiplier is what carries pan, so
 * the left/right ratio is exact and survives a device without a usable MASTER_GAIN control. On a
 * device that honours MASTER_GAIN the audible attenuation is roughly gain squared, which only
 * steepens the fade - it is never louder than requested.
 *
 * <p>Pan uses an equal-power law ({@code cos/sin} of {@code (pan+1)*PI/4}) normalised so that the
 * louder channel never exceeds unity: centre is 1.0/1.0, hard left is 1.0/0.0, hard right is
 * 0.0/1.0. Samples are clamped to [-32768, 32767] after the multiply, so nothing ever wraps into
 * noise.</p>
 *
 * <h3>Looping</h3>
 * {@code loop = true} requires an {@link InputStream} whose {@code mark/reset} works; the player
 * calls {@code mark(8 MiB)} in {@link #start} and {@code reset()} at end of stream. If the stream
 * cannot be marked, or {@code reset()} fails, the player <b>plays the stream once</b> and then
 * reports {@link #finished()} - the caller is expected to open a fresh stream and start again.
 * That is the contract: <i>loop is best effort, re-starting is always correct.</i>
 *
 * <h3>Format degradation</h3>
 * The requested format is tried first; then the same stream at 48000/44100/22050 Hz (speed shifts,
 * no resampling) and finally mono (down-mixed). If nothing opens, {@link #start} returns
 * {@code false} after printing one line to {@code System.err}; the caller is expected to skip
 * playback.
 *
 * <p>Not thread-safe with respect to two concurrent {@code start()} calls, but any number of
 * {@code setGain}/{@code setPan}/{@code stop}/{@code isPlaying}/{@code playedSeconds} calls are.</p>
 */
public final class PcmPlayer implements AutoCloseable {

    /** Upper bound for the overall gain; sanity only, samples are clamped anyway. */
    private static final float MAX_GAIN = 16.0f;
    /** 0.25 s of audio, but never less than 4 KiB (low-end machines underrun on smaller buffers). */
    private static final int MIN_BUFFER_BYTES = 4096;
    /** mark() limit used for looping streams: bounded so a BufferedInputStream cannot eat the heap. */
    private static final int LOOP_MARK_LIMIT = 8 * 1024 * 1024;
    private static final double SQRT2 = 1.4142135623730951;
    /** How long start()/close() are willing to wait for the previous writer to release the line. */
    private static final long HANDOVER_WAIT_MS = 120L;
    private static final long CLOSE_JOIN_MS = 40L;
    private static final AudioFormat.Encoding PCM_SIGNED = AudioFormat.Encoding.PCM_SIGNED;

    private volatile Session session;
    private volatile Runnable onFinish;
    private volatile boolean closed;
    private volatile float gain = 1.0f;
    private volatile float pan = 0.0f;
    private volatile double lastSessionSeconds;

    /* ---- diagnostics: package private on purpose (no extra public API), used by PlayLab ---- */
    volatile AudioFormat diagLineFormat;
    volatile long diagWrittenFrames;
    volatile int diagPeak;
    volatile double diagSumL2;
    volatile double diagSumR2;
    volatile long diagSamples;
    volatile float diagMasterGainDb = Float.NaN;
    volatile String diagLastError;
    volatile int diagLoopWraps;

    public PcmPlayer() {
        // no resources are taken until start()
    }

    /**
     * Starts (or restarts) playback. A previous session on this instance is stopped first.
     *
     * @param format 16-bit signed PCM format describing {@code pcm}; not null
     * @param pcm    raw interleaved PCM bytes; consumed on the writer thread, not closed by this class
     * @param gain   0..N overall gain (negative and NaN are clamped/ignored)
     * @param pan    -1 = hard left, 0 = centre, +1 = hard right
     * @param loop   loop the stream while {@code mark/reset} works (see class javadoc)
     * @return true when a line was opened and the writer thread started
     */
    public boolean start(AudioFormat format, InputStream pcm, float gain, float pan, boolean loop) {
        if (closed) {
            return fail("start() called on a closed player");
        }
        if (format == null) {
            return fail("start() needs a non-null AudioFormat");
        }
        if (pcm == null) {
            return fail("start() needs a non-null InputStream");
        }
        String invalid = validateSourceFormat(format);
        if (invalid != null) {
            return fail(invalid);
        }

        stopAndWait(HANDOVER_WAIT_MS);

        this.gain = clampGain(gain);
        this.pan = clampPan(pan);

        boolean canLoop = loop;
        if (loop) {
            if (pcm.markSupported()) {
                try {
                    pcm.mark(LOOP_MARK_LIMIT);
                } catch (RuntimeException e) {
                    canLoop = false;
                    System.err.println("[PcmPlayer] loop=true but mark() failed (" + e + "); playing once");
                }
            } else {
                canLoop = false;
                System.err.println("[PcmPlayer] loop=true but the stream does not support mark/reset;"
                        + " playing once - re-start with a fresh stream to loop");
            }
        }

        int[] bufferBytes = new int[1];
        SourceDataLine line = openLine(format, bufferBytes);
        if (line == null) {
            return false;
        }

        Session s = new Session(pcm, format, line, bufferBytes[0], canLoop);
        s.streamReset = canLoop;
        session = s;
        diagLineFormat = line.getFormat();
        diagWrittenFrames = 0L;
        diagPeak = 0;
        diagSumL2 = 0.0;
        diagSumR2 = 0.0;
        diagSamples = 0L;
        diagMasterGainDb = Float.NaN;
        diagLoopWraps = 0;
        diagLastError = null;

        Thread t = new Thread(s.runner(), "PcmPlayer-writer");
        t.setDaemon(true);
        s.thread = t;
        t.start();
        return true;
    }

    /** Updates the overall gain. Safe from any thread, including while playing. */
    public void setGain(float gain) {
        this.gain = clampGain(gain);
        Session s = session;
        if (s != null) {
            s.masterGainDirty = true;
        }
    }

    /** Updates the pan (-1 left .. +1 right). Safe from any thread, including while playing. */
    public void setPan(float pan) {
        this.pan = clampPan(pan);
    }

    /** True while a session exists, has not been asked to stop and its line is still open. */
    public boolean isPlaying() {
        Session s = session;
        if (s == null || s.terminal || s.stopRequested) {
            return false;
        }
        SourceDataLine line = s.line;
        return line != null && line.isOpen();
    }

    /**
     * Stops playback: silences the device immediately and releases the line.
     * Never blocks on the audio device and never calls {@code drain()}.
     */
    public void stop() {
        Session s = session;
        if (s != null) {
            requestStop(s);
        }
    }

    /** True when the current (or last) session ended - either the stream ran out or {@link #stop()} was called. */
    public boolean finished() {
        Session s = session;
        if (s == null) {
            return true;
        }
        return s.terminal || s.stopRequested;
    }

    /**
     * Seconds of audio actually delivered to the device for the current/last session.
     * Counted from frames, never from wall clock; the device play position is used when it is
     * available (it avoids the buffer pre-read skew of the raw write counter), otherwise the
     * number of frames written is used.
     */
    public double playedSeconds() {
        Session s = session;
        if (s == null) {
            return lastSessionSeconds;
        }
        if (s.terminal) {
            return s.finalFrames / (double) s.lineFormat.getSampleRate();
        }
        long frames = s.deviceFramePosition();
        if (frames <= 0L) {
            frames = s.writtenFrames;
        }
        return frames / (double) s.lineFormat.getSampleRate();
    }

    /** Registers the one-shot finish callback; it runs on the writer thread, exactly once per session. */
    public void setOnFinish(Runnable r) {
        this.onFinish = r;
    }

    /** stop() plus best-effort thread release. Does not block on the device. */
    @Override
    public void close() {
        closed = true;
        Session s = session;
        if (s == null) {
            return;
        }
        requestStop(s);
        Thread t = s.thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(CLOSE_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        session = null;
        onFinish = null;
    }

    // ---------------------------------------------------------------- internals

    private boolean fail(String why) {
        diagLastError = why;
        System.err.println("[PcmPlayer] " + why);
        return false;
    }

    private static String validateSourceFormat(AudioFormat f) {
        if (!PCM_SIGNED.equals(f.getEncoding())) {
            return "unsupported encoding " + f.getEncoding() + " (need PCM_SIGNED 16 bit)";
        }
        if (f.getSampleSizeInBits() != 16) {
            return "unsupported sample size " + f.getSampleSizeInBits() + " bit (need 16)";
        }
        int channels = f.getChannels();
        if (channels < 1 || channels > 8) {
            return "unsupported channel count " + channels;
        }
        float rate = f.getSampleRate();
        if (!(rate >= 8000f && rate <= 384000f)) {
            return "implausible sample rate " + rate + " Hz";
        }
        return null;
    }

    private static float clampGain(float g) {
        if (Float.isNaN(g) || Float.isInfinite(g)) {
            return 1.0f;
        }
        if (g < 0.0f) {
            return 0.0f;
        }
        return g > MAX_GAIN ? MAX_GAIN : g;
    }

    private static float clampPan(float p) {
        if (Float.isNaN(p)) {
            return 0.0f;
        }
        if (p < -1.0f) {
            return -1.0f;
        }
        return p > 1.0f ? 1.0f : p;
    }

    private void stopAndWait(long maxMillis) {
        Session old = session;
        if (old == null) {
            return;
        }
        requestStop(old);
        Thread t = old.thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(maxMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void requestStop(Session s) {
        s.stopRequested = true;
        SourceDataLine line = s.line;
        if (line == null) {
            return;
        }
        try {
            if (line.isOpen()) {
                line.flush();
                line.stop();
            }
        } catch (RuntimeException ignored) {
            // the line may be closing concurrently; nothing to do
        }
    }

    private static int bufferBytesFor(AudioFormat f) {
        int frameSize = f.getChannels() * 2;
        int quarterSecond = (int) (f.getSampleRate() / 4.0f) * frameSize;
        return Math.max(MIN_BUFFER_BYTES, quarterSecond);
    }

    /**
     * Tries the requested format, then alternative rates, then mono. Returns null (after one
     * System.err line) when no line can be opened at all.
     */
    private SourceDataLine openLine(AudioFormat requested, int[] bufferBytesOut) {
        AudioFormat[] candidates = candidateFormats(requested);
        String lastError = null;
        for (int i = 0; i < candidates.length; i++) {
            AudioFormat f = candidates[i];
            try {
                int bufferBytes = bufferBytesFor(f);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, f);
                SourceDataLine line;
                if (AudioSystem.isLineSupported(info)) {
                    line = (SourceDataLine) AudioSystem.getLine(info);
                } else {
                    line = AudioSystem.getSourceDataLine(f);
                }
                if (!line.isOpen()) {
                    line.open(f, bufferBytes);
                }
                bufferBytesOut[0] = Math.max(MIN_BUFFER_BYTES, line.getBufferSize());
                if (!f.equals(requested)) {
                    System.err.println("[PcmPlayer] requested " + describe(requested) + " is not available,"
                            + " fell back to " + describe(f) + " (no resampling: pitch/speed will differ)");
                }
                return line;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        diagLastError = lastError;
        System.err.println("[PcmPlayer] no SourceDataLine for " + describe(requested)
                + " and no fallback worked (" + lastError + "); playback skipped");
        return null;
    }

    private static AudioFormat[] candidateFormats(AudioFormat requested) {
        AudioFormat[] list = new AudioFormat[8];
        int n = 0;
        list[n++] = requested;
        float rate = requested.getSampleRate();
        int channels = requested.getChannels();
        float[] rates = { 48000f, 44100f, 22050f };
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] != rate) {
                list = push(list, n, new AudioFormat(rates[i], 16, channels, true, false));
                n++;
            }
        }
        if (channels > 1) {
            AudioFormat[] mono = new AudioFormat[4];
            int m = 0;
            mono[m++] = new AudioFormat(rate, 16, 1, true, false);
            for (int i = 0; i < rates.length; i++) {
                if (rates[i] != rate) {
                    mono[m++] = new AudioFormat(rates[i], 16, 1, true, false);
                }
            }
            for (int i = 0; i < m; i++) {
                list = push(list, n, mono[i]);
                n++;
            }
        }
        AudioFormat[] out = new AudioFormat[n];
        System.arraycopy(list, 0, out, 0, n);
        return out;
    }

    private static AudioFormat[] push(AudioFormat[] array, int size, AudioFormat value) {
        if (size < array.length) {
            array[size] = value;
            return array;
        }
        AudioFormat[] bigger = new AudioFormat[array.length * 2];
        System.arraycopy(array, 0, bigger, 0, size);
        bigger[size] = value;
        return bigger;
    }

    private static String describe(AudioFormat f) {
        return String.format("%.0f Hz / %d bit / %d ch / %s",
                Float.valueOf(f.getSampleRate()),
                Integer.valueOf(f.getSampleSizeInBits()),
                Integer.valueOf(f.getChannels()),
                f.isBigEndian() ? "BE" : "LE");
    }

    private void fireFinish() {
        Runnable r = onFinish;
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            System.err.println("[PcmPlayer] onFinish threw: " + t);
        }
    }

    private void applyMasterGain(Session s) {
        SourceDataLine line = s.line;
        if (line == null || !line.isOpen()) {
            return;
        }
        float g = this.gain;
        float db = g <= 0.0001f ? -100.0f : (float) (20.0 * Math.log10(g));
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl c = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float clamped = Math.max(c.getMinimum(), Math.min(c.getMaximum(), db));
                c.setValue(clamped);
                diagMasterGainDb = c.getValue();
            } else {
                diagMasterGainDb = Float.NaN;
            }
        } catch (RuntimeException e) {
            diagMasterGainDb = Float.NaN;
        }
    }

    /** One playback session: a line, a stream, a thread and its counters. */
    private final class Session {
        final InputStream stream;
        final AudioFormat srcFormat;
        final SourceDataLine line;
        final AudioFormat lineFormat;
        final int srcChannels;
        final int srcFrameSize;
        final int lineChannels;
        final int lineFrameSize;
        final boolean srcBigEndian;
        final boolean lineBigEndian;
        final boolean loop;
        final int chunkFrames;

        volatile boolean stopRequested;
        volatile boolean terminal;
        volatile boolean streamReset;
        volatile boolean masterGainDirty = true;
        volatile long writtenFrames;
        volatile long deviceFrames;
        volatile long finalFrames;
        volatile Throwable error;
        Thread thread;

        Session(InputStream stream, AudioFormat srcFormat, SourceDataLine line, int bufferBytes, boolean loop) {
            this.stream = stream;
            this.srcFormat = srcFormat;
            this.line = line;
            this.lineFormat = line.getFormat();
            this.srcChannels = srcFormat.getChannels();
            this.srcFrameSize = srcChannels * 2;
            this.lineChannels = Math.max(1, lineFormat.getChannels());
            this.lineFrameSize = this.lineChannels * 2;
            this.srcBigEndian = srcFormat.isBigEndian();
            this.lineBigEndian = lineFormat.isBigEndian();
            this.loop = loop;
            int framesInBuffer = Math.max(1, bufferBytes / this.lineFrameSize);
            this.chunkFrames = Math.max(128, framesInBuffer / 8);
        }

        Runnable runner() {
            return new Runnable() {
                @Override
                public void run() {
                    runSession(Session.this);
                }
            };
        }

        long deviceFramePosition() {
            SourceDataLine l = line;
            if (l != null) {
                try {
                    if (l.isOpen()) {
                        long pos = l.getLongFramePosition();
                        if (pos > 0L) {
                            deviceFrames = pos;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // closed under us; fall back to the snapshot
                }
            }
            return deviceFrames;
        }
    }

    private void runSession(Session s) {
        SourceDataLine line = s.line;
        byte[] in = new byte[s.chunkFrames * s.srcFrameSize];
        byte[] out = new byte[s.chunkFrames * s.lineFrameSize];
        boolean naturalEnd = false;
        try {
            line.start();
            while (!s.stopRequested) {
                int got = readFully(s.stream, in);
                int frames = got / s.srcFrameSize;
                if (frames <= 0) {
                    if (s.loop && s.streamReset && rewind(s)) {
                        continue;
                    }
                    naturalEnd = true;
                    break;
                }
                int outLen = mix(s, in, frames, out);
                if (outLen <= 0) {
                    naturalEnd = true;
                    break;
                }
                if (!writeAll(s, line, out, outLen)) {
                    break;
                }
                s.writtenFrames += outLen / s.lineFrameSize;
                s.deviceFramePosition();
                publishDiagnostics(s);
            }
            if (naturalEnd && !s.stopRequested) {
                try {
                    line.drain();
                } catch (RuntimeException ignored) {
                    // line closed while draining
                }
            }
        } catch (Throwable t) {
            s.error = t;
            if (!s.stopRequested) {
                System.err.println("[PcmPlayer] playback aborted: " + t);
            }
        } finally {
            long played = s.deviceFramePosition();
            s.finalFrames = played > 0L ? played : s.writtenFrames;
            lastSessionSeconds = s.finalFrames / (double) s.lineFormat.getSampleRate();
            publishDiagnostics(s);
            try {
                if (line.isOpen()) {
                    line.flush();
                    line.stop();
                    line.close();
                }
            } catch (RuntimeException ignored) {
                // already closed
            }
            s.terminal = true;
            fireFinish();
        }
    }

    private void publishDiagnostics(Session s) {
        diagWrittenFrames = s.writtenFrames;
    }

    private boolean rewind(Session s) {
        try {
            s.stream.reset();
            diagLoopWraps++;
            return true;
        } catch (Exception e) {
            s.streamReset = false;
            System.err.println("[PcmPlayer] loop reset() failed (" + e + "); ending after one pass,"
                    + " the caller should start again with a fresh stream");
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        int len = buf.length;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off;
    }

    private boolean writeAll(Session s, SourceDataLine line, byte[] buf, int len) {
        int off = 0;
        while (off < len) {
            if (s.stopRequested) {
                return false;
            }
            int n = line.write(buf, off, len - off);
            if (n <= 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    /** Applies gain/pan to one chunk of frames and writes the result into {@code out}. */
    private int mix(Session s, byte[] in, int frames, byte[] out) {
        if (s.masterGainDirty) {
            s.masterGainDirty = false;
            applyMasterGain(s);
        }
        float g = this.gain;
        float p = this.pan;
        double theta = (p + 1.0) * (Math.PI / 4.0);
        float gl = (float) (Math.cos(theta) * SQRT2);
        float gr = (float) (Math.sin(theta) * SQRT2);
        float norm = Math.max(1.0f, Math.max(gl, gr));
        gl = (gl / norm) * g;
        gr = (gr / norm) * g;

        final boolean inBe = s.srcBigEndian;
        final boolean outBe = s.lineBigEndian;
        final int srcFrame = s.srcFrameSize;
        final int outChannels = s.lineChannels;
        int ip = 0;
        int op = 0;
        int peak = diagPeak;
        double sumL2 = diagSumL2;
        double sumR2 = diagSumR2;
        long samples = diagSamples;

        for (int f = 0; f < frames; f++) {
            int l;
            int r;
            if (s.srcChannels == 1) {
                l = readS16(in, ip, inBe);
                r = l;
            } else {
                l = readS16(in, ip, inBe);
                r = readS16(in, ip + 2, inBe);
            }
            ip += srcFrame;

            int ol = clampS16(l * gl);
            int or = clampS16(r * gr);

            int a = ol < 0 ? -ol : ol;
            int b = or < 0 ? -or : or;
            if (a > peak) {
                peak = a;
            }
            if (b > peak) {
                peak = b;
            }
            sumL2 += (double) ol * (double) ol;
            sumR2 += (double) or * (double) or;
            samples++;

            if (outChannels == 1) {
                writeS16(out, op, (ol + or) >> 1, outBe);
                op += 2;
            } else {
                writeS16(out, op, ol, outBe);
                writeS16(out, op + 2, or, outBe);
                op += 4;
                for (int c = 2; c < outChannels; c++) {
                    writeS16(out, op, 0, outBe);
                    op += 2;
                }
            }
        }

        diagPeak = peak;
        diagSumL2 = sumL2;
        diagSumR2 = sumR2;
        diagSamples = samples;
        return op;
    }

    private static int readS16(byte[] b, int off, boolean bigEndian) {
        int hi = bigEndian ? b[off] : b[off + 1];
        int lo = bigEndian ? b[off + 1] : b[off];
        return (short) ((hi << 8) | (lo & 0xFF));
    }

    private static void writeS16(byte[] b, int off, int v, boolean bigEndian) {
        if (bigEndian) {
            b[off] = (byte) (v >> 8);
            b[off + 1] = (byte) v;
        } else {
            b[off] = (byte) v;
            b[off + 1] = (byte) (v >> 8);
        }
    }

    private static int clampS16(float v) {
        if (v >= 32767.0f) {
            return 32767;
        }
        if (v <= -32768.0f) {
            return -32768;
        }
        return v >= 0.0f ? (int) (v + 0.5f) : (int) (v - 0.5f);
    }
}
