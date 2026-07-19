package com.tether.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
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

    // UDP 广播发现缓存
    private val udpCache = mutableMapOf<String, DeviceInfo>()

    fun init(context: Context) {
        this.context = context
        prefs = context.getSharedPreferences("tether_devices", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        loadDevices()
        _quality.value = prefs.getInt("quality", 1)
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
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

    fun clearAllDevices() {
        _devices.value = emptyList()
        _selectedDevice.value = null
        udpCache.clear()
        prefs.edit().clear().apply()
        _statusMessage.value = "已清除所有设备数据"
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
        return info.toString()
    }

    fun setQuality(level: Int) {
        _quality.value = level
        prefs.edit().putInt("quality", level).apply()
        sendQualityToAgent(level)
    }

    private fun sendQualityToAgent(level: Int) {
        val device = _selectedDevice.value ?: return
        val ip = device.ip
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Socket(ip, qualityPort).use { socket ->
                        socket.soTimeout = 3000
                        socket.tcpNoDelay = true
                        val output = socket.getOutputStream()
                        output.write("RES:$level\n".toByteArray())
                        output.flush()
                        Log.d("Tether", "画质指令已发送: RES:$level")
                    }
                } catch (e: Exception) {
                    Log.e("Tether", "画质切换失败", e)
                }
            }
        }
    }

    // ==================== UDP 广播发现 ====================
    private fun updateDevicesFromCache() {
        val now = System.currentTimeMillis()
        val aliveDevices = udpCache.filter { now - it.value.hashCode() < cacheTimeout }
        val currentMap = _devices.value.associateBy { it.id }.toMutableMap()
        aliveDevices.values.forEach { device ->
            currentMap[device.id] = device.copy(isOnline = true)
        }
        _devices.value = currentMap.values.toList()
        saveDevices()
    }

    fun startUdpListener() {
        if (udpListenerJob?.isActive == true) return

        udpListenerJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket(udpPort).apply {
                        broadcast = true
                        reuseAddress = true
                        soTimeout = 5000
                    }
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)

                    Log.d("Tether", "UDP 监听启动，端口 $udpPort")

                    while (currentCoroutineContext().isActive) {
                        try {
                            socket.receive(packet)
                            val message = String(packet.data, 0, packet.length)
                            val ip = packet.address.hostAddress ?: continue

                            if (message.isNotBlank() && message.startsWith("TETHER_AGENT|")) {
                                val parts = message.split("|")
                                if (parts.size >= 3) {
                                    val deviceName = parts[1]
                                    val machineCode = if (parts.size >= 4) parts[3] else ""
                                    val id = if (machineCode.isNotEmpty()) machineCode else "$deviceName|$ip"

                                    val device = DeviceInfo(
                                        id = id,
                                        ip = ip,
                                        name = deviceName,
                                        isOnline = true,
                                        machineCode = machineCode
                                    )

                                    synchronized(udpCache) {
                                        // 同ID或同IP只保留最新
                                        val existingKey = udpCache.keys.find { key ->
                                            val existing = udpCache[key]
                                            existing != null && (existing.id == device.id || existing.ip == device.ip)
                                        }
                                        if (existingKey != null) {
                                            udpCache.remove(existingKey)
                                        }
                                        if (udpCache.size >= 50) {
                                            val oldestKey = udpCache.minByOrNull { it.value.hashCode() }?.key
                                            oldestKey?.let { udpCache.remove(it) }
                                        }
                                        udpCache[id] = device
                                        updateDevicesFromCache()
                                    }
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            synchronized(udpCache) {
                                val now = System.currentTimeMillis()
                                val expiredKeys = udpCache.filter { now - it.value.hashCode() > cacheTimeout }.keys
                                expiredKeys.forEach { udpCache.remove(it) }
                                if (expiredKeys.isNotEmpty()) {
                                    updateDevicesFromCache()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Tether", "UDP 接收异常", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Tether", "UDP 监听启动失败", e)
                } finally {
                    socket?.close()
                    Log.d("Tether", "UDP 监听已停止")
                }
            }
        }
    }

    fun stopUdpListener() {
        udpListenerJob?.cancel()
        udpListenerJob = null
        Log.d("Tether", "UDP 监听停止请求")
    }

    // ==================== 混合发现 ====================
    fun startHybridDiscovery() {
        if (_isScanning.value) {
            stopScan()
            return
        }
        _devices.value = emptyList()
        _selectedDevice.value = null
        _isScanning.value = true
        _statusMessage.value = "正在监听设备广播..."
        _scanProgress.value = "0/254"

        startUdpListener()

        scanJob = viewModelScope.launch {
            val foundDevices = mutableMapOf<String, DeviceInfo>()
            var mdnsFound = false

            val mdnsDeferred = async {
                try {
                    mdnsFound = performMdnsDiscovery(foundDevices)
                } catch (e: Exception) {
                    Log.d("Tether", "mDNS 异常: ${e.message}")
                }
            }

            val tcpDeferred = async {
                delay(5000)
                if (_devices.value.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "切换到深度扫描..."
                    }
                    performTcpDiscovery(foundDevices)
                } else {
                    Log.d("Tether", "已通过 UDP/mDNS 发现设备，跳过 TCP 扫描")
                }
            }

            mdnsDeferred.await()
            tcpDeferred.await()

            withContext(Dispatchers.Main) {
                val onlineCount = _devices.value.count { it.isOnline }
                _isScanning.value = false
                _scanProgress.value = "254/254"
                _statusMessage.value = if (onlineCount > 0) {
                    "发现 ${onlineCount} 台在线设备 (mDNS: ${if (mdnsFound) "✓" else "✗"})"
                } else {
                    "未发现设备，请检查 PC Agent 是否运行，或手动输入 IP"
                }
            }
        }
    }

    // ==================== mDNS 发现 ====================
    private suspend fun performMdnsDiscovery(foundDevices: MutableMap<String, DeviceInfo>): Boolean {
        val manager = nsdManager ?: return false
        var found = false
        var listener: NsdManager.DiscoveryListener? = null

        try {
            listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val ip = serviceInfo.host?.hostAddress ?: return
                    val name = serviceInfo.serviceName
                    val machineCode = serviceInfo.attributes?.get("machineCode")?.let { String(it) } ?: ""
                    val id = if (machineCode.isNotEmpty()) machineCode else "$name|$ip"
                    val device = DeviceInfo(
                        id = id,
                        ip = ip,
                        name = name,
                        isOnline = true,
                        machineCode = machineCode
                    )
                    synchronized(foundDevices) {
                        foundDevices[id] = device
                    }
                    found = true
                    _statusMessage.value = "发现设备: $ip:$name (mDNS)"
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val ip = serviceInfo.host?.hostAddress ?: return
                    val name = serviceInfo.serviceName
                    val machineCode = serviceInfo.attributes?.get("machineCode")?.let { String(it) } ?: ""
                    val id = if (machineCode.isNotEmpty()) machineCode else "$name|$ip"
                    viewModelScope.launch {
                        val current = _devices.value.toMutableList()
                        val index = current.indexOfFirst { it.id == id }
                        if (index >= 0) {
                            current[index] = current[index].copy(isOnline = false)
                            _devices.value = current
                            saveDevices()
                            _statusMessage.value = "设备离线: $ip:$name"
                        }
                    }
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d("Tether", "mDNS 发现已启动: $serviceType")
                }

                override fun onDiscoveryStopped(service: String) {
                    Log.d("Tether", "mDNS 发现已停止")
                }

                override fun onStartDiscoveryFailed(service: String, errorCode: Int) {
                    Log.d("Tether", "mDNS 启动失败: $errorCode")
                }

                override fun onStopDiscoveryFailed(service: String, errorCode: Int) {
                    Log.d("Tether", "mDNS 停止失败: $errorCode")
                }
            }

            mdnsListener = listener
            manager.discoverServices("_tether._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            Log.d("Tether", "mDNS 发现已启动")

            delay(6000)

        } catch (e: Exception) {
            Log.e("Tether", "mDNS 异常", e)
        } finally {
            try {
                listener?.let { manager.stopServiceDiscovery(it) }
                mdnsListener = null
                Log.d("Tether", "mDNS 发现已停止")
            } catch (e: Exception) {
                Log.e("Tether", "停止mDNS失败", e)
            }
        }
        return found
    }

    // ==================== TCP 深度扫描（兜底） ====================
    private suspend fun performTcpDiscovery(foundDevices: MutableMap<String, DeviceInfo>) {
        val baseIp = getLocalIpBase()
        val total = 254
        var foundCount = 0

        Log.d("Tether", "========== TCP 深度扫描开始 ==========")
        Log.d("Tether", "扫描网段: $baseIp.*")

        withContext(Dispatchers.Main) {
            _scanProgress.value = "0/254"
            _statusMessage.value = "深度扫描中..."
        }

        val batchSize = 10
        for (batchStart in 1..total step batchSize) {
            if (!_isScanning.value) break
            val batchEnd = minOf(batchStart + batchSize - 1, total)
            val jobs = mutableListOf<Job>()

            for (i in batchStart..batchEnd) {
                val ip = "$baseIp.$i"
                val job = viewModelScope.launch {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf(
                            "sh", "-c",
                            "echo 'ping' | nc -w 1 $ip $tcpPort 2>/dev/null"
                        ))
                        val output = process.inputStream.bufferedReader().readText().trim()
                        process.destroy()

                        if (output.isNotEmpty() && output.startsWith("pong")) {
                            foundCount++
                            val parts = output.split("|")
                            val deviceName = if (parts.size >= 2 && parts[1].isNotEmpty()) parts[1] else "PC"
                            val machineCode = if (parts.size >= 4) parts[3] else ""
                            val id = if (machineCode.isNotEmpty()) machineCode else "$deviceName|$ip"
                            val device = DeviceInfo(
                                id = id,
                                ip = ip,
                                name = deviceName,
                                isOnline = true,
                                machineCode = machineCode
                            )
                            synchronized(foundDevices) {
                                foundDevices[id] = device
                            }
                            Log.d("Tether", "✅ TCP 发现: $ip ($deviceName)")
                            withContext(Dispatchers.Main) {
                                _statusMessage.value = "发现: $ip:$deviceName"
                                val currentMap = _devices.value.associateBy { it.id }.toMutableMap()
                                currentMap[device.id] = device
                                _devices.value = currentMap.values.toList()
                            }
                        }
                    } catch (e: Exception) { /* 跳过 */ }
                }
                jobs.add(job)
            }
            jobs.forEach { it.join() }

            withContext(Dispatchers.Main) {
                _scanProgress.value = "${batchEnd}/254"
            }
            delay(5)
        }

        withContext(Dispatchers.Main) {
            _scanProgress.value = "254/254"
        }

        Log.d("Tether", "========== TCP 深度扫描完成 ==========")
        Log.d("Tether", "发现 $foundCount 台设备")
    }

    fun stopScan() {
        _isScanning.value = false
        scanJob?.cancel()
        scanJob = null
        stopUdpListener()

        try {
            mdnsListener?.let {
                nsdManager?.stopServiceDiscovery(it)
                mdnsListener = null
            }
        } catch (e: Exception) {
            Log.e("Tether", "清理mDNS失败", e)
        }

        _statusMessage.value = "扫描已停止"
    }

    fun deleteDevice(device: DeviceInfo) {
        val newList = _devices.value.filter { it.id != device.id }
        _devices.value = newList
        saveDevices()
        if (_selectedDevice.value?.id == device.id) {
            _selectedDevice.value = if (newList.isNotEmpty()) newList.first() else null
        }
        _statusMessage.value = "已删除: ${device.ip}"
        synchronized(udpCache) {
            udpCache.remove(device.id)
        }
    }

    fun editDeviceIp(oldDevice: DeviceInfo, newIp: String) {
        if (oldDevice.ip == newIp) { _statusMessage.value = "IP 未改变"; return }
        if (_devices.value.any { it.ip == newIp && it.id != oldDevice.id }) {
            _statusMessage.value = "IP $newIp 已存在"
            return
        }
        val newDevice = oldDevice.copy(ip = newIp)
        val newList = _devices.value.map { if (it.id == oldDevice.id) newDevice else it }
        _devices.value = newList
        saveDevices()
        if (_selectedDevice.value?.id == oldDevice.id) { _selectedDevice.value = newDevice }
        _statusMessage.value = "已更新 IP: ${oldDevice.ip} → $newIp"
    }

    fun updateDeviceNote(device: DeviceInfo, note: String) {
        val newDevice = device.copy(note = note)
        val newList = _devices.value.map { if (it.id == device.id) newDevice else it }
        _devices.value = newList
        saveDevices()
        if (_selectedDevice.value?.id == device.id) { _selectedDevice.value = newDevice }
        _statusMessage.value = "已更新备注: $note"
    }

    private fun getLocalIpBase(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
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
        } catch (e: Exception) {
            Log.e("Tether", "获取本机 IP 失败", e)
        }
        return "192.168.1"
    }

    fun addManualDevice(ip: String) {
        Log.d("Tether", "addManualDevice: ip=$ip")
        val id = "manual|$ip"
        if (_devices.value.any { it.id == id }) {
            _statusMessage.value = "设备已存在"
            return
        }
        val display = DeviceInfo(
            id = id,
            ip = ip,
            name = "手动连接",
            isManual = true,
            isOnline = true
        )
        _devices.value = _devices.value + display
        saveDevices()
        _selectedDevice.value = display
        _statusMessage.value = "已添加设备: $ip"
    }

    fun selectDevice(device: DeviceInfo) {
        Log.d("Tether", "selectDevice: ${device.ip}")
        _selectedDevice.value = device
        _statusMessage.value = "已选择: ${device.ip}"
    }

    fun sendCommand(command: String) {
        val device = _selectedDevice.value
        if (device == null) {
            _statusMessage.value = "请先选择或添加设备"
            return
        }
        if (!device.isOnline) {
            _statusMessage.value = "设备离线，无法发送指令"
            return
        }
        val ip = device.ip

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Socket(ip, tcpPort).use { socket ->
                        socket.soTimeout = 3000
                        socket.tcpNoDelay = true
                        val output = socket.getOutputStream()
                        output.write((command + "\n").toByteArray())
                        output.flush()

                        try {
                            val input = socket.getInputStream()
                            val buffer = ByteArray(1024)
                            val len = input.read(buffer)
                            if (len > 0) {
                                val response = String(buffer, 0, len)
                                withContext(Dispatchers.Main) {
                                    _statusMessage.value = "指令已发送: $command (响应: $response)"
                                }
                                return@withContext
                            }
                        } catch (e: Exception) { /* 没有响应也正常 */ }

                        withContext(Dispatchers.Main) {
                            _statusMessage.value = "指令已发送: $command"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "发送失败: ${e.message}"
                    }
                    Log.e("Tether", "发送指令异常", e)
                }
            }
        }
    }
}