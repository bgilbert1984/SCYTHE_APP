package com.rfscythe.commandops;

import android.webkit.JavascriptInterface;

/**
 * JS -> native bridge for the Cesium globe home (scythe-globe.html).
 * The page calls onGlobeReady() once the Cesium Viewer exists; MainActivity
 * flushes any queued state (mode, layers, nodes, self position) at that point.
 * All other traffic is native -> JS via WebView.evaluateJavascript.
 */
public class GlobeBridge {

    public interface ReadyListener {
        void onGlobeReady();
    }

    private volatile ReadyListener listener;

    public void setReadyListener(ReadyListener listener) {
        this.listener = listener;
    }

    @JavascriptInterface
    public void onGlobeReady() {
        ReadyListener l = listener;
        if (l != null) {
            l.onGlobeReady();
        }
    }
}
