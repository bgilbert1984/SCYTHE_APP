package com.rfscythe.commandops;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ScytheConfig {

    public static final String PREFS_NAME = "ScytheCommandPrefs";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String DEFAULT_SERVER_URL = "http://192.168.1.185:5001";

    private ScytheConfig() {}

    public static String getServerUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normaliseServerUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
    }

    public static String getServerUrl(Context context, Intent intent) {
        String candidate = intent != null ? intent.getStringExtra(KEY_SERVER_URL) : null;
        if (candidate == null || candidate.trim().isEmpty()) {
            return getServerUrl(context);
        }
        return normaliseServerUrl(candidate);
    }

    public static void saveServerUrl(Context context, String serverUrl) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, normaliseServerUrl(serverUrl))
            .apply();
    }

    public static String normaliseServerUrl(String serverUrl) {
        String url = (serverUrl == null || serverUrl.trim().isEmpty())
            ? DEFAULT_SERVER_URL
            : serverUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static JSONObject fetchStreamConfig(String serverUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(normaliseServerUrl(serverUrl) + "/api/config/streams");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " from /api/config/streams");
            }
            try (InputStream input = conn.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String resolveRelayUrl(String serverUrl) throws Exception {
        JSONObject cfg = fetchStreamConfig(serverUrl);
        String ws = firstNonEmpty(
            cfg.optString("stream_relay", ""),
            cfg.optString("stream_relay_ws", "")
        );
        if (ws != null && !ws.isEmpty()) {
            return normaliseRelayUrl(serverUrl, ws);
        }
        return deriveRelayUrl(serverUrl);
    }

    public static String deriveRelayUrl(String serverUrl) {
        Uri uri = Uri.parse(normaliseServerUrl(serverUrl));
        String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        return scheme + "://" + host + ":8765/ws";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normaliseRelayUrl(String serverUrl, String relayUrl) {
        Uri server = Uri.parse(normaliseServerUrl(serverUrl));
        Uri relay = Uri.parse(relayUrl);
        String serverHost = server.getHost();
        String relayHost = relay.getHost();
        if (serverHost == null || relayHost == null) {
            return relayUrl;
        }
        if (!shouldRewriteRelayHost(serverHost, relayHost)) {
            return relayUrl;
        }

        String serverScheme = server.getScheme();
        String relayScheme = relay.getScheme();
        String targetScheme = "https".equalsIgnoreCase(serverScheme) ? "wss" : "ws";
        if (relayScheme != null && !relayScheme.isEmpty()) {
            targetScheme = relayScheme;
            if ("https".equalsIgnoreCase(serverScheme) && "ws".equalsIgnoreCase(relayScheme)) {
                targetScheme = "wss";
            } else if ("http".equalsIgnoreCase(serverScheme) && "wss".equalsIgnoreCase(relayScheme)) {
                targetScheme = "ws";
            }
        }

        Uri.Builder builder = relay.buildUpon().scheme(targetScheme);
        int port = relay.getPort();
        String authority = port > 0 ? serverHost + ":" + port : serverHost;
        builder.encodedAuthority(authority);
        return builder.build().toString();
    }

    private static boolean shouldRewriteRelayHost(String serverHost, String relayHost) {
        if (serverHost.equalsIgnoreCase(relayHost)) {
            return false;
        }
        boolean serverPrivate = isLoopbackOrPrivate(serverHost);
        boolean relayPrivate = isLoopbackOrPrivate(relayHost);
        return relayPrivate && !serverPrivate;
    }

    private static boolean isLoopbackOrPrivate(String host) {
        if (host == null || host.isEmpty()) {
            return true;
        }
        String lower = host.toLowerCase();
        if ("localhost".equals(lower) || lower.startsWith("127.") || lower.equals("::1")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
