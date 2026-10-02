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

/* ------------------------------------------------------------------ */
/* Phase 2: native peak detection on the full-resolution averaged      */
/* periodogram (65536 bins, ~31.25 Hz/bin @ 2.048 MS/s), run BEFORE    */
/* the 256-bin downsample -- frequency accuracy matters.               */
/*                                                                     */
/* Detection constants (documented; Phase 3 tunes these):              */
/*   DSP_PEAK_THRESH_DB    12.0 dB above the median floor.             */
/*   DSP_PEAK_MIN_SEP_BINS 8 bins (~250 Hz): one emitter, one peak.    */
/*   DSP_MAX_PEAKS         32: hard cap per report (bounds the JNI     */
/*                         buffer; a phone screen of RF should never   */
/*                         hold more).                                 */
/*                                                                     */
/* Threshold justification: Hann periodogram noise in dB has std ~4.3  */
/* dB for a single FFT; 8-block linear averaging drops it to ~1.5 dB.  */
/* 12 dB is ~8 sigma above the noise -- thermal noise essentially      */
/* never trips it (the host test finds zero false peaks on pure       */
/* noise), while anything a human would call a signal (+20 dB and up)  */
/* clears it with margin.                                              */
/* ------------------------------------------------------------------ */
#define DSP_PEAK_THRESH_DB    12.0f
#define DSP_PEAK_MIN_SEP_BINS 8
#define DSP_MAX_PEAKS         32

/*
 * One detected peak. 12 bytes, no padding on LP64/arm64.
 * JNI layout (documented -- SdrNative.sdrGetPeaks reads this verbatim):
 *   offset 0: float offset_hz -- RF offset from the tune centre, Hz.
 *             bin * 2048000/65536 after fftshift (bin 0 = -fs/2).
 *             Java adds the centre: freq_hz = center_hz + offset_hz.
 *             (Absolute freq as float would quantize at ~8 Hz near
 *             100 MHz; the offset stays small and exact.)
 *   offset 4: float snr_db    -- peak dB minus the detection floor, dB.
 *   offset 8: float bw_hz     -- 3 dB occupied width, Hz.
 */
typedef struct {
    float offset_hz;
    float snr_db;
    float bw_hz;
} dsp_peak_t;

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

/*
 * Full pipeline: thumbnail + floor (as above) PLUS peak detection on the
 * full-resolution periodogram. One FFT pass -- the thumbnail and the peaks
 * always describe the same 256 ms of data.
 *   peaks    : caller-allocated array of >= max_peaks dsp_peak_t
 *              (may be NULL to skip detection, with max_peaks = 0)
 *   max_peaks: capacity of peaks[]
 * Returns the number of peaks found (>= 0), or -1 on bad arguments.
 */
int dsp_compute_spectrum_peaks(dsp_ctx_t *ctx, const uint8_t *bytes, size_t nbytes,
                               uint8_t *out256, float *floor_db,
                               dsp_peak_t *peaks, int max_peaks);

#endif
