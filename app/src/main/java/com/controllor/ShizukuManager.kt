package com.tether.controller

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val PERMISSION_REQUEST_CODE = 1000

    private var isInitialized = false
    private val isDestroyed = AtomicBoolean(false)

    // 监听器实例
    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
    private var permissionResultListener: Shizuku.OnPermissionResultListener? = null

    private val binderReceivedCallbacks = mutableListOf<() -> Unit>()
    private val binderDeadCallbacks = mutableListOf<() -> Unit>()

    // ==================== 初始化 ====================
    @Synchronized
    fun init(context: Context) {
        if (isInitialized && !isDestroyed.get()) {
            Log.d(TAG, "Shizuku 已初始化，跳过")
            return
        }

        if (isDestroyed.get()) {
            Log.d(TAG, "Shizuku 已销毁，重新初始化")
            isDestroyed.set(false)
        }

        try {
            ShizukuProvider.enableMultiProcessSupport()

            binderReceivedListener = Shizuku.OnBinderReceivedListener {
                Log.d(TAG, "✅ Shizuku Binder 已连接")
                binderReceivedCallbacks.forEach { it.invoke() }
            }
            binderDeadListener = Shizuku.OnBinderDeadListener {
                Log.d(TAG, "❌ Shizuku Binder 已断开")
                binderDeadCallbacks.forEach { it.invoke() }
            }

            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)

            permissionResultListener = Shizuku.OnPermissionResultListener { requestCode, grantResult ->
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                    Log.d(TAG, if (granted) "✅ Shizuku 权限已授予" else "❌ Shizuku 权限被拒绝")
                }
            }
            Shizuku.addPermissionResultListener(permissionResultListener)

            isInitialized = true
            Log.d(TAG, "✅ Shizuku 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Shizuku 初始化失败", e)
            destroyInternal()
            isInitialized = false
        }
    }

    @Synchronized
    fun destroy() {
        if (isDestroyed.getAndSet(true)) return
        destroyInternal()
        isInitialized = false
        Log.d(TAG, "Shizuku 已销毁")
    }

    private fun destroyInternal() {
        runCatching { binderReceivedListener?.let { Shizuku.removeBinderReceivedListener(it) } }
            .onFailure { Log.w(TAG, "移除 BinderReceivedListener 失败", it) }
        runCatching { binderDeadListener?.let { Shizuku.removeBinderDeadListener(it) } }
            .onFailure { Log.w(TAG, "移除 BinderDeadListener 失败", it) }
        runCatching { permissionResultListener?.let { Shizuku.removePermissionResultListener(it) } }
            .onFailure { Log.w(TAG, "移除 PermissionResultListener 失败", it) }

        binderReceivedCallbacks.clear()
        binderDeadCallbacks.clear()
        binderReceivedListener = null
        binderDeadListener = null
        permissionResultListener = null
    }

    // ==================== 状态检查 ====================
    fun isAvailable(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrElse { false }
    }

    fun isGranted(): Boolean {
        return runCatching {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrElse { false }
    }

    fun canUseHighPrivilege(): Boolean {
        return isAvailable() && isGranted() && !Shizuku.isPreV11() && !isDestroyed.get()
    }

    fun getShizukuUid(): Int = runCatching { Shizuku.getUid() }.getOrElse { -1 }
    fun isRootMode(): Boolean = getShizukuUid() == 0
    fun isAdbMode(): Boolean = getShizukuUid() == 2000

    // ==================== 权限请求 ====================
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
            return
        }

        // ✅ 使用匿名对象，this 正确指向自身
        val listener = object : Shizuku.OnPermissionResultListener {
            override fun onPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                    onResult?.invoke(granted)
                    runCatching { Shizuku.removePermissionResultListener(this) }
                        .onFailure { Log.w(TAG, "移除权限监听器失败", it) }
                }
            }
        }

        try {
            Shizuku.addPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 请求权限异常", e)
            runCatching { Shizuku.removePermissionResultListener(listener) }
                .onFailure { Log.w(TAG, "异常后移除权限监听器失败", it) }
            onResult?.invoke(false)
        }
    }

    // ==================== 执行命令 ====================
    fun executeCommand(command: String, timeoutMs: Long = 5000): String {
        if (!canUseHighPrivilege()) {
            Log.w(TAG, "⚠️ Shizuku 不可用")
            return ""
        }

        val resultHolder = mutableListOf<String>()
        val errorHolder = mutableListOf<String>()
        val latch = CountDownLatch(1)
        var shouldReturnEmpty = false

        try {
            val binder = Shizuku.getBinder()
            if (binder == null) {
                Log.w(TAG, "Binder 为空")
                shouldReturnEmpty = true
                // ✅ 不直接 return，让 finally 执行
            } else {
                val wrapper = ShizukuBinderWrapper(binder)

                try {
                    val shellService = SystemServiceHelper.getSystemService(wrapper, "shell")
                    if (shellService == null) {
                        Log.w(TAG, "Shell 服务为空")
                        shouldReturnEmpty = true
                    } else {
                        val result = invokeShellCommand(shellService, command)
                        if (result != null) {
                            resultHolder.add(result.first)
                            errorHolder.add(result.second)
                        } else {
                            shouldReturnEmpty = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku 执行失败", e)
                    shouldReturnEmpty = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行命令异常", e)
            shouldReturnEmpty = true
        } finally {
            // ✅ 确保 latch 一定被释放
            latch.countDown()
        }

        // ✅ 在 finally 之后处理返回
        if (shouldReturnEmpty && resultHolder.isEmpty()) {
            Log.w(TAG, "命令执行失败: $command")
            return ""
        }

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "命令执行超时: $command")
                return resultHolder.firstOrNull() ?: ""
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return ""
        }

        if (errorHolder.isNotEmpty()) {
            Log.e(TAG, "命令错误: ${errorHolder.first()}")
        }

        return resultHolder.firstOrNull() ?: ""
    }

    // ==================== invokeShellCommand ====================
    private fun invokeShellCommand(shellService: Any, command: String): Pair<String, String>? {
        val methodAttempts = listOf(
            MethodAttempt("execCommand", arrayOf(String::class.java, Array<String>::class.java, String::class.java)) { result ->
                when (result) {
                    is Array<*> -> {
                        val stdout = result.getOrNull(0) as? String ?: ""
                        val stderr = result.getOrNull(1) as? String ?: ""
                        Pair(stdout, stderr)
                    }
                    else -> null
                }
            },
            MethodAttempt("execCommand", arrayOf(String::class.java, Array<String>::class.java)) { result ->
                when (result) {
                    is Array<*> -> {
                        val stdout = result.getOrNull(0) as? String ?: ""
                        val stderr = result.getOrNull(1) as? String ?: ""
                        Pair(stdout, stderr)
                    }
                    else -> null
                }
            },
            MethodAttempt("exec", arrayOf(String::class.java)) { result ->
                when (result) {
                    is String -> Pair(result, "")
                    else -> null
                }
            },
            MethodAttempt("execCommand", arrayOf(String::class.java)) { result ->
                when (result) {
                    is String -> Pair(result, "")
                    else -> null
                }
            }
        )

        for (attempt in methodAttempts) {
            try {
                val method = shellService.javaClass.getDeclaredMethod(attempt.methodName, *attempt.paramTypes)
                method.isAccessible = true

                val args = when (attempt.paramTypes.size) {
                    3 -> arrayOf<Any>(command, arrayOf<String>(), null)
                    2 -> arrayOf<Any>(command, arrayOf<String>())
                    1 -> arrayOf<Any>(command)
                    else -> arrayOf<Any>(command)
                }

                val result = method.invoke(shellService, *args)
                val extracted = attempt.resultExtractor(result)
                if (extracted != null) {
                    return extracted
                }
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "方法不存在: ${attempt.methodName}")
            } catch (e: IllegalAccessException) {
                Log.d(TAG, "方法访问受限: ${attempt.methodName}")
            } catch (e: Exception) {
                Log.d(TAG, "方法调用失败: ${attempt.methodName}, ${e.message}")
            }
        }

        return null
    }

    // ==================== MethodAttempt ====================
    private data class MethodAttempt(
        val methodName: String,
        val paramTypes: Array<Class<*>>,
        val resultExtractor: (Any?) -> Pair<String, String>?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as MethodAttempt

            if (methodName != other.methodName) return false
            if (!paramTypes.contentEquals(other.paramTypes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = methodName.hashCode()
            result = 31 * result + paramTypes.contentHashCode()
            return result
        }
    }

    // ==================== 快捷方法 ====================
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

    // ==================== 回调管理 ====================
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