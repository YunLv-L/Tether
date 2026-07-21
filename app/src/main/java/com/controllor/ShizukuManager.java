package com.tether.controller;

import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class ShizukuManager {
    private static final String TAG = "ShizukuManager";
    private static final int PERMISSION_REQUEST_CODE = 1000;
    private static final String DESCRIPTOR = "com.tether.controller.IUserService";
    private static final int TRANSACTION_executeCommand = 1;
    private static final int TRANSACTION_ping = 2;

    private static boolean isInitialized = false;
    private static IBinder userServiceBinder = null;
    private static boolean userServiceConnected = false;

    // 监听器实例（必须保存引用以便移除）
    private static Shizuku.OnBinderReceivedListener binderReceivedListener = null;
    private static Shizuku.OnBinderDeadListener binderDeadListener = null;
    private static Shizuku.OnRequestPermissionResultListener requestPermissionResultListener = null;

    private static final Shizuku.ServiceConnection userServiceConnection = new Shizuku.ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.d(TAG, "✅ UserService 已连接");
            userServiceBinder = iBinder;
            userServiceConnected = true;
            pingUserService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG, "❌ UserService 已断开");
            userServiceBinder = null;
            userServiceConnected = false;
        }
    };

    private static final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName("com.tether.controller", "com.tether.controller.UserService"))
                    .daemon(false)
                    .processNameSuffix("user_service")
                    .debuggable(true)
                    .version(1);

    private static void pingUserService() {
        if (userServiceBinder == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            userServiceBinder.transact(TRANSACTION_ping, data, reply, 0);
            Log.d(TAG, "UserService ping 成功");
        } catch (RemoteException e) {
            Log.e(TAG, "UserService ping 失败", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static void init(Context context) {
        if (isInitialized) {
            Log.d(TAG, "Shizuku 已初始化，跳过");
            return;
        }

        try {
            ShizukuProvider.enableMultiProcessSupport();

            binderReceivedListener = () -> {
                Log.d(TAG, "✅ Shizuku Binder 已连接");
                if (!userServiceConnected) {
                    bindUserService();
                }
            };

            binderDeadListener = () -> {
                Log.d(TAG, "❌ Shizuku Binder 已断开");
                userServiceBinder = null;
                userServiceConnected = false;
            };

            requestPermissionResultListener = (requestCode, grantResult) -> {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    Log.d(TAG, granted ? "✅ Shizuku 权限已授予" : "❌ Shizuku 权限被拒绝");
                    if (granted) {
                        bindUserService();
                    }
                }
            };

            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);

            isInitialized = true;
            Log.d(TAG, "✅ Shizuku 初始化完成");

            if (canUseHighPrivilege()) {
                bindUserService();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Shizuku 初始化失败", e);
            destroy();
            isInitialized = false;
        }
    }

    public static void destroy() {
        unbindUserService();
        try { if (binderReceivedListener != null) Shizuku.removeBinderReceivedListener(binderReceivedListener); } catch (Exception ignored) {}
        try { if (binderDeadListener != null) Shizuku.removeBinderDeadListener(binderDeadListener); } catch (Exception ignored) {}
        try { if (requestPermissionResultListener != null) Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener); } catch (Exception ignored) {}
        binderReceivedListener = null;
        binderDeadListener = null;
        requestPermissionResultListener = null;
        isInitialized = false;
        userServiceBinder = null;
        userServiceConnected = false;
        Log.d(TAG, "Shizuku 已销毁");
    }

    private static void bindUserService() {
        if (userServiceConnected) {
            Log.d(TAG, "UserService 已连接，跳过绑定");
            return;
        }
        if (!canUseHighPrivilege()) {
            Log.w(TAG, "Shizuku 不可用，无法绑定 UserService");
            return;
        }
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection);
            Log.d(TAG, "📨 UserService 绑定请求已发送");
        } catch (Exception e) {
            Log.e(TAG, "❌ 绑定 UserService 失败", e);
        }
    }

    private static void unbindUserService() {
        if (!userServiceConnected) return;
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true);
            Log.d(TAG, "UserService 已解绑");
        } catch (Exception e) {
            Log.e(TAG, "解绑 UserService 失败", e);
        }
        userServiceBinder = null;
        userServiceConnected = false;
    }

    public static boolean isAvailable() {
        try { return Shizuku.pingBinder(); } catch (Exception e) { return false; }
    }

    public static boolean isGranted() {
        try { return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED; } catch (Exception e) { return false; }
    }

    public static boolean canUseHighPrivilege() {
        return isAvailable() && isGranted() && !Shizuku.isPreV11();
    }

    public static void requestPermission(OnResultCallback callback) {
        if (Shizuku.isPreV11() || !isAvailable()) {
            if (callback != null) callback.onResult(false);
            return;
        }
        if (isGranted()) {
            if (callback != null) callback.onResult(true);
            bindUserService();
            return;
        }

        Shizuku.OnRequestPermissionResultListener listener = (requestCode, grantResult) -> {
            if (requestCode == PERMISSION_REQUEST_CODE) {
                boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (callback != null) callback.onResult(granted);
                try { Shizuku.removeRequestPermissionResultListener(this); } catch (Exception ignored) {}
                if (granted) {
                    bindUserService();
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

    public static String executeCommand(String command) {
        if (!isUserServiceReady()) {
            Log.w(TAG, "UserService 未就绪");
            bindUserService();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            if (!isUserServiceReady()) {
                Log.e(TAG, "UserService 不可用");
                return "";
            }
        }

        if (userServiceBinder == null) return "";
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(command);
            userServiceBinder.transact(TRANSACTION_executeCommand, data, reply, 0);
            reply.readException();
            return reply.readString();
        } catch (RemoteException e) {
            Log.e(TAG, "执行命令失败", e);
            return "";
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean isUserServiceReady() {
        return userServiceConnected && userServiceBinder != null;
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

    public static String tcpProbe(String ip, int port) {
        return executeCommand("echo 'ping' | timeout 2 bash -c \"cat >/dev/tcp/" + ip + "/" + port + "\" 2>/dev/null && echo done");
    }

    public interface OnResultCallback {
        void onResult(boolean granted);
    }
}