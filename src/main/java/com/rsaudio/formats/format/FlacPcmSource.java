package com.rsaudio.formats.format;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import org.jflac.ChannelData;
import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.Metadata;
import org.jflac.metadata.StreamInfo;

/**
 * FLAC decoding through jFLAC. jFLAC exposes integer samples at the stream bit
 * depth (8, 16, 24 or 32 bit), so every sample is normalized to signed 16-bit
 * here. Duration comes from STREAMINFO and never requires a full decode.
 */
final class FlacPcmSource implements PcmSource {

    private final String name;
    private final InputStream stream;
    private final FLACDecoder decoder;
    private final AudioFormat format;
    private final int bitsPerSample;
    private final int channels;
    private final long totalSamples;

    private byte[] frameBytes = new byte[0];
    private int framePosition;
    private int frameLength;
    private boolean endOfStream;
    private boolean closed;

    private FlacPcmSource(
            String name,
            InputStream stream,
            FLACDecoder decoder,
            AudioFormat format,
            int bitsPerSample,
            int channels,
            long totalSamples) {
        this.name = name;
        this.stream = stream;
        this.decoder = decoder;
        this.format = format;
        this.bitsPerSample = bitsPerSample;
        this.channels = channels;
        this.totalSamples = totalSamples;
    }

    static PcmSource open(File file) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(file), 65536);
        boolean success = false;
        try {
            FLACDecoder decoder = new FLACDecoder(raw);
            Metadata[] metadata = decoder.readMetadata();
            StreamInfo info = null;
            for (Metadata block : metadata) {
                if (block instanceof StreamInfo) {
                    info = (StreamInfo) block;
                    break;
                }
            }
            if (info == null) {
                throw new IOException("missing FLAC STREAMINFO");
            }
            int channels = info.getChannels();
            int sampleRate = info.getSampleRate();
            int bits = info.getBitsPerSample();
            if (channels <= 0 || channels > 8) {
                throw new IOException("bad FLAC channel count: " + channels);
            }
            if (sampleRate <= 0) {
                throw new IOException("bad FLAC sample rate: " + sampleRate);
            }
            if (bits < 4 || bits > 32) {
                throw new IOException("bad FLAC bit depth: " + bits);
            }
            AudioFormat format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels, channels * 2, sampleRate, false);
            FlacPcmSource source =
                    new FlacPcmSource(file.getName(), raw, decoder, format, bits, channels, info.getTotalSamples());
            success = true;
            return source;
        } finally {
            if (!success) {
                closeQuietly(raw);
            }
        }
    }

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (closed) {
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
        int written = 0;
        try {
            while (written < len) {
                if (framePosition >= frameLength) {
                    if (endOfStream || !decodeFrame()) {
                        break;
                    }
                }
                int copy = Math.min(len - written, frameLength - framePosition);
                System.arraycopy(frameBytes, framePosition, buf, off + written, copy);
                framePosition += copy;
                written += copy;
            }
        } catch (IOException e) {
            endOfStream = true;
            FmLog.warn("FLAC read failed for " + name + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (RuntimeException e) {
            endOfStream = true;
            FmLog.warn("FLAC decoder failed for " + name + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        return written == 0 ? -1 : written;
    }

    private boolean decodeFrame() throws IOException {
        while (true) {
            Frame frame = decoder.readNextFrame();
            if (frame == null) {
                endOfStream = true;
                return false;
            }
            int blockSize = frame.header.blockSize;
            if (blockSize <= 0) {
                continue;
            }
            if (frame.header.channels != channels) {
                throw new IOException(
                        "FLAC channel count changed mid-stream: " + frame.header.channels + " != " + channels);
            }
            ChannelData[] channelData = decoder.getChannelData();
            if (channelData == null) {
                throw new IOException("jFLAC returned no channel buffers");
            }
            int needed = blockSize * channels * 2;
            if (frameBytes.length < needed) {
                frameBytes = new byte[needed];
            }
            int position = 0;
            for (int sample = 0; sample < blockSize; sample++) {
                for (int channel = 0; channel < channels; channel++) {
                    int value = channelData[channel].getOutput()[sample];
                    int normalized = toSigned16(value, bitsPerSample);
                    frameBytes[position++] = (byte) normalized;
                    frameBytes[position++] = (byte) (normalized >>> 8);
                }
            }
            framePosition = 0;
            frameLength = position;
            return true;
        }
    }

    static int toSigned16(int value, int bits) {
        int sample;
        if (bits > 16) {
            sample = value >> (bits - 16);
        } else if (bits < 16) {
            sample = value << (16 - bits);
        } else {
            sample = value;
        }
        if (sample > 32767) {
            return 32767;
        }
        if (sample < -32768) {
            return -32768;
        }
        return sample;
    }

    @Override
    public double durationSeconds() {
        if (totalSamples > 0 && format.getSampleRate() > 0) {
            return totalSamples / (double) format.getSampleRate();
        }
        return -1;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(stream);
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
