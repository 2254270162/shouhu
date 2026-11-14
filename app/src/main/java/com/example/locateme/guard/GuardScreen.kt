package com.example.locateme.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 被监控端（守护端）界面。
 *
 * 刻意把状态、上报内容、开关全部摊开显示：
 * 用户随时能看到这台设备正在共享什么、能一键停止、能直接解绑。
 */
@Composable
fun GuardScreen(
    state: GuardUiState,
    onServerUrlChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onClaim: () -> Unit,
    onToggleGuarding: () -> Unit,
    onAllowCaptureChange: (Boolean) -> Unit,
    onUnpair: () -> Unit,
    onRefresh: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutoStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设备守护", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "开启后，这台设备会把位置共享给已配对的监控端。通知栏常驻显示运行状态，随时可停止。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 状态总览
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.guarding) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        !state.paired -> "未配对"
                        state.guarding -> "守护进行中"
                        else -> "已配对 · 未开启守护"
                    },
                    fontWeight = FontWeight.Bold,
                )
                if (state.lastReportAt > 0) {
                    Text(
                        "上次上报：" + SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(state.lastReportAt)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.paired) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onToggleGuarding) {
                            Text(if (state.guarding) "停止守护" else "开始守护")
                        }
                        OutlinedButton(onClick = onUnpair) { Text("解绑设备") }
                    }
                }
            }
        }

        if (!state.paired) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("配对", fontWeight = FontWeight.SemiBold)
                    Text(
                        "在监控端点「生成配对码」，把 6 位数字填到这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("服务器地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.deviceName,
                        onValueChange = onDeviceNameChange,
                        label = { Text("设备名称（监控端显示用）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.pairCode,
                        onValueChange = onPairCodeChange,
                        label = { Text("6 位配对码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onClaim,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.busy) "正在配对…" else "完成配对") }
                }
            }
        }

        // 权限/行为开关，全部可见可控
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("共享内容", fontWeight = FontWeight.SemiBold)
                Divider()
                SwitchRow(
                    title = "允许远程拍照与相册同步",
                    subtitle = "关闭后监控端只能看位置，无法触发摄像头或读取相册",
                    checked = state.allowRemoteCapture,
                    enabled = state.paired,
                    onCheckedChange = onAllowCaptureChange,
                )
                Divider()
                // 前台应用需要用户在系统设置里单独授权，这里给出明确入口
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("上报前台应用", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (state.usageAccess) {
                                "已授权，会随运行状态一起上报当前前台应用"
                            } else {
                                "需要「使用情况访问权限」才能读取，点击右侧前往设置"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.usageAccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onOpenUsageAccess) {
                        Text(if (state.usageAccess) "已授权" else "去授权")
                    }
                }
            }
        }

        TextButton(onClick = onRefresh) { Text("刷新状态") }

        // 后台保活设置：息屏掉线基本都是这里没配好
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("后台保活", fontWeight = FontWeight.SemiBold)
                Text(
                    "如果不设置，息屏后系统会切断后台网络，监控端会显示设备离线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("电池优化", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "允许后台持续运行，这是防止息屏掉线最关键的一步",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenBatterySettings) { Text("去设置") }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("自启动权限", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "小米/华为/OPPO 等机型需要单独开启，否则重启后守护不会自动恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenAutoStart) { Text("去设置") }
                }
            }
        }

        val message = state.message
        val error = state.error
        if (message != null || error != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    error ?: message ?: "",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "隐私提示：本机上报的内容仅你自己账号下的监控端可见。解绑后立即停止上报并删除服务端记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
