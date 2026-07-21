package com.tether.controller

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 1000
    private const val DESCRIPTOR = "com.tether.controller.IUserService"

    private const val TRANSACTION_executeCommand = 1
    private const val TRANSACTION_ping = 2

    private var isInitialized = false
    private var userServiceBinder: IBinder? = null
    private var userServiceConnected = false

    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
    private var requestPermissionResultListener: Shizuku.OnRequestPermissionResultListener? = null

    private val userServiceConnection = object : Shizuku.ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            Log.d(TAG, "✅ UserService 已连接")
            userServiceBinder = iBinder
            userServiceConnected = true
            pingUserService()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Log.d(TAG, "❌ UserService 已断开")
            userServiceBinder = null
            userServiceConnected = false
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.tether.controller",
            "com.tether.controller.UserService"
        )
    )
        .daemon(false)
        .processNameSuffix("user_service")
        .debuggable(true)
        .version(1)

    private fun pingUserService() {
        val binder = userServiceBinder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_ping, data, reply, 0)
            Log.d(TAG, "UserService ping 成功")
        } catch (e: RemoteException) {
            Log.e(TAG, "UserService ping 失败", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "Shizuku 已初始化，跳过")
            return
        }

        try {
            // Shizuku 13.1.5 中 enableMultiProcessSupport 不需要参数
            ShizukuProvider.enableMultiProcessSupport()

            binderReceivedListener = Shizuku.OnBinderReceivedListener {
                Log.d(TAG, "✅ Shizuku Binder 已连接")
                if (!userServiceConnected) {
                    bindUserService()
                }
            }

            binderDeadListener = Shizuku.OnBinderDeadListener {
                Log.d(TAG, "❌ Shizuku Binder 已断开")
                userServiceBinder = null
                userServiceConnected = false
            }

            requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                    Log.d(TAG, if (granted) "✅ Shizuku 权限已授予" else "❌ Shizuku 权限被拒绝")
                    if (granted) {
                        bindUserService()
                    }
                }
            }

            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

            isInitialized = true
            Log.d(TAG, "✅ Shizuku 初始化完成")

            if (canUseHighPrivilege()) {
                bindUserService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Shizuku 初始化失败", e)
            destroy()
            isInitialized = false
        }
    }

    @Synchronized
    fun destroy() {
        unbindUserService()

        binderReceivedListener?.let {
            try {
                Shizuku.removeBinderReceivedListener(it)
            } catch (e: Exception) { /* ignore */ }
        }
        binderDeadListener?.let {
            try {
                Shizuku.removeBinderDeadListener(it)
            } catch (e: Exception) { /* ignore */ }
        }
        requestPermissionResultListener?.let {
            try {
                Shizuku.removeRequestPermissionResultListener(it)
            } catch (e: Exception) { /* ignore */ }
        }

        binderReceivedListener = null
        binderDeadListener = null
        requestPermissionResultListener = null
        isInitialized = false
        userServiceBinder = null
        userServiceConnected = false
        Log.d(TAG, "Shizuku 已销毁")
    }

    private fun bindUserService() {
        if (userServiceConnected) {
            Log.d(TAG, "UserService 已连接，跳过绑定")
            return
        }
        if (!canUseHighPrivilege()) {
            Log.w(TAG, "Shizuku 不可用，无法绑定 UserService")
            return
        }

        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            Log.d(TAG, "📨 UserService 绑定请求已发送")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 绑定 UserService 失败", e)
        }
    }

    private fun unbindUserService() {
        if (!userServiceConnected) return
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            Log.d(TAG, "UserService 已解绑")
        } catch (e: Exception) {
            Log.e(TAG, "解绑 UserService 失败", e)
        }
        userServiceBinder = null
        userServiceConnected = false
    }

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

    fun canUseHighPrivilege(): Boolean {
        return isAvailable() && isGranted() && !Shizuku.isPreV11()
    }

    fun isUserServiceReady(): Boolean = userServiceConnected && userServiceBinder != null

    fun requestPermission(onResult: ((Boolean) -> Unit)? = null) {
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "⚠️ Shizuku 版本过旧")
            onResult?.invoke(false)
            return
        }
        if (!isAvailable()) {
            Log.w(TAG, "⚠️ Shizuku 服务未运行")
            onResult?.invoke(false)
            return
        }
        if (isGranted()) {
            onResult?.invoke(true)
            bindUserService()
            return
        }

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                    onResult?.invoke(granted)
                    try {
                        Shizuku.removeRequestPermissionResultListener(this)
                    } catch (e: Exception) { /* ignore */ }
                    if (granted) {
                        bindUserService()
                    }
                }
            }
        }

        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 请求权限异常", e)
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (ex: Exception) { /* ignore */ }
            onResult?.invoke(false)
        }
    }

    fun executeCommand(command: String): String {
        if (!isUserServiceReady()) {
            Log.w(TAG, "UserService 未就绪，尝试重新绑定")
            bindUserService()
            Thread.sleep(500)
            if (!isUserServiceReady()) {
                Log.e(TAG, "UserService 不可用")
                return ""
            }
        }

        val binder = userServiceBinder ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(command)
            binder.transact(TRANSACTION_executeCommand, data, reply, 0)
            reply.readException()
            reply.readString() ?: ""
        } catch (e: RemoteException) {
            Log.e(TAG, "执行命令失败", e)
            ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // 快捷方法
    fun pingHost(ip: String): Boolean {
        val result = executeCommand("ping -c 1 -W 1 $ip 2>/dev/null && echo alive")
        return result.contains("alive") || result.contains("1 received")
    }

    fun checkPort(ip: String, port: Int): Boolean {
        val result = executeCommand(
            "timeout 1 bash -c \"echo >/dev/tcp/$ip/$port\" 2>/dev/null && echo open"
        )
        return result.contains("open")
    }

    fun tcpProbe(ip: String, port: Int): String {
        return executeCommand(
            "echo 'ping' | timeout 2 bash -c \"cat >/dev/tcp/$ip/$port\" 2>/dev/null && echo done"
        )
    }
}