package com.rfscythe.commandops;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Console tab: the previous WebView-first home screen, preserved intact.
 * Loads {server}/command-ops-visualization.html with the ScytheBridge
 * JavaScript interface. Reached from the map home's console button.
 */
public class ConsoleActivity extends AppCompatActivity {

    private static final String TAG = "ScytheConsole";
    private static final String DEMO_URL = "file:///android_asset/eve_demo.html";

    private WebView webView;
    private LinearLayout loadingOverlay;
    private TextView tvStatus;
    private TextView tvLoadingMsg;

    private String serverUrl;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_console);

        webView = findViewById(R.id.webView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        tvStatus = findViewById(R.id.tvStatus);
        tvLoadingMsg = findViewById(R.id.tvLoadingMsg);
        Button btnBack = findViewById(R.id.btnBackMap);
        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button btnOfflineDemo = findViewById(R.id.btnOfflineDemo);

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

        btnBack.setOnClickListener(v -> finish());
        btnOpenSettings.setOnClickListener(v ->
            startActivity(new android.content.Intent(this, SettingsActivity.class)));
        btnOfflineDemo.setOnClickListener(v -> loadOfflineDemo());

        loadScythe();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String newUrl = ScytheConfig.getServerUrl(this);
        if (!newUrl.equals(serverUrl)) loadScythe();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    void loadScythe() {
        serverUrl = ScytheConfig.getServerUrl(this);
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
}
