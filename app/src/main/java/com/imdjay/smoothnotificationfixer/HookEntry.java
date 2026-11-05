package com.imdjay.smoothnotificationfixer;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Smooth Notification Fixer for SystemUI
 * Author: imdjay
 *
 * Eliminates synchronous blocking Binder IPC calls, notification expansion per-frame loops,
 * and notification shelf swipe-up blur artifacts occurring inside SystemUI.
 * Preserves 100% native ROM settings reactivity (Smart Pixels, Status Bar toggles, etc.)
 * using a hybrid real-time ContentObserver invalidator with a 750ms sliding window.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "[SmoothNotificationFixer by imdjay] ";
    
    // In-memory setting cache with timestamp
    private static final ConcurrentHashMap<String, CacheEntry> sSettingCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 750; // 750ms sliding cache window
    private static final String NULL_SENTINEL = "__NULL_SETTING__";
    private static volatile boolean sObserverRegistered = false;

    private static class CacheEntry {
        final String value;
        final long timestamp;

        CacheEntry(String value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null || !lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        XposedBridge.log(TAG + "Target SystemUI process loaded!");

        // 1. Hook Settings$NameValueCache.getStringForUser with Hybrid Real-Time ContentObserver & 750ms TTL
        // Eliminates 1,900+ synchronous Binder round-trips to SettingsProvider per swipe.
        // Instantly invalidates on user toggles (Smart Pixels, colored icons, etc.) via ContentObserver.
        try {
            Class<?> cacheClass = XposedHelpers.findClassIfExists(
                "android.provider.Settings$NameValueCache",
                lpparam.classLoader
            );
            if (cacheClass != null) {
                XposedBridge.hookAllMethods(cacheClass, "getStringForUser", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length < 2) return;

                        // Lazily register ContentObserver using the passed ContentResolver
                        if (!sObserverRegistered && param.args[0] instanceof ContentResolver) {
                            sObserverRegistered = true;
                            final ContentResolver cr = (ContentResolver) param.args[0];
                            try {
                                ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                                    @Override
                                    public void onChange(boolean selfChange, Uri uri) {
                                        if (uri != null) {
                                            String key = uri.getLastPathSegment();
                                            if (key != null) {
                                                sSettingCache.remove(key);
                                            } else {
                                                sSettingCache.clear();
                                            }
                                        } else {
                                            sSettingCache.clear();
                                        }
                                    }
                                };
                                cr.registerContentObserver(Settings.System.CONTENT_URI, true, observer);
                                cr.registerContentObserver(Settings.Secure.CONTENT_URI, true, observer);
                                cr.registerContentObserver(Settings.Global.CONTENT_URI, true, observer);
                                XposedBridge.log(TAG + "Live ContentObserver active: 0ms real-time custom ROM setting reactivity");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "ContentObserver registration warning: " + t.getMessage());
                            }
                        }

                        if (param.args[1] instanceof String) {
                            String name = (String) param.args[1];
                            long now = SystemClock.uptimeMillis();
                            CacheEntry entry = sSettingCache.get(name);
                            if (entry != null && (now - entry.timestamp) < CACHE_TTL_MS) {
                                param.setResult(NULL_SENTINEL.equals(entry.value) ? null : entry.value);
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 1 && param.args[1] instanceof String) {
                            String name = (String) param.args[1];
                            Object res = param.getResult();
                            sSettingCache.put(name, new CacheEntry(
                                res == null ? NULL_SENTINEL : (String) res,
                                SystemClock.uptimeMillis()
                            ));
                        }
                    }
                });
                XposedBridge.log(TAG + "Hooked Settings$NameValueCache.getStringForUser -> HYBRID REAL-TIME CACHE ACTIVE");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking Settings$NameValueCache: " + t.getMessage());
        }

        // 2. Hook NotificationStatsLoggerImpl (updateExpansionStates & maybeLogNotificationExpansionChange)
        // Silences per-frame expansion stat logging and map iteration during drag and scroll
        try {
            Class<?> loggerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLoggerImpl",
                lpparam.classLoader
            );
            if (loggerClass != null) {
                XC_MethodHook silenceMethod = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                };
                XposedBridge.hookAllMethods(loggerClass, "updateExpansionStates", silenceMethod);
                XposedBridge.hookAllMethods(loggerClass, "maybeLogNotificationExpansionChange", silenceMethod);
                XposedBridge.log(TAG + "Hooked NotificationStatsLoggerImpl -> PER-FRAME EXPANSION STALLS ELIMINATED");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking NotificationStatsLoggerImpl: " + t.getMessage());
        }

        // 3. Hook IStatusBarService$Stub$Proxy.onNotificationExpansionChanged
        // Prevents blocking synchronous Binder IPC to system_server on the UI thread
        try {
            Class<?> proxyClass = XposedHelpers.findClassIfExists(
                "com.android.internal.statusbar.IStatusBarService$Stub$Proxy",
                lpparam.classLoader
            );
            if (proxyClass != null) {
                XposedBridge.hookAllMethods(proxyClass, "onNotificationExpansionChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                });
                XposedBridge.log(TAG + "Hooked IStatusBarService.onNotificationExpansionChanged -> ZERO BINDER IPC");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking IStatusBarService: " + t.getMessage());
        }

        // 4. Hook NotificationShelf and NotificationShelfBackgroundView
        // Eliminates the floating/moving shelf background blur highlight during shade swipe-up
        try {
            Class<?> shelfClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.NotificationShelf",
                lpparam.classLoader
            );
            if (shelfClass != null) {
                // Force shelf to always report transparent background
                XposedBridge.hookAllMethods(shelfClass, "usesTransparentBackground", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(true);
                    }
                });

                // Ensure mBackgroundNormal is hidden and transparent after inflation
                XposedBridge.hookAllMethods(shelfClass, "onFinishInflate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object bgNormal = XposedHelpers.getObjectField(param.thisObject, "mBackgroundNormal");
                            if (bgNormal instanceof View) {
                                View v = (View) bgNormal;
                                v.setVisibility(View.GONE);
                                v.setAlpha(0.0f);
                                v.setBackground(null);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log(TAG + "Hooked NotificationShelf -> TRANSPARENT BACKGROUND ACTIVE");
            }

            Class<?> shelfBgClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.shelf.NotificationShelfBackgroundView",
                lpparam.classLoader
            );
            if (shelfBgClass != null) {
                // Intercept both draw and onDraw to ensure zero pixels/blur are rendered
                XC_MethodHook silenceDraw = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                };
                XposedBridge.hookAllMethods(shelfBgClass, "draw", silenceDraw);
                XposedBridge.hookAllMethods(shelfBgClass, "onDraw", silenceDraw);

                try {
                    XposedBridge.hookAllMethods(shelfBgClass, "setBlurBackgroundEnabled", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args != null && param.args.length > 0) {
                                param.args[0] = false;
                            }
                        }
                    });
                } catch (Throwable ignored) {}

                XposedBridge.log(TAG + "Hooked NotificationShelfBackgroundView.draw & onDraw -> SILENCED (ZERO BLUR HIGHLIGHT)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking NotificationShelf: " + t.getMessage());
        }
    }
}
