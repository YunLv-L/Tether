package com.tether.controller

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.controller.ui.theme.TetherTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    
    // ===== IdentityManager (新架构) =====
    private lateinit var identityManager: IdentityManager
    
    // ===== Shizuku 权限请求状态 =====
    private var shizukuPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ============================================================
        // 1️⃣ 初始化 IdentityManager (新架构)
        // ============================================================
        identityManager = IdentityManager(applicationContext)
        identityManager.start()

        // ============================================================
        // 2️⃣ 初始化 Shizuku (Java 版)
        // ============================================================
        ShizukuManager.init(applicationContext)

        // ============================================================
        // 3️⃣ 🚀 启动时自动申请 Shizuku 权限
        // ============================================================
        requestShizukuPermissionOnStart()

        setContent {
            TetherTheme {
                val viewModel: TetherViewModel = viewModel()
                LaunchedEffect(Unit) {
                    viewModel.init(applicationContext)
                    // 传递 IdentityManager 引用给 ViewModel
                    viewModel.setIdentityManager(identityManager)
                }
                TetherApp(
                    viewModel = viewModel,
                    identityManager = identityManager
                )
            }
        }
    }

    // ============================================================
    //  🚀 启动时自动申请 Shizuku 权限
    // ============================================================
    private fun requestShizukuPermissionOnStart() {
        // 检查 Shizuku 是否可用
        if (!ShizukuManager.isAvailable()) {
            // Shizuku 服务未启动，等待一下再试
            android.util.Log.d("Tether", "⏳ Shizuku 服务未启动，等待 2 秒后重试...")
            android.os.Handler(mainLooper).postDelayed({
                tryRequestShizukuPermission()
            }, 2000)
            return
        }

        // 检查是否已授权
        if (ShizukuManager.isGranted()) {
            android.util.Log.d("Tether", "✅ Shizuku 权限已授权")
            return
        }

        // 检查是否预 V11 (不支持)
        if (ShizukuManager.canUseHighPrivilege()) {
            // 已授权但 canUseHighPrivilege 为 true，说明可用
            android.util.Log.d("Tether", "✅ Shizuku 高权限可用")
            return
        }

        // 请求权限
        tryRequestShizukuPermission()
    }

    private fun tryRequestShizukuPermission() {
        if (shizukuPermissionRequested) {
            android.util.Log.d("Tether", "⏳ Shizuku 权限已请求过，等待用户响应...")
            return
        }

        if (!ShizukuManager.isAvailable()) {
            android.util.Log.d("Tether", "⚠️ Shizuku 服务不可用，请先启动 Shizuku")
            // 可以在这里显示一个 Toast 或通知
            return
        }

        if (ShizukuManager.isGranted()) {
            android.util.Log.d("Tether", "✅ Shizuku 权限已授权")
            return
        }

        android.util.Log.d("Tether", "🚀 启动时自动申请 Shizuku 权限...")
        shizukuPermissionRequested = true

        ShizukuManager.requestPermission { granted ->
            if (granted) {
                android.util.Log.d("Tether", "✅ Shizuku 权限授权成功")
                // 权限授予后，可以触发高权限扫描
                // viewModel.shizukuScan() 会在 UI 中按需调用
            } else {
                android.util.Log.d("Tether", "❌ Shizuku 权限被拒绝")
                shizukuPermissionRequested = false
                // 用户拒绝后，可以提示降级方案
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        identityManager.stop()
        ShizukuManager.destroy()
    }

    override fun onResume() {
        super.onResume()
        // 每次返回前台时，检查是否需要重新申请权限
        // (用户可能在设置中关闭了权限)
        if (!ShizukuManager.isGranted() && ShizukuManager.isAvailable()) {
            shizukuPermissionRequested = false
            tryRequestShizukuPermission()
        }
    }
}

// ============================================================
//  Composable UI (集成 IdentityManager)
// ============================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TetherApp(
    viewModel: TetherViewModel = viewModel(),
    identityManager: IdentityManager
) {
    // ===== 使用 IdentityManager 的设备列表 =====
    val verifiedPeers by identityManager.verifiedPeers.collectAsState()
    val cachedPeers by identityManager.peers.collectAsState()
    
    // ===== 兼容旧 ViewModel 状态 =====
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val isDebugMode by viewModel.isDebugMode.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val quality by viewModel.quality.collectAsState()

    val context = LocalContext.current

    // ===== 对话框状态 =====
    var showDeleteDialog by remember { mutableStateOf<PeerInfo?>(null) }
    var showEditDialog by remember { mutableStateOf<PeerInfo?>(null) }
    var showNoteDialog by remember { mutableStateOf<PeerInfo?>(null) }

    // ===== Debug 模式触发 =====
    var headerClickCount by remember { mutableStateOf(0) }
    var headerClickStartTime by remember { mutableStateOf(0L) }

    // ===== 将 PeerInfo 转换为 DeviceInfo (兼容旧 UI) =====
    val devices = remember(verifiedPeers, cachedPeers) {
        // 优先显示已验证设备，再显示缓存设备
        val verifiedList = verifiedPeers.map { peer ->
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
        
        val cachedList = cachedPeers
            .filter { cached -> !verifiedPeers.any { it.machineCode == cached.machineCode } }
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
        
        verifiedList + cachedList
    }

    // ===== 选中设备同步 =====
    LaunchedEffect(devices) {
        if (selectedDevice != null && devices.none { it.id == selectedDevice?.id }) {
            // 如果选中的设备不在列表中，取消选中
            viewModel.selectDevice(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isDebugMode) "Tether 🔧" else "Tether",
                            modifier = Modifier.combinedClickable(
                                onLongClick = {
                                    viewModel.toggleDebugMode()
                                },
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    if (now - headerClickStartTime > 3000) {
                                        headerClickCount = 0
                                    }
                                    headerClickStartTime = now
                                    headerClickCount++
                                    if (headerClickCount >= 5) {
                                        viewModel.toggleDebugMode()
                                        headerClickCount = 0
                                    }
                                }
                            )
                        )
                        if (isDebugMode) {
                            AssistChip(
                                onClick = { viewModel.toggleDebugMode() },
                                label = { Text("Debug", fontSize = MaterialTheme.typography.labelSmall.fontSize) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.height(24.dp)
                            )
                        }
                        // 显示在线设备数量
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    "${verifiedPeers.count { it.isOnline }} 台在线",
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { /* 手动刷新 */ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.startHybridDiscovery() },
                icon = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "扫描")
                    }
                },
                text = {
                    Text(if (isScanning) "停止扫描" else "扫描设备")
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // ===== 状态提示 + 进度 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (statusMessage.isNotEmpty()) {
                    AssistChip(
                        onClick = { /* 可忽略 */ },
                        label = { Text(statusMessage) },
                        modifier = Modifier.weight(1f),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                }
                if (isScanning) {
                    Text(
                        text = scanProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // ===== Shizuku 状态提示 =====
            ShizukuStatusRow()

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 手动输入 IP =====
            var manualIp by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text("输入 IP 地址") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                Button(
                    onClick = {
                        if (manualIp.isNotBlank()) {
                            viewModel.addManualDevice(manualIp)
                            manualIp = ""
                        }
                    },
                    enabled = manualIp.isNotBlank(),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text("连接")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== 设备列表 =====
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isScanning) "正在扫描设备..." else "未发现设备\n请确保 PC Agent 已运行",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices) { device ->
                        val isSelected = device == selectedDevice
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onLongClick = {
                                        // 转换为 PeerInfo 以便操作
                                        val peer = verifiedPeers.find { it.machineCode == device.machineCode }
                                        if (peer != null) {
                                            showDeleteDialog = peer
                                        } else {
                                            // 如果是未验证设备，提供删除选项
                                            showDeleteDialog = PeerInfo(
                                                machineCode = device.machineCode,
                                                ip = device.ip,
                                                deviceName = device.name,
                                                isOnline = device.isOnline,
                                                isVerified = device.note.contains("已验证"),
                                                lastSeen = System.currentTimeMillis()
                                            )
                                        }
                                    },
                                    onClick = {
                                        android.util.Log.d("Tether", "点击设备: ${device.ip}")
                                        viewModel.selectDevice(device)
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isSelected) 4.dp else 1.dp
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // 状态图标
                                        Text(
                                            text = if (device.isOnline) "🟢" else "🔴",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = device.getDisplayName(),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (device.isOnline) {
                                                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            },
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (device.note.isNotEmpty() && !device.note.contains("⏳")) {
                                            Text(
                                                text = device.note,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }
                                    Text(
                                        text = device.ip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (device.isOnline) {
                                            if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                        }
                                    )
                                    // 显示机器码 (Debug 模式)
                                    if (isDebugMode && device.machineCode.isNotEmpty()) {
                                        Text(
                                            text = "MC: ${device.machineCode.take(8)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "已选中",
                                        tint = if (device.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== Shizuku 扫描按钮 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (!ShizukuManager.canUseHighPrivilege()) {
                            ShizukuManager.requestPermission { granted ->
                                if (granted) {
                                    viewModel.shizukuScan()
                                }
                            }
                            return@OutlinedButton
                        }
                        viewModel.shizukuScan()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (ShizukuManager.canUseHighPrivilege()) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (ShizukuManager.canUseHighPrivilege()) "Shizuku 扫描" else "Shizuku 不可用")
                }
                
                OutlinedButton(
                    onClick = {
                        // 强制重新申请 Shizuku 权限
                        ShizukuManager.requestPermission { granted ->
                            android.util.Log.d("Tether", "Shizuku 重新授权: $granted")
                        }
                    },
                    modifier = Modifier
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("🔑 授权")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 指令按钮 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { viewModel.sendCommand("lock") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    enabled = selectedDevice?.isOnline == true
                ) {
                    Text("🔒 锁定")
                }
                FilledTonalButton(
                    onClick = { viewModel.sendCommand("sleep") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    enabled = selectedDevice?.isOnline == true
                ) {
                    Text("💤 睡眠")
                }
                FilledTonalButton(
                    onClick = { viewModel.sendCommand("shutdown") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    enabled = selectedDevice?.isOnline == true
                ) {
                    Text("⏻ 关机")
                }
            }

            // ===== 画质切换 + 查看画面 =====
            if (selectedDevice != null) {
                Spacer(modifier = Modifier.height(12.dp))

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = when (quality) {
                            0 -> "流畅 (720p)"
                            1 -> "标准 (1080p)"
                            else -> "高清 (原始)"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("画质") },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("流畅 (720p)") },
                            onClick = {
                                viewModel.setQuality(0)
                                expanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("标准 (1080p)") },
                            onClick = {
                                viewModel.setQuality(1)
                                expanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("高清 (原始)") },
                            onClick = {
                                viewModel.setQuality(2)
                                expanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val ip = selectedDevice?.ip ?: return@Button
                        val machineCode = selectedDevice?.machineCode ?: ""
                        android.util.Log.d("Tether", "查看画面, IP: $ip, MC: $machineCode")
                        val intent = Intent(context, ScreenActivity::class.java)
                        intent.putExtra("ip", ip)
                        intent.putExtra("quality", quality)
                        intent.putExtra("machineCode", machineCode)
                        context.startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text("🖥️ 查看画面", fontSize = MaterialTheme.typography.titleMedium.fontSize)
                }
            }

            // ===== Debug 信息 =====
            if (isDebugMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = debugInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        // 显示 IdentityManager 状态
                        Text(
                            text = "📡 已验证: ${verifiedPeers.size} | 缓存: ${cachedPeers.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // ===== 长按菜单对话框 =====
    if (showDeleteDialog != null) {
        val peer = showDeleteDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("设备操作") },
            text = { Text("${peer.ip} (${peer.deviceName})") },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = {
                            showDeleteDialog = null
                            // 编辑 IP (需要转换为 DeviceInfo)
                            val device = DeviceInfo(
                                id = peer.machineCode,
                                ip = peer.ip,
                                name = peer.deviceName,
                                isManual = false,
                                note = if (peer.isVerified) "已验证" else "",
                                isOnline = peer.isOnline,
                                machineCode = peer.machineCode
                            )
                            showEditDialog = peer
                        }
                    ) {
                        Text("✏️ 编辑 IP")
                    }
                    TextButton(
                        onClick = {
                            showDeleteDialog = null
                            showNoteDialog = peer
                        }
                    ) {
                        Text("📝 编辑备注")
                    }
                    TextButton(
                        onClick = {
                            // 从设备列表中移除
                            viewModel.deleteDevice(
                                DeviceInfo(
                                    id = peer.machineCode,
                                    ip = peer.ip,
                                    name = peer.deviceName,
                                    isManual = false,
                                    note = "",
                                    isOnline = peer.isOnline,
                                    machineCode = peer.machineCode
                                )
                            )
                            showDeleteDialog = null
                        }
                    ) {
                        Text("🗑️ 删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // ===== 编辑 IP 对话框 =====
    if (showEditDialog != null) {
        val peer = showEditDialog!!
        var newIp by remember { mutableStateOf(peer.ip) }
        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            title = { Text("编辑 IP") },
            text = {
                OutlinedTextField(
                    value = newIp,
                    onValueChange = { newIp = it },
                    label = { Text("IP 地址") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newIp.isNotBlank()) {
                            // 通知 ViewModel 更新
                            val device = DeviceInfo(
                                id = peer.machineCode,
                                ip = peer.ip,
                                name = peer.deviceName,
                                isManual = false,
                                note = if (peer.isVerified) "已验证" else "",
                                isOnline = peer.isOnline,
                                machineCode = peer.machineCode
                            )
                            viewModel.editDeviceIp(device, newIp)
                            showEditDialog = null
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // ===== 编辑备注对话框 =====
    if (showNoteDialog != null) {
        val peer = showNoteDialog!!
        var newNote by remember { mutableStateOf(peer.deviceName) }
        AlertDialog(
            onDismissRequest = { showNoteDialog = null },
            title = { Text("设备备注") },
            text = {
                OutlinedTextField(
                    value = newNote,
                    onValueChange = { newNote = it },
                    label = { Text("备注名称") },
                    singleLine = true,
                    placeholder = { Text("例如: 我的台式机") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 保存备注 (通过 ViewModel)
                        val device = DeviceInfo(
                            id = peer.machineCode,
                            ip = peer.ip,
                            name = peer.deviceName,
                            isManual = false,
                            note = newNote,
                            isOnline = peer.isOnline,
                            machineCode = peer.machineCode
                        )
                        viewModel.updateDeviceNote(device, newNote)
                        showNoteDialog = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

// ============================================================
//  Shizuku 状态显示
// ============================================================
@Composable
fun ShizukuStatusRow() {
    val shizukuAvailable = ShizukuManager.isAvailable()
    val shizukuGranted = ShizukuManager.isGranted()
    val shellReady = ShizukuManager.isShellServiceReady()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态指示灯
        val statusColor = when {
            !shizukuAvailable -> MaterialTheme.colorScheme.error
            !shizukuGranted -> MaterialTheme.colorScheme.warning
            !shellReady -> MaterialTheme.colorScheme.warning
            else -> MaterialTheme.colorScheme.primary
        }
        
        Box(
            modifier = Modifier
                .size(10.dp)
                .then(
                    if (statusColor == MaterialTheme.colorScheme.primary)
                        Modifier
                    else
                        Modifier
                )
        ) {
            // 圆点
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawCircle(color = statusColor)
            }
        }

        val statusText = when {
            !shizukuAvailable -> "Shizuku 未启动"
            !shizukuGranted -> "Shizuku 未授权"
            !shellReady -> "Shell 服务加载中..."
            else -> "Shizuku 就绪 ✅"
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            modifier = Modifier.weight(1f)
        )
        
        // 显示 Shell 服务状态 (Debug)
        if (shizukuGranted && shellReady) {
            Text(
                text = "🔧 Shell 已就绪",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================
//  PeerInfo 扩展 (用于对话框)
// ============================================================
data class PeerInfo(
    val machineCode: String,
    val ip: String,
    val deviceName: String,
    val isOnline: Boolean,
    val isVerified: Boolean,
    val lastSeen: Long
)