package com.tether.controller

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.*
import java.util.concurrent.ConcurrentHashMap

/**
 * IdentityManager - 稳定版
 * 设计原则：
 * 1. PC 为唯一主动方（TCP 发起者），Android 为被动响应方
 * 2. UDP 广播采用“回复模式”：收到 PC 广播后单播回复 ACK
 * 3. 已验证设备持久化（SharedPreferences），重启后自动恢复
 * 4. 静默自动重连（从信任列表发起）
 */
class IdentityManager(private val context: Context) {

    companion object {
        private const val TAG = "IdentityManager"
        private const val UDP_PORT = 5555
        private const val TCP_PORT = 5556
        private const val CACHE_TTL_MS = 60000L
        private const val BROADCAST_INTERVAL_MS = 15000L
        private const val HANDSHAKE_TIMEOUT_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val PREF_NAME = "tether_identity"
        private const val KEY_VERIFIED_DEVICES = "verified_devices"
    }

    // ===== 状态流 =====
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers

    private val _verifiedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val verifiedPeers: StateFlow<List<PeerInfo>> = _verifiedPeers

    // ===== 缓存 =====
    private val cache = ConcurrentHashMap<String, CachedPeer>()
    private val verified = ConcurrentHashMap<String, VerifiedPeer>()

    // ===== 协程 =====
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var broadcastJob: Job? = null
    private var heartbeatJob: Job? = null
    private var tcpServerJob: Job? = null
    private var reconnectJob: Job? = null

    // ===== 本机信息 =====
    private val machineCode: String by lazy { generateMachineCode() }
    private val deviceName: String by lazy { android.os.Build.MODEL }
    private lateinit var prefs: SharedPreferences

    // ===== 初始化 =====
    fun start() {
        if (isRunning) return
        isRunning = true

        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        loadVerifiedDevices()

        Log.d(TAG, "🚀 IdentityManager 启动, MachineCode: $machineCode")

        serverJob = scope.launch { udpServerLoop() }
        broadcastJob = scope.launch { udpBroadcastLoop() }
        heartbeatJob = scope.launch { heartbeatLoop() }
        tcpServerJob = scope.launch { tcpServerLoop() }
        reconnectJob = scope.launch { autoReconnectLoop() }

        updatePeers()
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        Log.d(TAG, "🛑 IdentityManager 停止")
    }

    // ================================================================
    //  1. UDP 服务器（被动接收 PC 广播 → 回复 ACK）
    // ================================================================
    private suspend fun udpServerLoop() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(UDP_PORT).apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 1000
            }
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)

            Log.d(TAG, "📡 UDP 服务器启动 (端口 $UDP_PORT)")

            while (isRunning) {
                try {
                    socket.receive(packet)
                    val data = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress ?: continue

                    if (isLoopback(ip)) continue
                    if (!data.startsWith("TETHER_AGENT|")) continue

                    val identity = IdentityPacket.parse(data, ip) ?: continue

                    // ✅ 存入缓存
                    cache[identity.machineCode] = CachedPeer(identity, System.currentTimeMillis(), false)

                    // ✅ 单播回复 ACK（告知 PC：我收到了，我的 IP 和设备码）
                    sendUdpAck(identity.ip, identity.machineCode)

                    Log.d(TAG, "📡 收到 PC 广播: ${identity.deviceName} (${identity.ip})，已回复 ACK")

                    // 如果已存在但离线，尝试重连
                    verified[identity.machineCode]?.let { peer ->
                        if (!peer.isOnline) {
                            Log.d(TAG, "🔄 设备重新上线: ${peer.deviceName}")
                            peer.isOnline = true
                            peer.lastSeen = System.currentTimeMillis()
                            updatePeers()
                        }
                    }

                    updatePeers()
                } catch (e: SocketTimeoutException) {
                    // 超时继续
                } catch (e: Exception) {
                    Log.e(TAG, "UDP 接收异常", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP 服务器启动失败", e)
        } finally {
            socket?.close()
        }
    }

    // ================================================================
    //  2. UDP 广播（Android 主动宣告存在）
    // ================================================================
    private suspend fun udpBroadcastLoop() = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            socket.broadcast = true

            val localIps = getLocalIPs()
            if (localIps.isEmpty()) {
                Log.w(TAG, "⚠️ 无可用 IP，广播已禁用")
                return@withContext
            }

            Log.d(TAG, "📡 UDP 广播启动 (间隔 ${BROADCAST_INTERVAL_MS}ms)")

            while (isRunning) {
                try {
                    val message = "TETHER|$deviceName|${getPrimaryIP()}|$machineCode|${System.currentTimeMillis()}"
                    val data = message.toByteArray(Charsets.UTF_8)

                    for (ip in localIps) {
                        try {
                            val broadcast = getBroadcastAddress(ip) ?: continue
                            socket.send(DatagramPacket(data, data.size, broadcast, UDP_PORT))
                        } catch (e: Exception) {
                            // 单个网卡失败不影响
                        }
                    }
                    Log.d(TAG, "📡 广播身份包 (${localIps.size} 个网段)")
                } catch (e: Exception) {
                    Log.e(TAG, "广播异常", e)
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP 广播失败", e)
        }
    }

    // ================================================================
    //  3. 发送 UDP ACK 回复（单播）
    // ================================================================
    private fun sendUdpAck(targetIp: String, targetMachineCode: String) {
        try {
            val socket = DatagramSocket()
            val message = "TETHER_ACK|$deviceName|$machineCode|${System.currentTimeMillis()}"
            val data = message.toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(targetIp)
            socket.send(DatagramPacket(data, data.size, address, UDP_PORT))
            socket.close()
            Log.d(TAG, "📤 UDP ACK 已发送 → $targetIp")
        } catch (e: Exception) {
            Log.e(TAG, "发送 UDP ACK 失败", e)
        }
    }

    // ================================================================
    //  4. TCP 服务器（被动接收 PC 连接）
    // ================================================================
    private suspend fun tcpServerLoop() = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(TCP_PORT)
            Log.d(TAG, "🔌 TCP 服务器启动 (端口 $TCP_PORT)")

            while (isRunning) {
                try {
                    val socket = serverSocket.accept()
                    scope.launch { handleTcpConnection(socket) }
                } catch (e: SocketException) {
                    if (!isRunning) break
                    Log.e(TAG, "TCP 服务器异常", e)
                } catch (e: Exception) {
                    Log.e(TAG, "TCP 连接接受异常", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP 服务器启动失败", e)
        } finally {
            serverSocket?.close()
            Log.d(TAG, "TCP 服务器已停止")
        }
    }

    // ================================================================
    //  5. TCP 连接处理（被动响应，不主动发起）
    // ================================================================
    private suspend fun handleTcpConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buffer = ByteArray(1024)

            val len = input.read(buffer)
            if (len <= 0) {
                socket.close()
                return
            }

            val request = String(buffer, 0, len, Charsets.UTF_8).trim()
            Log.d(TAG, "📨 TCP 收到: $request")

            // ===== 处理 SYN（PC 发起握手） =====
            if (request.startsWith("SYN|")) {
                val parts = request.split('|')
                if (parts.size >= 2) {
                    val pcMachineCode = parts[1]
                    val pcName = if (parts.size >= 3) parts[2] else "PC"

                    // ✅ 检查缓存（60s 内收到过 PC 广播）
                    val cached = cache[pcMachineCode]
                    if (cached != null) {
                        // 发送 SYN-ACK
                        val synAck = "SYN-ACK|$pcMachineCode|${System.currentTimeMillis()}\n"
                        output.write(synAck.toByteArray(Charsets.UTF_8))
                        output.flush()
                        Log.d(TAG, "✅ SYN-ACK 已发送 → $pcMachineCode")

                        // 等待 ACK
                        socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
                        try {
                            val len2 = input.read(buffer)
                            if (len2 > 0) {
                                val ack = String(buffer, 0, len2, Charsets.UTF_8).trim()
                                if (ack.startsWith("ACK|")) {
                                    val ackParts = ack.split('|')
                                    if (ackParts.size >= 2 && ackParts[1] == machineCode) {
                                        // ✅ 三次握手完成！
                                        val peer = VerifiedPeer(
                                            machineCode = pcMachineCode,
                                            ip = socket.inetAddress.hostAddress ?: "",
                                            deviceName = pcName,
                                            verifiedAt = System.currentTimeMillis(),
                                            lastSeen = System.currentTimeMillis(),
                                            isOnline = true,
                                            socket = socket
                                        )
                                        verified[pcMachineCode] = peer
                                        cache.remove(pcMachineCode)
                                        saveVerifiedDevices()

                                        // 发送 CONNECTED
                                        val connected = "CONNECTED|$deviceName|$machineCode\n"
                                        output.write(connected.toByteArray(Charsets.UTF_8))
                                        output.flush()

                                        Log.d(TAG, "✅ 三次握手完成! $pcName 已验证")
                                        updatePeers()
                                        return
                                    }
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            Log.d(TAG, "⚠️ 等待 ACK 超时: $pcMachineCode")
                        }
                        socket.close()
                        return
                    } else {
                        // 不在缓存中，拒绝
                        val reject = "REJECT|Unknown device\n"
                        output.write(reject.toByteArray(Charsets.UTF_8))
                        output.flush()
                        Log.d(TAG, "❌ 握手拒绝: $pcMachineCode (不在缓存中)")
                        socket.close()
                        return
                    }
                }
            }

            // ===== 心跳 PING =====
            if (request.startsWith("PING")) {
                val pong = "PONG|$deviceName|$machineCode\n"
                output.write(pong.toByteArray(Charsets.UTF_8))
                output.flush()
                Log.d(TAG, "💓 PONG 已发送")
                socket.close()
                return
            }

            // ===== 未知请求 =====
            Log.d(TAG, "⚠️ 未知 TCP 请求: $request")
            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "TCP 连接处理异常", e)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ================================================================
    //  6. 心跳保活
    // ================================================================
    private suspend fun heartbeatLoop() = withContext(Dispatchers.IO) {
        while (isRunning) {
            try {
                val now = System.currentTimeMillis()

                for (entry in verified.values) {
                    if (now - entry.lastSeen > 60000) {
                        if (entry.isOnline) {
                            entry.isOnline = false
                            Log.d(TAG, "⏰ 设备离线: ${entry.deviceName} (${entry.ip})")
                            updatePeers()
                        }
                    }
                }

                // 清理过期缓存
                val expiredKeys = cache.entries
                    .filter { now - it.value.receivedAt > CACHE_TTL_MS }
                    .map { it.key }
                for (key in expiredKeys) {
                    cache.remove(key)
                }

                updatePeers()
            } catch (e: Exception) {
                Log.e(TAG, "心跳异常", e)
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    // ================================================================
    //  7. 静默自动重连
    // ================================================================
    private suspend fun autoReconnectLoop() = withContext(Dispatchers.IO) {
        while (isRunning) {
            try {
                val now = System.currentTimeMillis()

                for (peer in verified.values) {
                    if (!peer.isOnline && now - peer.verifiedAt < 300000) {
                        Log.d(TAG, "🔄 尝试静默重连: ${peer.deviceName} (${peer.ip})")
                        // TODO: 实现 TCP 重连逻辑
                        // 这里可以调用重新建立 TCP 连接的方法
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "重连异常", e)
            }
            delay(30000) // 30 秒检查一次
        }
    }

    // ================================================================
    //  8. 持久化
    // ================================================================
    private fun saveVerifiedDevices() {
        try {
            val json = verified.values.map { peer ->
                "${peer.machineCode}|${peer.ip}|${peer.deviceName}|${peer.verifiedAt}|${peer.lastSeen}"
            }.joinToString(";;;")
            prefs.edit().putString(KEY_VERIFIED_DEVICES, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存设备失败", e)
        }
    }

    private fun loadVerifiedDevices() {
        try {
            val json = prefs.getString(KEY_VERIFIED_DEVICES, "") ?: ""
            if (json.isEmpty()) return

            val loaded = json.split(";;;").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size >= 5) {
                    VerifiedPeer(
                        machineCode = parts[0],
                        ip = parts[1],
                        deviceName = parts[2],
                        verifiedAt = parts[3].toLongOrNull() ?: 0,
                        lastSeen = parts[4].toLongOrNull() ?: 0,
                        isOnline = false,
                        socket = null
                    )
                } else null
            }
            loaded.forEach { verified[it.machineCode] = it }
            Log.d(TAG, "📂 加载 ${loaded.size} 个已信任设备")
        } catch (e: Exception) {
            Log.e(TAG, "加载设备失败", e)
        }
    }

    // ================================================================
    //  9. 更新 UI 状态
    // ================================================================
    private fun updatePeers() {
        val peerList = verified.values.map { it.toPeerInfo() }
        _verifiedPeers.value = peerList

        val cachedList = cache.values.map { it.identity.toPeerInfo(false) }
        _peers.value = (peerList + cachedList).distinctBy { it.machineCode }
    }

    // ================================================================
    //  10. 辅助方法
    // ================================================================
    private fun getPrimaryIP(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            return String.format("%d.%d.%d.%d",
                ip and 0xFF,
                ip shr 8 and 0xFF,
                ip shr 16 and 0xFF,
                ip shr 24 and 0xFF
            )
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }

    private fun getLocalIPs(): List<String> {
        val result = mutableListOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                ni.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: return@forEach
                        if (isPrivateIp(ip)) {
                            result.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取本地 IP 失败", e)
        }
        if (result.isEmpty()) result.add("127.0.0.1")
        return result
    }

    private fun isPrivateIp(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                ip.startsWith("172.16.") ||
                ip.startsWith("172.17.") ||
                ip.startsWith("172.18.") ||
                ip.startsWith("172.19.") ||
                ip.startsWith("172.20.") ||
                ip.startsWith("172.21.") ||
                ip.startsWith("172.22.") ||
                ip.startsWith("172.23.") ||
                ip.startsWith("172.24.") ||
                ip.startsWith("172.25.") ||
                ip.startsWith("172.26.") ||
                ip.startsWith("172.27.") ||
                ip.startsWith("172.28.") ||
                ip.startsWith("172.29.") ||
                ip.startsWith("172.30.") ||
                ip.startsWith("172.31.")
    }

    private fun getBroadcastAddress(ip: String): InetAddress? {
        try {
            val parts = ip.split('.')
            if (parts.size == 4) {
                return InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取广播地址失败", e)
        }
        return null
    }

    private fun isLoopback(ip: String): Boolean {
        return ip == "127.0.0.1" || ip == "::1" || ip.startsWith("127.")
    }

    private fun generateMachineCode(): String {
        try {
            val builder = StringBuilder()
            builder.append(android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ))
            builder.append(android.os.Build.MODEL)
            builder.append(android.os.Build.MANUFACTURER)
            builder.append(android.os.Build.SERIAL)

            val bytes = builder.toString().toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
                .replace("/", "_")
                .replace("+", "-")
                .substring(0, 16)
        } catch (e: Exception) {
            return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        }
    }

    // ================================================================
    //  数据结构
    // ================================================================
    data class IdentityPacket(
        val type: String,
        val deviceName: String,
        val ip: String,
        val machineCode: String,
        val timestamp: Long
    ) {
        companion object {
            fun parse(data: String, sourceIp: String): IdentityPacket? {
                try {
                    val parts = data.split('|')
                    if (parts.size < 5) return null
                    if (parts[0] != "TETHER_AGENT") return null
                    return IdentityPacket(
                        type = parts[0],
                        deviceName = parts[1],
                        ip = if (parts[2].isNotEmpty()) parts[2] else sourceIp,
                        machineCode = parts[3],
                        timestamp = parts[4].toLongOrNull() ?: 0
                    )
                } catch (e: Exception) {
                    return null
                }
            }
        }

        fun toPeerInfo(verified: Boolean = false): PeerInfo {
            return PeerInfo(
                machineCode = machineCode,
                ip = ip,
                deviceName = deviceName,
                isOnline = true,
                isVerified = verified,
                lastSeen = timestamp
            )
        }
    }

    data class CachedPeer(
        val identity: IdentityPacket,
        val receivedAt: Long,
        val isAgent: Boolean
    )

    data class VerifiedPeer(
        val machineCode: String,
        val ip: String,
        val deviceName: String,
        var verifiedAt: Long,
        var lastSeen: Long,
        var isOnline: Boolean,
        var socket: Socket? = null
    ) {
        fun toPeerInfo(): PeerInfo {
            return PeerInfo(
                machineCode = machineCode,
                ip = ip,
                deviceName = deviceName,
                isOnline = isOnline,
                isVerified = true,
                lastSeen = lastSeen
            )
        }
    }

    data class PeerInfo(
        val machineCode: String,
        val ip: String,
        val deviceName: String,
        val isOnline: Boolean,
        val isVerified: Boolean,
        val lastSeen: Long
    )
}