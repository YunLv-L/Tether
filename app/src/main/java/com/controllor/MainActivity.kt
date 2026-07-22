package com.tether.controller

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.controller.IdentityManager.PeerInfo
import com.tether.controller.ui.theme.TetherTheme

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
        // 2️⃣ 初始化所有权限通道 (Shizuku + Dhizuku)
        // ============================================================
        CommandExecutor.init(applicationContext)

        // ============================================================
        // 3️⃣ 启动时自动申请 Shizuku 权限
        // ============================================================
        requestShizukuPermissionOnStart()

        setContent {
            TetherTheme {
                val viewModel: TetherViewModel = viewModel()
                LaunchedEffect(Unit) {
                    viewModel.init(applicationContext)
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
            android.util.Log.d("Tether", "⏳ Shizuku 服务未启动，等待 2 秒后重试...")
            android.os.Handler(mainLooper).postDelayed({
                tryRequestShizukuPermission()
            }, 2000)
            return
        }

        if (ShizukuManager.isGranted()) {
            android.util.Log.d("Tether", "✅ Shizuku 权限已授权")
            return
        }

        if (ShizukuManager.canUseHighPrivilege()) {
            android.util.Log.d("Tether", "✅ Shizuku 高权限可用")
            return
        }

        tryRequestShizukuPermission()
    }

    private fun tryRequestShizukuPermission() {
        if (shizukuPermissionRequested) {
            android.util.Log.d("Tether", "⏳ Shizuku 权限已请求过，等待用户响应...")
            return
        }

        if (!ShizukuManager.isAvailable()) {
            android.util.Log.d("Tether", "⚠️ Shizuku 服务不可用，请先启动 Shizuku")
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
            } else {
                android.util.Log.d("Tether", "❌ Shizuku 权限被拒绝")
                shizukuPermissionRequested = false
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
        if (!ShizukuManager.isGranted() && ShizukuManager.isAvailable()) {
            shizukuPermissionRequested = false
            tryRequestShizukuPermission()
        }
    }
}

// ============================================================
//  Composable UI
// ============================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TetherApp(
    viewModel: TetherViewModel = viewModel(),
    identityManager: IdentityManager
) {
    val verifiedPeers by identityManager.verifiedPeers.collectAsState()
    val cachedPeers by identityManager.peers.collectAsState()

    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val isDebugMode by viewModel.isDebugMode.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val quality by viewModel.quality.collectAsState()

    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var showEditDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var showNoteDialog by remember { mutableStateOf<DeviceInfo?>(null) }

    var headerClickCount by remember { mutableStateOf(0) }
    var headerClickStartTime by remember { mutableStateOf(0L) }

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
                                onLongClick = { viewModel.toggleDebugMode() },
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
                        val onlineCount = verifiedPeers.count { it.isOnline }
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    "$onlineCount 台在线",
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.startHybridDiscovery() }) {
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
            // ===== 状态提示 =====
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

            // ===== 权限状态行 =====
            PermissionStatusRow()

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
                                    onLongClick = { showDeleteDialog = device },
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
                                if (granted) viewModel.shizukuScan()
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

            // ===== 深度扫描按钮 =====
            OutlinedButton(
                onClick = { viewModel.deepScan() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("深度扫描（兜底）")
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
                        Text(
                            text = "📡 已验证: ${verifiedPeers.size} | 缓存: ${cachedPeers.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = CommandExecutor.getStatus(),
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
        val device = showDeleteDialog!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("设备操作") },
            text = { Text("${device.ip} (${device.getDisplayName()})") },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = {
                            showDeleteDialog = null
                            showEditDialog = device
                        }
                    ) {
                        Text("✏️ 编辑 IP")
                    }
                    TextButton(
                        onClick = {
                            showDeleteDialog = null
                            showNoteDialog = device
                        }
                    ) {
                        Text("📝 编辑备注")
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteDevice(device)
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
        val device = showEditDialog!!
        var newIp by remember { mutableStateOf(device.ip) }
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
        val device = showNoteDialog!!
        var newNote by remember { mutableStateOf(device.note) }
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
//  权限状态行 (Shizuku + Dhizuku)
// ============================================================
@Composable
fun PermissionStatusRow() {
    val shizukuAvailable = ShizukuManager.isAvailable()
    val shizukuGranted = ShizukuManager.isGranted()
    val shellReady = ShizukuManager.isShellServiceReady()

    val dhizukuAvailable = DhizukuManager.isAvailable()
    val dhizukuGranted = DhizukuManager.isPermissionGranted()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ===== Shizuku 状态 =====
        val shizukuColor = when {
            !shizukuAvailable -> MaterialTheme.colorScheme.error
            !shizukuGranted -> MaterialTheme.colorScheme.tertiary
            !shellReady -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }

        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = shizukuColor)
        }

        Text(
            text = when {
                !shizukuAvailable -> "Shizuku: 未启动"
                !shizukuGranted -> "Shizuku: 未授权"
                !shellReady -> "Shell: 加载中"
                else -> "Shizuku: ✅"
            },
            style = MaterialTheme.typography.labelSmall,
            color = shizukuColor,
            modifier = Modifier.weight(1f)
        )

        // ===== Dhizuku 状态 =====
        val dhizukuColor = when {
            !dhizukuAvailable -> MaterialTheme.colorScheme.error
            !dhizukuGranted -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }

        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = dhizukuColor)
        }

        Text(
            text = when {
                !dhizukuAvailable -> "Dhizuku: 不可用"
                !dhizukuGranted -> "Dhizuku: 未授权"
                else -> "Dhizuku: ✅"
            },
            style = MaterialTheme.typography.labelSmall,
            color = dhizukuColor
        )
    }
}