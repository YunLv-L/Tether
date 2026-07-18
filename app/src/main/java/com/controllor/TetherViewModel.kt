package com.tether.controller

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

data class DeviceInfo(
    val ip: String,
    val name: String = "未知设备",
    val machineCode: String = "",  // 机器码
    val isManual: Boolean = false,
    val note: String = ""          // 用户备注
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

    private val udpPort = 5555
    private val tcpPort = 5556
    private val discoveryTimeout = 3000L
    private val tcpTimeout = 500

    private var scanJob: Job? = null
    private var machineCodeCache: String? = null

    // ==================== 机器码生成（极难撞库） ====================
    fun generateMachineCode(context: Context): String {
        machineCodeCache?.let { return it }

        // 方案：SHA-256(设备硬件信息 + 随机盐 + 时间种子)
        return try {
            val hardwareInfo = buildString {
                append(android.os.Build.SERIAL)
                append(android.os.Build.MODEL)
                append(android.os.Build.MANUFACTURER)
                append(android.os.Build.BOARD)
                append(android.os.Build.DEVICE)
                append(android.os.Build.FINGERPRINT)
                // 添加网络接口 MAC（如果可用）
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        val mac = iface.hardwareAddress
                        if (mac != null) {
                            append(mac.joinToString("") { "%02x".format(it) })
                        }
                    }
                } catch (e: Exception) { /* 忽略 */ }
            }

            // 加盐（固定盐 + 随机盐）
            val fixedSalt = "Tether_Salt_2026_@#$%^&*()"
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            val randomSalt = randomBytes.joinToString("") { "%02x".format(it) }

            val combined = "$hardwareInfo|$fixedSalt|$randomSalt|${System.currentTimeMillis()}"
            val digest = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray())
            val hash = digest.joinToString("") { "%02x".format(it) }

            // 取前 64 位作为机器码（可读性）
            val result = hash.take(64)
            machineCodeCache = result
            Log.d("Tether", "机器码生成: $result")
            result
        } catch (e: Exception) {
            Log.e("Tether", "机器码生成失败", e)
            // Fallback: 使用时间戳 + 随机数
            val fallback = "${System.currentTimeMillis()}_${SecureRandom().nextInt(Int.MAX_VALUE)}"
            machineCodeCache = fallback
            fallback
        }
    }

    // ==================== Debug 模式切换 ====================
    fun toggleDebugMode() {
        _isDebugMode.value = !_isDebugMode.value
        _debugInfo.value = if (_isDebugMode.value) {
            buildDebugInfo()
        } else {
            ""
        }
    }

    private fun buildDebugInfo(): String {
        val info = StringBuilder()
        info.appendLine("📡 调试信息")
        info.appendLine("━━━━━━━━━━━━━━━━━")
        info.appendLine("扫描状态: ${if (_isScanning.value) "运行中" else "空闲"}")
        info.appendLine("扫描进度: ${_scanProgress.value}")
        info.appendLine("设备数量: ${_devices.value.size}")
        info.appendLine("选中设备: ${_selectedDevice.value?.ip ?: "无"}")
        info.appendLine("网段: ${getLocalIpBase()}.x")
        info.appendLine("TCP 端口: $tcpPort")
        info.appendLine("UDP 端口: $udpPort")
        info.appendLine("━━━━━━━━━━━━━━━━━")
        _devices.value.forEachIndexed { index, device ->
            info.appendLine("[${index + 1}] ${device.ip} | ${device.name} | MC: ${device.machineCode.take(16)}...")
        }
        return info.toString()
    }

    // ==================== UDP 扫描 ====================
    fun startDiscovery() {
        if (_isScanning.value) {
            stopScan()
            return
        }
        _devices.value = emptyList()
        _isScanning.value = true
        _statusMessage.value = "正在扫描..."
        _scanProgress.value = "0/254"

        scanJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket(udpPort).apply {
                        broadcast = true
                        soTimeout = discoveryTimeout.toInt()
                        reuseAddress = true
                    }
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    val startTime = System.currentTimeMillis()
                    val foundDevices = mutableListOf<DeviceInfo>()

                    while (System.currentTimeMillis() - startTime < discoveryTimeout) {
                        if (!_isScanning.value) break
                        try {
                            socket.receive(packet)
                            val message = String(packet.data, 0, packet.length)
                            val ip = packet.address.hostAddress ?: continue
                            if (message.isNotBlank() && message.startsWith("TETHER_AGENT|")) {
                                val parts = message.split("|")
                                if (parts.size >= 2) {
                                    val deviceName = parts[1]
                                    val machineCode = if (parts.size >= 3) parts[2] else ""
                                    val display = DeviceInfo(
                                        ip = ip,
                                        name = deviceName,
                                        machineCode = machineCode
                                    )
                                    if (!foundDevices.any { it.ip == ip }) {
                                        foundDevices.add(display)
                                        withContext(Dispatchers.Main) {
                                            _devices.value = foundDevices.toList()
                                        }
                                    }
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            // 超时继续
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (_isScanning.value) {
                            _statusMessage.value = if (foundDevices.isEmpty()) {
                                "UDP 扫描无结果，切换到 TCP 扫描..."
                            } else {
                                "发现 ${foundDevices.size} 台设备"
                            }
                        }
                    }
                    if (foundDevices.isEmpty() && _isScanning.value) {
                        withContext(Dispatchers.Main) {
                            _statusMessage.value = "切换到 TCP 端口扫描..."
                        }
                        tcpScanNetwork()
                    }
                } catch (e: Exception) {
                    if (_isScanning.value) {
                        withContext(Dispatchers.Main) {
                            _statusMessage.value = "扫描异常: ${e.message}"
                        }
                        Log.e("Tether", "扫描异常", e)
                    }
                } finally {
                    socket?.close()
                    if (_isScanning.value) {
                        withContext(Dispatchers.Main) {
                            _isScanning.value = false
                        }
                    }
                }
            }
        }
    }

    // ==================== TCP 并行扫描 ====================
    fun tcpScanNetwork() {
        if (_isScanning.value) {
            stopScan()
            return
        }
        _devices.value = emptyList()
        _isScanning.value = true
        _statusMessage.value = "正在扫描局域网..."
        _scanProgress.value = "0/254"

        scanJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val baseIp = getLocalIpBase()
                val foundDevices = mutableListOf<DeviceInfo>()
                val total = 254
                val batchSize = 20
                var scannedCount = 0

                Log.d("Tether", "开始并行 TCP 扫描网段: $baseIp.*")

                for (batchStart in 1..total step batchSize) {
                    if (!_isScanning.value) {
                        Log.d("Tether", "扫描被用户暂停")
                        break
                    }

                    val batchEnd = minOf(batchStart + batchSize - 1, total)
                    val batchJobs = mutableListOf<Job>()

                    for (i in batchStart..batchEnd) {
                        val ip = "$baseIp.$i"
                        val job = launch {
                            try {
                                Socket(ip, tcpPort).use { socket ->
                                    socket.soTimeout = tcpTimeout
                                    val output: OutputStream = socket.getOutputStream()
                                    output.write("ping\n".toByteArray())
                                    output.flush()

                                    val input = socket.getInputStream()
                                    val buffer = ByteArray(1024)
                                    val len = input.read(buffer)
                                    if (len > 0) {
                                        val response = String(buffer, 0, len)
                                        if (response.startsWith("pong|")) {
                                            val parts = response.split("|")
                                            val deviceName = if (parts.size >= 2) parts[1] else "PC"
                                            val machineCode = if (parts.size >= 3) parts[2] else ""
                                            val display = DeviceInfo(
                                                ip = ip,
                                                name = deviceName,
                                                machineCode = machineCode
                                            )
                                            if (!foundDevices.any { it.ip == ip }) {
                                                foundDevices.add(display)
                                                withContext(Dispatchers.Main) {
                                                    _devices.value = foundDevices.toList()
                                                    _statusMessage.value = "发现设备: $display"
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // 连接失败，跳过
                            }
                        }
                        batchJobs.add(job)
                    }

                    batchJobs.joinAll()

                    scannedCount += (batchEnd - batchStart + 1)
                    if (_isScanning.value) {
                        withContext(Dispatchers.Main) {
                            _scanProgress.value = "${scannedCount}/254"
                            _statusMessage.value = "扫描中... ${scannedCount}/254"
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                    _scanProgress.value = "254/254"
                    if (foundDevices.isEmpty()) {
                        _statusMessage.value = "未发现设备，请检查 PC Agent 是否运行，或手动输入 IP"
                    } else {
                        _statusMessage.value = "发现 ${foundDevices.size} 台设备"
                    }
                }
            }
        }
    }

    // ==================== 停止扫描 ====================
    fun stopScan() {
        _isScanning.value = false
        scanJob?.cancel()
        scanJob = null
        _statusMessage.value = "扫描已停止"
        Log.d("Tether", "扫描已停止")
    }

    // ==================== 删除设备 ====================
    fun deleteDevice(device: DeviceInfo) {
        val newList = _devices.value.filter { it.ip != device.ip }
        _devices.value = newList
        if (_selectedDevice.value?.ip == device.ip) {
            _selectedDevice.value = if (newList.isNotEmpty()) newList.first() else null
        }
        _statusMessage.value = "已删除: ${device.ip}"
    }

    // ==================== 编辑设备 IP ====================
    fun editDeviceIp(oldDevice: DeviceInfo, newIp: String) {
        if (oldDevice.ip == newIp) {
            _statusMessage.value = "IP 未改变"
            return
        }
        // 检查新 IP 是否已存在
        if (_devices.value.any { it.ip == newIp }) {
            _statusMessage.value = "IP $newIp 已存在"
            return
        }
        val newDevice = oldDevice.copy(ip = newIp)
        val newList = _devices.value.map { if (it.ip == oldDevice.ip) newDevice else it }
        _devices.value = newList
        if (_selectedDevice.value?.ip == oldDevice.ip) {
            _selectedDevice.value = newDevice
        }
        _statusMessage.value = "已更新 IP: ${oldDevice.ip} → $newIp"
    }

    // ==================== 更新设备备注 ====================
    fun updateDeviceNote(device: DeviceInfo, note: String) {
        val newDevice = device.copy(note = note)
        val newList = _devices.value.map { if (it.ip == device.ip) newDevice else it }
        _devices.value = newList
        if (_selectedDevice.value?.ip == device.ip) {
            _selectedDevice.value = newDevice
        }
        _statusMessage.value = "已更新备注: $note"
    }

    // ==================== 获取本机 IP 前缀 ====================
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

    // ==================== 手动添加设备 ====================
    fun addManualDevice(ip: String) {
        val display = DeviceInfo(ip = ip, name = "手动连接", isManual = true)
        if (!_devices.value.any { it.ip == ip }) {
            _devices.value = _devices.value + display
        }
        _selectedDevice.value = display
        _statusMessage.value = "已添加设备: $ip"
    }

    // ==================== 选择设备 ====================
    fun selectDevice(device: DeviceInfo) {
        _selectedDevice.value = device
        _statusMessage.value = "已选择: ${device.ip}"
    }

    // ==================== 发送指令 ====================
    fun sendCommand(command: String) {
        val device = _selectedDevice.value
        if (device == null) {
            _statusMessage.value = "请先选择或添加设备"
            return
        }
        val ip = device.ip

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Socket(ip, tcpPort).use { socket ->
                        socket.soTimeout = 3000
                        val output: OutputStream = socket.getOutputStream()
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
                        } catch (e: Exception) {
                            // 没有响应也正常
                        }

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