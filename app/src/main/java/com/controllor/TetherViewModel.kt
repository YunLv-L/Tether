package com.tether.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

data class DeviceInfo(
    val id: String,
    val ip: String,
    val name: String = "未知设备",
    val isManual: Boolean = false,
    val note: String = "",
    var isOnline: Boolean = true,
    val machineCode: String = ""
) {
    fun getDisplayName(): String = if (note.isNotEmpty()) note else name
    override fun toString(): String = if (note.isNotEmpty()) "$ip:$note" else "$ip:$name"
}

class TetherViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices

    private val _selectedDevice = MutableStateFlow<DeviceInfo?>(null)
    val selectedDevice: StateFlow<DeviceInfo?> = _selectedDevice

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _scanProgress = MutableStateFlow("0/254")
    val scanProgress: StateFlow<String> = _scanProgress

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode

    private val _debugInfo = MutableStateFlow("")
    val debugInfo: StateFlow<String> = _debugInfo

    private val _quality = MutableStateFlow(1)
    val quality: StateFlow<Int> = _quality

    private val tcpPort = 5556
    private val qualityPort = 5558
    private val udpPort = 5555
    private val cacheTimeout = 30000L

    private var scanJob: Job? = null
    private var udpListenerJob: Job? = null
    private lateinit var prefs: android.content.SharedPreferences
    private var nsdManager: NsdManager? = null
    private var context: Context? = null
    private var mdnsListener: NsdManager.DiscoveryListener? = null
    private val udpCache = mutableMapOf<String, DeviceInfo>()
    @Volatile private var isUdpListening = false
    private var identityManager: IdentityManager? = null

    fun init(context: Context) {
        this.context = context
        prefs = context.getSharedPreferences("tether_devices", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        loadDevices()
        _quality.value = prefs.getInt("quality", 1)
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    fun setIdentityManager(manager: IdentityManager) {
        this.identityManager = manager
        viewModelScope.launch {
            manager.verifiedPeers.collect { peers ->
                val deviceList = peers.map { peer ->
                    DeviceInfo(
                        id = peer.machineCode,
                        ip = peer.ip,
                        name = peer.deviceName,
                        isManual = false,
                        note = if (peer.isVerified) "✅ 已验证" else "",
                        isOnline = peer.isOnline,
                        machineCode = peer.machineCode
                    )
                }
                val manualDevices = _devices.value.filter { it.isManual }
                val allDevices = (deviceList + manualDevices).distinctBy { it.id }
                _devices.value = allDevices
                saveDevices()
            }
        }
        viewModelScope.launch {
            manager.peers.collect { cachedPeers ->
                val verifiedIds = _devices.value.map { it.id }.toSet()
                val cachedDevices = cachedPeers
                    .filter { it.machineCode !in verifiedIds }
                    .map { peer ->
                        DeviceInfo(
                            id = peer.machineCode,
                            ip = peer.ip,
                            name = peer.deviceName,
                            isManual = false,
                            note = "⏳ 发现中...",
                            isOnline = false,
                            machineCode = peer.machineCode
                        )
                    }
                val currentDevices = _devices.value.filter { it.id in verifiedIds || it.isManual }
                _devices.value = currentDevices + cachedDevices
            }
        }
    }

    private fun saveDevices() {
        try {
            val json = _devices.value.map { device ->
                "${device.id}|${device.ip}|${device.name}|${device.isManual}|${device.note}|${device.isOnline}|${device.machineCode}"
            }.joinToString(";;;")
            prefs.edit().putString("devices", json).apply()
        } catch (e: Exception) {
            Log.e("Tether", "保存设备失败", e)
        }
    }

    private fun loadDevices() {
        try {
            val json = prefs.getString("devices", "") ?: ""
            if (json.isEmpty()) return
            val loaded = json.split(";;;").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size >= 7) {
                    DeviceInfo(
                        id = parts[0],
                        ip = parts[1],
                        name = parts[2],
                        isManual = parts[3].toBoolean(),
                        note = parts[4],
                        isOnline = parts[5].toBoolean(),
                        machineCode = parts[6]
                    )
                } else null
            }
            _devices.value = loaded
        } catch (e: Exception) {
            Log.e("Tether", "加载设备失败", e)
            _devices.value = emptyList()
        }
    }

    fun toggleDebugMode() {
        _isDebugMode.value = !_isDebugMode.value
        _debugInfo.value = if (_isDebugMode.value) buildDebugInfo() else ""
        _statusMessage.value = if (_isDebugMode.value) "🔧 Debug 模式已开启" else "Debug 模式已关闭"
    }

    private fun buildDebugInfo(): String {
        val info = StringBuilder()
        info.appendLine("📡 调试信息")
        info.appendLine("━━━━━━━━━━━━━━━━━")
        info.appendLine("扫描状态: ${if (_isScanning.value) "运行中" else "空闲"}")
        info.appendLine("扫描进度: ${_scanProgress.value}")
        val onlineCount = _devices.value.count { it.isOnline }
        info.appendLine("设备数量: ${_devices.value.size} (在线: $onlineCount)")
        info.appendLine("UDP 缓存: ${udpCache.size}")
        info.appendLine("选中设备: ${_selectedDevice.value?.ip ?: "无"}")
        info.appendLine("网段: ${getLocalIpBase()}.x")
        info.appendLine("TCP 端口: $tcpPort")
        info.appendLine("UDP 端口: $udpPort")
        info.appendLine("画质: ${when(_quality.value) { 0 -> "流畅"; 1 -> "标准"; else -> "高清" }}")
        info.appendLine("━━━━━━━━━━━━━━━━━")
        _devices.value.forEachIndexed { index, device ->
            val status = if (device.isOnline) "🟢" else "🔴"
            info.appendLine("[$index] $status ${device.ip} | ${device.name} | MC: ${device.machineCode.take(8)}...")
        }
        info.appendLine("━━━━━━━━━━━━━━━━━")
        info.appendLine(CommandExecutor.getStatus())
        return info.toString()
    }

    fun setQuality(level: Int) {
        _quality.value = level
        prefs.edit().putInt("quality", level).apply()
        sendQualityToAgent(level)
    }

    private fun sendQualityToAgent(level: Int) {
        val device = _selectedDevice.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Socket(device.ip, qualityPort).use { socket ->
                        socket.soTimeout = 3000
                        socket.tcpNoDelay = true
                        socket.getOutputStream().write("RES:$level\n".toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    Log.e("Tether", "画质切换失败", e)
                }
            }
        }
    }

    fun startHybridDiscovery() {
        if (_isScanning.value) { stopScan(); return }
        _devices.value = emptyList()
        _selectedDevice.value = null
        _isScanning.value = true
        _statusMessage.value = "正在监听设备广播..."
        _scanProgress.value = "0/254"
        startUdpListener()
        scanJob = viewModelScope.launch {
            try { performMdnsDiscovery() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                val onlineCount = _devices.value.count { it.isOnline }
                _isScanning.value = false
                _scanProgress.value = "254/254"
                _statusMessage.value = if (onlineCount > 0) "发现 ${onlineCount} 台设备" else "未发现设备"
            }
        }
    }

    fun deepScan() {
        if (_isScanning.value) { _statusMessage.value = "扫描进行中..."; return }
        _isScanning.value = true
        _statusMessage.value = "深度扫描中..."
        _scanProgress.value = "0/254"
        scanJob = viewModelScope.launch {
            performTcpDiscovery()
            withContext(Dispatchers.Main) {
                val onlineCount = _devices.value.count { it.isOnline }
                _isScanning.value = false
                _scanProgress.value = "254/254"
                _statusMessage.value = if (onlineCount > 0) "发现 ${onlineCount} 台设备" else "深度扫描未发现设备"
            }
        }
    }

    fun shizukuScan() {
        if (!ShizukuManager.canUseHighPrivilege() && !DhizukuManager.isPermissionGranted()) {
            _statusMessage.value = "Shizuku 和 Dhizuku 均不可用"
            return
        }
        if (_isScanning.value) { stopScan(); return }
        _devices.value = emptyList()
        _selectedDevice.value = null
        _isScanning.value = true
        _statusMessage.value = "高权限扫描中..."
        _scanProgress.value = "0/254"
        scanJob = viewModelScope.launch {
            val baseIp = getLocalIpBase()
            for (i in 1..254) {
                if (!_isScanning.value) break
                val ip = "$baseIp.$i"
                val ping = CommandExecutor.executeCommand("ping -c 1 -W 1 $ip 2>/dev/null && echo alive")
                if (ping.contains("alive") || ping.contains("1 received")) {
                    val port = CommandExecutor.executeCommand("timeout 1 bash -c \"echo >/dev/tcp/$ip/$tcpPort\" 2>/dev/null && echo open")
                    if (port.contains("open")) {
                        val info = CommandExecutor.executeCommand("echo 'ping' | nc -w 1 $ip $tcpPort 2>/dev/null")
                        val parts = info.split("|")
                        val name = if (parts.size >= 2 && parts[1].isNotEmpty()) parts[1] else "PC"
                        val mc = if (parts.size >= 4) parts[3] else ""
                        val id = if (mc.isNotEmpty()) mc else "$name|$ip"
                        val device = DeviceInfo(id = id, ip = ip, name = name, isOnline = true, machineCode = mc)
                        val current = _devices.value.toMutableList()
                        if (current.none { it.id == device.id }) {
                            current.add(device)
                            _devices.value = current
                            _statusMessage.value = "发现: $ip ($name)"
                        }
                    }
                }
                _scanProgress.value = "$i/254"
            }
            _isScanning.value = false
            _scanProgress.value = "254/254"
            _statusMessage.value = if (_devices.value.isNotEmpty()) "扫描完成" else "未发现设备"
        }
    }

    private suspend fun performMdnsDiscovery() {
        val manager = nsdManager ?: return
        var listener: NsdManager.DiscoveryListener? = null
        try {
            listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    val ip = info.host?.hostAddress ?: return
                    val name = info.serviceName
                    val mc = info.attributes?.get("machineCode")?.let { String(it) } ?: ""
                    val id = if (mc.isNotEmpty()) mc else "$name|$ip"
                    val device = DeviceInfo(id = id, ip = ip, name = name, isOnline = true, machineCode = mc)
                    synchronized(udpCache) {
                        if (udpCache.size >= 50) udpCache.remove(udpCache.minByOrNull { it.value.hashCode() }?.key)
                        udpCache[id] = device
                        updateDevicesFromCache()
                    }
                    _statusMessage.value = "发现: $ip:$name (mDNS)"
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(service: String) {}
                override fun onStartDiscoveryFailed(service: String, error: Int) {}
                override fun onStopDiscoveryFailed(service: String, error: Int) {}
            }
            mdnsListener = listener
            manager.discoverServices("_tether._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            delay(6000)
        } finally {
            try { listener?.let { manager.stopServiceDiscovery(it) } } catch (_: Exception) {}
            mdnsListener = null
        }
    }

    private suspend fun performTcpDiscovery() {
        val baseIp = getLocalIpBase()
        for (i in 1..254) {
            if (!_isScanning.value) break
            val ip = "$baseIp.$i"
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo 'ping' | nc -w 1 $ip $tcpPort 2>/dev/null"))
                val output = process.inputStream.bufferedReader().readText().trim()
                process.destroy()
                if (output.isNotEmpty() && output.startsWith("pong")) {
                    val parts = output.split("|")
                    val name = if (parts.size >= 2 && parts[1].isNotEmpty()) parts[1] else "PC"
                    val mc = if (parts.size >= 4) parts[3] else ""
                    val id = if (mc.isNotEmpty()) mc else "$name|$ip"
                    val device = DeviceInfo(id = id, ip = ip, name = name, isOnline = true, machineCode = mc)
                    synchronized(udpCache) {
                        if (udpCache.size >= 50) udpCache.remove(udpCache.minByOrNull { it.value.hashCode() }?.key)
                        udpCache[id] = device
                        updateDevicesFromCache()
                    }
                    _statusMessage.value = "发现: $ip ($name)"
                }
            } catch (_: Exception) {}
            _scanProgress.value = "$i/254"
        }
    }

    private fun updateDevicesFromCache() {
        val now = System.currentTimeMillis()
        val alive = udpCache.filter { now - it.value.hashCode() < cacheTimeout }
        val map = _devices.value.associateBy { it.id }.toMutableMap()
        alive.values.forEach { map[it.id] = it.copy(isOnline = true) }
        _devices.value = map.values.toList()
        saveDevices()
    }

    private fun startUdpListener() {
        if (isUdpListening) return
        udpListenerJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket(udpPort).apply { broadcast = true; reuseAddress = true; soTimeout = 5000 }
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    isUdpListening = true
                    while (isUdpListening) {
                        try {
                            socket.receive(packet)
                            val msg = String(packet.data, 0, packet.length)
                            val ip = packet.address.hostAddress ?: continue
                            if (msg.isNotBlank() && msg.startsWith("TETHER_AGENT|")) {
                                val parts = msg.split("|")
                                if (parts.size >= 3) {
                                    val name = parts[1]
                                    val mc = if (parts.size >= 4) parts[3] else ""
                                    val id = if (mc.isNotEmpty()) mc else "$name|$ip"
                                    val device = DeviceInfo(id = id, ip = ip, name = name, isOnline = true, machineCode = mc)
                                    synchronized(udpCache) {
                                        if (udpCache.size >= 50) udpCache.remove(udpCache.minByOrNull { it.value.hashCode() }?.key)
                                        udpCache[id] = device
                                        updateDevicesFromCache()
                                    }
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            synchronized(udpCache) {
                                val now = System.currentTimeMillis()
                                udpCache.filter { now - it.value.hashCode() > cacheTimeout }.keys.forEach { udpCache.remove(it) }
                                updateDevicesFromCache()
                            }
                        }
                    }
                } finally { socket?.close(); isUdpListening = false }
            }
        }
    }

    private fun stopUdpListener() { isUdpListening = false; udpListenerJob?.cancel(); udpListenerJob = null }

    fun stopScan() {
        _isScanning.value = false
        scanJob?.cancel()
        scanJob = null
        stopUdpListener()
        try { mdnsListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        mdnsListener = null
        _statusMessage.value = "扫描已停止"
    }

    fun deleteDevice(device: DeviceInfo) {
        _devices.value = _devices.value.filter { it.id != device.id }
        saveDevices()
        if (_selectedDevice.value?.id == device.id) _selectedDevice.value = _devices.value.firstOrNull()
        synchronized(udpCache) { udpCache.remove(device.id) }
        _statusMessage.value = "已删除: ${device.ip}"
    }

    fun editDeviceIp(old: DeviceInfo, newIp: String) {
        if (old.ip == newIp) return
        if (_devices.value.any { it.ip == newIp && it.id != old.id }) { _statusMessage.value = "IP 已存在"; return }
        val new = old.copy(ip = newIp)
        _devices.value = _devices.value.map { if (it.id == old.id) new else it }
        saveDevices()
        if (_selectedDevice.value?.id == old.id) _selectedDevice.value = new
        _statusMessage.value = "已更新 IP: ${old.ip} → $newIp"
    }

    fun updateDeviceNote(device: DeviceInfo, note: String) {
        val new = device.copy(note = note)
        _devices.value = _devices.value.map { if (it.id == device.id) new else it }
        saveDevices()
        if (_selectedDevice.value?.id == device.id) _selectedDevice.value = new
        _statusMessage.value = "已更新备注: $note"
    }

    fun selectDevice(device: DeviceInfo?) {
        _selectedDevice.value = device
        device?.let { _statusMessage.value = "已选择: ${it.ip}" }
    }

    private fun getLocalIpBase(): String {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                ni.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: return@forEach
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
                            return ip.substringBeforeLast(".")
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.1"
    }

    fun addManualDevice(ip: String) {
        val id = "manual|$ip"
        if (_devices.value.any { it.id == id }) { _statusMessage.value = "设备已存在"; return }
        val device = DeviceInfo(id = id, ip = ip, name = "手动连接", isManual = true, isOnline = true)
        _devices.value = _devices.value + device
        saveDevices()
        _selectedDevice.value = device
        _statusMessage.value = "已添加设备: $ip"
    }

    fun sendCommand(command: String) {
        val device = _selectedDevice.value
        if (device == null) { _statusMessage.value = "请先选择设备"; return }
        if (!device.isOnline) { _statusMessage.value = "设备离线"; return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Socket(device.ip, tcpPort).use { socket ->
                        socket.soTimeout = 3000
                        socket.tcpNoDelay = true
                        socket.getOutputStream().write("$command\n".toByteArray())
                        socket.getOutputStream().flush()
                        val buffer = ByteArray(1024)
                        val len = socket.getInputStream().read(buffer)
                        val response = if (len > 0) String(buffer, 0, len) else "无响应"
                        withContext(Dispatchers.Main) { _statusMessage.value = "指令已发送: $command (响应: $response)" }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { _statusMessage.value = "发送失败: ${e.message}" }
                }
            }
        }
    }
}