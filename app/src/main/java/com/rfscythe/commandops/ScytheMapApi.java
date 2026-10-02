package com.rfscythe.commandops;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * REST client for map-layer data from rf_scythe_api_server.py.
 * Endpoints mirror the ATAK plugin's ScytheApiClient:
 *   GET /api/rf-hypergraph/visualization  -> nodes (sensors, emitters)
 *
 * All calls are async via OkHttp; never block the UI thread.
 * Missing endpoints (older server checkouts) surface as onError —
 * callers must degrade to empty layers, never invented data.
 *
 * Phase B seam: add SSE entity streaming here.
 * Phase C seam: add CoT consume here.
 */
public class ScytheMapApi {

    private static final String TAG = "ScytheMapApi";

    public interface NodesCallback {
        void onSuccess(List<MapNode> nodes);
        void onError(String message);
    }

    private final OkHttpClient http;
    private volatile String baseUrl;

    public ScytheMapApi(String serverUrl) {
        this.baseUrl = ScytheConfig.normaliseServerUrl(serverUrl);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void updateServerUrl(String serverUrl) {
        this.baseUrl = ScytheConfig.normaliseServerUrl(serverUrl);
    }

    /**
     * GET /api/rf-hypergraph/visualization
     * Returns positioned nodes; caller filters by kind.
     */
    public void getNodes(final NodesCallback cb) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/rf-hypergraph/visualization")
                .header("Accept", "application/json")
                .get()
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "getNodes failed: " + e.getMessage());
                cb.onError(e.getMessage() != null ? e.getMessage() : "network error");
            }
            @Override public void onResponse(Call call, Response resp) {
                try {
                    if (!resp.isSuccessful()) {
                        cb.onError("HTTP " + resp.code() + " from /api/rf-hypergraph/visualization");
                        return;
                    }
                    String raw = resp.body() != null ? resp.body().string() : "";
                    JSONObject json = new JSONObject(raw);
                    JSONArray nodes = json.optJSONArray("nodes");
                    if (nodes == null) {
                        // Fallback: server may return a bare array.
                        try {
                            nodes = new JSONArray(raw);
                        } catch (Exception ignored) {
                            nodes = new JSONArray();
                        }
                    }
                    List<MapNode> out = new ArrayList<>();
                    for (int i = 0; i < nodes.length(); i++) {
                        try {
                            MapNode n = MapNode.fromJson(nodes.getJSONObject(i));
                            if (n.hasPosition()) out.add(n);
                        } catch (Exception ignore) { /* skip malformed */ }
                    }
                    cb.onSuccess(out);
                } catch (Exception e) {
                    cb.onError(e.getMessage() != null ? e.getMessage() : "parse error");
                } finally {
                    if (resp.body() != null) resp.body().close();
                }
            }
        });
    }
}
