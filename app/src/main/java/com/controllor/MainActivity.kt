package com.tether.controller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var btnDiscover: Button
    private lateinit var btnSendLock: Button
    private lateinit var btnSendSleep: Button
    private lateinit var btnSendShutdown: Button
    private lateinit var etManualIP: EditText
    private lateinit var btnManualConnect: Button

    private val discoveredDevices = mutableListOf<String>()
    private val adapter: ArrayAdapter<String> by lazy {
        ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredDevices)
    }

    private var selectedDeviceIp: String? = null
    private val udpPort = 5555
    private val tcpPort = 5556
    private val discoveryTimeout = 3000L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listViewDevices)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnSendLock = findViewById(R.id.btnSendLock)
        btnSendSleep = findViewById(R.id.btnSendSleep)
        btnSendShutdown = findViewById(R.id.btnSendShutdown)
        etManualIP = findViewById(R.id.etManualIP)
        btnManualConnect = findViewById(R.id.btnManualConnect)

        listView.adapter = adapter

        // 点击列表项选择设备
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = discoveredDevices[position]
            selectedDeviceIp = item.substringBefore(":")
            Toast.makeText(this, "已选择: $item", Toast.LENGTH_SHORT).show()
        }

        // 手动连接按钮
        btnManualConnect.setOnClickListener {
            val ip = etManualIP.text.toString().trim()
            if (ip.isNotEmpty()) {
                selectedDeviceIp = ip
                val display = "$ip:手动连接"
                if (!discoveredDevices.contains(display)) {
                    discoveredDevices.add(display)
                    adapter.notifyDataSetChanged()
                }
                Toast.makeText(this, "已设置 IP: $ip", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show()
            }
        }

        // 刷新扫描
        btnDiscover.setOnClickListener {
            if (checkAndRequestPermissions()) {
                startDiscovery()
                // 同时添加一个固定 IP 作为备选
                mainHandler.postDelayed({
                    val fixedIp = "192.168.10.10:YUNLV·DEVICE(直连)"
                    if (!discoveredDevices.contains(fixedIp)) {
                        discoveredDevices.add(fixedIp)
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this, "已添加固定设备: 192.168.10.10", Toast.LENGTH_SHORT).show()
                    }
                }, 2000)
            }
        }

        // 发送指令
        btnSendLock.setOnClickListener { sendCommand("lock") }
        btnSendSleep.setOnClickListener { sendCommand("sleep") }
        btnSendShutdown.setOnClickListener { sendCommand("shutdown") }

        // 首次启动自动扫描
        if (checkAndRequestPermissions()) {
            startDiscovery()
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
            false
        } else {
            true
        }
    }

    private fun startDiscovery() {
        discoveredDevices.clear()
        adapter.notifyDataSetChanged()
        Toast.makeText(this, "正在扫描局域网设备...", Toast.LENGTH_SHORT).show()

        executor.execute {
            try {
                val socket = DatagramSocket(udpPort).apply {
                    broadcast = true
                    soTimeout = discoveryTimeout.toInt()
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < discoveryTimeout) {
                    try {
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        val ip = packet.address.hostAddress ?: continue
                        if (message.startsWith("TETHER_AGENT|")) {
                            val deviceName = message.substringAfter("|")
                            val display = "$ip:$deviceName"
                            if (!discoveredDevices.contains(display)) {
                                mainHandler.post {
                                    discoveredDevices.add(display)
                                    adapter.notifyDataSetChanged()
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 超时继续
                    }
                }
                socket.close()
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "发现 ${discoveredDevices.size} 台设备",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    Toast.makeText(this, "扫描异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendCommand(command: String) {
        val ip = selectedDeviceIp
        if (ip == null) {
            Toast.makeText(this, "请先选择一台设备", Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            try {
                Socket(ip, tcpPort).use { socket ->
                    val output: OutputStream = socket.getOutputStream()
                    output.write((command + "\n").toByteArray())
                    output.flush()
                    mainHandler.post {
                        Toast.makeText(this, "指令已发送: $command", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startDiscovery()
        } else {
            Toast.makeText(this, "需要网络权限才能发现设备", Toast.LENGTH_LONG).show()
        }
    }
}