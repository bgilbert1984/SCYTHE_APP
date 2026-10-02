package com.rfscythe.commandops;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Map home (Phase A): native MapLibre GL tactical map.
 *
 * - Full-screen dark map; own position (GPS) centered, blue dot.
 * - Sensor nodes (friendly, blue squares) + coverage rings from
 *   GET /api/rf-hypergraph/visualization — OPERATOR mode only.
 * - RF emitters (amber diamonds) from the same endpoint.
 * - Right-edge layer rail: RF / AIR / SPC / SEA / COV.
 * - Mode banner (SAFARI green / OPERATOR amber), tappable.
 *   SAFARI mode NEVER fetches or renders sensor positions
 *   (structural absence, not hiding).
 *
 * The old WebView home lives on in ConsoleActivity.
 * Phase B seams: SSE entity stream -> refreshNodes(); marker tap ->
 * bottom sheet. Phase C seams: CoT consume, tune-to-emitter.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ScytheMapHome";
    private static final String PREFS_NAME = "ScytheCommandPrefs";
    private static final String PREF_MAP_MODE = "map_mode";
    private static final String MODE_OPERATOR = "operator";
    private static final String MODE_SAFARI = "safari";

    private static final String STYLE_DARK =
        "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json";

    // MapLibre source / layer ids
    private static final String SRC_SELF = "self-src";
    private static final String LYR_SELF = "self-layer";
    private static final String SRC_SENSORS = "sensors-src";
    private static final String LYR_SENSORS = "sensors-layer";
    private static final String SRC_EMITTERS = "emitters-src";
    private static final String LYR_EMITTERS = "emitters-layer";
    private static final String SRC_COV = "coverage-src";
    private static final String LYR_COV_FILL = "coverage-fill";
    private static final String LYR_COV_LINE = "coverage-line";

    private static final String IMG_SELF = "marker-self";
    private static final String IMG_SENSOR = "marker-sensor";
    private static final String IMG_EMITTER = "marker-emitter";

    /** Phase A placeholder: nominal sensor coverage radius. Per-node when the API provides it. */
    private static final double COVERAGE_RADIUS_M = 25000;

    private MapView mapView;
    private MapLibreMap map;
    private Style mapStyle;

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
            updateSelfMarker();
            if (!centeredOnFix && map != null) {
                centeredOnFix = true;
                map.animateCamera(CameraUpdateFactory.newCameraPosition(
                    new CameraPosition.Builder()
                        .target(new LatLng(loc.getLatitude(), loc.getLongitude()))
                        .zoom(10)
                        .build()), 1200);
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        serverUrl = ScytheConfig.getServerUrl(this);
        mapApi = new ScytheMapApi(serverUrl);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mapMode = prefs.getString(PREF_MAP_MODE, MODE_OPERATOR);

        mapView = findViewById(R.id.mapView);
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

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(mapboxMap -> {
            map = mapboxMap;
            mapboxMap.setStyle(new Style.Builder().fromUri(STYLE_DARK), style -> {
                mapStyle = style;
                setupMapLayers();
                updateModeBanner();
                refreshNodes();
            });
        });

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
        updateStatusLine();
    }

    // ------------------------------------------------------------------
    // Map setup
    // ------------------------------------------------------------------

    private void setupMapLayers() {
        if (mapStyle == null) return;
        mapStyle.addImage(IMG_SELF, makeDotBitmap());
        mapStyle.addImage(IMG_SENSOR, makeSquareBitmap());
        mapStyle.addImage(IMG_EMITTER, makeDiamondBitmap());

        mapStyle.addSource(new GeoJsonSource(SRC_SELF));
        mapStyle.addSource(new GeoJsonSource(SRC_SENSORS));
        mapStyle.addSource(new GeoJsonSource(SRC_EMITTERS));
        mapStyle.addSource(new GeoJsonSource(SRC_COV));

        SymbolLayer selfLayer = new SymbolLayer(LYR_SELF, SRC_SELF);
        selfLayer.setProperties(
            PropertyFactory.iconImage(IMG_SELF),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true));
        mapStyle.addLayer(selfLayer);

        FillLayer covFill = new FillLayer(LYR_COV_FILL, SRC_COV);
        covFill.setProperties(
            PropertyFactory.fillColor("rgba(0, 212, 255, 0.07)"));
        mapStyle.addLayerBelow(covFill, LYR_SELF);

        LineLayer covLine = new LineLayer(LYR_COV_LINE, SRC_COV);
        covLine.setProperties(
            PropertyFactory.lineColor("rgba(0, 212, 255, 0.45)"),
            PropertyFactory.lineWidth(1.5f));
        mapStyle.addLayerAbove(covLine, LYR_COV_FILL);

        SymbolLayer sensorsLayer = new SymbolLayer(LYR_SENSORS, SRC_SENSORS);
        sensorsLayer.setProperties(
            PropertyFactory.iconImage(IMG_SENSOR),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField("{label}"),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#9fd8ff"),
            PropertyFactory.textOffset(new Float[]{0f, 1.6f}));
        mapStyle.addLayerAbove(sensorsLayer, LYR_COV_LINE);

        SymbolLayer emittersLayer = new SymbolLayer(LYR_EMITTERS, SRC_EMITTERS);
        emittersLayer.setProperties(
            PropertyFactory.iconImage(IMG_EMITTER),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField("{label}"),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#ffd9a0"),
            PropertyFactory.textOffset(new Float[]{0f, 1.6f}));
        mapStyle.addLayerAbove(emittersLayer, LYR_SENSORS);

        applyLayerVisibility();
    }

    private void applyLayerVisibility() {
        if (mapStyle == null) return;
        setVisible(LYR_SELF, true);
        setVisible(LYR_SENSORS, showRf && isOperator());
        setVisible(LYR_EMITTERS, showRf && isOperator());
        setVisible(LYR_COV_FILL, showCov && isOperator());
        setVisible(LYR_COV_LINE, showCov && isOperator());
    }

    private void setVisible(String layerId, boolean visible) {
        if (mapStyle == null) return;
        org.maplibre.android.style.layers.Layer l = mapStyle.getLayer(layerId);
        if (l != null) {
            l.setProperties(PropertyFactory.visibility(visible ? Property.VISIBLE : Property.NONE));
        }
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
        if (mapStyle == null) return;
        List<Feature> sensorFeats = new ArrayList<>();
        List<Feature> emitterFeats = new ArrayList<>();
        List<Feature> covFeats = new ArrayList<>();
        for (MapNode n : sensorNodes) {
            Feature f = Feature.fromGeometry(
                Point.fromLngLat(n.lon, n.lat));
            f.addStringProperty("label", n.label);
            sensorFeats.add(f);
            covFeats.add(Feature.fromGeometry(coveragePolygon(n.lat, n.lon, COVERAGE_RADIUS_M)));
        }
        for (MapNode n : emitterNodes) {
            String lbl = n.label;
            String fl = n.frequencyLabel();
            if (!fl.isEmpty()) lbl = lbl + " " + fl;
            Feature f = Feature.fromGeometry(
                Point.fromLngLat(n.lon, n.lat));
            f.addStringProperty("label", lbl);
            emitterFeats.add(f);
        }
        setSource(SRC_SENSORS, sensorFeats);
        setSource(SRC_EMITTERS, emitterFeats);
        setSource(SRC_COV, covFeats);
        applyLayerVisibility();
    }

    private void setSource(String id, List<Feature> feats) {
        if (mapStyle == null) return;
        GeoJsonSource src = mapStyle.getSourceAs(id);
        if (src != null) {
            src.setGeoJson(FeatureCollection.fromFeatures(feats));
        }
    }

    private void updateSelfMarker() {
        if (mapStyle == null || lastLocation == null) return;
        List<Feature> feats = new ArrayList<>();
        feats.add(Feature.fromGeometry(Point.fromLngLat(
            lastLocation.getLongitude(), lastLocation.getLatitude())));
        setSource(SRC_SELF, feats);
    }

    /** Approximate geodesic circle as a polygon (64 vertices). */
    private org.maplibre.geojson.Polygon coveragePolygon(double lat, double lon, double radiusM) {
        List<Point> ring = new ArrayList<>();
        double latR = Math.toRadians(lat);
        double angDist = radiusM / 6371000.0;
        for (int i = 0; i <= 64; i++) {
            double brng = Math.toRadians(i * (360.0 / 64));
            double la2 = Math.asin(Math.sin(latR) * Math.cos(angDist)
                + Math.cos(latR) * Math.sin(angDist) * Math.cos(brng));
            double lo2 = Math.toRadians(lon) + Math.atan2(
                Math.sin(brng) * Math.sin(angDist) * Math.cos(latR),
                Math.cos(angDist) - Math.sin(latR) * Math.sin(la2));
            ring.add(Point.fromLngLat(Math.toDegrees(lo2), Math.toDegrees(la2)));
        }
        List<List<Point>> coords = new ArrayList<>();
        coords.add(ring);
        return org.maplibre.geojson.Polygon.fromLngLats(coords);
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
    // Marker bitmaps (generated; no assets needed)
    // ------------------------------------------------------------------

    private Bitmap makeDotBitmap() {
        int s = 64;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#00d4ff"));
        c.drawCircle(s / 2f, s / 2f, 14f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(Color.WHITE);
        c.drawCircle(s / 2f, s / 2f, 14f, p);
        return b;
    }

    private Bitmap makeSquareBitmap() {
        int s = 64;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#4488ff"));
        c.drawRect(14, 14, 50, 50, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(Color.WHITE);
        c.drawRect(14, 14, 50, 50, p);
        return b;
    }

    private Bitmap makeDiamondBitmap() {
        int s = 64;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path path = new Path();
        path.moveTo(s / 2f, 8f);
        path.lineTo(56f, s / 2f);
        path.lineTo(s / 2f, 56f);
        path.lineTo(8f, s / 2f);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#ffaa00"));
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(Color.WHITE);
        c.drawPath(path, p);
        return b;
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
                updateSelfMarker();
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
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
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
        mapView.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        stopLocation();
        if (sdrManager != null) sdrManager.stop();
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (sdrManager != null) sdrManager.handleIntent(intent);
    }
}
