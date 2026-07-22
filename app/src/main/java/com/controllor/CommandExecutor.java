package com.tether.controller;

import android.content.Context;
import android.util.Log;

public class CommandExecutor {
    private static final String TAG = "CommandExecutor";

    private static boolean shizukuInit = false;
    private static boolean dhizukuInit = false;

    /**
     * 初始化所有权限通道
     */
    public static void init(Context context) {
        // 初始化 Shizuku
        ShizukuManager.init(context);
        shizukuInit = true;

        // 初始化 Dhizuku
        dhizukuInit = DhizukuManager.init(context);

        Log.d(TAG, "初始化完成 | Shizuku: " + ShizukuManager.getStatus() +
                " | Dhizuku: " + DhizukuManager.getStatus());
    }

    /**
     * 执行命令（自动选择最佳通道）
     */
    public static String executeCommand(String command) {
        Log.d(TAG, "📨 执行命令: " + command);

        // 1️⃣ 优先：Shizuku Shell 服务
        if (ShizukuManager.isShellServiceReady()) {
            String result = ShizukuManager.executeCommand(command);
            if (!result.isEmpty()) {
                Log.d(TAG, "✅ Shizuku 执行成功");
                return result;
            }
            Log.w(TAG, "Shizuku 执行返回空，尝试 Dhizuku");
        }

        // 2️⃣ 备选：Dhizuku
        if (DhizukuManager.isPermissionGranted()) {
            String result = DhizukuManager.executeCommand(command);
            if (!result.isEmpty()) {
                Log.d(TAG, "✅ Dhizuku 执行成功");
                return result;
            }
            Log.w(TAG, "Dhizuku 执行返回空");
        }

        // 3️⃣ 降级：Runtime.exec() 无权限
        Log.d(TAG, "⚠️ 所有高权限方案失败，使用降级方案");
        return fallbackExec(command);
    }

    private static String fallbackExec(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
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

    public static String getStatus() {
        return "Shizuku: " + ShizukuManager.getStatus() +
                "\nDhizuku: " + DhizukuManager.getStatus();
    }
}