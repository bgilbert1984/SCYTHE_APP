package com.rfscythe.commandops;

import android.util.Log;

/**
 * Phase 0 JNI declarations for libscythe_sdr.so.
 *
 * The native library is loaded LAZILY (ensureLoaded) so the app stays fully
 * inert on devices / installs without the native lib or without a dongle.
 * All methods are static; the native side owns a per-session handle (jlong).
 */
final class SdrNative {
    private static final String TAG = "ScytheSdr";
    private static boolean loaded;

    /** Loads libscythe_sdr.so on first use. Returns false if unavailable. */
    static synchronized boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        try {
            System.loadLibrary("scythe_sdr");
            loaded = true;
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libscythe_sdr not available", e);
            return false;
        }
    }

    /** Opens the dongle from the framework USB fd. Returns handle or 0. */
    static native long sdrOpen(int fd);

    /** Stops the stream if running and frees the handle. Does NOT close the USB fd. */
    static native void sdrClose(long handle);

    /** Retunes (flushes stale buffers). Returns 0 on success. */
    static native int sdrSetCenterFreq(long handle, long hz);

    static native int sdrSetSampleRate(long handle, int hz);

    /** Starts the native stream thread. 0 ok, -2 already streaming. */
    static native int sdrStart(long handle);

    /** Cancels from the calling (Java) thread and joins the stream thread. */
    static native int sdrStop(long handle);

    /**
     * Drains up to maxLen bytes into a direct ByteBuffer. Non-blocking;
     * returns bytes copied, 0 when empty, negative on error.
     */
    static native int sdrReadBlock(long handle, java.nio.ByteBuffer dst, int maxLen);

    static native long sdrGetTotalBytes(long handle);

    static native long sdrGetDropped(long handle);

    /** 1 if the stream thread is alive, else 0. */
    static native int sdrIsStreaming(long handle);

    /** rtlsdr_tuner enum value, or -1. */
    static native int sdrGetTunerType(long handle);

    // ---- Phase 1: edge DSP (spectrum reporter) ----

    /** 1 when the spectrum tap holds a full report's worth of I/Q, else 0. */
    static native int sdrSpectrumReady(long handle);

    /**
     * Computes the 256-bin spectrum thumbnail natively into a direct
     * ByteBuffer (capacity >= 256). Returns 0 ok, -1 bad handle/buffer,
     * -2 not enough data buffered yet.
     */
    static native int sdrComputeSpectrum(long handle, java.nio.ByteBuffer out256);

    /** Median floor (dB) from the last successful sdrComputeSpectrum. */
    static native float sdrGetFloorDb(long handle);

    /** Last successfully tuned centre frequency in Hz, or -1. */
    static native long sdrGetCenterFreq(long handle);

    // ---- Phase 2: native peak detection ----

    /**
     * Copies up to maxPeaks peaks stashed by the most recent
     * sdrComputeSpectrum() into a direct ByteBuffer: 3 little-endian
     * floats per peak -- offset_hz (RF offset from the tune centre),
     * snr_db (peak minus detection floor), bw_hz (3 dB width).
     * See dsp_peak_t in sdr_dsp.h. Call right after sdrComputeSpectrum();
     * the next compute overwrites them. Returns the peak count,
     * -1 on bad handle/buffer.
     */
    static native int sdrGetPeaks(long handle, java.nio.ByteBuffer dst, int maxPeaks);
}
