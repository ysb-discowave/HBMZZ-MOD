package com.rsaudio.formats.format;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * MP3 duration from the frame headers. Used only when javax.sound reports no
 * frame length (mp3spi does not scan the file). The scan is sequential, reads
 * four bytes per frame and never loads the file into memory.
 */
final class Mp3Duration {

    private static final int[] RATES_MPEG1 = {44100, 48000, 32000};
    private static final int[] RATES_MPEG2 = {22050, 24000, 16000};
    private static final int[] RATES_MPEG25 = {11025, 12000, 8000};

    private static final int[] BITRATES_MPEG1_L1 = {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};
    private static final int[] BITRATES_MPEG1_L2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
    private static final int[] BITRATES_MPEG1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BITRATES_MPEG2_L1 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256};
    private static final int[] BITRATES_MPEG2_L23 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    private Mp3Duration() {}

    /** Returns the duration in seconds, or -1 when no valid frame was found. */
    static double seconds(File file) {
        RandomAccessFile input = null;
        try {
            input = new RandomAccessFile(file, "r");
            long length = input.length();
            long position = skipId3(input);
            double seconds = 0;
            int frames = 0;
            int resyncs = 0;
            while (position + 4 <= length) {
                Header header = readHeader(input, position);
                if (header == null) {
                    position++;
                    if (++resyncs > 65536) {
                        break;
                    }
                    continue;
                }
                if (frames == 0 && !hasSecondFrame(input, position, header, length)) {
                    position++;
                    if (++resyncs > 65536) {
                        break;
                    }
                    continue;
                }
                resyncs = 0;
                seconds += header.samples / (double) header.sampleRate;
                frames++;
                long next = position + header.frameLength;
                if (next <= position) {
                    break;
                }
                position = next;
            }
            return frames == 0 ? -1 : seconds;
        } catch (IOException e) {
            return -1;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static long skipId3(RandomAccessFile input) throws IOException {
        if (input.length() < 10) {
            return 0;
        }
        input.seek(0);
        byte[] header = new byte[10];
        int read = 0;
        while (read < header.length) {
            int count = input.read(header, read, header.length - read);
            if (count < 0) {
                return 0;
            }
            read += count;
        }
        if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            return 0;
        }
        int size = ((header[6] & 0x7F) << 21)
                | ((header[7] & 0x7F) << 14)
                | ((header[8] & 0x7F) << 7)
                | (header[9] & 0x7F);
        long offset = 10L + size;
        if ((header[5] & 0x10) != 0) {
            offset += 10L;
        }
        return Math.min(offset, input.length());
    }

    private static Header readHeader(RandomAccessFile input, long position) throws IOException {
        input.seek(position);
        int b0 = input.read();
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            return null;
        }
        return parse(b0, b1, b2, b3);
    }

    private static boolean hasSecondFrame(RandomAccessFile input, long position, Header header, long length)
            throws IOException {
        long next = position + header.frameLength;
        if (next + 4 > length) {
            return true;
        }
        Header second = readHeader(input, next);
        return second != null
                && second.sampleRate == header.sampleRate
                && second.layer == header.layer
                && second.version == header.version;
    }

    private static Header parse(int b0, int b1, int b2, int b3) {
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return null;
        }
        int version = (b1 >> 3) & 0x03;
        int layer = (b1 >> 1) & 0x03;
        if (version == 1 || layer == 0) {
            return null;
        }
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int rateIndex = (b2 >> 2) & 0x03;
        int padding = (b2 >> 1) & 0x01;
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
            return null;
        }
        int[] rates = version == 3 ? RATES_MPEG1 : version == 2 ? RATES_MPEG2 : RATES_MPEG25;
        int sampleRate = rates[rateIndex];
        int[] bitrates;
        if (version == 3) {
            bitrates = layer == 3 ? BITRATES_MPEG1_L1 : layer == 2 ? BITRATES_MPEG1_L2 : BITRATES_MPEG1_L3;
        } else {
            bitrates = layer == 3 ? BITRATES_MPEG2_L1 : BITRATES_MPEG2_L23;
        }
        int bitrate = bitrates[bitrateIndex] * 1000;
        int samples;
        if (layer == 3) {
            samples = 384;
        } else if (layer == 2) {
            samples = 1152;
        } else {
            samples = version == 3 ? 1152 : 576;
        }
        long frameLength;
        if (layer == 3) {
            frameLength = ((12L * bitrate / sampleRate) + padding) * 4L;
        } else {
            frameLength = (samples / 8L) * bitrate / sampleRate + padding;
        }
        if (frameLength < 4 || frameLength > 8192) {
            return null;
        }
        return new Header(version, layer, sampleRate, samples, (int) frameLength);
    }

    private static final class Header {
        final int version;
        final int layer;
        final int sampleRate;
        final int samples;
        final int frameLength;

        Header(int version, int layer, int sampleRate, int samples, int frameLength) {
            this.version = version;
            this.layer = layer;
            this.sampleRate = sampleRate;
            this.samples = samples;
            this.frameLength = frameLength;
        }
    }
}
