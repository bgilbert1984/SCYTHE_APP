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
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG         = "ScytheMain";
    private static final String PREFS_NAME  = "ScytheCommandPrefs";
    private static final String KEY_SERVER  = "server_url";
    private static final String DEFAULT_URL = "http://192.168.1.185:5001";
    private static final String DEMO_URL    = "file:///android_asset/eve_demo.html";
    private static final String TWIN_URL    = "file:///android_asset/digital_twin.html";
    private static final int    REQ_PERMS   = 100;

    private WebView       webView;
    private LinearLayout  loadingOverlay;
    private TextView      tvStatus;
    private TextView      tvLoadingMsg;
    private LinearLayout  sensorBar;
    private TextView      tvSensorStatus;
    private TextView      tvSensorMeta;
    private TextView      tvSensorStop;

    private String serverUrl;

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
            String relaySummary = relayConnected
                ? "Relay online"
                : "Relay offline";
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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView        = findViewById(R.id.webView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        tvStatus       = findViewById(R.id.tvStatus);
        tvLoadingMsg   = findViewById(R.id.tvLoadingMsg);
        sensorBar      = findViewById(R.id.sensorBar);
        tvSensorStatus = findViewById(R.id.tvSensorStatus);
        tvSensorMeta   = findViewById(R.id.tvSensorMeta);
        tvSensorStop   = findViewById(R.id.tvSensorStop);
        Button      btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button      btnOfflineDemo  = findViewById(R.id.btnOfflineDemo);
        Button      btnDigitalTwin  = findViewById(R.id.btnDigitalTwin);
        Button      btnDemo         = findViewById(R.id.btnDemo);
        Button      btnTwin         = findViewById(R.id.btnTwin);
        ImageButton btnSettings     = findViewById(R.id.btnSettings);
        ImageButton btnReload       = findViewById(R.id.btnReload);

        serverUrl = ScytheConfig.getServerUrl(this);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString(ws.getUserAgentString() + " ScytheCommand/1.0");

        ScytheBridge bridge = new ScytheBridge(this);
        webView.addJavascriptInterface(bridge, "ScytheBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    tvStatus.setText("⟳ Loading…");
                    tvStatus.setTextColor(getColor(R.color.scythe_accent));
                });
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    if (DEMO_URL.equals(url)) {
                        tvStatus.setText("● OFFLINE DEMO");
                        tvStatus.setTextColor(getColor(R.color.scythe_accent));
                    } else if (TWIN_URL.equals(url)) {
                        tvStatus.setText("● DIGITAL TWIN");
                        tvStatus.setTextColor(getColor(R.color.scythe_accent));
                    } else {
                        tvStatus.setText("● " + serverUrl);
                        tvStatus.setTextColor(getColor(R.color.status_connected));
                    }
                });
            }
            @Override
            public void onReceivedError(WebView view,
                    android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    runOnUiThread(() -> {
                        tvStatus.setText("○ Unreachable");
                        tvStatus.setTextColor(getColor(R.color.status_disconnected));
                        tvLoadingMsg.setText("Cannot reach " + serverUrl
                            + "\nCheck server and network, or open the offline lane demo.");
                        loadingOverlay.setVisibility(View.VISIBLE);
                    });
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    android.util.Log.e(TAG, "[JS] " + msg.message()
                        + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")");
                }
                return true;
            }
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progress < 100)
                    runOnUiThread(() -> tvStatus.setText("⟳ " + progress + "%"));
            }
        });

        btnSettings.setOnClickListener(v -> openSettings());
        btnOpenSettings.setOnClickListener(v -> openSettings());
        btnOfflineDemo.setOnClickListener(v -> loadOfflineDemo());
        btnDigitalTwin.setOnClickListener(v -> loadDigitalTwin());
        btnDemo.setOnClickListener(v -> loadOfflineDemo());
        btnTwin.setOnClickListener(v -> loadDigitalTwin());
        btnReload.setOnClickListener(v -> loadScythe());
        tvSensorStop.setOnClickListener(v -> stopSensorService());

        requestRuntimePermissions();
        loadScythe();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(
            sensorReceiver, new IntentFilter(ScytheSensorService.ACTION_STATUS));
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String newUrl = prefs.getString(KEY_SERVER, DEFAULT_URL);
        if (!newUrl.equals(serverUrl)) loadScythe();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(sensorReceiver);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    void loadScythe() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        serverUrl = ScytheConfig.normaliseServerUrl(prefs.getString(KEY_SERVER, DEFAULT_URL));
        String url = serverUrl + "/command-ops-visualization.html";
        tvLoadingMsg.setText("Connecting to " + serverUrl + "…");
        loadingOverlay.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void loadOfflineDemo() {
        tvLoadingMsg.setText("Loading offline lane demo…");
        loadingOverlay.setVisibility(View.VISIBLE);
        webView.loadUrl(DEMO_URL);
    }

    private void loadDigitalTwin() {
        startActivity(new Intent(this, DigitalTwinArActivity.class));
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void stopSensorService() {
        Intent intent = new Intent(this, ScytheSensorService.class);
        intent.setAction(ScytheSensorService.ACTION_STOP);
        startService(intent);
        sensorBar.setVisibility(View.GONE);
    }

    private void requestRuntimePermissions() {
        ArrayList<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        needed.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        boolean allGranted = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMS);
        }
    }

    private String formatAge(long timestampMs) {
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - timestampMs);
        long seconds = elapsedMs / 1000L;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60L;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60L;
        return hours + "h ago";
    }
}
