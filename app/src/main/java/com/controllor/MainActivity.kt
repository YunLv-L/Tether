package com.tether.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tether.controller.ui.theme.TetherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TetherTheme {
                TetherApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TetherApp(
    viewModel: TetherViewModel = viewModel()
) {
    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val isDebugMode by viewModel.isDebugMode.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var showEditDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var showNoteDialog by remember { mutableStateOf<DeviceInfo?>(null) }

    // 长按标题触发 Debug 模式
    var headerClickCount by remember { mutableStateOf(0) }
    var headerClickStartTime by remember { mutableStateOf(0L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tether",
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                // 5 次点击进入 Debug
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
                            },
                            onLongClick = {
                                // 长按 5 秒进入 Debug
                                viewModel.toggleDebugMode()
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.tcpScanNetwork() }) {
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
                onClick = { viewModel.tcpScanNetwork() },
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
            // 状态提示 + 进度
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 手动输入 IP
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

            // 设备列表
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isScanning) "正在扫描设备..." else "未发现设备\n请点击右下角扫描按钮",
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
                            onClick = { viewModel.selectDevice(device) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onLongClick = {
                                        // 长按弹出操作菜单
                                        showDeleteDialog = device
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
                                    Text(
                                        text = device.getDisplayName(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = device.ip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (device.note.isNotEmpty()) {
                                        Text(
                                            text = "📝 ${device.note}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "已选中",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 指令按钮
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
                    )
                ) {
                    Text("🔒 锁定")
                }
                FilledTonalButton(
                    onClick = { viewModel.sendCommand("sleep") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("💤 睡眠")
                }
                FilledTonalButton(
                    onClick = { viewModel.sendCommand("shutdown") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("⏻ 关机")
                }
            }

            // Debug 信息
            if (isDebugMode && debugInfo.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = debugInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
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