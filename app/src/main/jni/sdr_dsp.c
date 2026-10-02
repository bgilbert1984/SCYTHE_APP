/*
 * sdr_dsp.c — Phase 1 edge DSP + Phase 2 native peak detection.
 *
 * Pipeline per report:
 *   1. 8 consecutive blocks of 65536 uint8 I/Q samples (-> float [-1,1])
 *   2. Hann window + complex FFT per block, accumulate |X|^2 (linear power)
 *   3. Average the 8 power spectra (still linear)
 *   4a. Full-resolution dB array (65536 bins, fftshifted) -> peak detection
 *   4b. fftshift, downsample 65536 -> 256 by linear mean
 *   5. Convert to dBFS, quantize -90..0 dB -> 0..255
 *   6. Median of the 256 dB bins = reported floor (also the peak threshold
 *      reference: threshold = floor + DSP_PEAK_THRESH_DB)
 *
 * Peak detection runs on the FULL-resolution periodogram BEFORE the
 * downsample, in the same FFT pass as the thumbnail -- both describe the
 * same 256 ms of data.
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

/* Sample rate the DSP is tuned for (must match the streamer's 2.048 MS/s). */
#define DSP_SAMPLE_RATE_HZ 2048000.0

/* Candidate collection bound: a phone screen of RF should never need more;
 * if exceeded, only the first found are kept (documented, not silent --
 * the count saturates, the strongest survive the greedy pass anyway). */
#define DSP_MAX_CANDIDATES 2048

/* Bins around DC excluded from detection: the RTL-SDR DC offset spike
 * lives here and is not a signal. +/-2 bins = +/-62.5 Hz notch. */
#define DSP_DC_NOTCH_BINS 2

struct dsp_ctx {
    kiss_fft_cfg cfg;
    float *hann;          /* [DSP_FFT_N] */
    kiss_fft_cpx *buf;    /* [DSP_FFT_N] complex scratch (in-place FFT) */
    float *power_acc;     /* [DSP_FFT_N] accumulated linear power */
    float *thumb_db;      /* [DSP_THUMB_BINS] scratch for median */
    float *spec_db;       /* [DSP_FFT_N] full-res dB, fftshifted (Phase 2) */
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
    c->spec_db = (float *)malloc(sizeof(float) * DSP_FFT_N);
    if (!c->cfg || !c->hann || !c->buf || !c->power_acc || !c->thumb_db ||
        !c->spec_db) {
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
    free(ctx->spec_db);
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

/* qsort comparator: candidate bins ordered by descending power. */
static float *g_sort_db; /* set before qsort; single-threaded use only */
static int cmp_cand_desc(const void *a, const void *b) {
    int ia = *(const int *)a;
    int ib = *(const int *)b;
    float da = g_sort_db[ia];
    float db = g_sort_db[ib];
    return (da < db) - (da > db);
}

/*
 * Phase 2 peak detection on the full-resolution fftshifted dB array.
 *   spec_db : [DSP_FFT_N] dB values, bin g = (g - N/2) * fs/N Hz offset
 *   floor_db: detection floor (thumbnail median); threshold = floor + 12 dB
 *   peaks   : output array, capacity max_peaks (if NULL/0, detection skipped)
 * Returns peak count (>= 0).
 */
static int detect_peaks(const float *spec_db, float floor_db,
                        dsp_peak_t *peaks, int max_peaks) {
    if (!peaks || max_peaks <= 0)
        return 0;

    const float thresh = floor_db + DSP_PEAK_THRESH_DB;
    const float bin_hz = (float)(DSP_SAMPLE_RATE_HZ / DSP_FFT_N);
    const int dc = DSP_FFT_N / 2;

    int cand[DSP_MAX_CANDIDATES];
    int ncand = 0;
    for (int g = 1; g < DSP_FFT_N - 1 && ncand < DSP_MAX_CANDIDATES; g++) {
        if (g >= dc - DSP_DC_NOTCH_BINS && g <= dc + DSP_DC_NOTCH_BINS)
            continue; /* DC spike notch */
        float v = spec_db[g];
        if (v > thresh && v > spec_db[g - 1] && v >= spec_db[g + 1])
            cand[ncand++] = g;
    }

    /* Strongest first; greedy accept with minimum bin separation so one
     * wide emitter yields one peak, not a comb of sidelobes. */
    g_sort_db = (float *)spec_db;
    qsort(cand, (size_t)ncand, sizeof(int), cmp_cand_desc);
    g_sort_db = NULL;

    int accepted[DSP_MAX_PEAKS];
    int nacc = 0;
    for (int i = 0; i < ncand && nacc < max_peaks; i++) {
        int g = cand[i];
        int ok = 1;
        for (int j = 0; j < nacc; j++) {
            int d = g - accepted[j];
            if (d < 0)
                d = -d;
            if (d < DSP_PEAK_MIN_SEP_BINS) {
                ok = 0;
                break;
            }
        }
        if (!ok)
            continue;
        accepted[nacc] = g;

        /* 3 dB occupied width: walk from the peak until power drops 3 dB. */
        float pk = spec_db[g];
        int lo = g, hi = g;
        while (lo > 0 && spec_db[lo - 1] > pk - 3.0f)
            lo--;
        while (hi < DSP_FFT_N - 1 && spec_db[hi + 1] > pk - 3.0f)
            hi++;

        peaks[nacc].offset_hz = (float)(g - dc) * bin_hz;
        peaks[nacc].snr_db = pk - floor_db;
        peaks[nacc].bw_hz = (float)(hi - lo + 1) * bin_hz;
        nacc++;
    }
    return nacc;
}

int dsp_compute_spectrum_peaks(dsp_ctx_t *ctx, const uint8_t *bytes, size_t nbytes,
                               uint8_t *out256, float *floor_db,
                               dsp_peak_t *peaks, int max_peaks) {
    if (!ctx || !ctx->cfg || !ctx->spec_db || !bytes ||
        nbytes < DSP_NEED_BYTES || !out256)
        return -1;
    if ((peaks == NULL) != (max_peaks <= 0))
        return -1; /* peaks and max_peaks must agree */

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

    /* Full-resolution dB array (fftshifted) for peak detection. */
    const float inv_blocks = 1.0f / (float)DSP_AVG_BLOCKS;
    for (int g = 0; g < DSP_FFT_N; g++) {
        int k = (g + DSP_FFT_N / 2) % DSP_FFT_N; /* fftshift */
        double p = (double)ctx->power_acc[k] * (double)inv_blocks;
        ctx->spec_db[g] = (p > 0.0)
            ? (float)(10.0 * log10(p + 1e-30) - norm + DSP_HANN_CORR_DB)
            : -300.0f;
    }

    /* Average, fftshift, downsample 65536 -> 256 (linear power throughout). */
    const int down = DSP_FFT_N / DSP_THUMB_BINS; /* 256 */
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

    float floor = median256(ctx->thumb_db);
    if (floor_db)
        *floor_db = floor;

    return detect_peaks(ctx->spec_db, floor, peaks, max_peaks);
}

int dsp_compute_spectrum(dsp_ctx_t *ctx, const uint8_t *bytes, size_t nbytes,
                         uint8_t *out256, float *floor_db) {
    int n = dsp_compute_spectrum_peaks(ctx, bytes, nbytes, out256, floor_db,
                                       NULL, 0);
    return n < 0 ? -1 : 0;
}
