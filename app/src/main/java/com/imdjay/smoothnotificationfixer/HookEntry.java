package com.imdjay.smoothnotificationfixer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smooth Notification Fixer for SystemUI
 * Author: imdjay
 *
 * Eliminates synchronous blocking Binder IPC calls and uncached settings loops
 * occurring inside SystemUI during notification shade scrolling.
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "[SmoothNotificationFixer by imdjay] ";
    private static final ConcurrentHashMap<String, String> sSettingCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> sSettingCounts = new ConcurrentHashMap<>();
    private static final String NULL_SENTINEL = "__NULL_SETTING__";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null || !lpparam.packageName.equals("com.android.systemui")) {
            return;
        }

        XposedBridge.log(TAG + "Target SystemUI process loaded!");

        // 1. Hook NotificationStatsLoggerImpl.maybeLogNotificationExpansionChange
        // Silences per-frame expansion stat logging during scroll
        try {
            Class<?> loggerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLoggerImpl",
                lpparam.classLoader
            );
            if (loggerClass != null) {
                XposedBridge.hookAllMethods(loggerClass, "maybeLogNotificationExpansionChange", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                });
                XposedBridge.log(TAG + "Hooked NotificationStatsLoggerImpl.maybeLogNotificationExpansionChange -> SILENCED");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking NotificationStatsLoggerImpl: " + t.getMessage());
        }

        // 2. Hook IStatusBarService$Stub$Proxy.onNotificationExpansionChanged
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

        // 3. Hook Settings$NameValueCache.getStringForUser
        // Custom ROMs introduce un-indexed custom toggles (like colored statusbar icons) that invalidate
        // AOSP generation tracking, triggering thousands of synchronous round-trip IPCs to system_server.
        // Intercepting with a concurrent in-memory cache delivers results in <0.001ms.
        try {
            Class<?> cacheClass = XposedHelpers.findClassIfExists(
                "android.provider.Settings$NameValueCache",
                lpparam.classLoader
            );
            if (cacheClass != null) {
                XposedBridge.hookAllMethods(cacheClass, "getStringForUser", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 1 && param.args[1] instanceof String) {
                            String name = (String) param.args[1];
                            AtomicInteger count = sSettingCounts.computeIfAbsent(name, k -> new AtomicInteger(0));
                            int c = count.incrementAndGet();
                            if (c == 1 || c % 1000 == 0) {
                                XposedBridge.log(TAG + "Setting intercepted: " + name + " (hits=" + c + ")");
                            }
                            String cached = sSettingCache.get(name);
                            if (cached != null) {
                                param.setResult(NULL_SENTINEL.equals(cached) ? null : cached);
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 1 && param.args[1] instanceof String) {
                            String name = (String) param.args[1];
                            if (!sSettingCache.containsKey(name)) {
                                Object res = param.getResult();
                                sSettingCache.put(name, res == null ? NULL_SENTINEL : (String) res);
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + "Hooked Settings$NameValueCache.getStringForUser -> ULTRA-FAST IN-MEMORY CACHE ACTIVE");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking Settings$NameValueCache: " + t.getMessage());
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
                            if (bgNormal instanceof android.view.View) {
                                android.view.View v = (android.view.View) bgNormal;
                                v.setVisibility(android.view.View.GONE);
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

                // Also silence setBlurBackgroundEnabled if called
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
