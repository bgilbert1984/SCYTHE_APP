package com.rfscythe.commandops;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class ScytheBridge {

    private final Context context;

    public ScytheBridge(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public String getServerUrl() {
        return ScytheConfig.getServerUrl(context);
    }

    @JavascriptInterface
    public String getDeviceLocation() {
        try {
            Location loc = getBestLastKnownLocation();
            if (loc != null) {
                return "{\"lat\":" + loc.getLatitude()
                       + ",\"lon\":" + loc.getLongitude()
                       + ",\"alt\":" + loc.getAltitude()
                       + ",\"accuracy\":" + loc.getAccuracy() + "}";
            }
        } catch (Exception e) {
            // ignore
        }
        return "{\"error\":\"no_location\"}";
    }

    @JavascriptInterface
    public String getObserverId() {
        String deviceId = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ANDROID_ID
        );
        if (deviceId == null || deviceId.length() < 6) {
            deviceId = "unknown-device";
        }
        return "android-" + deviceId;
    }

    @JavascriptInterface
    public String getDigitalTwinProjection() {
        try {
            String baseUrl = ScytheConfig.normaliseServerUrl(ScytheConfig.getServerUrl(context));
            String observerId = getObserverId();
            StringBuilder url = new StringBuilder(baseUrl)
                .append("/api/digital-twin/projection?observer_id=")
                .append(URLEncoder.encode(observerId, StandardCharsets.UTF_8.name()))
                .append("&limit=12");

            Location loc = getBestLastKnownLocation();
            if (loc != null) {
                url.append("&lat=").append(loc.getLatitude());
                url.append("&lon=").append(loc.getLongitude());
                url.append("&alt_m=").append(loc.getAltitude());
            }

            return httpGet(url.toString());
        } catch (Exception e) {
            try {
                JSONObject error = new JSONObject();
                error.put("status", "error");
                error.put("message", e.getMessage());
                return error.toString();
            } catch (Exception ignored) {
                return "{\"status\":\"error\",\"message\":\"projection_unavailable\"}";
            }
        }
    }

    @JavascriptInterface
    public void startSensorService() {
        ((Activity) context).runOnUiThread(() -> {
            String serverUrl = context.getSharedPreferences("ScytheCommandPrefs", Context.MODE_PRIVATE)
                    .getString(ScytheConfig.KEY_SERVER_URL, ScytheConfig.DEFAULT_SERVER_URL);
            Intent intent = new Intent(context, ScytheSensorService.class);
            intent.putExtra(ScytheConfig.KEY_SERVER_URL, serverUrl);
            context.startForegroundService(intent);
        });
    }

    @JavascriptInterface
    public void stopSensorService() {
        ((Activity) context).runOnUiThread(() -> {
            Intent intent = new Intent(context, ScytheSensorService.class);
            intent.setAction(ScytheSensorService.ACTION_STOP);
            context.startService(intent);
        });
    }

    @JavascriptInterface
    public boolean isSensorServiceRunning() {
        android.app.ActivityManager am =
            (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo svc :
                am.getRunningServices(Integer.MAX_VALUE)) {
            if (ScytheSensorService.class.getName().equals(svc.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    @JavascriptInterface
    public void showToast(String message) {
        ((Activity) context).runOnUiThread(() ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void openSettings() {
        ((Activity) context).runOnUiThread(() ->
            context.startActivity(new android.content.Intent(context, SettingsActivity.class)));
    }

    @JavascriptInterface
    public String getAppVersion() {
        return "1.2.0";
    }

    @JavascriptInterface
    public String getRelayUrl() {
        try {
            return ScytheConfig.resolveRelayUrl(ScytheConfig.getServerUrl(context));
        } catch (Exception e) {
            return ScytheConfig.deriveRelayUrl(ScytheConfig.getServerUrl(context));
        }
    }

    private Location getBestLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            return loc;
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readFully(stream);
        conn.disconnect();
        return body == null || body.isEmpty()
            ? "{\"status\":\"error\",\"message\":\"empty_response\"}"
            : body;
    }

    private String readFully(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
