/*
 * sdr_jni.c — Phase 0 JNI bridge: USB file descriptor -> librtlsdr streaming.
 *
 * Ownership model (see BUILD_NOTES.md):
 *  - The Java UsbDeviceConnection owns the USB file descriptor for the whole
 *    session (strong reference held by RfSdrManager). libusb_close() does NOT
 *    close a wrapped descriptor, so we must NOT dup() it here either --
 *    holding the Java reference AND a dup would close it twice.
 *  - The interface claim is owned by libusb (rtlsdr_open_fd claims it).
 *    Java must NOT call UsbDeviceConnection.claimInterface().
 *  - rtlsdr_cancel_async() is always called from the JNI calling thread
 *    (a Java thread), never from the native stream thread blocked in
 *    rtlsdr_read_async(). Cancelling from the blocked thread deadlocks.
 *
 * Phase 0 scope: open / tune / stream raw blocks / sanity stats.
 * No FFT, no detection -- that is Phase 1+.
 */

#include <jni.h>
#include <android/log.h>
#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "rtl-sdr.h"
#include "sdr_dsp.h"

#define LOG_TAG "ScytheSdr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Phase 0 defaults: identical to the E4/E7 evidence captures. */
#define PHASE0_SAMPLE_RATE_HZ 2048000u
#define PHASE0_CENTER_HZ      100000000u
#define PHASE0_GAIN_TENTH_DB  400   /* 40.0 dB, manual gain (no AGC) */

/* Ring buffer between the async callback and Java polling. */
#define RING_CAP (4u * 1024u * 1024u)

/* Phase 1 spectrum tap: overwrite-oldest ring fed by the same async
 * callback. Holds ~0.5 s; the reporter drains DSP_NEED_BYTES (1 MB)
 * without contending with the poll thread's primary drain. */
#define SPEC_RING_CAP (2u * 1024u * 1024u)

typedef struct {
    rtlsdr_dev_t *dev;
    pthread_t thread;
    int thread_running;   /* stream thread alive */
    uint8_t *ring;
    size_t head;          /* write index */
    size_t tail;          /* read index */
    size_t avail;         /* bytes available to read */
    uint64_t total_bytes; /* bytes ever delivered to the ring */
    uint64_t dropped;     /* bytes dropped on ring overflow */
    pthread_mutex_t lock;
    /* Phase 1: spectrum tap ring + DSP state. */
    uint8_t *spec_ring;
    size_t spec_head;
    size_t spec_tail;
    size_t spec_avail;
    dsp_ctx_t *dsp;       /* lazy: kiss cfg + Hann table */
    float last_floor_db;
    /* Phase 2: peaks stashed by sdrComputeSpectrum, read by sdrGetPeaks.
     * Same data as the thumbnail -- no second FFT, no re-drain. */
    dsp_peak_t last_peaks[DSP_MAX_PEAKS];
    int last_npeaks;
    uint32_t center_hz;
} sdr_t;

static sdr_t *handle_of(jlong h) {
    return (sdr_t *)(intptr_t)h;
}

/* Runs on librtlsdr's async callback path. Only takes the ring lock;
 * never calls back into Java (per-buffer JNI upcalls drop samples). */
static void sdr_async_cb(unsigned char *buf, uint32_t len, void *ctx) {
    sdr_t *s = (sdr_t *)ctx;

    pthread_mutex_lock(&s->lock);
    size_t space = RING_CAP - s->avail;
    size_t n = len < space ? len : space;
    if (n < len)
        s->dropped += (len - n);

    size_t first = RING_CAP - s->head;
    if (first > n)
        first = n;
    memcpy(s->ring + s->head, buf, first);
    memcpy(s->ring, buf + first, n - first);
    s->head = (s->head + n) % RING_CAP;
    s->avail += n;
    s->total_bytes += n;

    /* Phase 1 spectrum tap: overwrite-oldest, always takes the full len. */
    if (s->spec_ring) {
        size_t m = len;
        if (m > SPEC_RING_CAP)
            m = SPEC_RING_CAP;
        const unsigned char *src = buf + (len - m);
        size_t sfirst = SPEC_RING_CAP - s->spec_head;
        if (sfirst > m)
            sfirst = m;
        memcpy(s->spec_ring + s->spec_head, src, sfirst);
        memcpy(s->spec_ring, src + sfirst, m - sfirst);
        s->spec_head = (s->spec_head + m) % SPEC_RING_CAP;
        if (s->spec_avail + m > SPEC_RING_CAP) {
            size_t over = s->spec_avail + m - SPEC_RING_CAP;
            s->spec_tail = (s->spec_tail + over) % SPEC_RING_CAP;
            s->spec_avail = SPEC_RING_CAP;
        } else {
            s->spec_avail += m;
        }
    }
    pthread_mutex_unlock(&s->lock);
}

/* Blocks in rtlsdr_read_async until rtlsdr_cancel_async() or fatal error. */
static void *stream_thread(void *arg) {
    sdr_t *s = (sdr_t *)arg;
    int r = rtlsdr_read_async(s->dev, sdr_async_cb, s, 32, 16384);
    if (r < 0)
        LOGE("rtlsdr_read_async ended rc=%d", r);
    else
        LOGI("rtlsdr_read_async ended cleanly");
    return NULL;
}

static void sdr_destroy(sdr_t *s) {
    if (!s)
        return;
    pthread_mutex_destroy(&s->lock);
    free(s->ring);
    free(s->spec_ring);
    dsp_destroy(s->dsp);
    free(s);
}

/*
 * class:     com_rfscythe_commandops_SdrNative
 * method:    sdrOpen
 * signature: (I)J
 *
 * Opens the dongle from the framework-provided file descriptor, tunes it to
 * the Phase 0 defaults and flushes one stale block. Returns a native handle,
 * or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrOpen(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;

    sdr_t *s = (sdr_t *)calloc(1, sizeof(*s));
    if (!s)
        return 0;
    s->ring = (uint8_t *)malloc(RING_CAP);
    if (!s->ring) {
        free(s);
        return 0;
    }
    s->spec_ring = (uint8_t *)malloc(SPEC_RING_CAP);
    if (!s->spec_ring) {
        free(s->ring);
        free(s);
        return 0;
    }
    pthread_mutex_init(&s->lock, NULL);
    s->center_hz = PHASE0_CENTER_HZ;

    int r = rtlsdr_open_fd(&s->dev, fd);
    if (r < 0 || !s->dev) {
        LOGE("rtlsdr_open_fd failed rc=%d", r);
        sdr_destroy(s);
        return 0;
    }
    LOGI("device opened, tuner_type=%d", (int)rtlsdr_get_tuner_type(s->dev));

    if ((r = rtlsdr_set_sample_rate(s->dev, PHASE0_SAMPLE_RATE_HZ)) < 0) {
        LOGE("set_sample_rate rc=%d", r);
        goto fail;
    }
    if ((r = rtlsdr_set_center_freq(s->dev, PHASE0_CENTER_HZ)) < 0) {
        LOGE("set_center_freq rc=%d", r);
        goto fail;
    }
    /* Fixed gain: AGC would smear Phase 1+ measurements across their
     * own dynamic range, and gain must travel with comparable products. */
    rtlsdr_set_tuner_gain_mode(s->dev, 1);
    rtlsdr_set_tuner_gain(s->dev, PHASE0_GAIN_TENTH_DB);
    rtlsdr_reset_buffer(s->dev);

    /* Discard one block post-tune: in-flight buffers after a retune carry
     * the previous centre frequency's transients. */
    {
        uint8_t tmp[16384];
        int n = 0;
        rtlsdr_read_sync(s->dev, tmp, sizeof(tmp), &n);
        LOGI("flushed %d stale bytes post-tune", n);
    }

    LOGI("open ok: %u S/s @ %u Hz, gain %d.%d dB",
         PHASE0_SAMPLE_RATE_HZ, PHASE0_CENTER_HZ,
         PHASE0_GAIN_TENTH_DB / 10, PHASE0_GAIN_TENTH_DB % 10);
    return (jlong)(intptr_t)s;

fail:
    rtlsdr_close(s->dev);
    sdr_destroy(s);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrClose(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return;

    int was_running;
    pthread_mutex_lock(&s->lock);
    was_running = s->thread_running;
    pthread_mutex_unlock(&s->lock);
    if (was_running) {
        /* Called on a Java thread -- never the stream thread. Safe. */
        rtlsdr_cancel_async(s->dev);
        pthread_join(s->thread, NULL);
    }
    /* NOTE: rtlsdr_close() does NOT close the wrapped USB fd;
     * the Java UsbDeviceConnection still owns it. */
    rtlsdr_close(s->dev);
    sdr_destroy(s);
    LOGI("closed");
}

JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrSetCenterFreq(JNIEnv *env, jclass cls,
                                                        jlong h, jlong hz) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return -1;
    int r = rtlsdr_set_center_freq(s->dev, (uint32_t)hz);
    if (r == 0) {
        rtlsdr_reset_buffer(s->dev); /* flush stale post-retune */
        pthread_mutex_lock(&s->lock);
        s->center_hz = (uint32_t)hz;
        /* Phase 2: the spectrum tap still holds pre-retune I/Q. Drain it so
         * the next report only sees the new centre -- otherwise a peak from
         * the old centre would be stamped with the new one. */
        s->spec_head = 0;
        s->spec_tail = 0;
        s->spec_avail = 0;
        s->last_npeaks = 0;
        pthread_mutex_unlock(&s->lock);
    }
    return r;
}

JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrSetSampleRate(JNIEnv *env, jclass cls,
                                                        jlong h, jint hz) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return -1;
    return rtlsdr_set_sample_rate(s->dev, (uint32_t)hz);
}

JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrStart(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return -1;

    pthread_mutex_lock(&s->lock);
    if (s->thread_running) {
        pthread_mutex_unlock(&s->lock);
        return -2;
    }
    s->thread_running = 1;
    pthread_mutex_unlock(&s->lock);

    if (pthread_create(&s->thread, NULL, stream_thread, s) != 0) {
        pthread_mutex_lock(&s->lock);
        s->thread_running = 0;
        pthread_mutex_unlock(&s->lock);
        LOGE("pthread_create failed");
        return -3;
    }
    LOGI("streaming started");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrStop(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return -1;

    pthread_mutex_lock(&s->lock);
    int was_running = s->thread_running;
    pthread_mutex_unlock(&s->lock);
    if (!was_running)
        return -2;

    /* Called on a Java thread, which is never the stream thread:
     * cancelling from the blocked thread would deadlock. */
    rtlsdr_cancel_async(s->dev);
    pthread_join(s->thread, NULL);

    pthread_mutex_lock(&s->lock);
    s->thread_running = 0;
    pthread_mutex_unlock(&s->lock);
    LOGI("streaming stopped");
    return 0;
}

/*
 * Drains up to maxLen bytes from the ring into a direct ByteBuffer.
 * Non-blocking: returns 0 when no data is available. Never performs a
 * JNI upcall per async buffer -- one call per poll.
 */
JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrReadBlock(JNIEnv *env, jclass cls,
                                                    jlong h, jobject dst,
                                                    jint maxLen) {
    (void)cls;
    sdr_t *s = handle_of(h);
    if (!s || !dst || maxLen <= 0)
        return -1;

    uint8_t *out = (*env)->GetDirectBufferAddress(env, dst);
    jlong cap = (*env)->GetDirectBufferCapacity(env, dst);
    if (!out || cap < maxLen)
        return -2;

    pthread_mutex_lock(&s->lock);
    size_t n = s->avail < (size_t)maxLen ? s->avail : (size_t)maxLen;
    size_t first = RING_CAP - s->tail;
    if (first > n)
        first = n;
    memcpy(out, s->ring + s->tail, first);
    memcpy(out + first, s->ring, n - first);
    s->tail = (s->tail + n) % RING_CAP;
    s->avail -= n;
    pthread_mutex_unlock(&s->lock);
    return (jint)n;
}

JNIEXPORT jlong JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrGetTotalBytes(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return -1;
    pthread_mutex_lock(&s->lock);
    uint64_t n = s->total_bytes;
    pthread_mutex_unlock(&s->lock);
    return (jlong)n;
}

JNIEXPORT jlong JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrGetDropped(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return -1;
    pthread_mutex_lock(&s->lock);
    uint64_t n = s->dropped;
    pthread_mutex_unlock(&s->lock);
    return (jlong)n;
}

JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrIsStreaming(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return 0;
    pthread_mutex_lock(&s->lock);
    int running = s->thread_running;
    pthread_mutex_unlock(&s->lock);
    return running;
}

JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrGetTunerType(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s || !s->dev)
        return -1;
    return (jint)rtlsdr_get_tuner_type(s->dev);
}

/* ------------------------------------------------------------------ */
/* Phase 1: edge DSP — spectrum reporter.                              */
/*                                                                     */
/* The reporter drains DSP_NEED_BYTES (8 x 65536 I/Q samples, ~256 ms) */
/* from the dedicated spectrum tap ring (no contention with the poll    */
/* thread) and runs the Hann periodogram natively. Java only ever sees  */
/* the 256 quantized bytes + the median floor. Raw I/Q never crosses    */
/* the JNI boundary for reporting.                                     */
/* ------------------------------------------------------------------ */

/*
 * Returns 1 when the spectrum tap holds enough data for a report,
 * 0 otherwise.
 */
JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrSpectrumReady(JNIEnv *env, jclass cls,
                                                        jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return 0;
    pthread_mutex_lock(&s->lock);
    int ready = s->spec_avail >= DSP_NEED_BYTES;
    pthread_mutex_unlock(&s->lock);
    return ready;
}

/*
 * Drains one report's worth of I/Q from the spectrum tap and computes the
 * 256-bin thumbnail into a direct ByteBuffer (must have capacity >= 256).
 * Phase 2: peak detection runs in the same FFT pass; the peaks are stashed
 * and read back with sdrGetPeaks() before the next compute. Thumbnail and
 * peaks always describe the same 256 ms of data.
 * Returns 0 on success, -1 on bad handle/buffer, -2 when not enough data
 * is buffered yet.
 *
 * The FFT runs WITHOUT the ring lock held. The Java caller
 * (RfSdrManager.computeSpectrum) holds its own stateLock for the whole
 * call, so teardown cannot free the handle mid-compute.
 */
JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrComputeSpectrum(JNIEnv *env, jclass cls,
                                                          jlong h, jobject dst) {
    (void)cls;
    sdr_t *s = handle_of(h);
    if (!s || !dst)
        return -1;

    uint8_t *out = (*env)->GetDirectBufferAddress(env, dst);
    jlong cap = (*env)->GetDirectBufferCapacity(env, dst);
    if (!out || cap < DSP_THUMB_BINS)
        return -1;

    uint8_t *raw = (uint8_t *)malloc(DSP_NEED_BYTES);
    if (!raw)
        return -1;

    pthread_mutex_lock(&s->lock);
    if (s->spec_avail < DSP_NEED_BYTES) {
        pthread_mutex_unlock(&s->lock);
        free(raw);
        return -2;
    }
    size_t first = SPEC_RING_CAP - s->spec_tail;
    if (first > DSP_NEED_BYTES)
        first = DSP_NEED_BYTES;
    memcpy(raw, s->spec_ring + s->spec_tail, first);
    memcpy(raw + first, s->spec_ring, DSP_NEED_BYTES - first);
    s->spec_tail = (s->spec_tail + DSP_NEED_BYTES) % SPEC_RING_CAP;
    s->spec_avail -= DSP_NEED_BYTES;
    if (!s->dsp)
        s->dsp = dsp_create(); /* lazy: kiss cfg + Hann table */
    dsp_ctx_t *dsp = s->dsp;
    pthread_mutex_unlock(&s->lock);

    if (!dsp) {
        free(raw);
        return -1;
    }
    float floor_db = 0.0f;
    dsp_peak_t peaks[DSP_MAX_PEAKS];
    int npeaks = dsp_compute_spectrum_peaks(dsp, raw, DSP_NEED_BYTES, out,
                                            &floor_db, peaks, DSP_MAX_PEAKS);
    free(raw);
    if (npeaks < 0)
        return -1;

    pthread_mutex_lock(&s->lock);
    s->last_floor_db = floor_db;
    memcpy(s->last_peaks, peaks, sizeof(dsp_peak_t) * (size_t)npeaks);
    s->last_npeaks = npeaks;
    pthread_mutex_unlock(&s->lock);
    return 0;
}

/* Median floor (dB) from the most recent successful sdrComputeSpectrum. */
JNIEXPORT jfloat JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrGetFloorDb(JNIEnv *env, jclass cls,
                                                     jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return nanf("");
    pthread_mutex_lock(&s->lock);
    float f = s->last_floor_db;
    pthread_mutex_unlock(&s->lock);
    return (jfloat)f;
}

/* Last successfully tuned centre frequency, Hz (-1 on bad handle). */
JNIEXPORT jlong JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrGetCenterFreq(JNIEnv *env, jclass cls,
                                                        jlong h) {
    (void)env; (void)cls;
    sdr_t *s = handle_of(h);
    if (!s)
        return -1;
    pthread_mutex_lock(&s->lock);
    uint32_t hz = s->center_hz;
    pthread_mutex_unlock(&s->lock);
    return (jlong)hz;
}

/* ------------------------------------------------------------------ */
/* Phase 2: peak readout.                                              */
/*                                                                     */
/* Copies up to maxPeaks peaks stashed by the most recent              */
/* sdrComputeSpectrum() into a direct ByteBuffer: 3 little-endian      */
/* floats per peak (offset_hz, snr_db, bw_hz -- see dsp_peak_t in       */
/* sdr_dsp.h). Call right after sdrComputeSpectrum(); the next        */
/* compute overwrites them. Returns the peak count, -1 on bad          */
/* handle/buffer.                                                      */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL
Java_com_rfscythe_commandops_SdrNative_sdrGetPeaks(JNIEnv *env, jclass cls,
                                                  jlong h, jobject dst,
                                                  jint maxPeaks) {
    (void)cls;
    sdr_t *s = handle_of(h);
    if (!s || !dst || maxPeaks <= 0 || maxPeaks > DSP_MAX_PEAKS)
        return -1;

    uint8_t *out = (*env)->GetDirectBufferAddress(env, dst);
    jlong cap = (*env)->GetDirectBufferCapacity(env, dst);
    if (!out || cap < (jlong)maxPeaks * (jlong)sizeof(dsp_peak_t))
        return -1;

    pthread_mutex_lock(&s->lock);
    int n = s->last_npeaks;
    if (n > maxPeaks)
        n = maxPeaks;
    memcpy(out, s->last_peaks, sizeof(dsp_peak_t) * (size_t)n);
    pthread_mutex_unlock(&s->lock);
    return n;
}
