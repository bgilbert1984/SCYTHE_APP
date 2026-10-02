package com.rfscythe.commandops;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 0: USB plumbing for the NESDR SMArt v5 (0bda:2838 / 0bda:2832).
 *
 * - Enumerates the dongle, runs the framework permission flow, opens a
 *   UsbDeviceConnection and hands its file descriptor to the native layer
 *   (SdrNative.sdrOpen). The connection object is held with a STRONG
 *   reference for the whole session: libusb_close() does not close a wrapped
 *   descriptor, and if the UsbDeviceConnection is GC'd mid-stream the next
 *   libusb call fails with NO_DEVICE for no visible reason.
 * - The interface claim belongs to libusb (inside rtlsdr_open_fd); this class
 *   deliberately never calls UsbDeviceConnection.claimInterface().
 * - Detach is handled from the framework USB_DEVICE_DETACHED broadcast
 *   (libusb hotplug is dead once device discovery is disabled). Teardown
 *   stops the stream first -- rtlsdr_cancel_async() runs on the calling
 *   (Java) thread, never on the native thread blocked in read_async.
 * - Fully inert when no dongle is attached: every public method degrades to
 *   a status line, and the native library is never loaded.
 */
public class RfSdrManager {
    private static final String TAG = "ScytheSdr";

    public static final String ACTION_USB_PERMISSION =
            "com.rfscythe.commandops.USB_PERMISSION";

    private static final int VID = 0x0bda;
    private static final int[] PIDS = {0x2838, 0x2832}; // SMArt v5 / SMArt v4

    private static final int POLL_BLOCK = 65536;
    private static final long STATS_MS = 1000;

    /** UI callback. line1 == null hides the SDR status bar. */
    public interface Listener {
        void onSdrStatus(String line1, String line2);
    }

    private final Context appContext;
    private final UsbManager usbManager;
    private Listener listener;

    private final Object stateLock = new Object();
    private UsbDevice dongle;
    private UsbDeviceConnection connection; // strong ref: owns the fd
    private long nativeHandle;
    private boolean streaming;
    private Thread pollThread;
    private volatile boolean pollStop;

    // stats accumulated by the poll thread
    private long statBytes;
    private double statMean = 127.5;
    private double statVar;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                synchronized (stateLock) {
                    if (granted && dev != null) {
                        openDongleLocked(dev);
                    } else {
                        report("SDR: USB permission denied",
                                "grant access to open the dongle");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (isDongle(dev)) {
                    Log.i(TAG, "dongle attached: " + dev.getDeviceName());
                    synchronized (stateLock) {
                        onDongleAttachedLocked(dev);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                synchronized (stateLock) {
                    if (dev != null && dev.equals(dongle)) {
                        Log.i(TAG, "dongle detached");
                        teardownLocked("SDR: dongle detached", "unplug/replug to retry");
                    }
                }
            }
        }
    };

    public RfSdrManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.usbManager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Register USB receivers. Safe to call with no dongle present. */
    public void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(usbReceiver, filter);
        }
        // If launched by the USB_DEVICE_ATTACHED intent-filter, the intent
        // arrives via MainActivity.onNewIntent -> handleIntent().
    }

    public void stop() {
        try {
            appContext.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        synchronized (stateLock) {
            teardownLocked(null, null);
        }
    }

    /**
     * Called from MainActivity.onCreate/onNewIntent with the launch intent.
     * If it carries a USB_DEVICE_ATTACHED for our dongle, begin the
     * permission/open flow.
     */
    public void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (isDongle(dev)) {
                synchronized (stateLock) {
                    onDongleAttachedLocked(dev);
                }
            }
        }
    }

    /** User tapped "Find dongle" / Start: find it and open it, or explain why not. */
    public void userStart() {
        synchronized (stateLock) {
            if (nativeHandle != 0) {
                if (!streaming) {
                    startStreamLocked();
                }
                return;
            }
            UsbDevice dev = findDongle();
            if (dev == null) {
                report("SDR: no dongle found",
                        "plug in the NESDR (0bda:2838) via OTG and try again");
                return;
            }
            onDongleAttachedLocked(dev);
        }
    }

    /** User tapped Stop: halt the stream but keep the device open. */
    public void userStop() {
        synchronized (stateLock) {
            stopStreamLocked();
            if (nativeHandle != 0) {
                report("SDR: stream stopped",
                        String.format(Locale.US, "%.1f MB received total",
                                statBytes / 1048576.0));
            } else {
                report(null, null);
            }
        }
    }

    /** True while the native stream thread is up. */
    public boolean isStreaming() {
        synchronized (stateLock) {
            return streaming;
        }
    }

    /** Last successfully tuned centre frequency in Hz (100 MHz default). */
    public long getCenterFreqHz() {
        synchronized (stateLock) {
            if (nativeHandle == 0) {
                return 100000000L;
            }
            long hz = SdrNative.sdrGetCenterFreq(nativeHandle);
            return hz > 0 ? hz : 100000000L;
        }
    }

    /**
     * Phase 1: compute a 256-bin spectrum thumbnail natively (Hann,
     * 8x64k FFT, averaged). The destination must be a direct ByteBuffer
     * with capacity >= 256. Returns 0 ok, -1 no handle/not streaming,
     * -2 not enough data buffered yet.
     *
     * Holds stateLock for the whole native call so teardown cannot free
     * the handle mid-compute (the FFT runs without the native ring lock
     * held, so the stream thread is never blocked).
     */
    public int computeSpectrum(java.nio.ByteBuffer out256) {
        synchronized (stateLock) {
            if (nativeHandle == 0 || !streaming) {
                return -1;
            }
            return SdrNative.sdrComputeSpectrum(nativeHandle, out256);
        }
    }

    /** Median floor (dB) from the last successful computeSpectrum(). */
    public float getSpectrumFloorDb() {
        synchronized (stateLock) {
            if (nativeHandle == 0) {
                return Float.NaN;
            }
            return SdrNative.sdrGetFloorDb(nativeHandle);
        }
    }

    // ---- Phase 2: peak detection + retune ----

    /** Native peak buffer capacity (matches DSP_MAX_PEAKS). */
    public static final int MAX_PEAKS = 32;

    /** One native peak detection: offset relative to the tune centre. */
    public static final class Peak {
        /** RF offset from the tune centre, Hz (exact; see dsp_peak_t). */
        public final float offsetHz;
        /** Peak power minus the detection floor, dB. */
        public final float snrDb;
        /** 3 dB occupied width, Hz. */
        public final float bwHz;

        public Peak(float offsetHz, float snrDb, float bwHz) {
            this.offsetHz = offsetHz;
            this.snrDb = snrDb;
            this.bwHz = bwHz;
        }
    }

    /**
     * Reads the peaks stashed by the most recent computeSpectrum().
     * Call right after computeSpectrum() -- the next compute overwrites
     * them. Returns an empty list when there are none or on any error.
     * Holds stateLock, like computeSpectrum().
     */
    public List<Peak> getPeaks() {
        synchronized (stateLock) {
            List<Peak> out = new ArrayList<>();
            if (nativeHandle == 0 || !streaming) {
                return out;
            }
            ByteBuffer buf = ByteBuffer.allocateDirect(MAX_PEAKS * 12)
                    .order(ByteOrder.nativeOrder());
            int n = SdrNative.sdrGetPeaks(nativeHandle, buf, MAX_PEAKS);
            if (n <= 0) {
                return out;
            }
            for (int i = 0; i < n; i++) {
                float off = buf.getFloat(i * 12);
                float snr = buf.getFloat(i * 12 + 4);
                float bw = buf.getFloat(i * 12 + 8);
                if (Float.isNaN(off) || Float.isNaN(snr) || Float.isNaN(bw)) {
                    continue;
                }
                out.add(new Peak(off, snr, bw));
            }
            return out;
        }
    }

    /**
     * Retunes the dongle to the given centre frequency. Validates the
     * R820T/R828D tuning range (24--1766 MHz); the native side flushes
     * stale buffers including the spectrum tap, so the next report only
     * sees the new centre. Returns 0 on success, negative on error or
     * when no dongle is open. Persistence reset is the caller's job --
     * RfSpectrumReporter watches the centre and resets on change.
     */
    public int retune(long hz) {
        synchronized (stateLock) {
            if (nativeHandle == 0) {
                return -1;
            }
            if (hz < 24_000_000L || hz > 1_766_000_000L) {
                return -2;
            }
            return SdrNative.sdrSetCenterFreq(nativeHandle, hz);
        }
    }

    // ---- internals, all called with stateLock held unless noted ----

    private boolean isDongle(UsbDevice dev) {
        if (dev == null) {
            return false;
        }
        if (dev.getVendorId() != VID) {
            return false;
        }
        for (int pid : PIDS) {
            if (dev.getProductId() == pid) {
                return true;
            }
        }
        return false;
    }

    private UsbDevice findDongle() {
        if (usbManager == null) {
            return null;
        }
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null) {
            return null;
        }
        for (UsbDevice dev : devices.values()) {
            if (isDongle(dev)) {
                return dev;
            }
        }
        return null;
    }

    private void onDongleAttachedLocked(UsbDevice dev) {
        dongle = dev;
        if (!SdrNative.ensureLoaded()) {
            report("SDR: native library missing", "libscythe_sdr.so not packaged?");
            return;
        }
        if (usbManager.hasPermission(dev)) {
            openDongleLocked(dev);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(
                    appContext, 0, new Intent(ACTION_USB_PERMISSION), flags);
            report("SDR: requesting USB permission…", dev.getDeviceName());
            usbManager.requestPermission(dev, pi);
        }
    }

    private void openDongleLocked(UsbDevice dev) {
        // Never claim the interface from Java: the claim belongs to libusb
        // (rtlsdr_open_fd), and a double claim returns LIBUSB_ERROR_BUSY.
        UsbDeviceConnection conn = usbManager.openDevice(dev);
        if (conn == null) {
            report("SDR: openDevice failed", "another app may hold the dongle");
            return;
        }
        int fd = conn.getFileDescriptor();
        if (fd < 0) {
            conn.close();
            report("SDR: bad file descriptor", "fd=" + fd);
            return;
        }
        long handle = SdrNative.sdrOpen(fd);
        if (handle == 0) {
            conn.close();
            report("SDR: native open failed", "see logcat ScytheSdr for rc");
            return;
        }
        // Strong reference: this object owns the fd for the whole session.
        connection = conn;
        dongle = dev;
        nativeHandle = handle;
        int tuner = SdrNative.sdrGetTunerType(handle);
        Log.i(TAG, "opened, tuner_type=" + tuner + " fd=" + fd);
        report("SDR: NESDR open (tuner " + tuner + ")",
                "tap START to stream 2.048 MS/s @ 100 MHz");
        startStreamLocked();
    }

    private void startStreamLocked() {
        if (nativeHandle == 0 || streaming) {
            return;
        }
        int rc = SdrNative.sdrStart(nativeHandle);
        if (rc != 0) {
            report("SDR: stream start failed", "rc=" + rc);
            return;
        }
        streaming = true;
        statBytes = 0;
        pollStop = false;
        pollThread = new Thread(this::pollLoop, "SdrPoll");
        pollThread.setDaemon(true);
        pollThread.start();
        report("SDR: streaming", "warming up…");
    }

    private void stopStreamLocked() {
        pollStop = true;
        Thread t = pollThread;
        pollThread = null;
        // sdrStop cancels from THIS thread (a Java thread), never from the
        // native stream thread blocked in read_async -- cancelling from the
        // blocked thread deadlocks.
        if (nativeHandle != 0 && streaming) {
            SdrNative.sdrStop(nativeHandle);
        }
        streaming = false;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Full teardown: stop stream, free native handle, release USB. */
    private void teardownLocked(String line1, String line2) {
        stopStreamLocked();
        if (nativeHandle != 0) {
            // sdrClose does NOT close the wrapped fd; we close it below.
            SdrNative.sdrClose(nativeHandle);
            nativeHandle = 0;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        dongle = null;
        report(line1, line2);
    }

    /**
     * Poll loop (own thread): drains the native ring buffer and maintains
     * the Phase 0 sanity stats -- total bytes plus mean/variance of the
     * uint8 I/Q stream (same shape as a capture sidecar's head_stats).
     */
    private void pollLoop() {
        ByteBuffer buf = ByteBuffer.allocateDirect(POLL_BLOCK);
        byte[] tmp = new byte[POLL_BLOCK];
        long lastReport = 0;
        long handle;
        synchronized (stateLock) {
            handle = nativeHandle;
        }
        while (!pollStop) {
            buf.clear();
            int n = (handle != 0) ? SdrNative.sdrReadBlock(handle, buf, POLL_BLOCK) : -1;
            if (n > 0) {
                buf.flip();
                buf.get(tmp, 0, n);
                // mean/variance over uint8 samples
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += (tmp[i] & 0xFF);
                }
                double mean = sum / n;
                double varSum = 0;
                for (int i = 0; i < n; i++) {
                    double d = (tmp[i] & 0xFF) - mean;
                    varSum += d * d;
                }
                statMean = mean;
                statVar = varSum / n;
                statBytes += n;
            } else if (n < 0) {
                break;
            }
            long now = System.currentTimeMillis();
            if (now - lastReport >= STATS_MS) {
                lastReport = now;
                long total = (handle != 0) ? SdrNative.sdrGetTotalBytes(handle) : statBytes;
                long dropped = (handle != 0) ? SdrNative.sdrGetDropped(handle) : 0;
                String l1 = String.format(Locale.US,
                        "SDR: streaming 2.048 MS/s @ 100 MHz — %.2f MB",
                        total / 1048576.0);
                String l2 = String.format(Locale.US,
                        "mean %.2f var %.2f%s",
                        statMean, statVar,
                        dropped > 0 ? " dropped " + dropped : "");
                report(l1, l2);
            }
            if (n <= 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            synchronized (stateLock) {
                handle = nativeHandle;
                if (handle == 0) {
                    break;
                }
            }
        }
    }

    private void report(String line1, String line2) {
        Listener l = listener;
        if (l != null) {
            l.onSdrStatus(line1, line2);
        }
    }
}
