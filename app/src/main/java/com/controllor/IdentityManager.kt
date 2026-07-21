package com.tether.controller

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class IdentityManager(private val context: Context) {
    companion object {
        private const val TAG = "IdentityManager"
        private const val UDP_PORT = 5555
        private const val TCP_PORT = 5556
        private const val CACHE_TTL_MS = 60000L      // 60s 滞留
        private const val BROADCAST_INTERVAL_MS = 15000L // 15s 发一次
        private const val HANDSHAKE_TIMEOUT_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
    }

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers

    private val _verifiedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val verifiedPeers: StateFlow<List<PeerInfo>> = _verifiedPeers

    // 60s 滞留缓存
    private val cache = ConcurrentHashMap<String, CachedPeer>()

    // 已验证设备
    private val verified = ConcurrentHashMap<String, VerifiedPeer>()

    private var isRunning = false
    private var serverJob: Job? = null
    private var broadcastJob: Job? = null
    private var heartbeatJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val machineCode: String by lazy { generateMachineCode() }
    private val deviceName: String by lazy { android.os.Build.MODEL }

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "🚀 IdentityManager 启动, MachineCode: $machineCode")

        serverJob = scope.launch { udpServerLoop() }
        broadcastJob = scope.launch { udpBroadcastLoop() }
        heartbeatJob = scope.launch { heartbeatLoop() }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        broadcastJob?.cancel()
        heartbeatJob?.cancel()
        scope.cancel()
        Log.d(TAG, "🛑 IdentityManager 停止")
    }

    // ================================================================
    //  UDP 服务器 (被动接收 Agent 身份包)
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

                    if (IPAddress.isLoopback(ip)) continue
                    if (!data.startsWith("TETHER_AGENT|")) continue

                    val identity = IdentityPacket.parse(data, ip) ?: continue

                    // 存入 60s 缓存 (自动去重)
                    val cached = CachedPeer(identity, System.currentTimeMillis(), false)
                    cache[identity.machineCode] = cached

                    // 检查是否已验证
                    if (verified.containsKey(identity.machineCode)) {
                        verified[identity.machineCode]?.let {
                            it.lastSeen = System.currentTimeMillis()
                            it.isOnline = true
                        }
                        Log.d(TAG, "🔄 设备在线: ${identity.deviceName} (${identity.ip})")
                    } else {
                        Log.d(TAG, "📡 发现新设备: ${identity.deviceName} (${identity.ip})")
                        // 自动发起三次握手
                        scope.launch { initiateHandshake(identity) }
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
    //  UDP 广播 (Tether 主动发身份包, 15s 一次)
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
    //  发起三次握手
    // ================================================================
    private suspend fun initiateHandshake(identity: IdentityPacket) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🤝 发起三次握手: ${identity.deviceName} (${identity.ip})")

                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.tcpNoDelay = true
                    socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()

                    socket.connect(InetSocketAddress(identity.ip, TCP_PORT), HANDSHAKE_TIMEOUT_MS.toInt())

                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()

                    // 发送 SYN
                    val syn = "SYN|$machineCode|$deviceName\n"
                    output.write(syn.toByteArray(Charsets.UTF_8))
                    output.flush()

                    // 等待 SYN-ACK
                    val buffer = ByteArray(1024)
                    val len = input.read(buffer)
                    if (len <= 0) {
                        Log.d(TAG, "❌ 无 SYN-ACK 响应")
                        return@withContext
                    }

                    val response = String(buffer, 0, len, Charsets.UTF_8).trim()
                    if (response.startsWith("SYN-ACK|")) {
                        val parts = response.split('|')
                        if (parts.size >= 2 && parts[1] == identity.machineCode) {
                            // 发送 ACK
                            val ack = "ACK|$machineCode\n"
                            output.write(ack.toByteArray(Charsets.UTF_8))
                            output.flush()

                            // 等待 CONNECTED
                            val len2 = input.read(buffer)
                            if (len2 > 0) {
                                val connected = String(buffer, 0, len2, Charsets.UTF_8).trim()
                                if (connected.startsWith("CONNECTED|")) {
                                    val connectedParts = connected.split('|')
                                    val agentName = if (connectedParts.size >= 2) connectedParts[1] else "PC"

                                    // 握手完成！
                                    val peer = VerifiedPeer(
                                        machineCode = identity.machineCode,
                                        ip = identity.ip,
                                        deviceName = agentName,
                                        verifiedAt = System.currentTimeMillis(),
                                        lastSeen = System.currentTimeMillis(),
                                        isOnline = true,
                                        socket = socket
                                    )
                                    verified[identity.machineCode] = peer

                                    // 从缓存中移除
                                    cache.remove(identity.machineCode)

                                    Log.d(TAG, "✅ 三次握手完成! $agentName (${identity.ip}) 已验证")
                                    updatePeers()
                                    return@withContext
                                }
                            }
                        }
                    }

                    // 握手失败
                    Log.d(TAG, "❌ 三次握手失败: ${identity.deviceName}")
                } catch (e: Exception) {
                    Log.d(TAG, "❌ 握手异常: ${e.message}")
                } finally {
                    socket?.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "握手失败", e)
            }
        }
    }

    // ================================================================
    //  心跳保活 (每 10s 检查)
    // ================================================================
    private suspend fun heartbeatLoop() = withContext(Dispatchers.IO) {
        while (isRunning) {
            try {
                val now = System.currentTimeMillis()

                // 检查已验证设备是否超时 (60s 无心跳)
                for (entry in verified.values) {
                    if (now - entry.lastSeen > 60000) {
                        if (entry.isOnline) {
                            entry.isOnline = false
                            Log.d(TAG, "⏰ 设备离线: ${entry.deviceName} (${entry.ip})")
                            updatePeers()
                        }
                    }
                }

                // 清理缓存过期包 (60s TTL)
                val expiredKeys = cache.entries
                    .filter { now - it.value.receivedAt > CACHE_TTL_MS }
                    .map { it.key }
                for (key in expiredKeys) {
                    cache.remove(key)
                }

                // 主动发送心跳到已验证设备
                for (entry in verified.values) {
                    if (entry.isOnline) {
                        try {
                            sendHeartbeat(entry)
                        } catch (e: Exception) {
                            // 心跳失败不影响整体
                        }
                    }
                }

                updatePeers()
            } catch (e: Exception) {
                Log.e(TAG, "心跳异常", e)
            }

            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private suspend fun sendHeartbeat(peer: VerifiedPeer) {
        withContext(Dispatchers.IO) {
            try {
                val socket = peer.socket ?: return@withContext
                if (socket.isClosed || !socket.isConnected) {
                    peer.isOnline = false
                    return@withContext
                }

                val output = socket.getOutputStream()
                output.write("PING\n".toByteArray(Charsets.UTF_8))
                output.flush()

                // 等待 PONG
                val buffer = ByteArray(1024)
                val input = socket.getInputStream()
                val len = input.read(buffer)
                if (len > 0) {
                    val response = String(buffer, 0, len, Charsets.UTF_8).trim()
                    if (response.startsWith("PONG|")) {
                        peer.lastSeen = System.currentTimeMillis()
                        if (!peer.isOnline) {
                            peer.isOnline = true
                            Log.d(TAG, "🔄 设备重新上线: ${peer.deviceName}")
                            updatePeers()
                        }
                    }
                }
            } catch (e: Exception) {
                peer.isOnline = false
                Log.d(TAG, "⚠️ 心跳失败: ${peer.deviceName}")
            }
        }
    }

    // ================================================================
    //  更新 UI 状态
    // ================================================================
    private fun updatePeers() {
        val peerList = verified.values.map { it.toPeerInfo() }
        _verifiedPeers.value = peerList

        // 同时显示缓存中的设备 (未验证但 60s 内出现过)
        val cachedList = cache.values.map { it.identity.toPeerInfo(false) }
        _peers.value = (peerList + cachedList).distinctBy { it.machineCode }
    }

    // ================================================================
    //  获取验证过的设备 (供其他模块使用)
    // ================================================================
    fun getVerifiedPeers(): List<PeerInfo> = _verifiedPeers.value
    fun getVerifiedPeer(machineCode: String): PeerInfo? = verified[machineCode]?.toPeerInfo()

    // ================================================================
    //  画面连接验证 (ScreenActivity 使用)
    // ================================================================
    fun validateScreenConnection(machineCode: String): Boolean {
        return verified.containsKey(machineCode)
    }

    // ================================================================
    //  辅助方法
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
                        // 私有 IP 段
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            ip.startsWith("172.16.") || ip.startsWith("172.17.") ||
                            ip.startsWith("172.18.") || ip.startsWith("172.19.") ||
                            ip.startsWith("172.20.") || ip.startsWith("172.21.") ||
                            ip.startsWith("172.22.") || ip.startsWith("172.23.") ||
                            ip.startsWith("172.24.") || ip.startsWith("172.25.") ||
                            ip.startsWith("172.26.") || ip.startsWith("172.27.") ||
                            ip.startsWith("172.28.") || ip.startsWith("172.29.") ||
                            ip.startsWith("172.30.") || ip.startsWith("172.31.")
                        ) {
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

    private fun generateMachineCode(): String {
        try {
            val builder = StringBuilder()
            // 使用 Android ID + 硬件信息
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

// IPAddress 扩展
object IPAddress {
    fun isLoopback(ip: String): Boolean {
        return ip == "127.0.0.1" || ip == "::1" || ip.startsWith("127.")
    }
}