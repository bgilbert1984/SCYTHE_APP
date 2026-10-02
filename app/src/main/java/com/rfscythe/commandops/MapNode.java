package com.rfscythe.commandops;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Map DTO for a positioned node from the SCYTHE backend.
 * Shape follows the ATAK plugin's RFNode:
 *   GET /api/rf-hypergraph/visualization
 * {
 *   "id": "node-uuid",
 *   "kind": "rf_emitter" | "rf_signal" | "sensor" | ...,
 *   "position": [lat, lon, alt],
 *   "frequency": 2450000000.0,
 *   "labels": { "obs_class": "observed", "confidence": 0.87, ... },
 *   "metadata": { ... }
 * }
 *
 * Phase A: sensors (friendly) and rf_emitters (unknown) only.
 * Phase B/C seams: kind is preserved verbatim for future marker types.
 */
public class MapNode {

    public final String id;
    public final String kind;
    public final double lat;
    public final double lon;
    public final double frequencyHz;
    public final String label;

    private MapNode(String id, String kind, double lat, double lon,
                    double frequencyHz, String label) {
        this.id = id != null ? id : "";
        this.kind = kind != null ? kind : "unknown";
        this.lat = lat;
        this.lon = lon;
        this.frequencyHz = frequencyHz;
        this.label = label != null && !label.isEmpty() ? label : this.id;
    }

    public static MapNode fromJson(JSONObject o) {
        String id = o.optString("id", "");
        String kind = o.optString("kind", "unknown");
        double lat = 0, lon = 0;
        JSONArray pos = o.optJSONArray("position");
        if (pos != null && pos.length() >= 2) {
            lat = pos.optDouble(0, 0);
            lon = pos.optDouble(1, 0);
        } else {
            lat = o.optDouble("lat", 0);
            lon = o.optDouble("lon", o.optDouble("lng", 0));
        }
        double freq = o.optDouble("frequency", o.optDouble("frequency_hz", 0));
        String label = o.optString("callsign", "");
        if (label.isEmpty()) {
            JSONObject labels = o.optJSONObject("labels");
            if (labels != null) label = labels.optString("name", "");
        }
        if (label.isEmpty()) label = id;
        return new MapNode(id, kind, lat, lon, freq, label);
    }

    public boolean hasPosition() {
        return lat != 0 || lon != 0;
    }

    public boolean isSensor() {
        return "sensor".equals(kind);
    }

    public boolean isEmitter() {
        return "rf_emitter".equals(kind) || "rf_signal".equals(kind);
    }

    /** Friendly frequency label, e.g. "100.8 MHz". Empty when unknown. */
    public String frequencyLabel() {
        if (frequencyHz <= 0) return "";
        if (frequencyHz >= 1e9) return String.format("%.3f GHz", frequencyHz / 1e9);
        if (frequencyHz >= 1e6) return String.format("%.1f MHz", frequencyHz / 1e6);
        return String.format("%.0f kHz", frequencyHz / 1e3);
    }
}
