package com.tether.controller

import android.content.Context
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private var isInitialized = false
    private val permissionRequestCode = 1000

    private val binderReceivedListeners = mutableListOf<() -> Unit>()
    private val binderDeadListeners = mutableListOf<() -> Unit>()

    // ==================== 初始化 ====================
    fun init(context: Context) {
        if (isInitialized) return
        try {
            ShizukuProvider.enableMultiProcessSupport()
            isInitialized = true

            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Log.d(TAG, "✅ Shizuku Binder 已连接")
                    binderReceivedListeners.forEach { it.invoke() }
                }
            })

            Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() {
                    Log.d(TAG, "❌ Shizuku Binder 已断开")
                    binderDeadListeners.forEach { it.invoke() }
                }
            })

            Log.d(TAG, "✅ Shizuku 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Shizuku 初始化失败", e)
        }
    }

    // ==================== 状态检查 ====================
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun isGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun canUseHighPrivilege(): Boolean = isAvailable() && isGranted()

    fun getUid(): Int {
        return try {
            Shizuku.getUid()
        } catch (e: Exception) {
            -1
        }
    }

    fun getVersion(): Int {
        return try {
            Shizuku.getVersion()
        } catch (e: Exception) {
            0
        }
    }

    fun isRoot(): Boolean = getUid() == 0
    fun isAdb(): Boolean = getUid() == 2000

    // ==================== 权限请求 ====================
    fun requestPermission() {
        if (!isAvailable()) {
            Log.d(TAG, "⚠️ Shizuku 服务未运行")
            return
        }
        if (isGranted()) {
            Log.d(TAG, "✅ Shizuku 权限已授予")
            return
        }
        try {
            Shizuku.requestPermission(permissionRequestCode)
            Log.d(TAG, "📨 Shizuku 权限请求已发送")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 请求 Shizuku 权限失败", e)
        }
    }

    // ==================== 监听器管理 ====================
    fun addBinderReceivedListener(listener: () -> Unit) {
        binderReceivedListeners.add(listener)
    }

    fun addBinderDeadListener(listener: () -> Unit) {
        binderDeadListeners.add(listener)
    }

    fun removeBinderReceivedListener(listener: () -> Unit) {
        binderReceivedListeners.remove(listener)
    }

    fun removeBinderDeadListener(listener: () -> Unit) {
        binderDeadListeners.remove(listener)
    }

    // ==================== 执行命令 ====================
    fun executeCommand(command: String): String {
        if (!canUseHighPrivilege()) {
            Log.d(TAG, "⚠️ Shizuku 不可用，无法执行命令")
            return ""
        }

        val binder = try {
            Shizuku.getBinder()
        } catch (e: Exception) {
            Log.e(TAG, "❌ 获取 Binder 失败", e)
            return ""
        } ?: return ""

        // Shizuku 执行 shell 命令的 transaction code
        val transactionCode = 16777116

        return try {
            val data = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken("rikka.shizuku.IShizukuService")
                data.writeString(command)
                data.writeStringArray(arrayOf())
                data.writeString(null)
                binder.transact(transactionCode, data, reply, 0)
                val result = reply.readString() ?: ""
                reply.readInt() // 跳过 exit code
                reply.readInt() // 跳过 error
                result
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "❌ 执行命令失败: $command", e)
            ""
        }
    }

    // ==================== 快捷方法 ====================
    fun pingHost(ip: String, timeout: Int = 1): Boolean {
        val result = executeCommand("ping -c 1 -W $timeout $ip 2>/dev/null && echo 'alive'")
        return result.contains("alive") || result.contains("1 received")
    }

    fun checkPort(ip: String, port: Int, timeout: Int = 1): Boolean {
        // 用 /dev/tcp 探测端口
        val result = executeCommand(
            "timeout $timeout bash -c \"echo >/dev/tcp/$ip/$port\" 2>/dev/null && echo 'open'"
        )
        return result.contains("open")
    }

    fun tcpProbe(ip: String, port: Int): String {
        return executeCommand(
            "echo 'ping' | timeout 2 bash -c \"cat >/dev/tcp/$ip/$port\" 2>/dev/null && echo 'done'"
        )
    }
}