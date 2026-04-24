package com.rfscythe.commandops;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerDiscovery {

    private static final String TAG = "ScytheDiscovery";
    private static final String SERVICE_TYPE = "_scythe._tcp.";

    public static class ScytheService {
        public final String name;
        public final String host;
        public final int port;
        public final String type; // "instance" | "orchestrator" | "unknown"
        public final String instanceId;

        ScytheService(String name, String host, int port, String type, String instanceId) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.type = type;
            this.instanceId = instanceId;
        }

        public String baseUrl() {
            return "http://" + host + ":" + port;
        }

        @Override
        public String toString() {
            if ("instance".equals(type)) return "Instance " + instanceId + " (" + port + ")";
            if ("orchestrator".equals(type)) return "Orchestrator (" + port + ")";
            return name + " (" + port + ")";
        }
    }

    public interface MultiDiscoveryCallback {
        void onComplete(List<ScytheService> services);
        void onError(String message);
    }

    /** Discover all _scythe._tcp services over 8 seconds, return full list. */
    public static void discoverAll(Context context, MultiDiscoveryCallback callback) {
        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        List<ScytheService> results = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean(false);

        final NsdManager.DiscoveryListener[] listenerHolder = new NsdManager.DiscoveryListener[1];

        listenerHolder[0] = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {
                callback.onError("Discovery failed: " + e);
            }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {
                Log.d(TAG, "mDNS scan started");
            }
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Found: " + service.getServiceName());
                nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                        Log.w(TAG, "Resolve failed for " + si.getServiceName() + ": " + errorCode);
                    }
                    @Override
                    public void onServiceResolved(NsdServiceInfo si) {
                        String host = si.getHost() != null ? si.getHost().getHostAddress() : null;
                        int port = si.getPort();
                        if (host == null || port <= 0) return;

                        // Parse TXT record attributes
                        String type = "unknown";
                        String instanceId = "";
                        try {
                            java.util.Map<String, byte[]> attrs = si.getAttributes();
                            if (attrs != null) {
                                byte[] typeBytes = attrs.get("type");
                                byte[] idBytes = attrs.get("instance_id");
                                if (typeBytes != null) type = new String(typeBytes);
                                if (idBytes != null) instanceId = new String(idBytes);
                            }
                        } catch (Exception ignored) {}

                        synchronized (results) {
                            results.add(new ScytheService(si.getServiceName(), host, port, type, instanceId));
                        }
                        Log.d(TAG, "Resolved: " + host + ":" + port + " type=" + type);
                    }
                });
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listenerHolder[0]);
            // Collect for 8 seconds then return all results
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (stopped.compareAndSet(false, true)) {
                    try { nsdManager.stopServiceDiscovery(listenerHolder[0]); } catch (Exception ignored) {}
                    List<ScytheService> snapshot;
                    synchronized (results) { snapshot = new ArrayList<>(results); }
                    if (snapshot.isEmpty()) {
                        callback.onError("No _scythe._tcp services found");
                    } else {
                        // Sort: instances first, then orchestrators
                        snapshot.sort((a, b) -> {
                            int rankA = "instance".equals(a.type) ? 0 : "orchestrator".equals(a.type) ? 1 : 2;
                            int rankB = "instance".equals(b.type) ? 0 : "orchestrator".equals(b.type) ? 1 : 2;
                            return rankA - rankB;
                        });
                        callback.onComplete(snapshot);
                    }
                }
            }, 8000);
        } catch (Exception e) {
            callback.onError("Cannot start discovery: " + e.getMessage());
        }
    }
}
