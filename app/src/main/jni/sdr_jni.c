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
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "rtl-sdr.h"

#define LOG_TAG "ScytheSdr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Phase 0 defaults: identical to the E4/E7 evidence captures. */
#define PHASE0_SAMPLE_RATE_HZ 2048000u
#define PHASE0_CENTER_HZ      100000000u
#define PHASE0_GAIN_TENTH_DB  400   /* 40.0 dB, manual gain (no AGC) */

/* Ring buffer between the async callback and Java polling. */
#define RING_CAP (4u * 1024u * 1024u)

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
    pthread_mutex_init(&s->lock, NULL);

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
    if (r == 0)
        rtlsdr_reset_buffer(s->dev); /* flush stale post-retune */
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
