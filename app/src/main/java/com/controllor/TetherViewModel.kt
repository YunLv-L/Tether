package com.tether.controller

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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

class TetherViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<String>>(emptyList())
    val devices: StateFlow<List<String>> = _devices

    private val _selectedDevice = MutableStateFlow<String?>(null)
    val selectedDevice: StateFlow<String?> = _selectedDevice

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private val udpPort = 5555
    private val tcpPort = 5556
    private val discoveryTimeout = 3000L
    private val tcpTimeout = 500 // TCP 连接超时（毫秒）

    // ==================== UDP 广播扫描（保留，但可能被防火墙拦截） ====================
    fun startDiscovery() {
        if (_isScanning.value) return
        _devices.value = emptyList()
        _isScanning.value = true
        _statusMessage.value = "正在扫描..."

        viewModelScope.launch {
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
                    val foundDevices = mutableListOf<String>()

                    while (System.currentTimeMillis() - startTime < discoveryTimeout) {
                        try {
                            socket.receive(packet)
                            val message = String(packet.data, 0, packet.length)
                            val ip = packet.address.hostAddress ?: continue
                            if (message.isNotBlank() && message.startsWith("TETHER_AGENT|")) {
                                val parts = message.split("|")
                                if (parts.size >= 2) {
                                    val deviceName = parts[1]
                                    val display = "$ip:$deviceName"
                                    if (!foundDevices.contains(display)) {
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
                        _statusMessage.value = if (foundDevices.isEmpty()) {
                            "UDP 扫描无结果，尝试 TCP 扫描..."
                        } else {
                            "发现 ${foundDevices.size} 台设备"
                        }
                    }
                    // 如果 UDP 没有发现设备，自动启动 TCP 扫描
                    if (foundDevices.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            _statusMessage.value = "切换到 TCP 端口扫描..."
                        }
                        tcpScanNetwork()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "扫描异常: ${e.message}"
                    }
                    Log.e("Tether", "扫描异常", e)
                } finally {
                    socket?.close()
                    withContext(Dispatchers.Main) {
                        _isScanning.value = false
                    }
                }
            }
        }
    }

    // ==================== TCP 局域网 IP 段扫描（核心发现机制） ====================
    fun tcpScanNetwork() {
        if (_isScanning.value) return
        _devices.value = emptyList()
        _isScanning.value = true
        _statusMessage.value = "正在扫描局域网..."

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val baseIp = getLocalIpBase()
                val foundDevices = mutableListOf<String>()
                var scannedCount = 0

                Log.d("Tether", "开始 TCP 扫描网段: $baseIp.*")

                for (i in 1..254) {
                    val ip = "$baseIp.$i"
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
                                Log.d("Tether", "TCP 扫描 $ip 响应: $response")
                                if (response.startsWith("pong|")) {
                                    val parts = response.split("|")
                                    val deviceName = if (parts.size >= 2) parts[1] else "PC"
                                    val display = "$ip:$deviceName"
                                    if (!foundDevices.contains(display)) {
                                        foundDevices.add(display)
                                        withContext(Dispatchers.Main) {
                                            _devices.value = foundDevices.toList()
                                            _statusMessage.value = "发现 ${foundDevices.size} 台设备"
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 连接失败，跳过（大部分 IP 没有服务）
                    }

                    scannedCount++
                    if (scannedCount % 10 == 0) {
                        withContext(Dispatchers.Main) {
                            _statusMessage.value = "扫描中... ${scannedCount}/254"
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                    _statusMessage.value = if (foundDevices.isEmpty()) {
                        "未发现设备，请检查 PC Agent 是否运行，或手动输入 IP"
                    } else {
                        "发现 ${foundDevices.size} 台设备"
                    }
                }
            }
        }
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
                        // 只扫描私有 IP 段
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.") ||
                            ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.") ||
                            ip.startsWith("172.20.") || ip.startsWith("172.21.") || ip.startsWith("172.22.") ||
                            ip.startsWith("172.23.") || ip.startsWith("172.24.") || ip.startsWith("172.25.") ||
                            ip.startsWith("172.26.") || ip.startsWith("172.27.") || ip.startsWith("172.28.") ||
                            ip.startsWith("172.29.") || ip.startsWith("172.30.") || ip.startsWith("172.31.")
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
        val display = "$ip:手动连接"
        if (!_devices.value.contains(display)) {
            _devices.value = _devices.value + display
        }
        _selectedDevice.value = display
        _statusMessage.value = "已添加设备: $ip"
    }

    // ==================== 选择设备 ====================
    fun selectDevice(device: String) {
        _selectedDevice.value = device
        _statusMessage.value = "已选择: $device"
    }

    // ==================== 发送指令 ====================
    fun sendCommand(command: String) {
        val device = _selectedDevice.value
        if (device == null) {
            _statusMessage.value = "请先选择或添加设备"
            return
        }
        val ip = device.substringBefore(":")
        if (ip.isEmpty()) {
            _statusMessage.value = "无效 IP"
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Socket(ip, tcpPort).use { socket ->
                        socket.soTimeout = 3000
                        val output: OutputStream = socket.getOutputStream()
                        output.write((command + "\n").toByteArray())
                        output.flush()

                        // 尝试读取响应（可选）
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
                            // 没有响应也正常（部分指令不返回）
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