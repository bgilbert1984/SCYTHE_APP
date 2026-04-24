package com.rfscythe.commandops;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

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

    private final String relayUrl;
    private final Listener listener;
    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebSocket webSocket;
    private volatile boolean connected;

    public ScytheRelayClient(String relayUrl, Listener listener) {
        this.relayUrl = relayUrl;
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    }

    public synchronized void connect() {
        if (webSocket != null) {
            return;
        }
        Request request = new Request.Builder().url(relayUrl).build();
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
