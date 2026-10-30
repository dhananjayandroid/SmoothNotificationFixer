# Smooth Notification Fixer for SystemUI

[![Release](https://img.shields.io/github/v/release/dhananjayandroid/SmoothNotificationFixer?style=for-the-badge&color=blue)](https://github.com/dhananjayandroid/SmoothNotificationFixer/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg?style=for-the-badge)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%20--%2017-green.svg?style=for-the-badge)](https://developer.android.com)
[![Platform](https://img.shields.io/badge/Platform-Xposed%20%2F%20LSPosed%20%2F%20Vector-purple.svg?style=for-the-badge)](https://github.com/mywalkb/Vector)

**Author**: imdjay  
**Compatibility**: Android 12, 13, 14, 15, 16, 17 (AOSP / Evolution X / LineageOS / Custom ROMs)  
**Type**: Xposed / LSPosed / Vector Module  

---

## ⚡ The Problem

On many custom ROMs (most notably **Evolution X**, LineageOS forks, and various AOSP-based custom ROMs), scrolling through the notification shade causes severe stutter and frame drops as notifications accumulate. When 30 to 100+ notifications are pending, frame times can spike beyond **50ms (~20 FPS)** on a 120Hz display.

### The Root Cause: Uncached Synchronous Binder IPC Loops

Deep systrace/atrace profiling revealed that custom ROMs frequently introduce custom status bar customization toggles (e.g., `statusbar_colored_icons`, `statusbar_notif_count`).

Because these custom keys are not indexed into AOSP's internal `SettingsProvider` generation tracker:
1. Android's built-in `Settings$NameValueCache` misses every lookup.
2. Every notification row repeatedly calls `Settings.System.getStringForUser(...)` on every frame during scrolling.
3. SystemUI triggers **over 7,500 synchronous blocking Binder IPC calls to `system_server` in under two seconds** directly on the main UI thread.
4. Paired with synchronous `IStatusBarService.onNotificationExpansionChanged` IPC stalls, the UI thread spends almost all of its frame budget waiting for Binder responses.

---

## 🚀 The Solution

**Smooth Notification Fixer** hooks into `com.android.systemui` at runtime via LSPosed / Vector:
1. **Ultra-Fast In-Memory Cache**: Intercepts `Settings$NameValueCache.getStringForUser` inside SystemUI with a thread-safe in-memory cache (`ConcurrentHashMap`). Subsequent queries return in **<0.001 ms without Binder IPC**.
2. **Expansion IPC Silencing**: Silences redundant `maybeLogNotificationExpansionChange` and eliminates `onNotificationExpansionChanged` Binder calls during active scrolling.
3. **Zero System Modifications**: Runs 100% in memory via ART runtime hooking — leaves system partitions untouched, fully reversible at any time.

---

## 📊 Empirical Benchmarks (92 Live Notifications Tested)

Tested on Snapdragon 7+ Gen 2 (`marblein`), Evolution X (Android 17 Canary), 120Hz display:

| Metric | Stock Custom ROM | With SmoothNotificationFixer | Improvement |
| :--- | :--- | :--- | :--- |
| **`Measure + Layout + Draw`** | **50.35 ms** | **2.2 ms – 3.4 ms** | **~18x Faster** |
| **`VsyncDelay`** | 16.7 ms – 33.4 ms | **0.0 ms** | **Zero UI Thread Starvation** |
| **Janky Frames Percentage** | **67.86%** | **5.09%** | **>92% Reduction in Janks** |
| **Median (50th percentile) Frame Time** | 34 ms (~29 FPS) | **5 ms (>120 FPS)** | **Silky Smooth 120Hz** |
| **90th percentile Frame Time** | 73 ms | **16 ms** | **Consistent Fluidity** |

---

## 📦 Requirements

1. **Root**:
   - Magisk (v24+), KernelSU, KernelSU Next, or APatch.
2. **Zygisk**:
   - Magisk: Enable **Zygisk** in Magisk settings.
   - KernelSU / APatch: Install the **Zygisk Next** module.
3. **Xposed Framework**:
   - **Android 12 – 14**: [LSPosed (Zygisk Release)](https://github.com/LSPosed/LSPosed/releases)
   - **Android 14 – 17**: [Vector (LSPosed Android 15–17 continuation)](https://github.com/mywalkb/Vector) or LSPosed-mod.

---

## 📥 Installation

1. Download [`SmoothNotificationFixer-v1.1.apk`](https://github.com/dhananjayandroid/SmoothNotificationFixer/releases) from the **Releases** tab.
2. Install the APK on your rooted device.
3. Open **LSPosed** or **Vector Manager**.
4. Enable **Smooth Notification Fixer** in the Modules list.
5. Ensure the scope has **System UI (`com.android.systemui`)** checked (it is auto-selected).
6. Reboot your device or restart SystemUI (`su -c pkill -9 -f com.android.systemui`).
7. Enjoy lag-free 120Hz notification shade scrolling!

---

## 🛠️ Building from Source

### Using Android Studio / Gradle:
```bash
git clone https://github.com/dhananjayandroid/SmoothNotificationFixer.git
cd SmoothNotificationFixer
./gradlew assembleRelease
```

### Using Standalone Python Builder:
```bash
python build_standalone.py
```

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).  
Copyright © 2026 imdjay. All rights reserved.
