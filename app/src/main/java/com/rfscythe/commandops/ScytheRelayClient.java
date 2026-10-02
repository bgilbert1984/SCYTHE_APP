package com.rfscythe.commandops;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class ScytheRelayClient {

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
    }

    private final List<String> relayUrls;
    private final Listener listener;
    private int candidateIndex;
    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebSocket webSocket;
    private volatile boolean connected;

    public ScytheRelayClient(List<String> relayUrls, Listener listener) {
        this.relayUrls = new ArrayList<>(relayUrls);
        this.listener = listener;
        this.candidateIndex = 0;
        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    }

    /** Convenience: single URL behaves as a one-candidate list. */
    public ScytheRelayClient(String relayUrl, Listener listener) {
        this(java.util.Collections.singletonList(relayUrl), listener);
    }

    public synchronized void connect() {
        if (webSocket != null) {
            return;
        }
        tryCandidate(candidateIndex);
    }

    /** Current candidate URL (for status display). */
    public synchronized String currentUrl() {
        if (relayUrls == null || relayUrls.isEmpty()) return "";
        int i = Math.max(0, Math.min(candidateIndex, relayUrls.size() - 1));
        return relayUrls.get(i);
    }

    private synchronized void tryCandidate(int index) {
        if (index < 0 || index >= relayUrls.size()) {
            return;
        }
        candidateIndex = index;
        String url = relayUrls.get(index);
        android.util.Log.i("ScytheRelay", "Connecting to relay candidate " + (index + 1)
            + "/" + relayUrls.size() + ": " + url);
        Request request = new Request.Builder().url(url).build();
        webSocket = httpClient.newWebSocket(request, new RelaySocketListener());
    }

    public synchronized void close() {
        WebSocket socket = webSocket;
        webSocket = null;
        connected = false;
        if (socket != null) {
            socket.close(1000, "client closing");
        }
        httpClient.dispatcher().executorService().shutdown();
    }

    public boolean isConnected() {
        return connected;
    }

    public synchronized boolean send(JSONObject event) {
        if (webSocket == null) {
            return false;
        }
        return webSocket.send(event.toString());
    }

    private final class RelaySocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            connected = true;
            mainHandler.post(listener::onConnected);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            handleDisconnect(reason == null || reason.isEmpty() ? ("closed:" + code) : reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            synchronized (ScytheRelayClient.this) {
                if (candidateIndex + 1 < relayUrls.size()) {
                    // Try the next candidate (e.g. ws:// fallback when wss:// fails).
                    ScytheRelayClient.this.webSocket = null;
                    final int next = candidateIndex + 1;
                    mainHandler.post(() -> tryCandidate(next));
                    return;
                }
            }
            handleDisconnect(t != null ? t.getMessage() : "websocket failure");
        }

        private void handleDisconnect(String reason) {
            synchronized (ScytheRelayClient.this) {
                connected = false;
                ScytheRelayClient.this.webSocket = null;
            }
            mainHandler.post(() -> listener.onDisconnected(reason != null ? reason : "relay offline"));
        }
    }
}
