/*
 * sdr_dsp.c — Phase 1 edge DSP implementation.
 *
 * Pipeline per report:
 *   1. 8 consecutive blocks of 65536 uint8 I/Q samples (-> float [-1,1])
 *   2. Hann window + complex FFT per block, accumulate |X|^2 (linear power)
 *   3. Average the 8 power spectra (still linear)
 *   4. fftshift (bin 0 = -fs/2), downsample 65536 -> 256 by linear mean
 *   5. Convert to dBFS, quantize -90..0 dB -> 0..255
 *   6. Median of the 256 dB bins = reported floor
 *
 * No allocation in the hot path: all scratch lives in dsp_ctx_t.
 */

#include "sdr_dsp.h"
#include "kissfft/kiss_fft.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define DSP_PI 3.14159265358979323846

/* Hann coherent-gain correction: 20*log10(2) -- the window's DC gain is 0.5. */
#define DSP_HANN_CORR_DB 6.020599913279624

struct dsp_ctx {
    kiss_fft_cfg cfg;
    float *hann;          /* [DSP_FFT_N] */
    kiss_fft_cpx *buf;    /* [DSP_FFT_N] complex scratch (in-place FFT) */
    float *power_acc;     /* [DSP_FFT_N] accumulated linear power */
    float *thumb_db;      /* [DSP_THUMB_BINS] scratch for median */
};

dsp_ctx_t *dsp_create(void) {
    dsp_ctx_t *c = (dsp_ctx_t *)calloc(1, sizeof(*c));
    if (!c)
        return NULL;
    c->cfg = kiss_fft_alloc(DSP_FFT_N, 0, NULL, NULL);
    c->hann = (float *)malloc(sizeof(float) * DSP_FFT_N);
    c->buf = (kiss_fft_cpx *)malloc(sizeof(kiss_fft_cpx) * DSP_FFT_N);
    c->power_acc = (float *)malloc(sizeof(float) * DSP_FFT_N);
    c->thumb_db = (float *)malloc(sizeof(float) * DSP_THUMB_BINS);
    if (!c->cfg || !c->hann || !c->buf || !c->power_acc || !c->thumb_db) {
        dsp_destroy(c);
        return NULL;
    }
    for (int n = 0; n < DSP_FFT_N; n++)
        c->hann[n] = 0.5f * (1.0f - cosf((float)(2.0 * DSP_PI * n / DSP_FFT_N)));
    return c;
}

void dsp_destroy(dsp_ctx_t *ctx) {
    if (!ctx)
        return;
    /* kiss_fft_alloc without a custom mem allocator uses malloc; free() it. */
    free(ctx->cfg);
    free(ctx->hann);
    free(ctx->buf);
    free(ctx->power_acc);
    free(ctx->thumb_db);
    free(ctx);
}

static int quantize_db(float db) {
    float q = 255.0f * (db - DSP_DB_MIN) / (DSP_DB_MAX - DSP_DB_MIN);
    int qi = (int)(q + 0.5f);
    if (qi < 0)
        qi = 0;
    if (qi > 255)
        qi = 255;
    return qi;
}

/* Insertion sort is plenty for 256 elements; keeps this file dependency-free. */
static float median256(float *v) {
    for (int i = 1; i < DSP_THUMB_BINS; i++) {
        float x = v[i];
        int j = i - 1;
        while (j >= 0 && v[j] > x) {
            v[j + 1] = v[j];
            j--;
        }
        v[j + 1] = x;
    }
    return 0.5f * (v[DSP_THUMB_BINS / 2 - 1] + v[DSP_THUMB_BINS / 2]);
}

int dsp_compute_spectrum(dsp_ctx_t *ctx, const uint8_t *bytes, size_t nbytes,
                         uint8_t *out256, float *floor_db) {
    if (!ctx || !ctx->cfg || !bytes || nbytes < DSP_NEED_BYTES || !out256)
        return -1;

    const float norm = 20.0f * log10f((float)DSP_FFT_N);

    memset(ctx->power_acc, 0, sizeof(float) * DSP_FFT_N);

    for (int b = 0; b < DSP_AVG_BLOCKS; b++) {
        const uint8_t *blk = bytes + (size_t)b * DSP_FFT_N * 2u;
        for (int n = 0; n < DSP_FFT_N; n++) {
            float i = ((float)blk[2 * n] - 127.5f) / 127.5f;
            float q = ((float)blk[2 * n + 1] - 127.5f) / 127.5f;
            ctx->buf[n].r = i * ctx->hann[n];
            ctx->buf[n].i = q * ctx->hann[n];
        }
        /* In-place: kiss_fft permits out == in. */
        kiss_fft(ctx->cfg, ctx->buf, ctx->buf);
        for (int k = 0; k < DSP_FFT_N; k++) {
            float re = ctx->buf[k].r;
            float im = ctx->buf[k].i;
            ctx->power_acc[k] += re * re + im * im;
        }
    }

    /* Average, fftshift, downsample 65536 -> 256 (linear power throughout). */
    const int down = DSP_FFT_N / DSP_THUMB_BINS; /* 256 */
    const float inv_blocks = 1.0f / (float)DSP_AVG_BLOCKS;
    for (int g = 0; g < DSP_THUMB_BINS; g++) {
        double sum = 0.0;
        for (int j = 0; j < down; j++) {
            int k = (g * down + j + DSP_FFT_N / 2) % DSP_FFT_N; /* fftshift */
            sum += (double)ctx->power_acc[k];
        }
        double mean = sum * inv_blocks / (double)down;
        float db = (mean > 0.0)
            ? (float)(10.0 * log10(mean + 1e-30) - norm + DSP_HANN_CORR_DB)
            : -300.0f;
        ctx->thumb_db[g] = db;
        out256[g] = (uint8_t)quantize_db(db);
    }

    if (floor_db)
        *floor_db = median256(ctx->thumb_db);
    return 0;
}
