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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TetherTheme {
                val viewModel: TetherViewModel = viewModel()
                LaunchedEffect(Unit) {
                    viewModel.init(applicationContext)
                }
                TetherApp(viewModel = viewModel)
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
    val quality by viewModel.quality.collectAsState()

    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var showEditDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var showNoteDialog by remember { mutableStateOf<DeviceInfo?>(null) }

    var headerClickCount by remember { mutableStateOf(0) }
    var headerClickStartTime by remember { mutableStateOf(0L) }

    LaunchedEffect(selectedDevice) {
        android.util.Log.d("Tether", "selectedDevice 变化: $selectedDevice")
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
            // 状态提示 + 进度
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onLongClick = {
                                        showDeleteDialog = device
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
                                        if (!device.isOnline) {
                                            Text(
                                                text = "离线",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold
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
                                        tint = if (device.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== 🔍 深度扫描按钮 =====
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
                Text("深度扫描")
            }
            
            Spacer(modifier = Modifier.height(8.dp))


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
                
                // 画质切换
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
                
                // 查看画面按钮
               Button(
                    onClick = {
                        val ip = selectedDevice?.ip ?: return@Button
                        android.util.Log.d("Tether", "查看画面, IP: $ip")
                        val intent = Intent(context, ScreenActivity::class.java)
                        intent.putExtra("ip", ip)
                        intent.putExtra("quality", quality)
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
    
    // ===== 清除设备数据按钮（Debug 模式下显示） =====
    //if (isDebugMode) {
    //    Spacer(modifier = Modifier.height(8.dp))
    //    Button(
    //        onClick = { viewModel.clearAllDevices() },
    //        modifier = Modifier.fillMaxWidth(),
    //        colors = ButtonDefaults.buttonColors(
    //            containerColor = MaterialTheme.colorScheme.error,
    //            contentColor = MaterialTheme.colorScheme.onError
    //        )
    //    ) {
    //        Text("🗑️ 清除所有设备数据")
    //    }
    //}

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