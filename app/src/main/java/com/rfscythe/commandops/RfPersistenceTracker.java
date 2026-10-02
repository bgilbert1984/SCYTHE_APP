package com.rfscythe.commandops;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2: persistence tracking for native peak detections.
 *
 * A peak becomes a detection only after it is observed in K consecutive
 * reports ("persistent across reconnect" -- never RECONNECT_STABLE; that
 * is a §5.21 catalogue stability class, and these are pre-catalogue phone
 * detections). Exactly one android_rf_detection event is emitted per
 * track, on first-persisting; a track that stays visible is suppressed
 * until it drops and reappears.
 *
 * All detection constants live here, in one place, so Phase 3 can tune
 * them:
 *   MATCH_TOL_HZ = 500 Hz  -- a measured peak matches a track within this.
 *   PERSIST_K = 3          -- consecutive observations to persist
 *                             (90 s at the 30 s report cadence).
 *   MISS_M = 2             -- consecutive misses before a track is dropped;
 *                             a reappearance after a drop is a NEW event.
 *
 * A centre-frequency change invalidates every track (a peak at 100.8 MHz
 * is not the same emitter at 101.3 MHz), as does an explicit reset()
 * (stream stop).
 *
 * Pure logic, no Android dependencies beyond java.util -- unit-testable.
 */
public class RfPersistenceTracker {

    /** Peak matches a track when within this frequency, Hz. */
    public static final double MATCH_TOL_HZ = 500.0;

    /** Consecutive observations before a track counts as a detection. */
    public static final int PERSIST_K = 3;

    /** Consecutive misses before a track is dropped. */
    public static final int MISS_M = 2;

    /** One measured peak, absolute frequency. */
    public static final class Peak {
        public final double freqHz;
        public final float snrDb;
        public final float bwHz;

        public Peak(double freqHz, float snrDb, float bwHz) {
            this.freqHz = freqHz;
            this.snrDb = snrDb;
            this.bwHz = bwHz;
        }
    }

    /** A newly-persisting detection, emitted exactly once per track. */
    public static final class Detection {
        public final double freqHz;
        public final float snrDb;
        public final float bwHz;
        /** nObservations * reportIntervalS (90 s for K=3 at 30 s cadence). */
        public final double persistenceS;
        public final int nObservations;

        Detection(double freqHz, float snrDb, float bwHz,
                  double persistenceS, int nObservations) {
            this.freqHz = freqHz;
            this.snrDb = snrDb;
            this.bwHz = bwHz;
            this.persistenceS = persistenceS;
            this.nObservations = nObservations;
        }
    }

    private static final class Track {
        double freqHz;
        float snrDb;
        float bwHz;
        int obs;
        int misses;
        boolean reported;
    }

    private final List<Track> tracks = new ArrayList<>();
    private long trackCenterHz = -1L;

    /** Drop all tracks (retune is handled via centre change; stream stop). */
    public synchronized void reset() {
        tracks.clear();
        trackCenterHz = -1L;
    }

    /**
     * Feed one report's peaks. Returns the newly-persisting detections.
     *
     * @param peaks          measured peaks this report (absolute Hz)
     * @param centerHz       tune centre this report was taken at
     * @param reportIntervalS seconds between reports (for persistence_s)
     */
    public synchronized List<Detection> update(List<Peak> peaks, long centerHz,
                                               double reportIntervalS) {
        List<Detection> fresh = new ArrayList<>();
        if (centerHz != trackCenterHz) {
            reset();
            trackCenterHz = centerHz;
        }

        boolean[] used = new boolean[peaks.size()];
        for (Track t : tracks) {
            int best = -1;
            double bestD = MATCH_TOL_HZ;
            for (int i = 0; i < peaks.size(); i++) {
                if (used[i]) {
                    continue;
                }
                double d = Math.abs(peaks.get(i).freqHz - t.freqHz);
                if (d <= bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best >= 0) {
                used[best] = true;
                Peak p = peaks.get(best);
                t.freqHz = p.freqHz;
                t.snrDb = p.snrDb;
                t.bwHz = p.bwHz;
                t.obs++;
                t.misses = 0;
                if (t.obs >= PERSIST_K && !t.reported) {
                    t.reported = true;
                    fresh.add(new Detection(t.freqHz, t.snrDb, t.bwHz,
                            t.obs * reportIntervalS, t.obs));
                }
            } else {
                t.misses++;
            }
        }

        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (tracks.get(i).misses >= MISS_M) {
                tracks.remove(i);
            }
        }

        for (int i = 0; i < peaks.size(); i++) {
            if (used[i]) {
                continue;
            }
            Peak p = peaks.get(i);
            Track t = new Track();
            t.freqHz = p.freqHz;
            t.snrDb = p.snrDb;
            t.bwHz = p.bwHz;
            t.obs = 1;
            if (t.obs >= PERSIST_K && !t.reported) {
                t.reported = true;
                fresh.add(new Detection(t.freqHz, t.snrDb, t.bwHz,
                        t.obs * reportIntervalS, t.obs));
            }
            tracks.add(t);
        }
        return fresh;
    }

    /** Tracks currently held (persisting or not). */
    public synchronized int trackedCount() {
        return tracks.size();
    }

    /** Tracks that have emitted their one detection event. */
    public synchronized int reportedCount() {
        int c = 0;
        for (Track t : tracks) {
            if (t.reported) {
                c++;
            }
        }
        return c;
    }
}
