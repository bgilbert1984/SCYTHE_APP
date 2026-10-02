package com.rfscythe.commandops;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

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

    /** Local broadcast: reporter -> ScytheSensorService (new detections). */
    public static final String ACTION_DETECTION_READY =
            "com.rfscythe.commandops.ACTION_DETECTION_READY";
    public static final String EXTRA_DETECTIONS_JSON = "detections_json";
    // EXTRA_CENTER_HZ and EXTRA_TIMESTAMP_MS are reused for detections.

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
    /** Phase 2: persistence tracker -- peaks become detections here. */
    private final RfPersistenceTracker tracker = new RfPersistenceTracker();

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
                        List<RfPersistenceTracker.Detection> fresh =
                                detectPeaks(centerHz);
                        emit(copy, floorDb, centerHz, fresh);
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
                    // Persistence only resets when the stream itself stops;
                    // a relay outage must not lose RF tracks.
                    if (!sdr.isStreaming()) {
                        tracker.reset();
                    }
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

    private void emit(byte[] thumb, float floorDb, long centerHz,
                      List<RfPersistenceTracker.Detection> fresh) {
        long ts = System.currentTimeMillis();
        Intent i = new Intent(ACTION_SPECTRUM_READY)
                .putExtra(EXTRA_THUMB_B64, Base64.encodeToString(thumb, Base64.NO_WRAP))
                .putExtra(EXTRA_FLOOR_DB, floorDb)
                .putExtra(EXTRA_CENTER_HZ, centerHz)
                .putExtra(EXTRA_TIMESTAMP_MS, ts);
        LocalBroadcastManager.getInstance(appCtx).sendBroadcast(i);
        String status = "spectrum reporting every " + (reportIntervalMs / 1000)
                + "s — floor " + String.format("%.1f", floorDb) + " dB";
        int tracked = tracker.trackedCount();
        int reported = tracker.reportedCount();
        if (tracked > 0) {
            status += " — detections: " + reported + "/" + tracked
                    + (fresh.isEmpty() ? "" : " (" + fresh.size() + " new)");
        }
        publishStatus(status);
        Log.i(TAG, "spectrum report: center=" + centerHz + "Hz floor=" + floorDb
                + "dB tracked=" + tracked + " reported=" + reported);
    }

    /**
     * Phase 2: pulls the native peaks stashed by the last computeSpectrum(),
     * converts offsets to absolute frequencies, and feeds the persistence
     * tracker. Newly-persisting detections are broadcast for uplink.
     * A centre change inside the tracker resets all tracks (retune
     * invalidates peak matching).
     */
    private List<RfPersistenceTracker.Detection> detectPeaks(long centerHz) {
        List<RfSdrManager.Peak> peaks = sdr.getPeaks();
        List<RfPersistenceTracker.Peak> abs = new ArrayList<>(peaks.size());
        for (RfSdrManager.Peak p : peaks) {
            abs.add(new RfPersistenceTracker.Peak(centerHz + p.offsetHz,
                    p.snrDb, p.bwHz));
        }
        double intervalS = reportIntervalMs / 1000.0;
        List<RfPersistenceTracker.Detection> fresh =
                tracker.update(abs, centerHz, intervalS);
        if (!fresh.isEmpty()) {
            emitDetections(fresh, centerHz);
        }
        return fresh;
    }

    private void emitDetections(List<RfPersistenceTracker.Detection> fresh,
                                long centerHz) {
        try {
            JSONArray arr = new JSONArray();
            for (RfPersistenceTracker.Detection d : fresh) {
                arr.put(new JSONObject()
                        .put("freq_hz", d.freqHz)
                        .put("snr_db", (double) d.snrDb)
                        .put("bw_hz", (double) d.bwHz)
                        .put("persistence_s", d.persistenceS)
                        .put("n_observations", d.nObservations));
            }
            Intent i = new Intent(ACTION_DETECTION_READY)
                    .putExtra(EXTRA_DETECTIONS_JSON, arr.toString())
                    .putExtra(EXTRA_CENTER_HZ, centerHz)
                    .putExtra(EXTRA_TIMESTAMP_MS, System.currentTimeMillis());
            LocalBroadcastManager.getInstance(appCtx).sendBroadcast(i);
            StringBuilder sb = new StringBuilder("new detections persisting:");
            for (RfPersistenceTracker.Detection d : fresh) {
                sb.append(String.format(" %.3f MHz (%.1f dB)",
                        d.freqHz / 1e6, d.snrDb));
            }
            Log.i(TAG, sb.toString());
        } catch (JSONException e) {
            Log.w(TAG, "failed to build detection broadcast", e);
        }
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
