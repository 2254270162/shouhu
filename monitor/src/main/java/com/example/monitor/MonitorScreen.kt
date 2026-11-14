package com.example.monitor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    state: MonitorUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onCreateCode: () -> Unit,
    onLocate: (String) -> Unit,
    onCapture: (String, String) -> Unit,
    onSyncAlbum: (String) -> Unit,
    onOpenPhotos: (String) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onRefreshTelemetry: (String) -> Unit,
    onOpenLogs: (String) -> Unit,
    onReadClipboard: (String) -> Unit,
    onRequestPermissions: (String) -> Unit,
    onOpenAdmin: (String) -> Unit,
    onGroupFilter: (String?) -> Unit,
    onFilterLogs: (String?) -> Unit,
    onCloseLogs: () -> Unit,
    onCloseClipboard: () -> Unit,
    onBuild: (String, Boolean) -> Unit,
    onLoadEnrollment: () -> Unit,
    onSaveEnrollment: (Boolean, String, String, Int, String?) -> Unit,
    onRotateKey: () -> Unit,
    photoUrl: (String, String) -> String,
    authHeader: () -> String,
    onPromptShown: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("监控端", fontWeight = FontWeight.Bold) },
                actions = {
                    if (state.loggedIn) {
                        TextButton(onClick = onRefresh) { Text("刷新") }
                        TextButton(onClick = onLogout) { Text("退出") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.loggedIn) {
                ConfigPanel(
                    serverUrl = state.serverUrl,
                    username = state.username,
                    password = state.password,
                    loading = state.loading,
                    onServerUrlChange = onServerUrlChange,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
                    onLogin = onLogin,
                )
            } else {
                ConnectedPanel(
                    state = state,
                    onCreateCode = onCreateCode,
                    onLocate = onLocate,
                    onCapture = onCapture,
                    onSyncAlbum = onSyncAlbum,
                    onOpenPhotos = onOpenPhotos,
                    onDeleteDevice = onDeleteDevice,
                    onRefreshTelemetry = onRefreshTelemetry,
                    onOpenLogs = onOpenLogs,
                    onReadClipboard = onReadClipboard,
                    onRequestPermissions = onRequestPermissions,
                    onOpenAdmin = onOpenAdmin,
                    onGroupFilter = onGroupFilter,
                    onBuild = onBuild,
                    onLoadEnrollment = onLoadEnrollment,
                    onSaveEnrollment = onSaveEnrollment,
                    onRotateKey = onRotateKey,
                    onFilterLogs = onFilterLogs,
                    onCloseLogs = onCloseLogs,
                    onCloseClipboard = onCloseClipboard,
                    photoUrl = photoUrl,
                    authHeader = authHeader,
                )
            }
        }

        // 一次性提示
        state.error?.let { msg ->
            LaunchedEffect(msg) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                onPromptShown()
            }
        }
        state.message?.let { msg ->
            LaunchedEffect(msg) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                onPromptShown()
            }
        }
    }
}

@Composable
private fun ConfigPanel(
    serverUrl: String,
    username: String,
    password: String,
    loading: Boolean,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("登录服务器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "用管理员账号登录。密码只在登录时使用，不会保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.100:8080") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onLogin,
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (loading) "正在登录…" else "登录")
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("第一次使用？", fontWeight = FontWeight.SemiBold)
                Text(
                    "账号密码就是网页管理后台的账号密码。" +
                        "如果还没创建管理员，先在浏览器打开服务端地址完成初始化。",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("局域网用法", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text(
                    "在电脑上运行服务端程序，地址填 http://电脑的局域网IP:8080，" +
                        "手机和电脑连同一个 WiFi 即可。IP 在服务端启动时会打印出来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectedPanel(
    state: MonitorUiState,
    onCreateCode: () -> Unit,
    onLocate: (String) -> Unit,
    onCapture: (String, String) -> Unit,
    onSyncAlbum: (String) -> Unit,
    onOpenPhotos: (String) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onRefreshTelemetry: (String) -> Unit,
    onOpenLogs: (String) -> Unit,
    onReadClipboard: (String) -> Unit,
    onRequestPermissions: (String) -> Unit,
    onOpenAdmin: (String) -> Unit,
    onGroupFilter: (String?) -> Unit,
    onFilterLogs: (String?) -> Unit,
    onCloseLogs: () -> Unit,
    onCloseClipboard: () -> Unit,
    onBuild: (String, Boolean) -> Unit,
    onLoadEnrollment: () -> Unit,
    onSaveEnrollment: (Boolean, String, String, Int, String?) -> Unit,
    onRotateKey: () -> Unit,
    photoUrl: (String, String) -> String,
    authHeader: () -> String,
) {
    val context = LocalContext.current

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        // 服务器配置与编译
        item {
            ServerToolsPanel(
                state = state,
                onBuild = onBuild,
                onLoadEnrollment = onLoadEnrollment,
                onSaveEnrollment = onSaveEnrollment,
                onRotateKey = onRotateKey,
            )
        }

        // 分组筛选
        if (state.groups.isNotEmpty()) {            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 全部
                    FilterChip(
                        selected = state.groupFilter == null,
                        onClick = { onGroupFilter(null) },
                        label = { Text("全部") },
                    )
                    // 未分组
                    FilterChip(
                        selected = state.groupFilter == "none",
                        onClick = { onGroupFilter("none") },
                        label = { Text("未分组") },
                    )
                    // 各分组
                    state.groups.forEach { g ->
                        FilterChip(
                            selected = state.groupFilter == g.id,
                            onClick = { onGroupFilter(g.id) },
                            label = { Text("${g.name} ${g.deviceCount}") },
                        )
                    }
                }
            }
        }

        // 配对区
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("添加设备", fontWeight = FontWeight.SemiBold)
                    Text(
                        "在被监控端（备用机）安装同一个 App，切到「设备守护」页，填下面的配对码。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.pairingCode?.let { code ->
                        Text(
                            code,
                            fontSize = 34.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp,
                        )
                        Text(
                            "10 分钟内有效 · 在备用机输入这 6 位数字",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onCreateCode, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.pairingCode == null) "生成配对码" else "重新生成配对码")
                    }
                }
            }
        }

        // 加载 / 忙碌提示
        if (state.loading || state.busyText != null) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(state.busyText ?: "正在加载…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.devices.isEmpty() && !state.loading) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有设备", fontWeight = FontWeight.SemiBold)
                        Text(
                            "生成配对码后，在被监控端输入即可。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(state.devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                busy = state.busyText != null,
                onLocate = { onLocate(device.id) },
                onCapture = { lens -> onCapture(device.id, lens) },
                onSyncAlbum = { onSyncAlbum(device.id) },
                onOpenPhotos = { onOpenPhotos(device.id) },
                onRefreshTelemetry = { onRefreshTelemetry(device.id) },
                onOpenLogs = { onOpenLogs(device.id) },
                onReadClipboard = { onReadClipboard(device.id) },
                onRequestPermissions = { onRequestPermissions(device.id) },
                onOpenAdmin = { onOpenAdmin(device.id) },
                onDelete = { onDeleteDevice(device.id) },
            )
        }

        // 选中设备的照片
        if (state.selectedDeviceId != null) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "照片（${state.photos.size}）",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.photos.isEmpty()) {
                item {
                    Text(
                        "这台设备还没有上传照片。点上面的「远程拍照」或「同步相册」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.photos, key = { it.id }) { photo ->
                PhotoRow(
                    photo = photo,
                    url = photoUrl(state.selectedDeviceId, photo.id),
                    auth = authHeader(),
                )
            }
        }

        // 剪贴板面板
        if (state.clipboardDeviceId != null) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "设备剪贴板",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCloseClipboard) { Text("收起") }
                }
            }
            if (state.clipboardLoading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            } else {
                state.clipboard?.let { clip ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "内容",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (clip.fromHistory) "来自历史（设备在后台）" else "实时读取",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (clip.fromHistory) Color(0xFFB45309) else MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    clip.text ?: "（空）",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "${clip.length} 字符",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                if (clip.history.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text(
                                        "历史记录（${clip.history.size} 条）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    clip.history.take(10).forEachIndexed { i, item ->
                                        Text(
                                            "${i + 1}. $item",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 日志面板
        if (state.logsDeviceId != null) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "设备日志",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCloseLogs) { Text("收起") }
                }
            }
            item { LogFilterRow(state.logFilter, onFilterLogs) }
            if (state.logsLoading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (state.logs.isEmpty()) {
                item {
                    Text(
                        "暂无日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.logs, key = { "${it.ts}-${it.event}-${it.message}" }) { log ->
                    LogRow(log)
                }
            }
        }
    }
}

/** 日志事件筛选。 */
@Composable
private fun LogFilterRow(current: String?, onSelect: (String?) -> Unit) {
    val filters = listOf(
        null to "全部",
        "online" to "上线",
        "offline" to "离线",
        "location" to "定位",
        "telemetry" to "状态",
        "photo" to "照片",
        "command" to "指令",
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        filters.take(4).forEach { (key, label) ->
            FilterChip(selected = current == key, onClick = { onSelect(key) }, label = { Text(label, fontSize = 12.sp) })
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        filters.drop(4).forEach { (key, label) ->
            FilterChip(selected = current == key, onClick = { onSelect(key) }, label = { Text(label, fontSize = 12.sp) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = label)
}

/** 一条日志。 */
@Composable
private fun LogRow(log: LogEntry) {
    val color = when (log.level) {
        "error" -> MaterialTheme.colorScheme.error
        "warn" -> Color(0xFFB45309)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(log.ts)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Column(Modifier.weight(1f)) {
            Text(log.message, style = MaterialTheme.typography.bodySmall, color = color)
            log.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceSummary,
    busy: Boolean,
    onLocate: () -> Unit,
    onCapture: (String) -> Unit,
    onSyncAlbum: () -> Unit,
    onOpenPhotos: () -> Unit,
    onRefreshTelemetry: () -> Unit,
    onOpenLogs: () -> Unit,
    onReadClipboard: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAdmin: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (device.online) Color(0xFF2E7D32) else Color(0xFF9E9E9E)),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(device.model, if (device.online) "在线" else "离线").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDelete) { Text("解绑", color = MaterialTheme.colorScheme.error) }
            }

            device.lastLocation?.let { loc ->
                Divider()
                InfoLine("纬度", String.format(Locale.US, "%.6f", loc.latitude))
                InfoLine("经度", String.format(Locale.US, "%.6f", loc.longitude))
                InfoLine(
                    "精度",
                    loc.accuracy?.let { "±${it.toInt()} 米" } ?: "未知",
                )
                loc.timestamp?.let {
                    InfoLine("时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(it)))
                }
                if (loc.isApproximate) {
                    Text(
                        "该坐标来自「大致位置」授权，精度有限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } ?: Text(
                "还没有位置数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 运行状态面板
            device.lastTelemetry?.let { t ->
                Divider()
                TelemetryPanel(t)
            }

            HorizontalDivider()

            Text("操作", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLocate, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("定位")
                }
                OutlinedButton(onClick = onRefreshTelemetry, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("刷新状态")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCapture("back") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("后置拍照")
                }
                OutlinedButton(onClick = { onCapture("front") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("前置拍照")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSyncAlbum, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("同步相册")
                }
                OutlinedButton(onClick = onReadClipboard, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("读取剪贴板")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) {
                    Text("查看日志")
                }
                OutlinedButton(onClick = onRequestPermissions, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("请求权限")
                }
            }

            // 让设备打开管理页（服务端下发指令，设备端需输入口令）
            OutlinedButton(
                onClick = onOpenAdmin,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开设备管理页")
            }

            AssistChip(
                onClick = onOpenPhotos,
                label = { Text("查看照片（${device.photoCount}）") },
            )
        }
    }
}

/**
 * 服务器工具面板：预置注册配置 + APK 编译。
 *
 * 这些功能原本只在网页端有，搬到 App 里方便现场操作——
 * 不用为了改个配置专门找台电脑开浏览器。
 */
@Composable
private fun ServerToolsPanel(
    state: MonitorUiState,
    onBuild: (String, Boolean) -> Unit,
    onLoadEnrollment: () -> Unit,
    onSaveEnrollment: (Boolean, String, String, Int, String?) -> Unit,
    onRotateKey: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var groupKey by remember { mutableStateOf("") }
    var maxDevices by remember { mutableStateOf("0") }

    // 首次展开时加载配置
    LaunchedEffect(expanded) {
        if (expanded) onLoadEnrollment()
    }

    // 拉到配置后回填输入框
    LaunchedEffect(state.enrollment) {
        state.enrollment?.let {
            serverUrl = it.serverUrl
            groupKey = it.groupKey
            maxDevices = it.maxDevices.toString()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("服务器配置", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }

            if (!expanded) {
                Text(
                    "预置注册、编译安装包",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val e = state.enrollment
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (e?.enabled == true) "预置注册：已启用" else "预置注册：未启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e?.enabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRotateKey) { Text("生成密钥") }
            }

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("服务器地址（编译进 APK）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = groupKey,
                onValueChange = { groupKey = it },
                label = { Text("组密钥") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxDevices,
                onValueChange = { maxDevices = it.filter { c -> c.isDigit() } },
                label = { Text("注册上限（0=不限）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSaveEnrollment(true, groupKey, serverUrl, maxDevices.toIntOrNull() ?: 0, null)
                    },
                    enabled = state.busyText == null,
                    modifier = Modifier.weight(1f),
                ) { Text("保存并启用") }

                OutlinedButton(
                    onClick = {
                        onSaveEnrollment(false, groupKey, serverUrl, maxDevices.toIntOrNull() ?: 0, null)
                    },
                    enabled = state.busyText == null,
                ) { Text("停用") }
            }

            HorizontalDivider()

            Text("编译安装包", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(
                "被监控端会带上上面的服务器地址与组密钥，装完自动注册。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onBuild("device", true) },
                    enabled = state.busyText == null,
                    modifier = Modifier.weight(1f),
                ) { Text("编译被监控端") }
                OutlinedButton(
                    onClick = { onBuild("monitor", false) },
                    enabled = state.busyText == null,
                    modifier = Modifier.weight(1f),
                ) { Text("编译监控端") }
            }

            // 编译进度
            state.build?.let { b ->
                Text(
                    when {
                        b.running -> "编译中…"
                        b.ok == true -> "编译完成：${b.outputName ?: ""}"
                        b.ok == false -> "编译失败：${b.error ?: "未知错误"}"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        b.ok == false -> MaterialTheme.colorScheme.error
                        b.ok == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (b.outputName != null) {
                    Text(
                        "去网页端「安装包」页下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 运行状态面板：电量、温度、充电、屏幕、内存、存储、前台应用。 */
@Composable
private fun TelemetryPanel(t: TelemetryInfo) {
    Text("运行状态", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 两列网格，避免单列太长
    val rows = mutableListOf<Pair<String, String>>()
    t.batteryLevel?.let { rows += "电量" to "$it%" }
    t.temperatureC?.let { rows += "电池温度" to String.format(Locale.US, "%.1f°C", it) }
    t.chargingStatus?.let {
        val label = when (it) {
            "charging" -> "充电中"
            "full" -> "已充满"
            "discharging" -> "放电中"
            "not_charging" -> "未充电"
            else -> "未知"
        }
        val plug = when (t.pluggedType) {
            "ac" -> "（充电器）"
            "usb" -> "（USB）"
            "wireless" -> "（无线）"
            else -> ""
        }
        rows += "充电" to (label + plug)
    }
    t.screenOn?.let { rows += "屏幕" to (if (it) "亮屏" else "息屏") }
    t.memoryUsedPercent?.let { rows += "内存" to "$it%" }
    t.storageUsedPercent?.let { rows += "存储" to "$it%" }

    rows.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    // 前台应用单独一行，包名较长
    when {
        t.foregroundApp != null -> {
            InfoLine("前台应用", t.foregroundApp)
            t.foregroundPackage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        t.foregroundUnavailableReason != null -> Text(
            "前台应用：${t.foregroundUnavailableReason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    t.updatedAt?.let {
        Text(
            "更新于 " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhotoRow(photo: PhotoItem, url: String, auth: String) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .addHeader("Authorization", auth)
                    .crossfade(true)
                    .build(),
                contentDescription = "照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            )
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (photo.source == "camera") "远程拍照" else "相册") +
                        " · " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(photo.takenAt)),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    buildString {
                        append("${(photo.size / 1024.0).toInt()} KB")
                        if (photo.latitude != null && photo.longitude != null) {
                            append(" · ").append(String.format(Locale.US, "%.5f, %.5f", photo.latitude, photo.longitude))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
