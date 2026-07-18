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
import java.net.Socket

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
                        } catch (e: java.net.SocketTimeoutException) {
                            // 超时继续
                        }
                    }
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = if (foundDevices.isEmpty()) {
                            "未发现设备，可手动输入 IP"
                        } else {
                            "发现 ${foundDevices.size} 台设备"
                        }
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

    fun addManualDevice(ip: String) {
        val display = "$ip:手动连接"
        if (!_devices.value.contains(display)) {
            _devices.value = _devices.value + display
        }
        _selectedDevice.value = display
        _statusMessage.value = "已添加设备: $ip"
    }

    fun selectDevice(device: String) {
        _selectedDevice.value = device
        _statusMessage.value = "已选择: $device"
    }

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
                        val output: OutputStream = socket.getOutputStream()
                        output.write((command + "\n").toByteArray())
                        output.flush()
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