package com.rfscythe.commandops;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScytheSensorService extends Service {

    private static final String TAG = "ScytheSensor";

    static final String ACTION_STOP    = "com.rfscythe.commandops.STOP_SENSOR";
    static final String ACTION_STATUS  = "com.rfscythe.commandops.SENSOR_STATUS";
    static final String EXTRA_RUNNING  = "running";
    static final String EXTRA_LAT      = "lat";
    static final String EXTRA_LON      = "lon";
    static final String EXTRA_AP_COUNT = "ap_count";
    static final String EXTRA_BT_COUNT = "bt_count";
    static final String EXTRA_CALLSIGN = "callsign";
    static final String EXTRA_RELAY_URL = "relay_url";
    static final String EXTRA_RELAY_CONNECTED = "relay_connected";
    static final String EXTRA_STREAM_EVENTS = "stream_events";
    static final String EXTRA_STREAM_BATCHES = "stream_batches";
    static final String EXTRA_LAST_UPLINK_MS = "last_uplink_ms";
    static final String EXTRA_LAST_ERROR = "last_error";

    private static final String CHANNEL_ID     = "scythe_sensor";
    private static final int    NOTIF_ID       = 1001;
    private static final long   GPS_REPORT_MS  = 10_000;
    private static final long   WIFI_SCAN_MS   = 30_000;
    private static final long   WIFI_FIRST_MS  = 3_000;
    private static final long   BT_SCAN_MS     = 45_000;
    private static final long   BT_FIRST_MS    = 8_000;
    private static final long   BT_SCAN_WINDOW_MS = 8_000;
    private static final long   STREAM_HEARTBEAT_MS = 15_000;

    private String serverUrl;
    private String deviceId;
    private String callsign;
    private String relayUrl;
    private String lastRelayError;

    private Location lastLocation;
    private int      lastApCount;
    private int      lastBluetoothCount;
    private int      streamEventsSent;
    private int      streamBatchesSent;
    private long     lastUplinkAtMs;
    private boolean  relayConnected;

    private LocationManager locationManager;
    private WifiManager     wifiManager;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private ConnectivityManager connectivityManager;
    private BroadcastReceiver wifiScanReceiver;
    private ScytheRelayClient relayClient;
    private boolean bluetoothScanActive;
    private final Map<String, ObservedBluetoothDevice> bluetoothObservations = new HashMap<>();

    private final Handler         handler  = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ── Periodic runnables ────────────────────────────────────────────────────

    private final Runnable gpsReportRunnable = new Runnable() {
        @Override public void run() {
            if (lastLocation != null) reportGps(lastLocation);
            handler.postDelayed(this, GPS_REPORT_MS);
        }
    };

    private final Runnable wifiScanRunnable = new Runnable() {
        @Override public void run() {
            triggerWifiScan();
            handler.postDelayed(this, WIFI_SCAN_MS);
        }
    };

    private final Runnable bluetoothScanRunnable = new Runnable() {
        @Override public void run() {
            triggerBluetoothScan();
            handler.postDelayed(this, BT_SCAN_MS);
        }
    };

    private final Runnable bluetoothScanStopRunnable = new Runnable() {
        @Override public void run() {
            finishBluetoothScan();
        }
    };

    private final Runnable streamHeartbeatRunnable = new Runnable() {
        @Override public void run() {
            emitNetworkTelemetry();
            handler.postDelayed(this, STREAM_HEARTBEAT_MS);
        }
    };

    private static final class ObservedBluetoothDevice {
        final String address;
        final String name;
        final int rssi;
        final Integer txPowerDbm;

        ObservedBluetoothDevice(String address, String name, int rssi, Integer txPowerDbm) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
            this.txPowerDbm = txPowerDbm;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (deviceId == null || deviceId.length() < 6) {
            deviceId = "unknown-device";
        }
        callsign = "ANDROID-" + deviceId.substring(0, Math.min(deviceId.length(), 6)).toUpperCase(Locale.US);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        handler.removeCallbacks(gpsReportRunnable);
        handler.removeCallbacks(wifiScanRunnable);
        handler.removeCallbacks(bluetoothScanRunnable);
        handler.removeCallbacks(bluetoothScanStopRunnable);
        handler.removeCallbacks(streamHeartbeatRunnable);

        serverUrl = ScytheConfig.getServerUrl(this, intent);
        resolveRelayEndpoint();

        startForeground(NOTIF_ID, buildNotification("Initializing…"));
        initGps();
        initWifiScanner();
        initBluetoothScanner();
        handler.postDelayed(gpsReportRunnable, GPS_REPORT_MS);
        handler.postDelayed(wifiScanRunnable, WIFI_FIRST_MS);
        handler.postDelayed(bluetoothScanRunnable, BT_FIRST_MS);
        handler.postDelayed(streamHeartbeatRunnable, 5_000);
        broadcastStatus();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        stopRelayClient();
        stopBluetoothScan();
        try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        if (wifiScanReceiver != null) {
            try { unregisterReceiver(wifiScanReceiver); } catch (Exception ignored) {}
        }
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_RUNNING, false);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private final LocationListener locationListener = location -> {
        lastLocation = location;
        updateNotification();
        broadcastStatus();
    };

    private void initGps() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 5000, 10f, locationListener, Looper.getMainLooper());
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 10000, 50f, locationListener, Looper.getMainLooper());
            Location seed = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (seed == null) seed = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (seed != null) lastLocation = seed;
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission missing: " + e.getMessage());
        }
    }

    private void reportGps(Location loc) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("entity_id", "android-" + deviceId);
                body.put("name",      callsign);
                body.put("callsign",  callsign);
                body.put("lat",       loc.getLatitude());
                body.put("lon",       loc.getLongitude());
                body.put("alt",       loc.getAltitude());
                body.put("accuracy_m", (double) loc.getAccuracy());
                body.put("source",    "android_gps");
                body.put("platform",  "android");
                body.put("icon",      "friendly_force");
                httpPost(serverUrl + "/api/recon/entity", body.toString());
                streamGpsObservation(loc);
                Log.d(TAG, String.format("GPS → %.5f, %.5f", loc.getLatitude(), loc.getLongitude()));
            } catch (Exception e) {
                Log.w(TAG, "GPS report failed: " + e.getMessage());
            }
        });
    }

    // ── WiFi ──────────────────────────────────────────────────────────────────

    private void initWifiScanner() {
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                List<ScanResult> results = wifiManager.getScanResults();
                if (!results.isEmpty()) {
                    lastApCount = results.size();
                    updateNotification();
                    broadcastStatus();
                    reportWifiAps(results);
                    streamWifiObservation(results);
                }
            }
        };
        registerReceiver(wifiScanReceiver,
            new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    private void triggerWifiScan() {
        if (wifiManager == null) return;
        boolean started = wifiManager.startScan();
        if (!started) {
            // OS throttling — use cached results immediately
            List<ScanResult> cached = wifiManager.getScanResults();
            if (!cached.isEmpty()) {
                lastApCount = cached.size();
                updateNotification();
                broadcastStatus();
                reportWifiAps(cached);
                streamWifiObservation(cached);
            }
        }
    }

    private void reportWifiAps(List<ScanResult> aps) {
        if (lastLocation == null) {
            Log.d(TAG, "WiFi scan skipped — no GPS fix yet");
            return;
        }
        final double obLat = lastLocation.getLatitude();
        final double obLon = lastLocation.getLongitude();

        executor.execute(() -> {
            int posted = 0;
            for (ScanResult ap : aps) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("node_id",       "wifi-" + ap.BSSID.replace(":", ""));
                    body.put("type",          "wifi_ap");
                    body.put("ssid",          ap.SSID.isEmpty() ? "[hidden]" : ap.SSID);
                    body.put("bssid",         ap.BSSID);
                    body.put("rssi",          ap.level);
                    body.put("frequency_mhz", ap.frequency);
                    body.put("channel_width", ap.channelWidth);
                    body.put("lat",           obLat);
                    body.put("lon",           obLon);
                    body.put("observer_id",   "android-" + deviceId);
                    body.put("platform",      "android");
                    body.put("source",        "android_wifi_scan");
                    body.put("timestamp",     System.currentTimeMillis() / 1000.0);
                    httpPost(serverUrl + "/api/rf-hypergraph/node", body.toString());
                    posted++;
                } catch (Exception e) {
                    Log.w(TAG, "WiFi node post failed (" + ap.BSSID + "): " + e.getMessage());
                }
            }
            Log.d(TAG, "WiFi → posted " + posted + "/" + aps.size() + " APs to hypergraph");
        });
    }

    // ── Bluetooth LE ───────────────────────────────────────────────────────────

    private final ScanCallback bluetoothScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
            recordBluetoothObservation(result);
        }

        @Override
        public void onBatchScanResults(List<android.bluetooth.le.ScanResult> results) {
            for (android.bluetooth.le.ScanResult result : results) {
                recordBluetoothObservation(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            bluetoothScanActive = false;
            Log.w(TAG, "Bluetooth scan failed: " + errorCode);
        }
    };

    private void initBluetoothScanner() {
        bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (bluetoothAdapter == null) {
            Log.i(TAG, "Bluetooth adapter unavailable on this device");
        }
    }

    private void triggerBluetoothScan() {
        if (bluetoothAdapter == null) {
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            lastBluetoothCount = 0;
            updateNotification();
            broadcastStatus();
            Log.d(TAG, "Bluetooth scan skipped — adapter disabled");
            return;
        }
        if (!hasBluetoothScanPermission()) {
            Log.w(TAG, "Bluetooth scan skipped — missing BLUETOOTH_SCAN permission");
            return;
        }

        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.d(TAG, "Bluetooth scan skipped — BLE scanner unavailable");
            return;
        }

        stopBluetoothScan();
        synchronized (bluetoothObservations) {
            bluetoothObservations.clear();
        }
        bluetoothLeScanner = scanner;
        bluetoothScanActive = true;
        try {
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
            bluetoothLeScanner.startScan(null, settings, bluetoothScanCallback);
            handler.postDelayed(bluetoothScanStopRunnable, BT_SCAN_WINDOW_MS);
        } catch (SecurityException e) {
            bluetoothScanActive = false;
            Log.w(TAG, "Bluetooth scan start failed: " + e.getMessage());
        }
    }

    private void stopBluetoothScan() {
        handler.removeCallbacks(bluetoothScanStopRunnable);
        if (!bluetoothScanActive || bluetoothLeScanner == null) {
            bluetoothScanActive = false;
            bluetoothLeScanner = null;
            return;
        }
        try {
            bluetoothLeScanner.stopScan(bluetoothScanCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth scan stop failed: " + e.getMessage());
        }
        bluetoothScanActive = false;
        bluetoothLeScanner = null;
    }

    private void finishBluetoothScan() {
        stopBluetoothScan();
        List<ObservedBluetoothDevice> devices = snapshotBluetoothObservations();
        lastBluetoothCount = devices.size();
        updateNotification();
        broadcastStatus();
        if (!devices.isEmpty()) {
            reportBluetoothDevices(devices);
            streamBluetoothObservation(devices);
        }
    }

    private void recordBluetoothObservation(android.bluetooth.le.ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        String address = device.getAddress();
        if (address == null || address.isEmpty()) {
            return;
        }
        String name = safeBluetoothDeviceName(device);
        Integer txPower = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int value = result.getTxPower();
            txPower = value == Integer.MIN_VALUE ? null : value;
        }
        ObservedBluetoothDevice observed = new ObservedBluetoothDevice(
            address,
            name == null || name.isEmpty() ? "[unnamed]" : name,
            result.getRssi(),
            txPower
        );
        synchronized (bluetoothObservations) {
            ObservedBluetoothDevice existing = bluetoothObservations.get(address);
            if (existing == null || observed.rssi > existing.rssi) {
                bluetoothObservations.put(address, observed);
            }
        }
    }

    private List<ObservedBluetoothDevice> snapshotBluetoothObservations() {
        ArrayList<ObservedBluetoothDevice> snapshot;
        synchronized (bluetoothObservations) {
            snapshot = new ArrayList<>(bluetoothObservations.values());
            bluetoothObservations.clear();
        }
        snapshot.sort((left, right) -> Integer.compare(right.rssi, left.rssi));
        return snapshot;
    }

    private String safeBluetoothDeviceName(BluetoothDevice device) {
        if (device == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            return null;
        }
        try {
            return device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private boolean hasBluetoothScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void reportBluetoothDevices(List<ObservedBluetoothDevice> devices) {
        if (lastLocation == null) {
            Log.d(TAG, "Bluetooth scan skipped — no GPS fix yet");
            return;
        }
        final double obLat = lastLocation.getLatitude();
        final double obLon = lastLocation.getLongitude();

        executor.execute(() -> {
            int posted = 0;
            for (ObservedBluetoothDevice device : devices) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("node_id", "bluetooth-" + device.address.replace(":", "").toLowerCase(Locale.US));
                    body.put("type", "bluetooth_device");
                    body.put("name", device.name);
                    body.put("address", device.address);
                    body.put("rssi", device.rssi);
                    if (device.txPowerDbm != null) {
                        body.put("tx_power_dbm", device.txPowerDbm);
                    }
                    body.put("lat", obLat);
                    body.put("lon", obLon);
                    body.put("observer_id", "android-" + deviceId);
                    body.put("platform", "android");
                    body.put("source", "android_bluetooth_scan");
                    body.put("timestamp", System.currentTimeMillis() / 1000.0);
                    httpPost(serverUrl + "/api/rf-hypergraph/node", body.toString());
                    posted++;
                } catch (Exception e) {
                    Log.w(TAG, "Bluetooth node post failed (" + device.address + "): " + e.getMessage());
                }
            }
            Log.d(TAG, "Bluetooth → posted " + posted + "/" + devices.size() + " devices to hypergraph");
        });
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private void httpPost(String urlStr, String json) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code >= 400) Log.w(TAG, "HTTP " + code + " from " + urlStr);
    }

    private void resolveRelayEndpoint() {
        executor.execute(() -> {
            String resolved = null;
            try {
                resolved = ScytheConfig.resolveRelayUrl(serverUrl);
            } catch (Exception e) {
                Log.w(TAG, "Falling back to derived relay URL: " + e.getMessage());
            }
            relayUrl = resolved != null ? resolved : ScytheConfig.deriveRelayUrl(serverUrl);
            startRelayClient();
            broadcastStatus();
        });
    }

    private void startRelayClient() {
        if (relayUrl == null || relayUrl.isEmpty()) {
            return;
        }
        stopRelayClient();
        relayClient = new ScytheRelayClient(relayUrl, new ScytheRelayClient.Listener() {
            @Override
            public void onConnected() {
                relayConnected = true;
                lastRelayError = null;
                updateNotification();
                broadcastStatus();
            }

            @Override
            public void onDisconnected(String reason) {
                relayConnected = false;
                lastRelayError = reason;
                updateNotification();
                broadcastStatus();
            }
        });
        relayClient.connect();
    }

    private void stopRelayClient() {
        if (relayClient != null) {
            relayClient.close();
            relayClient = null;
        }
        relayConnected = false;
    }

    private void ensureRelayConnected() {
        if (relayClient == null) {
            startRelayClient();
        } else if (!relayClient.isConnected()) {
            relayClient.connect();
        }
    }

    private void streamGpsObservation(Location loc) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "android_sensor_position");
            event.put("source", "android_eve");
            event.put("observer_id", "android-" + deviceId);
            event.put("platform", "android");
            event.put("callsign", callsign);
            event.put("lat", loc.getLatitude());
            event.put("lon", loc.getLongitude());
            event.put("alt", loc.getAltitude());
            event.put("accuracy_m", loc.getAccuracy());
            event.put("timestamp", System.currentTimeMillis() / 1000.0);
            event.put("sensor_context", buildSensorContext());
            sendRelayEvent(event);
        } catch (Exception e) {
            Log.w(TAG, "GPS relay event failed: " + e.getMessage());
        }
    }

    private void streamWifiObservation(List<ScanResult> aps) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "android_wifi_scan");
            event.put("source", "android_eve");
            event.put("observer_id", "android-" + deviceId);
            event.put("platform", "android");
            event.put("callsign", callsign);
            event.put("ap_count", aps.size());
            event.put("timestamp", System.currentTimeMillis() / 1000.0);
            event.put("sensor_context", buildSensorContext());

            JSONArray strongest = new JSONArray();
            for (int i = 0; i < Math.min(aps.size(), 6); i++) {
                ScanResult ap = aps.get(i);
                JSONObject row = new JSONObject();
                row.put("ssid", ap.SSID == null || ap.SSID.isEmpty() ? "[hidden]" : ap.SSID);
                row.put("bssid", ap.BSSID);
                row.put("rssi", ap.level);
                row.put("frequency_mhz", ap.frequency);
                strongest.put(row);
            }
            event.put("aps", strongest);
            sendRelayEvent(event);
        } catch (Exception e) {
            Log.w(TAG, "WiFi relay event failed: " + e.getMessage());
        }
    }

    private void streamBluetoothObservation(List<ObservedBluetoothDevice> devices) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "android_bluetooth_scan");
            event.put("source", "android_eve");
            event.put("observer_id", "android-" + deviceId);
            event.put("platform", "android");
            event.put("callsign", callsign);
            event.put("device_count", devices.size());
            event.put("timestamp", System.currentTimeMillis() / 1000.0);
            event.put("sensor_context", buildSensorContext());

            JSONArray strongest = new JSONArray();
            for (int i = 0; i < Math.min(devices.size(), 8); i++) {
                ObservedBluetoothDevice device = devices.get(i);
                JSONObject row = new JSONObject();
                row.put("name", device.name);
                row.put("address", device.address);
                row.put("rssi", device.rssi);
                if (device.txPowerDbm != null) {
                    row.put("tx_power_dbm", device.txPowerDbm);
                }
                strongest.put(row);
            }
            event.put("devices", strongest);
            sendRelayEvent(event);
        } catch (Exception e) {
            Log.w(TAG, "Bluetooth relay event failed: " + e.getMessage());
        }
    }

    private void emitNetworkTelemetry() {
        ensureRelayConnected();
        try {
            JSONObject heartbeat = new JSONObject();
            heartbeat.put("type", "android_sensor_heartbeat");
            heartbeat.put("source", "android_eve");
            heartbeat.put("observer_id", "android-" + deviceId);
            heartbeat.put("platform", "android");
            heartbeat.put("callsign", callsign);
            heartbeat.put("ap_count", lastApCount);
            heartbeat.put("timestamp", System.currentTimeMillis() / 1000.0);
            heartbeat.put("sensor_context", buildSensorContext());
            sendRelayEvent(heartbeat);
            streamNetworkInfrastructure();
        } catch (Exception e) {
            Log.w(TAG, "Heartbeat relay event failed: " + e.getMessage());
        }
    }

    private JSONObject buildSensorContext() throws Exception {
        JSONObject ctx = new JSONObject();
        ctx.put("device_id", deviceId);
        ctx.put("callsign", callsign);
        ctx.put("server_url", serverUrl);
        if (relayUrl != null) {
            ctx.put("relay_url", relayUrl);
        }
        ctx.put("ap_count", lastApCount);
        ctx.put("bluetooth_count", lastBluetoothCount);
        if (lastLocation != null) {
            ctx.put("lat", lastLocation.getLatitude());
            ctx.put("lon", lastLocation.getLongitude());
            ctx.put("accuracy_m", lastLocation.getAccuracy());
        }
        return ctx;
    }

    private void streamNetworkInfrastructure() {
        if (connectivityManager == null) {
            return;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        LinkProperties props = connectivityManager.getLinkProperties(activeNetwork);
        if (props == null) {
            return;
        }
        String srcIp = extractIpv4Address(props);
        if (srcIp == null || srcIp.isEmpty()) {
            return;
        }

        for (InetAddress dnsServer : props.getDnsServers()) {
            if (dnsServer instanceof Inet4Address) {
                streamObservedFlow(srcIp, dnsServer.getHostAddress(), 53, "dns_resolver");
            }
        }
        for (RouteInfo route : props.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (gateway instanceof Inet4Address) {
                streamObservedFlow(srcIp, gateway.getHostAddress(), 0, "default_gateway");
            }
        }
    }

    private String extractIpv4Address(LinkProperties props) {
        for (LinkAddress address : props.getLinkAddresses()) {
            InetAddress inetAddress = address.getAddress();
            if (inetAddress instanceof Inet4Address) {
                return inetAddress.getHostAddress();
            }
        }
        return null;
    }

    private void streamObservedFlow(String srcIp, String dstIp, int dstPort, String observedKind) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "net_flow");
            event.put("source", "android_eve");
            event.put("sensor", "scythe_android_eve");
            event.put("observer_id", "android-" + deviceId);
            event.put("platform", "android");
            event.put("obs_class", "observed");
            event.put("observed_kind", observedKind);
            event.put("src_ip", srcIp);
            event.put("dst_ip", dstIp);
            event.put("src_port", 0);
            event.put("dst_port", dstPort);
            event.put("protocol", dstPort == 53 ? "dns" : "infra");
            event.put("timestamp", System.currentTimeMillis() / 1000.0);
            event.put("sensor_context", buildSensorContext());
            sendRelayEvent(event);
        } catch (Exception e) {
            Log.w(TAG, "Observed flow relay failed: " + e.getMessage());
        }
    }

    private void sendRelayEvent(JSONObject event) {
        if (relayUrl == null || relayUrl.isEmpty()) {
            return;
        }
        ensureRelayConnected();
        if (relayClient != null && relayClient.send(event)) {
            streamEventsSent++;
            if ("android_sensor_heartbeat".equals(event.optString("type"))
                    || "android_wifi_scan".equals(event.optString("type"))
                    || "android_bluetooth_scan".equals(event.optString("type"))) {
                streamBatchesSent++;
            }
            lastUplinkAtMs = System.currentTimeMillis();
            lastRelayError = null;
            updateNotification();
            broadcastStatus();
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, ScytheSensorService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📡 " + callsign + (relayConnected ? " • RELAY" : ""))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification() {
        String gpsText = lastLocation != null
            ? String.format(Locale.US, "%.4f, %.4f", lastLocation.getLatitude(), lastLocation.getLongitude())
            : "Acquiring GPS…";
        String relayText = relayConnected
            ? "RELAY " + streamEventsSent + " ev"
            : "RELAY offline";
        String text = gpsText + "  |  " + lastApCount + " APs  |  " + lastBluetoothCount + " BT  |  " + relayText;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIF_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Scythe Sensor", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GPS + WiFi + Bluetooth sensor reporting to Scythe");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    // ── Status broadcast ──────────────────────────────────────────────────────

    private void broadcastStatus() {
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_RUNNING,  true);
        i.putExtra(EXTRA_CALLSIGN, callsign);
        i.putExtra(EXTRA_AP_COUNT, lastApCount);
        i.putExtra(EXTRA_BT_COUNT, lastBluetoothCount);
        i.putExtra(EXTRA_RELAY_CONNECTED, relayConnected);
        i.putExtra(EXTRA_STREAM_EVENTS, streamEventsSent);
        i.putExtra(EXTRA_STREAM_BATCHES, streamBatchesSent);
        i.putExtra(EXTRA_LAST_UPLINK_MS, lastUplinkAtMs);
        i.putExtra(EXTRA_LAST_ERROR, lastRelayError);
        if (relayUrl != null) {
            i.putExtra(EXTRA_RELAY_URL, relayUrl);
        }
        if (lastLocation != null) {
            i.putExtra(EXTRA_LAT, lastLocation.getLatitude());
            i.putExtra(EXTRA_LON, lastLocation.getLongitude());
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }
}
