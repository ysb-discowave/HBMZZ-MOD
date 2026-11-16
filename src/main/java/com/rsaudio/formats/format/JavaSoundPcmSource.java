package com.rsaudio.formats.format;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * WAV / AIFF / AU / MP3 (through mp3spi) decoding via javax.sound.sampled,
 * converted to signed 16-bit little-endian PCM at the source sample rate and
 * channel count.
 */
final class JavaSoundPcmSource implements PcmSource {

    private final String name;
    private final AudioInputStream pcm;
    private final AudioFormat format;
    private final double seconds;
    private boolean endOfStream;
    private boolean closed;

    private JavaSoundPcmSource(String name, AudioInputStream pcm, AudioFormat format, double seconds) {
        this.name = name;
        this.pcm = pcm;
        this.format = format;
        this.seconds = seconds;
    }

    static PcmSource open(File file) throws Exception {
        AudioInputStream source = AudioSystem.getAudioInputStream(file);
        boolean success = false;
        try {
            AudioFormat sourceFormat = source.getFormat();
            int channels = sourceFormat.getChannels();
            float sampleRate = sourceFormat.getSampleRate();
            if (channels <= 0 || sampleRate <= 0) {
                throw new IOException("invalid source format: " + sourceFormat);
            }
            long frameLength = source.getFrameLength();
            int frameSize = sourceFormat.getFrameSize();
            if (frameLength != AudioSystem.NOT_SPECIFIED && frameLength > 0 && frameSize > 0
                    && isRawPcm(sourceFormat)
                    && frameLength * frameSize > file.length() + 4096L) {
                // Some JDK readers report the data chunk size in bytes as the
                // frame length (PCM_FLOAT WAV is one). Rebuild it from the file.
                frameLength = file.length() / frameSize;
            }
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels, channels * 2, sampleRate, false);
            AudioInputStream stream;
            if (sameLayout(sourceFormat, target)) {
                stream = source;
            } else {
                try {
                    stream = AudioSystem.getAudioInputStream(target, source);
                } catch (IllegalArgumentException e) {
                    throw new IOException("no conversion to 16-bit PCM for " + sourceFormat, e);
                }
            }
            float frameRate = sourceFormat.getFrameRate();
            if (frameRate == AudioSystem.NOT_SPECIFIED || frameRate <= 0) {
                frameRate = sampleRate;
            }
            double seconds = -1;
            if (frameLength != AudioSystem.NOT_SPECIFIED && frameLength > 0) {
                seconds = frameLength / (double) frameRate;
            }
            if (seconds <= 0 && "mp3".equals(FormatRegistry.codecLabel(file))) {
                seconds = Mp3Duration.seconds(file);
            }
            JavaSoundPcmSource result = new JavaSoundPcmSource(file.getName(), stream, target, seconds);
            success = true;
            return result;
        } finally {
            if (!success) {
                closeQuietly(source);
            }
        }
    }

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (closed || endOfStream) {
            return -1;
        }
        if (buf == null) {
            throw new NullPointerException("buf");
        }
        if (off < 0 || len < 0 || off + len > buf.length) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len);
        }
        if (len == 0) {
            return 0;
        }
        int total = 0;
        try {
            while (total < len) {
                int read = pcm.read(buf, off + total, len - total);
                if (read < 0) {
                    endOfStream = true;
                    break;
                }
                if (read == 0) {
                    break;
                }
                total += read;
            }
        } catch (IOException e) {
            endOfStream = true;
            FmLog.warn("javax.sound read failed for " + name + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (RuntimeException e) {
            endOfStream = true;
            FmLog.warn("javax.sound decoder failed for " + name + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public double durationSeconds() {
        return seconds;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(pcm);
    }

    private static boolean sameLayout(AudioFormat source, AudioFormat target) {
        return AudioFormat.Encoding.PCM_SIGNED.equals(source.getEncoding())
                && source.getSampleSizeInBits() == 16
                && source.getChannels() == target.getChannels()
                && source.getSampleRate() == target.getSampleRate()
                && source.getFrameSize() == target.getFrameSize()
                && !source.isBigEndian();
    }

    private static boolean isRawPcm(AudioFormat format) {
        AudioFormat.Encoding encoding = format.getEncoding();
        return AudioFormat.Encoding.PCM_SIGNED.equals(encoding)
                || AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding)
                || AudioFormat.Encoding.PCM_FLOAT.equals(encoding)
                || AudioFormat.Encoding.ALAW.equals(encoding)
                || AudioFormat.Encoding.ULAW.equals(encoding);
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
