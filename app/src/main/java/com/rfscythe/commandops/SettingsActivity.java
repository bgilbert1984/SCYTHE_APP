package com.rfscythe.commandops;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private EditText etServerUrl;
    private TextView tvDiscoverStatus;
    private TextView tvRelayStatus;
    private TextView tvSaveStatus;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etServerUrl = findViewById(R.id.etServerUrl);
        tvDiscoverStatus = findViewById(R.id.tvDiscoverStatus);
        tvRelayStatus = findViewById(R.id.tvRelayStatus);
        tvSaveStatus = findViewById(R.id.tvSaveStatus);
        Button btnTest = findViewById(R.id.btnTest);
        Button btnDiscover = findViewById(R.id.btnDiscover);
        Button btnSave = findViewById(R.id.btnSave);

        etServerUrl.setText(ScytheConfig.getServerUrl(this));
        refreshRelayStatus(etServerUrl.getText().toString().trim());

        btnTest.setOnClickListener(v -> testConnection());
        btnDiscover.setOnClickListener(v -> discoverServers());
        btnSave.setOnClickListener(v -> saveAndFinish());

        findViewById(R.id.btnPresetNeurosphere).setOnClickListener(v -> {
            String neurosphereUrl = "https://neurosphere-1.tail52f848.ts.net";
            etServerUrl.setText(neurosphereUrl);
            tvDiscoverStatus.setText("✅ Preset Selected: Neurosphere");
            refreshRelayStatus(neurosphereUrl);
        });
    }

    private void testConnection() {
        String urlStr = ScytheConfig.normaliseServerUrl(etServerUrl.getText().toString().trim());
        tvDiscoverStatus.setText("Testing " + urlStr + " …");
        tvRelayStatus.setText("Resolving relay…");
        executor.execute(() -> {
            try {
                URL url = new URL(urlStr + "/api/scythe/health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                int code = conn.getResponseCode();
                String relayUrl = ScytheConfig.resolveRelayUrl(urlStr);
                uiHandler.post(() -> tvDiscoverStatus.setText(
                    code == 200 ? "✅ Connected (" + code + ")" : "⚠ HTTP " + code));
                uiHandler.post(() -> tvRelayStatus.setText("Live relay: " + relayUrl));
            } catch (Exception e) {
                String fallbackRelay = ScytheConfig.deriveRelayUrl(urlStr);
                uiHandler.post(() -> {
                    tvDiscoverStatus.setText("✗ " + e.getMessage());
                    tvRelayStatus.setText("Live relay (fallback): " + fallbackRelay);
                });
            }
        });
    }

    private void discoverServers() {
        tvDiscoverStatus.setText("⟳ Scanning mDNS for _scythe._tcp … (8s)");
        ServerDiscovery.discoverAll(this, new ServerDiscovery.MultiDiscoveryCallback() {
            @Override
            public void onComplete(List<ServerDiscovery.ScytheService> services) {
                uiHandler.post(() -> showServiceChooser(services));
            }
            @Override
            public void onError(String message) {
                uiHandler.post(() -> tvDiscoverStatus.setText("○ " + message));
            }
        });
    }

    private void showServiceChooser(List<ServerDiscovery.ScytheService> services) {
        if (isFinishing() || isDestroyed()) return;
        String[] labels = new String[services.size()];
        for (int i = 0; i < services.size(); i++) {
            ServerDiscovery.ScytheService s = services.get(i);
            String icon = "instance".equals(s.type) ? "⚡" : "orchestrator".equals(s.type) ? "🗂" : "●";
            labels[i] = icon + " " + s.toString() + "\n    " + s.baseUrl();
        }

        new AlertDialog.Builder(this)
            .setTitle("Scythe Services Found")
            .setItems(labels, (dialog, which) -> {
                String url = services.get(which).baseUrl();
                etServerUrl.setText(url);
                tvDiscoverStatus.setText("✅ Selected: " + url);
                refreshRelayStatus(url);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveAndFinish() {
        String urlStr = ScytheConfig.normaliseServerUrl(etServerUrl.getText().toString().trim());
        ScytheConfig.saveServerUrl(this, urlStr);
        tvSaveStatus.setText("✅ Saved — " + urlStr);
        refreshRelayStatus(urlStr);
        uiHandler.postDelayed(this::finish, 800);
    }

    private void refreshRelayStatus(String serverUrl) {
        final String url = ScytheConfig.normaliseServerUrl(serverUrl);
        tvRelayStatus.setText("Live relay: " + ScytheConfig.deriveRelayUrl(url));
        executor.execute(() -> {
            try {
                String relay = ScytheConfig.resolveRelayUrl(url);
                uiHandler.post(() -> tvRelayStatus.setText("Live relay: " + relay));
            } catch (Exception e) {
                uiHandler.post(() ->
                    tvRelayStatus.setText("Live relay (fallback): " + ScytheConfig.deriveRelayUrl(url)));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
