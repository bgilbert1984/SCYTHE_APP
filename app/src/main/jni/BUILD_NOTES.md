# Phase 0 native dependencies — build notes

Prebuilt static libraries under `prebuilt/<abi>/` were built on dedirock
2026-10-01/02 with NDK **29.0.14206865** (side-by-side,
`~/Android/Sdk/ndk/29.0.14206865`), CMake 4.1.2, `ANDROID_PLATFORM=android-24`
(matches the app's `minSdk 24`), `ANDROID_ABI=arm64-v8a`, Release.

Third-party sources live OUTSIDE this repo in `~/sdr-build/` (kept out of
git deliberately). Rebuild recipe below reproduces the binaries byte-for-byte
modulo toolchain timestamps.

## libusb 1.0.29 (static)

- Source: https://github.com/libusb/libusb @ tag `v1.0.29`
- libusb has no top-level CMake; built with a hand-written `CMakeLists.txt`
  (`~/sdr-build/libusb-build/`) using libusb's own Android source list
  (`android/jni/libusb.mk`) and its Android `config.h` (`android/config.h`,
  on the include path for `#include <config.h>` from `libusbi.h`):
  `core.c descriptor.c hotplug.c io.c sync.c strerror.c
   os/linux_usbfs.c os/events_posix.c os/threads_posix.c os/linux_netlink.c`
- Flags: `-fvisibility=hidden -pthread`; links `log`.
- Why this works on Android: the app never lets libusb enumerate. The Java
  side passes the framework's file descriptor (`UsbDeviceConnection.
  getFileDescriptor()`) to `libusb_wrap_sys_device()` with
  `LIBUSB_OPTION_NO_DEVICE_DISCOVERY` set **before** `libusb_init()`.
  (libusb >= 1.0.25 for the `NO_DEVICE_DISCOVERY` name; `wrap_sys_device`
  exists since 1.0.23.)

## librtlsdr (static, rtl-sdr-blog fork with Android patch)

- Source: https://github.com/rtlsdrblog/rtl-sdr-blog @ `aed0` (2026-10-02)
  — the fork carries the R828D tuner driver the NESDR SMArt v5 needs.
- Patch applied to `src/librtlsdr.c` (+ declaration in `include/rtl-sdr.h`,
  vendored here as `include/rtl-sdr.h` along with the fork's in-tree `include/rtl-sdr_export.h`):
  1. Extracted the post-interface-claim initialisation (dummy write,
     baseband init, tuner probing incl. R828D, EEPROM/bias-tee) from
     `rtlsdr_open()` into `static int rtlsdr_init_device(rtlsdr_dev_t *)`.
     `rtlsdr_open()` now calls it; behaviour of the normal path unchanged.
  2. Added `int rtlsdr_open_fd(rtlsdr_dev_t **dev, int fd)`: mallocs the
     device, `libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY)`,
     `libusb_init()`, `libusb_wrap_sys_device(ctx, (intptr_t)fd, &devh)`,
     `libusb_set_auto_detach_kernel_driver(devh, 1)`,
     `libusb_claim_interface(devh, 0)`, then `rtlsdr_init_device()`.
- Configure: the fork finds libusb via pkg-config; for the NDK build we pass
  `-DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=TRUE` and point
  `-DLIBUSB_LIBRARIES=<...>/libusb.a -DLIBUSB_INCLUDE_DIRS=<...>/include/libusb-1.0`
  at the build above. Only the `rtlsdr_static` target is needed.

## Ownership rules (do not "fix" these)

- The Java `UsbDeviceConnection` owns the USB fd for the whole session
  (strong reference in `RfSdrManager`). `libusb_close()` does **not** close a
  wrapped descriptor. Never `dup()` the fd in JNI as well — holding the Java
  reference AND a dup closes it twice; holding neither lets the GC pick the
  moment your stream dies.
- The interface claim belongs to libusb. Do NOT call
  `UsbDeviceConnection.claimInterface()` from Java — the double claim
  returns `LIBUSB_ERROR_BUSY`.
- `rtlsdr_cancel_async()` must be called from a different thread than the one
  blocked in `rtlsdr_read_async()` (cancelling from the blocked thread
  deadlocks). `sdr_jni.c` only cancels from JNI (Java) threads; the stream
  runs on its own native pthread.
- After every retune, discard at least the first buffer: in-flight buffers
  were captured at the previous centre frequency and bake transients into the
  new one. `sdr_jni.c` does `rtlsdr_reset_buffer()` + one throwaway
  `rtlsdr_read_sync()` after tune.
- Hotplug is dead once discovery is disabled: detach is handled from the
  Android `USB_DEVICE_DETACHED` broadcast, never from libusb callbacks.

## 16 KB pages

NDK r27+ defaults to 16 KB-compatible output, and the final
`libscythe_sdr.so` is additionally linked with
`-Wl,-z,max-page-size=16384` (see `CMakeLists.txt`). Both static deps are
linked into that `.so`, so the flag covers them.
