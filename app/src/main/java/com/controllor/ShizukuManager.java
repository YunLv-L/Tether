package com.tether.controller;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.ShizukuProvider;
import rikka.shizuku.SystemServiceHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class ShizukuManager {
    private static final String TAG = "ShizukuManager";
    private static final int PERMISSION_REQUEST_CODE = 1000;

    private static boolean isInitialized = false;
    private static Shizuku.OnBinderReceivedListener binderReceivedListener = null;
    private static Shizuku.OnBinderDeadListener binderDeadListener = null;
    private static Shizuku.OnRequestPermissionResultListener requestPermissionResultListener = null;

    private static IBinder shellServiceBinder = null;

    // ==================== 初始化 ====================
    public static void init(Context context) {
        if (isInitialized) {
            Log.d(TAG, "Shizuku 已初始化，跳过");
            return;
        }

        try {
            ShizukuProvider.enableMultiProcessSupport(true);

            binderReceivedListener = () -> {
                Log.d(TAG, "✅ Shizuku Binder 已连接");
                initShellService();
            };

            binderDeadListener = () -> {
                Log.d(TAG, "❌ Shizuku Binder 已断开");
                shellServiceBinder = null;
            };

            requestPermissionResultListener = (requestCode, grantResult) -> {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    Log.d(TAG, granted ? "✅ Shizuku 权限已授予" : "❌ Shizuku 权限被拒绝");
                    if (granted) {
                        initShellService();
                    }
                }
            };

            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);

            isInitialized = true;
            Log.d(TAG, "✅ Shizuku 初始化完成");

            if (canUseHighPrivilege()) {
                initShellService();
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
        isInitialized = false;
        Log.d(TAG, "Shizuku 已销毁");
    }

    // ==================== 初始化 Shell 服务 ====================
    private static void initShellService() {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                Log.w(TAG, "Binder 为空");
                return;
            }
            shellServiceBinder = SystemServiceHelper.getSystemService("shell");
            if (shellServiceBinder != null) {
                Log.d(TAG, "✅ Shell 服务已获取");
            } else {
                Log.w(TAG, "⚠️ Shell 服务为空");
            }
        } catch (Exception e) {
            Log.e(TAG, "获取 Shell 服务失败", e);
        }
    }

    // ==================== 状态检查 ====================
    public static boolean isAvailable() {
        try { return Shizuku.pingBinder(); } catch (Exception e) { return false; }
    }

    public static boolean isGranted() {
        try { return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED; } catch (Exception e) { return false; }
    }

    public static boolean canUseHighPrivilege() {
        return isAvailable() && isGranted() && !Shizuku.isPreV11();
    }

    public static boolean isShellServiceReady() {
        return shellServiceBinder != null;
    }

    // ==================== 权限请求 ====================
    public static void requestPermission(OnResultCallback callback) {
        if (Shizuku.isPreV11() || !isAvailable()) {
            if (callback != null) callback.onResult(false);
            return;
        }
        if (isGranted()) {
            if (callback != null) callback.onResult(true);
            initShellService();
            return;
        }

        // ✅ listener 直接初始化，不用 null
        Shizuku.OnRequestPermissionResultListener listener = (requestCode, grantResult) -> {
            if (requestCode == PERMISSION_REQUEST_CODE) {
                boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (callback != null) callback.onResult(granted);
                // ✅ 用 listener 变量名移除自身
                try { Shizuku.removeRequestPermissionResultListener(listener); } catch (Exception ignored) {}
                if (granted) {
                    initShellService();
                }
            }
        };

        try {
            Shizuku.addRequestPermissionResultListener(listener);
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "❌ 请求权限异常", e);
            try { Shizuku.removeRequestPermissionResultListener(listener); } catch (Exception ignored) {}
            if (callback != null) callback.onResult(false);
        }
    }

    // ==================== 执行命令 ====================
    public static String executeCommand(String command) {
        if (!isShellServiceReady()) {
            Log.w(TAG, "Shell 服务未就绪，尝试重新获取");
            initShellService();
            if (!isShellServiceReady()) {
                Log.e(TAG, "Shell 服务不可用");
                return "";
            }
        }

        try {
            // 通过反射调用 execCommand
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
            Log.e(TAG, "执行命令失败", e);
        }

        // 降级方案：用 Runtime.exec
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            reader.close();
            return output.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "降级执行失败", e);
            return "";
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

    public static String tcpProbe(String ip, int port) {
        return executeCommand("echo 'ping' | timeout 2 bash -c \"cat >/dev/tcp/" + ip + "/" + port + "\" 2>/dev/null && echo done");
    }

    // ==================== 回调接口 ====================
    public interface OnResultCallback {
        void onResult(boolean granted);
    }
}