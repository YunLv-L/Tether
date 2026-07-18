package com.tether.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TetherApp(
    viewModel: TetherViewModel = viewModel()
) {
    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Tether") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.startDiscovery() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
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
                text = { Text(if (isScanning) "扫描中..." else "扫描设备") },
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
            // 状态提示
            if (statusMessage.isNotEmpty()) {
                AssistChip(
                    onClick = { /* 可忽略 */ },
                    label = { Text(statusMessage) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 手动输入 IP（MD3 OutlinedTextField）
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

            Spacer(modifier = Modifier.height(16.dp))

            // 设备列表（MD3 ListItem 风格）
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
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = device,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
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

            Spacer(modifier = Modifier.height(16.dp))

            // 指令按钮（MD3 FilledButton 风格）
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
        }
    }
}