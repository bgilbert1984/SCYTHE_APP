# app/src/main/jni/CMakeLists.txt
#
# Builds libscythe_sdr.so: the Phase 0 JNI bridge (sdr_jni.c) statically
# linked against prebuilt librtlsdr + libusb (see BUILD_NOTES.md).
#
# The prebuilt static libs live under prebuilt/<abi>/ so the Gradle build is
# self-contained -- no third-party source in the repo, no network at build.

cmake_minimum_required(VERSION 3.22)

project(scythe_sdr C)

set(PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")

add_library(rtlsdr_prebuilt STATIC IMPORTED)
set_target_properties(rtlsdr_prebuilt PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/librtlsdr.a")

add_library(usb_prebuilt STATIC IMPORTED)
set_target_properties(usb_prebuilt PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/libusb.a")

add_library(scythe_sdr SHARED
    sdr_jni.c
    sdr_dsp.c
    kissfft/kiss_fft.c)

# Public librtlsdr API header (rtl-sdr.h), vendored from the rtl-sdr-blog fork.
# kissfft sources are vendored public-domain-ish (BSD-3) FFT for the Phase 1
# edge DSP; sdr_dsp.c is the Hann periodogram -> 256-bin thumbnail core.
target_include_directories(scythe_sdr PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/include")

target_link_libraries(scythe_sdr
    rtlsdr_prebuilt
    usb_prebuilt
    log
    m)

# 16 KB page-size compatibility (Android 15+ devices, e.g. Pixel 7 Pro):
# a shared library built for 4 KB pages fails to load on 16 KB-page devices.
# Applies to the final .so; the static deps are linked in here.
target_link_options(scythe_sdr PRIVATE "LINKER:-z,max-page-size=16384")
