package com.controllor;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class ShizukuManager {
    private static final String TAG = "ShizukuManager";
    private static final int PERMISSION_REQUEST_CODE = 1000;
    private static boolean isInitialized = false;

    // 监听器实例（保存引用以便移除）
    private static Shizuku.OnBinderReceivedListener binderReceivedListener;
    private static Shizuku.OnBinderDeadListener binderDeadListener;
    private static Shizuku.OnPermissionResultListener permissionResultListener;

    // 初始化
    public static synchronized void init(Context context) {
        if (isInitialized) {
            Log.d(TAG, "Shizuku 已初始化，跳过");
            return;
        }

        try {
            // 支持多进程
            ShizukuProvider.enableMultiProcessSupport();

            // 注册 Binder 监听器
            binderReceivedListener = () -> {
                Log.d(TAG, "✅ Shizuku Binder 已连接");
            };
            binderDeadListener = () -> {
                Log.d(TAG, "❌ Shizuku Binder 已断开");
            };

            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);

            // 注册权限结果监听器（强引用，避免被 GC）
            permissionResultListener = (requestCode, grantResult) -> {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    Log.d(TAG, granted ? "✅ Shizuku 权限已授予" : "❌ Shizuku 权限被拒绝");
                }
            };
            Shizuku.addPermissionResultListener(permissionResultListener);

            isInitialized = true;
            Log.d(TAG, "✅ Shizuku 初始化完成");
        } catch (Exception e) {
            Log.e(TAG, "❌ Shizuku 初始化失败", e);
            destroyInternal();
            isInitialized = false;
        }
    }

    public static synchronized void destroy() {
        destroyInternal();
        isInitialized = false;
        Log.d(TAG, "Shizuku 已销毁");
    }

    private static void destroyInternal() {
        try { if (binderReceivedListener != null) Shizuku.removeBinderReceivedListener(binderReceivedListener); } catch (Exception ignored) {}
        try { if (binderDeadListener != null) Shizuku.removeBinderDeadListener(binderDeadListener); } catch (Exception ignored) {}
        try { if (permissionResultListener != null) Shizuku.removePermissionResultListener(permissionResultListener); } catch (Exception ignored) {}
    }

    // 状态检查
    public static boolean isAvailable() {
        try { return Shizuku.pingBinder(); } catch (Exception e) { return false; }
    }

    public static boolean isGranted() {
        try { return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED; } catch (Exception e) { return false; }
    }

    public static boolean canUseHighPrivilege() {
        return isAvailable() && isGranted() && !Shizuku.isPreV11();
    }

    public static int getShizukuUid() {
        try { return Shizuku.getUid(); } catch (Exception e) { return -1; }
    }

    // 请求权限
    public static void requestPermission(OnResultCallback callback) {
        if (Shizuku.isPreV11() || !isAvailable()) {
            if (callback != null) callback.onResult(false);
            return;
        }
        if (isGranted()) {
            if (callback != null) callback.onResult(true);
            return;
        }

        Shizuku.OnPermissionResultListener listener = new Shizuku.OnPermissionResultListener() {
            @Override
            public void onPermissionResult(int requestCode, int grantResult) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    if (callback != null) callback.onResult(granted);
                    Shizuku.removePermissionResultListener(this);
                }
            }
        };

        try {
            Shizuku.addPermissionResultListener(listener);
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "请求权限异常", e);
            Shizuku.removePermissionResultListener(listener);
            if (callback != null) callback.onResult(false);
        }
    }

    public interface OnResultCallback {
        void onResult(boolean granted);
    }

    // ==================== 执行命令（通过 Binder 调用系统服务） ====================
    public static String executeCommand(String command) {
        if (!canUseHighPrivilege()) {
            Log.w(TAG, "Shizuku 不可用");
            return "";
        }

        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                Log.w(TAG, "Binder 为空");
                return "";
            }

            // 通过 ShizukuBinderWrapper 调用 Shell 服务
            rikka.shizuku.ShizukuBinderWrapper wrapper = new rikka.shizuku.ShizukuBinderWrapper(binder);
            android.os.IBinder shellService = rikka.shizuku.SystemServiceHelper.getSystemService("shell");

            if (shellService == null) {
                Log.w(TAG, "Shell 服务未找到");
                return "";
            }

            // 使用标准的 Android Binder 事务调用 IShellService.execCommand
            // 这里简化处理，推荐使用 UserService 替代
            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("android.os.IShellService");
                data.writeString(command);
                data.writeStringArray(null); // env
                data.writeString(null); // cwd
                boolean success = shellService.transact(1, data, reply, 0); // 1 是 execCommand 的 transaction code
                if (success) {
                    reply.readException();
                    String result = reply.readString();
                    return result != null ? result : "";
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败", e);
        }
        return "";
    }

    // 快捷方法
    public static boolean pingHost(String ip) {
        String result = executeCommand("ping -c 1 -W 1 " + ip + " 2>/dev/null && echo alive");
        return result.contains("alive") || result.contains("1 received");
    }

    public static boolean checkPort(String ip, int port) {
        String result = executeCommand("timeout 1 bash -c \"echo >/dev/tcp/" + ip + "/" + port + "\" 2>/dev/null && echo open");
        return result.contains("open");
    }
}