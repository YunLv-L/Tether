package com.tether.controller

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredDevice(
    val id: String,           // 机器码或 IP+设备名
    val ip: String,
    val name: String,
    val machineCode: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > 30000 // 30秒
}

class UdpDiscoveryManager {
    companion object {
        private const val TAG = "UdpDiscovery"
        private const val UDP_PORT = 5555
        private const val CACHE_TIMEOUT = 30000L      // 30秒
        private const val MAX_CACHE_SIZE = 50         // 最大缓存设备数
        private const val CLEANUP_INTERVAL = 10000L   // 10秒清理一次
    }

    private val cache = ConcurrentHashMap<String, DiscoveredDevice>()
    private var isListening = false
    private var listenerJob: Job? = null
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 设备列表更新回调
    var onDevicesUpdated: ((List<DiscoveredDevice>) -> Unit)? = null

    fun startListening() {
        if (isListening) return
        isListening = true

        Log.d(TAG, "UDP 监听启动，端口 $UDP_PORT")

        // 监听 UDP 广播
        listenerJob = scope.launch {
            try {
                val socket = DatagramSocket(UDP_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 5000
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isListening) {
                    try {
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        val ip = packet.address.hostAddress ?: continue

                        if (message.startsWith("TETHER_AGENT|")) {
                            val parts = message.split("|")
                            if (parts.size >= 3) {
                                val deviceName = parts[1]
                                val machineCode = if (parts.size >= 4) parts[3] else ""
                                val id = if (machineCode.isNotEmpty()) machineCode else "$deviceName|$ip"

                                val device = DiscoveredDevice(
                                    id = id,
                                    ip = ip,
                                    name = deviceName,
                                    machineCode = machineCode,
                                    timestamp = System.currentTimeMillis()
                                )

                                addOrUpdateDevice(device)
                                Log.d(TAG, "📡 收到广播: $ip:$deviceName")
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 超时继续
                    } catch (e: Exception) {
                        Log.e(TAG, "UDP 接收异常", e)
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "UDP 监听启动失败", e)
            }
        }

        // 定期清理过期缓存
        cleanupJob = scope.launch {
            while (isListening) {
                delay(CLEANUP_INTERVAL)
                cleanExpiredCache()
            }
        }
    }

    fun stopListening() {
        isListening = false
        listenerJob?.cancel()
        cleanupJob?.cancel()
        scope.cancel()
        Log.d(TAG, "UDP 监听已停止")
    }

    private fun addOrUpdateDevice(device: DiscoveredDevice) {
        synchronized(cache) {
            // 1. 查找同 ID 或同 IP 的设备（同机器码或同 IP）
            val existingKey = cache.keys.find { key ->
                val existing = cache[key]
                existing != null && (existing.id == device.id || existing.ip == device.ip)
            }

            if (existingKey != null) {
                // 更新现有设备
                val existing = cache[existingKey]!!
                if (device.timestamp > existing.timestamp) {
                    cache[existingKey] = device
                    Log.d(TAG, "🔄 更新设备: ${device.ip} (${device.name})")
                }
            } else {
                // 2. 如果缓存已满，移除最旧的设备
                if (cache.size >= MAX_CACHE_SIZE) {
                    val oldestKey = cache.minByOrNull { it.value.timestamp }?.key
                    if (oldestKey != null) {
                        cache.remove(oldestKey)
                        Log.d(TAG, "🗑️ 缓存已满，移除最旧设备: $oldestKey")
                    }
                }
                // 添加新设备
                cache[device.id] = device
                Log.d(TAG, "✅ 添加新设备: ${device.ip} (${device.name})")
            }

            // 通知 UI 更新
            val aliveDevices = cache.values.filter { !it.isExpired() }
            onDevicesUpdated?.invoke(aliveDevices)
        }
    }

    private fun cleanExpiredCache() {
        synchronized(cache) {
            val expiredKeys = cache.filter { it.value.isExpired() }.keys
            if (expiredKeys.isNotEmpty()) {
                expiredKeys.forEach { cache.remove(it) }
                Log.d(TAG, "🧹 清理过期设备: ${expiredKeys.size} 台")
                val aliveDevices = cache.values.filter { !it.isExpired() }
                onDevicesUpdated?.invoke(aliveDevices)
            }
        }
    }

    fun getAliveDevices(): List<DiscoveredDevice> {
        synchronized(cache) {
            return cache.values.filter { !it.isExpired() }
        }
    }

    fun clearCache() {
        synchronized(cache) {
            cache.clear()
            onDevicesUpdated?.invoke(emptyList())
            Log.d(TAG, "🗑️ 缓存已清空")
        }
    }

    fun isDeviceAlive(id: String): Boolean {
        synchronized(cache) {
            val device = cache[id]
            return device != null && !device.isExpired()
        }
    }
}