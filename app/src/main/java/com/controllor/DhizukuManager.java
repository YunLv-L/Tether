package com.tether.controller;

import android.content.Context;
import android.util.Log;

import com.rosan.dhizuku.api.Dhizuku;
import com.rosan.dhizuku.api.DhizukuRemoteProcess;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class DhizukuManager {
    private static final String TAG = "DhizukuManager";

    private static boolean isAvailable = false;
    private static boolean isPermissionGranted = false;
    private static Context appContext;

    /**
     * 初始化 Dhizuku
     */
    public static boolean init(Context context) {
        appContext = context.getApplicationContext();
        try {
            isAvailable = Dhizuku.init(appContext);
            if (isAvailable) {
                isPermissionGranted = Dhizuku.isPermissionGranted();
                Log.d(TAG, "✅ Dhizuku 可用, 权限: " + isPermissionGranted);
            } else {
                Log.d(TAG, "❌ Dhizuku 不可用");
            }
            return isAvailable;
        } catch (Exception e) {
            Log.e(TAG, "Dhizuku 初始化异常", e);
            isAvailable = false;
            isPermissionGranted = false;
            return false;
        }
    }

    /**
     * 检查 Dhizuku 是否可用
     */
    public static boolean isAvailable() {
        return isAvailable;
    }

    /**
     * 检查 Dhizuku 权限是否已授权
     */
    public static boolean isPermissionGranted() {
        return isAvailable && isPermissionGranted;
    }

    /**
     * 通过 Dhizuku 执行 shell 命令
     */
    public static String executeCommand(String command) {
        if (!isPermissionGranted()) {
            Log.w(TAG, "Dhizuku 不可用或未授权");
            return "";
        }

        try {
            Log.d(TAG, "📨 Dhizuku 执行: " + command);

            String[] cmdArray = new String[]{"sh", "-c", command};
            DhizukuRemoteProcess process = Dhizuku.newProcess(cmdArray, null, null);

            // 读取 stdout
            InputStream inputStream = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // 读取 stderr
            InputStream errorStream = process.getErrorStream();
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream));
            StringBuilder errorOutput = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }

            // 等待完成（超时 5 秒）
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                Log.w(TAG, "Dhizuku 执行超时");
                return output.toString().trim();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && errorOutput.length() > 0) {
                Log.w(TAG, "Dhizuku 命令错误: " + errorOutput.toString().trim());
            }

            Log.d(TAG, "✅ Dhizuku 执行完成, 退出码: " + exitCode);
            return output.toString().trim();

        } catch (Exception e) {
            Log.e(TAG, "Dhizuku 执行失败", e);
            return "";
        }
    }

    /**
     * 获取状态信息
     */
    public static String getStatus() {
        return "Dhizuku: " + (isAvailable ? "✅" : "❌") +
                " | 权限: " + (isPermissionGranted ? "✅" : "❌");
    }
}