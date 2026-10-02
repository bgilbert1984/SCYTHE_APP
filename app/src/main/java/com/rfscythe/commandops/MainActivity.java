package com.rfscythe.commandops;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Globe home: CesiumJS 3D globe (vendored locally, fully offline) in a WebView.
 *
 * - Dark globe (#071422), starfield, no imagery/terrain (offline by design).
 * - Own position (GPS): cyan dot, camera flies to first fix.
 * - Sensor nodes (friendly, blue squares) + coverage rings from
 *   GET /api/rf-hypergraph/visualization — OPERATOR mode only.
 * - RF emitters (amber diamonds) from the same endpoint.
 * - Right-edge layer rail: RF / AIR / SPC / SEA / COV.
 * - Mode banner (SAFARI green / OPERATOR amber), tappable.
 *   SAFARI mode NEVER fetches or renders sensor positions
 *   (structural absence, not hiding — enforced natively AND in JS).
 *
 * The top chrome sits below the display cutout via WindowInsets
 * (Android 15+ draws edge-to-edge by default).
 *
 * Native -> globe: WebView.evaluateJavascript against window.ScytheGlobe.
 * Globe -> native: GlobeBridge.onGlobeReady() (see GlobeBridge.java).
 *
 * The old WebView home lives on in ConsoleActivity.
 * Phase B seams: SSE entity stream -> refreshNodes(); marker tap ->
 * bottom sheet. Phase C seams: CoT consume, tune-to-emitter.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ScytheGlobeHome";
    private static final String PREFS_NAME = "ScytheCommandPrefs";
    private static final String PREF_MAP_MODE = "map_mode";
    private static final String MODE_OPERATOR = "operator";
    private static final String MODE_SAFARI = "safari";

    private static final String GLOBE_URL = "file:///android_asset/globe/scythe-globe.html";

    /** Phase A placeholder: nominal sensor coverage radius. Per-node when the API provides it. */
    private static final double COVERAGE_RADIUS_M = 25000;

    private WebView globeView;
    private GlobeBridge globeBridge;
    private volatile boolean globeReady = false;

    private View topBar;
    private TextView tvStatus;
    private TextView modeBanner;
    private LinearLayout sensorBar;
    private TextView tvSensorStatus;
    private TextView tvSensorMeta;
    private TextView tvSensorStop;
    private LinearLayout sdrBar;
    private TextView tvSdrStatus;
    private TextView tvSdrMeta;
    private TextView tvSdrStart;
    private TextView tvSdrStop;
    private RfSdrManager sdrManager;

    // Layer rail buttons
    private TextView btnLayerRf, btnLayerAir, btnLayerSpace, btnLayerSea, btnLayerCov;
    private boolean showRf = true;
    private boolean showCov = true;
    // AIR/SPC/SEA have no Phase A data source; rail shows them dimmed.
    private boolean airHasData = false;
    private boolean spaceHasData = false;
    private boolean seaHasData = false;

    private String mapMode = MODE_OPERATOR;
    private String serverUrl;

    private LocationManager locationManager;
    private Location lastLocation;
    private boolean centeredOnFix = false;

    private ScytheMapApi mapApi;
    private final List<MapNode> sensorNodes = new ArrayList<>();
    private final List<MapNode> emitterNodes = new ArrayList<>();

    private final BroadcastReceiver sensorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            boolean running = intent.getBooleanExtra(ScytheSensorService.EXTRA_RUNNING, false);
            if (!running) {
                sensorBar.setVisibility(View.GONE);
                return;
            }
            sensorBar.setVisibility(View.VISIBLE);
            int apCount = intent.getIntExtra(ScytheSensorService.EXTRA_AP_COUNT, 0);
            int bluetoothCount = intent.getIntExtra(ScytheSensorService.EXTRA_BT_COUNT, 0);
            boolean relayConnected = intent.getBooleanExtra(ScytheSensorService.EXTRA_RELAY_CONNECTED, false);
            int streamEvents = intent.getIntExtra(ScytheSensorService.EXTRA_STREAM_EVENTS, 0);
            int streamBatches = intent.getIntExtra(ScytheSensorService.EXTRA_STREAM_BATCHES, 0);
            long lastUplinkMs = intent.getLongExtra(ScytheSensorService.EXTRA_LAST_UPLINK_MS, 0L);
            String relayUrl = intent.getStringExtra(ScytheSensorService.EXTRA_RELAY_URL);
            String lastError = intent.getStringExtra(ScytheSensorService.EXTRA_LAST_ERROR);
            if (intent.hasExtra(ScytheSensorService.EXTRA_LAT)) {
                double lat = intent.getDoubleExtra(ScytheSensorService.EXTRA_LAT, 0);
                double lon = intent.getDoubleExtra(ScytheSensorService.EXTRA_LON, 0);
                tvSensorStatus.setText(String.format(Locale.US,
                    "📡 %.4f, %.4f  |  %d APs  |  %d BT", lat, lon, apCount, bluetoothCount));
            } else {
                tvSensorStatus.setText("📡 Acquiring GPS…  |  " + apCount + " APs  |  " + bluetoothCount + " BT");
            }
            String relaySummary = relayConnected ? "Relay online" : "Relay offline";
            if (streamEvents > 0 || streamBatches > 0) {
                relaySummary += "  |  " + streamEvents + " ev / " + streamBatches + " bursts";
            }
            if (lastUplinkMs > 0) {
                relaySummary += "  |  " + formatAge(lastUplinkMs);
            }
            if (relayUrl != null && !relayUrl.isEmpty()) {
                relaySummary += "\n" + relayUrl;
            } else if (lastError != null && !lastError.isEmpty()) {
                relaySummary += "\n" + lastError;
            }
            tvSensorMeta.setText(relaySummary);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            lastLocation = loc;
            pushSelfMarker();
            if (!centeredOnFix) {
                centeredOnFix = true;
                globeEval(String.format(Locale.US, "ScytheGlobe.flyTo(%f, %f, 1500000)",
                    loc.getLatitude(), loc.getLongitude()));
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrl = ScytheConfig.getServerUrl(this);
        mapApi = new ScytheMapApi(serverUrl);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mapMode = prefs.getString(PREF_MAP_MODE, MODE_OPERATOR);

        View rootLayout = findViewById(R.id.rootLayout);
        topBar = findViewById(R.id.topBar);
        globeView = findViewById(R.id.globeView);
        tvStatus = findViewById(R.id.tvStatus);
        modeBanner = findViewById(R.id.modeBanner);
        sensorBar = findViewById(R.id.sensorBar);
        tvSensorStatus = findViewById(R.id.tvSensorStatus);
        tvSensorMeta = findViewById(R.id.tvSensorMeta);
        tvSensorStop = findViewById(R.id.tvSensorStop);
        sdrBar = findViewById(R.id.sdrBar);
        tvSdrStatus = findViewById(R.id.tvSdrStatus);
        tvSdrMeta = findViewById(R.id.tvSdrMeta);
        tvSdrStart = findViewById(R.id.tvSdrStart);
        tvSdrStop = findViewById(R.id.tvSdrStop);
        btnLayerRf = findViewById(R.id.btnLayerRf);
        btnLayerAir = findViewById(R.id.btnLayerAir);
        btnLayerSpace = findViewById(R.id.btnLayerSpace);
        btnLayerSea = findViewById(R.id.btnLayerSea);
        btnLayerCov = findViewById(R.id.btnLayerCov);
        ImageButton btnConsole = findViewById(R.id.btnConsole);
        ImageButton btnTwin = findViewById(R.id.btnTwin);
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        // ---- Display-cutout fix: keep the top chrome below the notch ----
        // Android 15+ draws edge-to-edge by default, so the window extends under
        // the status bar / camera cutout. Pad the top chrome by the union of the
        // system-bar and cutout insets. Self-calibrating: on devices without a
        // cutout (or when not edge-to-edge) the inset is 0 and nothing moves.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            int top = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top;
            FrameLayout.LayoutParams barLp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
            if (barLp.topMargin != top) {
                barLp.topMargin = top;
                topBar.setLayoutParams(barLp);
                FrameLayout.LayoutParams bannerLp =
                    (FrameLayout.LayoutParams) modeBanner.getLayoutParams();
                bannerLp.topMargin = top
                    + (int) (40 * getResources().getDisplayMetrics().density);
                modeBanner.setLayoutParams(bannerLp);
            }
            return insets;
        });

        // ---- Cesium globe ----
        WebSettings ws = globeView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        globeView.setBackgroundColor(Color.BLACK);
        globeBridge = new GlobeBridge();
        globeBridge.setReadyListener(() -> runOnUiThread(this::onGlobeReady));
        globeView.addJavascriptInterface(globeBridge, "ScytheGlobeBridge");
        globeView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Cesium init is async; the page calls ScytheGlobeBridge.onGlobeReady().
                // Poll as a fallback in case the bridge callback was missed.
                view.evaluateJavascript("window.__scytheGlobeReady === true", value -> {
                    if ("true".equals(value)) runOnUiThread(() -> onGlobeReady());
                });
            }
        });
        globeView.loadUrl(GLOBE_URL);

        modeBanner.setOnClickListener(v -> toggleMode());
        btnLayerRf.setOnClickListener(v -> { showRf = !showRf; applyLayerVisibility(); paintRail(); });
        btnLayerCov.setOnClickListener(v -> { showCov = !showCov; applyLayerVisibility(); paintRail(); });
        btnLayerAir.setOnClickListener(v ->
            Toast.makeText(this, "AIR: no data source in Phase A", Toast.LENGTH_SHORT).show());
        btnLayerSpace.setOnClickListener(v ->
            Toast.makeText(this, "SPACE: no data source in Phase A", Toast.LENGTH_SHORT).show());
        btnLayerSea.setOnClickListener(v ->
            Toast.makeText(this, "SEA: no data source in Phase A", Toast.LENGTH_SHORT).show());

        btnConsole.setOnClickListener(v ->
            startActivity(new Intent(this, ConsoleActivity.class)));
        btnTwin.setOnClickListener(v ->
            startActivity(new Intent(this, DigitalTwinArActivity.class)));
        btnSettings.setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        tvSensorStop.setOnClickListener(v -> stopSensorService());

        // ---- Phase 0: phone SDR (inert without a dongle) ----
        tvSdrStart.setOnClickListener(v -> { if (sdrManager != null) sdrManager.userStart(); });
        tvSdrStop.setOnClickListener(v -> { if (sdrManager != null) sdrManager.userStop(); });
        sdrManager = new RfSdrManager(this);
        sdrManager.setListener((line1, line2) -> runOnUiThread(() -> {
            if (line1 == null) {
                sdrBar.setVisibility(View.GONE);
                return;
            }
            sdrBar.setVisibility(View.VISIBLE);
            tvSdrStatus.setText(line1);
            tvSdrMeta.setText(line2 != null ? line2 : "");
        }));
        sdrManager.start();
        sdrManager.handleIntent(getIntent());

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        requestRuntimePermissions();
        paintRail();
        updateModeBanner();
        updateStatusLine();
        refreshNodes();
    }

    // ------------------------------------------------------------------
    // Globe bridge
    // ------------------------------------------------------------------

    /** Evaluate JS against the globe page. Safe to call before ready (no-op). */
    private void globeEval(String js) {
        WebView w = globeView;
        if (w == null) return;
        runOnUiThread(() -> {
            try {
                w.evaluateJavascript(js, null);
            } catch (Exception e) {
                android.util.Log.w(TAG, "globe eval: " + e.getMessage());
            }
        });
    }

    /** Called once the Cesium viewer exists (bridge callback or poll fallback). */
    private void onGlobeReady() {
        if (globeReady) return;
        globeReady = true;
        android.util.Log.i(TAG, "globe ready; pushing state");
        pushAllToGlobe();
    }

    /** Push the full native state into a freshly-ready globe. */
    private void pushAllToGlobe() {
        if (!globeReady) return;
        globeEval("ScytheGlobe.setMode('" + mapMode + "')");
        applyLayerVisibility();
        renderNodes();
        pushSelfMarker();
    }

    private void applyLayerVisibility() {
        if (!globeReady) return;
        globeEval("ScytheGlobe.setLayerVisible('self', true)");
        globeEval("ScytheGlobe.setLayerVisible('rf', " + (showRf && isOperator()) + ")");
        globeEval("ScytheGlobe.setLayerVisible('cov', " + (showCov && isOperator()) + ")");
    }

    // ------------------------------------------------------------------
    // Node data
    // ------------------------------------------------------------------

    /** Refresh sensor/emitter nodes. SAFARI mode: never fetch, clear all. */
    private void refreshNodes() {
        if (!isOperator()) {
            // Structural absence: sensor positions must not exist in Safari mode.
            sensorNodes.clear();
            emitterNodes.clear();
            runOnUiThread(this::renderNodes);
            updateStatusLine();
            return;
        }
        mapApi.getNodes(new ScytheMapApi.NodesCallback() {
            @Override public void onSuccess(List<MapNode> nodes) {
                sensorNodes.clear();
                emitterNodes.clear();
                for (MapNode n : nodes) {
                    if (n.isSensor()) sensorNodes.add(n);
                    else if (n.isEmitter()) emitterNodes.add(n);
                }
                runOnUiThread(() -> {
                    renderNodes();
                    updateStatusLine();
                });
            }
            @Override public void onError(String message) {
                android.util.Log.w(TAG, "node fetch: " + message);
                runOnUiThread(() -> {
                    sensorNodes.clear();
                    emitterNodes.clear();
                    renderNodes();
                    tvStatus.setText("\u25cb map: no node feed (" + shortErr(message) + ")");
                    tvStatus.setTextColor(getColor(R.color.scythe_accent));
                });
            }
        });
    }

    private String shortErr(String m) {
        if (m == null) return "unreachable";
        return m.length() > 42 ? m.substring(0, 42) + "…" : m;
    }

    private void renderNodes() {
        if (!globeReady) return;
        globeEval("ScytheGlobe.setSensors(" + nodesToJson(sensorNodes, false) + ")");
        globeEval("ScytheGlobe.setEmitters(" + nodesToJson(emitterNodes, true) + ")");
        globeEval("ScytheGlobe.setCoverage(" + coverageToJson() + ")");
        applyLayerVisibility();
    }

    private String nodesToJson(List<MapNode> nodes, boolean withFreq) {
        JSONArray arr = new JSONArray();
        for (MapNode n : nodes) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("lat", n.lat);
                o.put("lon", n.lon);
                String lbl = n.label;
                if (withFreq) {
                    String fl = n.frequencyLabel();
                    if (!fl.isEmpty()) lbl = lbl + " " + fl;
                }
                o.put("label", lbl);
                arr.put(o);
            } catch (Exception e) {
                android.util.Log.w(TAG, "node json: " + e.getMessage());
            }
        }
        return arr.toString();
    }

    private String coverageToJson() {
        JSONArray arr = new JSONArray();
        for (MapNode n : sensorNodes) {
            try {
                JSONObject o = new JSONObject();
                o.put("lat", n.lat);
                o.put("lon", n.lon);
                o.put("radiusM", COVERAGE_RADIUS_M);
                arr.put(o);
            } catch (Exception e) {
                android.util.Log.w(TAG, "coverage json: " + e.getMessage());
            }
        }
        return arr.toString();
    }

    private void pushSelfMarker() {
        if (!globeReady || lastLocation == null) return;
        globeEval(String.format(Locale.US, "ScytheGlobe.setSelf(%f, %f)",
            lastLocation.getLatitude(), lastLocation.getLongitude()));
    }

    // ------------------------------------------------------------------
    // Mode banner + rail
    // ------------------------------------------------------------------

    private boolean isOperator() {
        return MODE_OPERATOR.equals(mapMode);
    }

    private void toggleMode() {
        mapMode = isOperator() ? MODE_SAFARI : MODE_OPERATOR;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(PREF_MAP_MODE, mapMode).apply();
        updateModeBanner();
        if (globeReady) globeEval("ScytheGlobe.setMode('" + mapMode + "')");
        refreshNodes();
    }

    private void updateModeBanner() {
        if (isOperator()) {
            modeBanner.setText("\u25cf OPERATOR \u2014 full picture");
            modeBanner.setBackgroundColor(Color.parseColor("#8a5a00"));
            modeBanner.setTextColor(Color.parseColor("#ffe0b0"));
        } else {
            modeBanner.setText("\u25cf SAFARI \u2014 public ambient feed");
            modeBanner.setBackgroundColor(Color.parseColor("#0a6b3a"));
            modeBanner.setTextColor(Color.parseColor("#c0ffd9"));
        }
    }

    private void paintRail() {
        paintToggle(btnLayerRf, showRf, true);
        paintToggle(btnLayerCov, showCov, true);
        paintToggle(btnLayerAir, false, airHasData);
        paintToggle(btnLayerSpace, false, spaceHasData);
        paintToggle(btnLayerSea, false, seaHasData);
    }

    private void paintToggle(TextView b, boolean on, boolean hasData) {
        if (!hasData) {
            b.setAlpha(0.35f);
            b.setBackgroundColor(Color.parseColor("#1a2035"));
            b.setTextColor(Color.parseColor("#5a6a8a"));
        } else if (on) {
            b.setAlpha(1f);
            b.setBackgroundColor(Color.parseColor("#00d4ff"));
            b.setTextColor(Color.parseColor("#0a0e1a"));
        } else {
            b.setAlpha(1f);
            b.setBackgroundColor(Color.parseColor("#1a2035"));
            b.setTextColor(Color.parseColor("#00d4ff"));
        }
    }

    private void updateStatusLine() {
        String base = isOperator() ? "\u25cf " + serverUrl : "\u25cf " + serverUrl + " (safari)";
        if (isOperator() && (!sensorNodes.isEmpty() || !emitterNodes.isEmpty())) {
            base += "  |  " + sensorNodes.size() + " sensors, " + emitterNodes.size() + " emitters";
        }
        tvStatus.setText(base);
        tvStatus.setTextColor(getColor(R.color.status_connected));
    }

    // ------------------------------------------------------------------
    // Permissions / location
    // ------------------------------------------------------------------

    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), 100);
        } else {
            startLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        startLocation();
    }

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (locationManager == null) return;
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 5000, 5, locationListener);
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) {
                lastLocation = last;
                pushSelfMarker();
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "location: " + e.getMessage());
        }
    }

    private void stopLocation() {
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (Exception ignored) {}
        }
    }

    private String formatAge(long ms) {
        long s = (System.currentTimeMillis() - ms) / 1000;
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        return (m / 60) + "h ago";
    }

    private void stopSensorService() {
        Intent intent = new Intent(this, ScytheSensorService.class);
        intent.setAction(ScytheSensorService.ACTION_STOP);
        startService(intent);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (globeView != null) globeView.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(
            sensorReceiver, new IntentFilter(ScytheSensorService.ACTION_STATUS));
        String newUrl = ScytheConfig.getServerUrl(this);
        if (!newUrl.equals(serverUrl)) {
            serverUrl = newUrl;
            mapApi.updateServerUrl(serverUrl);
            refreshNodes();
        }
        updateStatusLine();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (globeView != null) globeView.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopLocation();
        if (sdrManager != null) sdrManager.stop();
        if (globeView != null) globeView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (sdrManager != null) sdrManager.handleIntent(intent);
    }
}
