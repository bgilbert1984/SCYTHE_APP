package com.rfscythe.commandops;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Phase 1: edge DSP spectrum reporter.
 *
 * Duty-cycled loop (this is a phone, not a server): while the SDR is
 * streaming AND the relay is up, compute a 256-bin spectrum thumbnail
 * natively every REPORT_INTERVAL_MS and hand the result to
 * ScytheSensorService (via local broadcast) for uplink as an
 * "android_rf_spectrum" event. Raw I/Q never leaves native code.
 *
 * Inert without a dongle: the loop idles when not streaming, emits
 * nothing when a spectrum can't be computed, and never spins.
 */
public class RfSpectrumReporter {
    private static final String TAG = "ScytheSpectrum";

    /** Local broadcast: reporter -> ScytheSensorService. */
    public static final String ACTION_SPECTRUM_READY =
            "com.rfscythe.commandops.ACTION_SPECTRUM_READY";
    public static final String EXTRA_THUMB_B64 = "thumb_b64";
    public static final String EXTRA_FLOOR_DB = "floor_db";
    public static final String EXTRA_CENTER_HZ = "center_hz";
    public static final String EXTRA_TIMESTAMP_MS = "ts_ms";

    /** Local broadcast: MainActivity -> reporter status line. */
    public static final String ACTION_REPORTER_STATUS =
            "com.rfscythe.commandops.ACTION_REPORTER_STATUS";
    public static final String EXTRA_STATUS = "status";

    /** Default 30 s duty cycle between reports. */
    public static final long DEFAULT_REPORT_INTERVAL_MS = 30_000L;
    /** Idle poll when streaming is down or relay is down. */
    private static final long IDLE_POLL_MS = 2_000L;
    /** Direct buffer for the 256 quantized thumbnail bins. */
    private static final int THUMB_BINS = 256;

    private final Context appCtx;
    private final RfSdrManager sdr;
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile boolean relayUp;
    private volatile long reportIntervalMs = DEFAULT_REPORT_INTERVAL_MS;
    private Thread worker;

    public RfSpectrumReporter(Context ctx, RfSdrManager sdr) {
        this.appCtx = ctx.getApplicationContext();
        this.sdr = sdr;
    }

    public void setRelayUp(boolean up) {
        relayUp = up;
    }

    public void setReportIntervalMs(long ms) {
        if (ms >= 5_000L) {
            reportIntervalMs = ms;
        }
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            worker = new Thread(this::loop, "ScytheSpectrumReporter");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = worker;
            worker = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(2_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        ByteBuffer thumb = ByteBuffer.allocateDirect(THUMB_BINS)
                .order(ByteOrder.nativeOrder());
        byte[] copy = new byte[THUMB_BINS];
        Log.i(TAG, "reporter loop started, interval=" + reportIntervalMs + "ms");
        while (running) {
            try {
                if (sdr.isStreaming() && relayUp) {
                    int rc = sdr.computeSpectrum(thumb);
                    if (rc == 0) {
                        thumb.position(0);
                        thumb.get(copy, 0, THUMB_BINS);
                        thumb.clear();
                        float floorDb = sdr.getSpectrumFloorDb();
                        long centerHz = sdr.getCenterFreqHz();
                        emit(copy, floorDb, centerHz);
                        sleepQuiet(reportIntervalMs);
                    } else if (rc == -2) {
                        // Tap not full yet; retry soon, not after a full interval.
                        publishStatus("spectrum tap filling…");
                        sleepQuiet(IDLE_POLL_MS);
                    } else {
                        sleepQuiet(IDLE_POLL_MS);
                    }
                } else {
                    // Not streaming or relay down: idle, never spin.
                    sleepQuiet(IDLE_POLL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "reporter iteration failed", e);
                try {
                    sleepQuiet(IDLE_POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Log.i(TAG, "reporter loop stopped");
    }

    private void emit(byte[] thumb, float floorDb, long centerHz) {
        long ts = System.currentTimeMillis();
        Intent i = new Intent(ACTION_SPECTRUM_READY)
                .putExtra(EXTRA_THUMB_B64, Base64.encodeToString(thumb, Base64.NO_WRAP))
                .putExtra(EXTRA_FLOOR_DB, floorDb)
                .putExtra(EXTRA_CENTER_HZ, centerHz)
                .putExtra(EXTRA_TIMESTAMP_MS, ts);
        LocalBroadcastManager.getInstance(appCtx).sendBroadcast(i);
        String status = "spectrum reporting every " + (reportIntervalMs / 1000)
                + "s — floor " + String.format("%.1f", floorDb) + " dB";
        publishStatus(status);
        Log.i(TAG, "spectrum report: center=" + centerHz + "Hz floor=" + floorDb + "dB");
    }

    private void publishStatus(String status) {
        LocalBroadcastManager.getInstance(appCtx).sendBroadcast(
                new Intent(ACTION_REPORTER_STATUS).putExtra(EXTRA_STATUS, status));
    }

    private void sleepQuiet(long ms) throws InterruptedException {
        // Interruptible sleep in 500 ms chunks so stop() is prompt.
        long deadline = SystemClock.elapsedRealtime() + ms;
        while (running) {
            long remain = deadline - SystemClock.elapsedRealtime();
            if (remain <= 0) {
                break;
            }
            Thread.sleep(Math.min(remain, 500L));
        }
    }
}
