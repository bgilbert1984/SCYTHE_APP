/*
 * sdr_dsp.h — Phase 1 edge DSP: Hann-windowed periodogram -> 256-bin thumbnail.
 *
 * Pure DSP, no JNI, no USB: compiles both for Android (via sdr_jni.c) and
 * for the host (dsp unit test). All multi-block averaging is done in LINEAR
 * power; dB conversion happens once, after downsampling.
 *
 * dBFS convention (documented, do not change silently):
 *   - uint8 I/Q -> float in [-1, 1]: x = (u - 127.5) / 127.5
 *   - Hann window, coherent-gain corrected (+6.02 dB)
 *   - P_dBFS[k] = 10*log10(P_lin[k]) - 20*log10(N) + 6.02
 *     A full-scale complex tone (amplitude 1.0) at a bin centre reads 0 dBFS
 *     at full resolution. The 256-bin downsample dilutes a single-bin tone
 *     by 10*log10(256) = 24.08 dB -- expected, not a bug.
 *   - Thumbnail covers the FULL span: bin 0 = -fs/2, bin 128 = DC,
 *     bin 255 = +fs/2 - one bin.
 *   - Quantization: -90 dB -> 0, 0 dB -> 255, linear in dB, clamped.
 */
#ifndef SCYTHE_SDR_DSP_H
#define SCYTHE_SDR_DSP_H

#include <stddef.h>
#include <stdint.h>

#define DSP_FFT_N      65536   /* per-block FFT size (~31.25 Hz bins @ 2.048 MS/s) */
#define DSP_AVG_BLOCKS 8       /* blocks averaged per report (~256 ms of data) */
#define DSP_THUMB_BINS 256     /* downsampled thumbnail width */
#define DSP_DB_MIN     -90.0f  /* quantize floor */
#define DSP_DB_MAX     0.0f    /* quantize ceiling (dBFS) */

/* Bytes of uint8 I/Q consumed per dsp_compute_spectrum() call. */
#define DSP_NEED_BYTES ((size_t)DSP_AVG_BLOCKS * DSP_FFT_N * 2u)

typedef struct dsp_ctx dsp_ctx_t;

dsp_ctx_t *dsp_create(void);
void dsp_destroy(dsp_ctx_t *ctx);

/*
 * Compute a 256-bin spectrum thumbnail from raw uint8 I/Q.
 *   bytes   : >= DSP_NEED_BYTES of interleaved uint8 I/Q
 *   out256  : 256 quantized bytes (caller-allocated)
 *   floor_db: median of the 256 thumbnail bins, in dB (may be NULL)
 * Returns 0 on success, -1 on bad arguments/allocation failure.
 */
int dsp_compute_spectrum(dsp_ctx_t *ctx, const uint8_t *bytes, size_t nbytes,
                         uint8_t *out256, float *floor_db);

#endif
