package com.tether.controller

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 1000

    private var isInitialized = false
    private var userService: IUserService? = null
    private var userServiceConnected = false

    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
    private var permissionResultListener: Shizuku.OnPermissionResultListener? = null

    private val binderReceivedCallbacks = mutableListOf<() -> Unit>()
    private val binderDeadCallbacks = mutableListOf<() -> Unit>()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            BuildConfig.APPLICATION_ID,
            UserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("user_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : Shizuku.ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            Log.d(TAG, "✅ UserService 已连接")
            userService = IUserService.Stub.asInterface(iBinder)
            userServiceConnected = true
            try {
                userService?.ping()
            } catch (e: RemoteException) {
                Log.e(TAG, "UserService ping 失败", e)
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Log.d(TAG, "❌ UserService 已断开")
            userService = null
            userServiceConnected = false
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "Shizuku 已初始化，跳过")
            return
        }

        try {
            ShizukuProvider.enableMultiProcessSupport()

            binderReceivedListener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Log.d(TAG, "✅ Shizuku Binder 已连接")
                    binderReceivedCallbacks.forEach { it.invoke() }
                    if (!userServiceConnected) {
                        bindUserService()
                    }
                }
            }
            binderDeadListener = object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() {
                    Log.d(TAG, "❌ Shizuku Binder 已断开")
                    binderDeadCallbacks.forEach { it.invoke() }
                    userService = null
                    userServiceConnected = false
                }
            }

            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)

            permissionResultListener = object : Shizuku.OnPermissionResultListener {
                override fun onPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == PERMISSION_REQUEST_CODE) {
                        val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                        Log.d(TAG, if (granted) "✅ Shizuku 权限已授予" else "❌ Shizuku 权限被拒绝")
                        if (granted) {
                            bindUserService()
                        }
                    }
                }
            }
            Shizuku.addPermissionResultListener(permissionResultListener)

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
        try { binderReceivedListener?.let { Shizuku.removeBinderReceivedListener(it) } } catch (e: Exception) { /* ignore */ }
        try { binderDeadListener?.let { Shizuku.removeBinderDeadListener(it) } } catch (e: Exception) { /* ignore */ }
        try { permissionResultListener?.let { Shizuku.removePermissionResultListener(it) } } catch (e: Exception) { /* ignore */ }
        binderReceivedCallbacks.clear()
        binderDeadCallbacks.clear()
        binderReceivedListener = null
        binderDeadListener = null
        permissionResultListener = null
        isInitialized = false
        userService = null
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
        userService = null
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

    fun isUserServiceReady(): Boolean = userServiceConnected && userService != null

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

        val listener = object : Shizuku.OnPermissionResultListener {
            override fun onPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                    onResult?.invoke(granted)
                    try { Shizuku.removePermissionResultListener(this) } catch (e: Exception) { /* ignore */ }
                    if (granted) {
                        bindUserService()
                    }
                }
            }
        }

        try {
            Shizuku.addPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 请求权限异常", e)
            try { Shizuku.removePermissionResultListener(listener) } catch (ex: Exception) { /* ignore */ }
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

        return try {
            userService?.executeCommand(command) ?: ""
        } catch (e: RemoteException) {
            Log.e(TAG, "执行命令失败", e)
            ""
        }
    }

    fun pingHost(ip: String, timeout: Int = 1): Boolean {
        val result = executeCommand("ping -c 1 -W $timeout $ip 2>/dev/null && echo alive")
        return result.contains("alive") || result.contains("1 received")
    }

    fun checkPort(ip: String, port: Int, timeout: Int = 1): Boolean {
        val result = executeCommand(
            "timeout $timeout bash -c \"echo >/dev/tcp/$ip/$port\" 2>/dev/null && echo open"
        )
        return result.contains("open")
    }

    fun tcpProbe(ip: String, port: Int): String {
        return executeCommand(
            "echo 'ping' | timeout 2 bash -c \"cat >/dev/tcp/$ip/$port\" 2>/dev/null && echo done"
        )
    }

    fun addBinderReceivedListener(listener: () -> Unit) {
        binderReceivedCallbacks.add(listener)
        if (isAvailable()) {
            listener.invoke()
        }
    }

    fun addBinderDeadListener(listener: () -> Unit) {
        binderDeadCallbacks.add(listener)
    }

    fun removeBinderReceivedListener(listener: () -> Unit) {
        binderReceivedCallbacks.remove(listener)
    }

    fun removeBinderDeadListener(listener: () -> Unit) {
        binderDeadCallbacks.remove(listener)
    }
}