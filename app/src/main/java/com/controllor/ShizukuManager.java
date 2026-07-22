package com.tether.controller;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;
import rikka.shizuku.SystemServiceHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class ShizukuManager {
    private static final String TAG = "ShizukuManager";
    private static final int PERMISSION_REQUEST_CODE = 1000;

    private static boolean isInitialized = false;
    private static Context appContext;

    private static Shizuku.OnBinderReceivedListener binderReceivedListener = null;
    private static Shizuku.OnBinderDeadListener binderDeadListener = null;
    private static Shizuku.OnRequestPermissionResultListener requestPermissionResultListener = null;

    private static IBinder shellServiceBinder = null;
    private static int shellRetryCount = 0;
    private static final int MAX_SHELL_RETRIES = 5;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== 初始化 ====================
    public static void init(Context context) {
        appContext = context.getApplicationContext();

        if (isInitialized) {
            Log.d(TAG, "Shizuku 已初始化，跳过");
            return;
        }

        try {
            ShizukuProvider.enableMultiProcessSupport(true);

            binderReceivedListener = () -> {
                Log.d(TAG, "✅ Shizuku Binder 已连接");
                initShellServiceWithRetry();
            };

            binderDeadListener = () -> {
                Log.d(TAG, "❌ Shizuku Binder 已断开");
                shellServiceBinder = null;
                shellRetryCount = 0;
            };

            requestPermissionResultListener = (requestCode, grantResult) -> {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    Log.d(TAG, granted ? "✅ Shizuku 权限已授予" : "❌ Shizuku 权限被拒绝");
                    if (granted) {
                        shellRetryCount = 0;
                        mainHandler.postDelayed(() -> initShellServiceWithRetry(), 500);
                    }
                }
            };

            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);

            isInitialized = true;
            Log.d(TAG, "✅ Shizuku 初始化完成");

            if (canUseHighPrivilege()) {
                initShellServiceWithRetry();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Shizuku 初始化失败", e);
            destroy();
            isInitialized = false;
        }
    }

    public static void destroy() {
        if (binderReceivedListener != null) {
            try { Shizuku.removeBinderReceivedListener(binderReceivedListener); } catch (Exception ignored) {}
        }
        if (binderDeadListener != null) {
            try { Shizuku.removeBinderDeadListener(binderDeadListener); } catch (Exception ignored) {}
        }
        if (requestPermissionResultListener != null) {
            try { Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener); } catch (Exception ignored) {}
        }
        binderReceivedListener = null;
        binderDeadListener = null;
        requestPermissionResultListener = null;
        shellServiceBinder = null;
        shellRetryCount = 0;
        isInitialized = false;
        Log.d(TAG, "Shizuku 已销毁");
    }

    // ==================== 获取 Shell 服务（带重试） ====================
    private static void initShellServiceWithRetry() {
        if (shellRetryCount >= MAX_SHELL_RETRIES) {
            Log.e(TAG, "❌ Shell 服务获取失败，已达最大重试次数");
            return;
        }

        shellRetryCount++;
        Log.d(TAG, "🔄 尝试获取 Shell 服务 (" + shellRetryCount + "/" + MAX_SHELL_RETRIES + ")");

        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                Log.w(TAG, "Binder 为空，延迟重试...");
                mainHandler.postDelayed(() -> initShellServiceWithRetry(), 500);
                return;
            }

            shellServiceBinder = SystemServiceHelper.getSystemService("shell");
            if (shellServiceBinder != null) {
                Log.d(TAG, "✅ Shell 服务已获取");
                shellRetryCount = 0;
                return;
            }

            // 反射尝试
            try {
                Method getServiceMethod = binder.getClass().getMethod("getService", String.class);
                getServiceMethod.setAccessible(true);
                IBinder shellBinder = (IBinder) getServiceMethod.invoke(binder, "shell");
                if (shellBinder != null) {
                    shellServiceBinder = shellBinder;
                    Log.d(TAG, "✅ Shell 服务已获取 (通过反射)");
                    shellRetryCount = 0;
                    return;
                }
            } catch (Exception e) {
                Log.d(TAG, "反射获取 Shell 服务失败: " + e.getMessage());
            }

            Log.w(TAG, "⚠️ Shell 服务获取失败，延迟重试...");
            mainHandler.postDelayed(() -> initShellServiceWithRetry(), 500);

        } catch (Exception e) {
            Log.e(TAG, "获取 Shell 服务异常", e);
            mainHandler.postDelayed(() -> initShellServiceWithRetry(), 500);
        }
    }

    // ==================== 执行命令（仅 Shizuku） ====================
    public static String executeCommand(String command) {
        if (!isShellServiceReady()) {
            Log.w(TAG, "Shell 服务未就绪");
            return "";
        }

        try {
            Method method = shellServiceBinder.getClass().getDeclaredMethod(
                    "execCommand",
                    String.class,
                    String[].class,
                    String.class
            );
            method.setAccessible(true);
            Object[] result = (Object[]) method.invoke(shellServiceBinder, command, new String[0], null);
            if (result != null && result.length > 0) {
                return result[0] != null ? result[0].toString() : "";
            }
        } catch (Exception e) {
            Log.e(TAG, "Shizuku 执行失败", e);
        }
        return "";
    }

    // ==================== 状态检查 ====================
    public static boolean isAvailable() {
        try { return Shizuku.pingBinder(); } catch (Exception e) { return false; }
    }

    public static boolean isGranted() {
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; } catch (Exception e) { return false; }
    }

    public static boolean canUseHighPrivilege() {
        return isAvailable() && isGranted() && !Shizuku.isPreV11();
    }

    public static boolean isShellServiceReady() {
        return shellServiceBinder != null;
    }

    public static String getStatus() {
        return "Shizuku: " + (isAvailable() ? "✅" : "❌") +
                " | 权限: " + (isGranted() ? "✅" : "❌") +
                " | Shell: " + (isShellServiceReady() ? "✅" : "❌");
    }

    // ==================== 权限请求 ====================
    public static void requestPermission(OnResultCallback callback) {
        if (Shizuku.isPreV11() || !isAvailable()) {
            if (callback != null) callback.onResult(false);
            return;
        }
        if (isGranted()) {
            if (callback != null) callback.onResult(true);
            shellRetryCount = 0;
            mainHandler.postDelayed(() -> initShellServiceWithRetry(), 300);
            return;
        }

        final Shizuku.OnRequestPermissionResultListener[] listenerHolder = new Shizuku.OnRequestPermissionResultListener[1];
        listenerHolder[0] = (requestCode, grantResult) -> {
            if (requestCode == PERMISSION_REQUEST_CODE) {
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                if (callback != null) callback.onResult(granted);
                try { Shizuku.removeRequestPermissionResultListener(listenerHolder[0]); } catch (Exception ignored) {}
                if (granted) {
                    shellRetryCount = 0;
                    mainHandler.postDelayed(() -> initShellServiceWithRetry(), 500);
                }
            }
        };

        try {
            Shizuku.addRequestPermissionResultListener(listenerHolder[0]);
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "❌ 请求权限异常", e);
            try { Shizuku.removeRequestPermissionResultListener(listenerHolder[0]); } catch (Exception ignored) {}
            if (callback != null) callback.onResult(false);
        }
    }

    // ==================== 快捷方法 ====================
    public static boolean pingHost(String ip) {
        String result = executeCommand("ping -c 1 -W 1 " + ip + " 2>/dev/null && echo alive");
        return result.contains("alive") || result.contains("1 received");
    }

    public static boolean checkPort(String ip, int port) {
        String result = executeCommand("timeout 1 bash -c \"echo >/dev/tcp/" + ip + "/" + port + "\" 2>/dev/null && echo open");
        return result.contains("open");
    }

    public interface OnResultCallback {
        void onResult(boolean granted);
    }
}