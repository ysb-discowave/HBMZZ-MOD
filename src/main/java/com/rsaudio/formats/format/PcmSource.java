package com.rsaudio.formats.format;

import java.io.Closeable;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;

/**
 * A decoded audio stream that always produces signed 16-bit little-endian
 * interleaved PCM.
 */
public interface PcmSource extends Closeable {

    /**
     * Format of the bytes returned by {@link #read(byte[], int, int)}.
     * Encoding is PCM_SIGNED, sample size is 16 bit.
     */
    AudioFormat format();

    /**
     * Fills {@code buf} with PCM bytes.
     *
     * @return number of bytes written, or -1 when the stream has ended
     */
    int read(byte[] buf, int off, int len) throws IOException;

    /**
     * Duration in seconds when it can be derived without decoding the whole
     * stream, otherwise -1.
     */
    double durationSeconds();
}
