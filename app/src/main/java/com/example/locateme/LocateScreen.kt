package com.example.locateme

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocateScreen(
    state: UiState,
    onRequestPermission: (after: () -> Unit) -> Unit,
    onLocate: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onCopy: (String) -> Unit,
    onPhotoCaptured: (java.io.File) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onPhotoMessageShown: () -> Unit,
    onRequestCameraPermission: () -> Unit,
) {
    val context = LocalContext.current
    // 只负责内容布局：滚动词与 Scaffold 交给外层，避免嵌套滚动导致
    // "measured with an infinity maximum height constraints" 崩溃
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            Text("定位助手", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "一键获取当前经纬度、精度与中文地址。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 权限 / 开关提示
            when {
                state.precision == Precision.NONE -> PermissionCard(
                    onGrant = { onRequestPermission { onLocate() } },
                    onRefresh = onRefresh,
                )

                !state.locationServiceEnabled -> NoticeCard(
                    title = "系统定位服务已关闭",
                    body = "请打开手机的定位开关后再试。",
                    actionLabel = "去设置",
                    onAction = onOpenLocationSettings,
                )

                state.precision == Precision.COARSE -> NoticeCard(
                    title = "当前只有大致位置权限",
                    body = "系统只会返回城市级别的模糊坐标（精度可能几公里）。要精确到街道，请在权限设置里改为「精确位置」。",
                    actionLabel = "知道了",
                    onAction = onRefresh,
                )
            }

            // 主按钮
            val busy = state.progress != LocateProgress.Idle
            Button(
                onClick = {
                    if (state.precision == Precision.NONE) {
                        onRequestPermission { onLocate() }
                    } else {
                        onLocate()
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    when (val progress = state.progress) {
                        LocateProgress.Idle -> if (state.snapshot == null) "获取我的位置" else "重新定位"
                        is LocateProgress.Searching -> "正在定位（${progress.source}）"
                        LocateProgress.ResolvingAddress -> "正在解析地址"
                    },
                    fontSize = 17.sp,
                )
            }

            state.error?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("定位失败", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            state.snapshot?.let { snap ->
                ResultCard(snap = snap, onCopy = onCopy)
                if (state.addressFailed) {
                    Text(
                        "地址解析失败（可能无网络或服务限流），经纬度不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PhotoCard(
                state = state,
                onCaptured = onPhotoCaptured,
                onPickPhoto = onPickPhoto,
                onClearPhoto = onClearPhoto,
                onRequestCameraPermission = onRequestCameraPermission,
            )

            // 照片相关提示只弹一次，避免重组时反复出现
            state.photoMessage?.let { message ->
                LaunchedEffect(message) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    onPhotoMessageShown()
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "提示：室内、地下车库或电梯里 GPS 信号很弱，建议到窗边或室外重新定位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit, onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("需要定位权限", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "App 只在你点击按钮时读取一次位置，不会在后台持续跟踪。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrant) { Text("授予权限") }
                OutlinedButton(onClick = onRefresh) { Text("已授予，刷新") }
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ResultCard(
    snap: LocationSnapshot,
    onCopy: (String) -> Unit,
) {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                snap.address?.text ?: "（地址未解析）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            snap.address?.let {
                Text(
                    "地址来源：${it.provider}" + if (snap.isApproximate) " · 坐标为模糊位置" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            InfoRow("纬度", String.format(Locale.US, "%.6f", snap.latitude))
            InfoRow("经度", String.format(Locale.US, "%.6f", snap.longitude))
            InfoRow("精度", "±${snap.accuracyMeters.toInt()} 米" + accuracyHint(snap.accuracyMeters))
            InfoRow("来源", providerLabel(snap.provider))
            snap.altitudeMeters?.let { InfoRow("海拔", "%.1f 米".format(Locale.US, it)) }
            snap.speedMetersPerSecond?.let { InfoRow("速度", "%.1f 米/秒".format(Locale.US, it)) }
            InfoRow("时间", fmt.format(Date(snap.timestamp)))

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    onCopy(
                        "纬度：${String.format(Locale.US, "%.6f", snap.latitude)}\n" +
                            "经度：${String.format(Locale.US, "%.6f", snap.longitude)}\n" +
                            "精度：±${snap.accuracyMeters.toInt()} 米\n" +
                            "地址：${snap.address?.text ?: "未解析"}",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("复制坐标") }
        }
    }
}

@Composable
private fun PhotoCard(
    state: UiState,
    onCaptured: (java.io.File) -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onRequestCameraPermission: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("照片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "相机画面实时预览，可切换前后摄像头；拍照时会把当前坐标写进照片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CameraPanel(
                onCaptured = onCaptured,
                onPickPhoto = onPickPhoto,
                busy = state.photoBusy,
                onRequestPermission = onRequestCameraPermission,
            )

            // 拍完/选完后的预览
            state.photo?.let { photo ->
                HorizontalDivider()
                Text(
                    "刚拍摄/选择的照片",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                photo.thumbnail?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "照片预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                Text(
                    when {
                        photo.taggedWithLocation -> "已写入当前定位到照片 EXIF"
                        photo.exifLatitude != null && photo.exifLongitude != null ->
                            "照片自带位置：" +
                                String.format(Locale.US, "%.6f, %.6f", photo.exifLatitude, photo.exifLongitude)
                        photo.source == PhotoSource.CAMERA -> "照片未携带位置信息（拍照前先获取定位即可写入）"
                        else -> "这张照片没有位置信息"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClearPhoto) { Text("清除照片") }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun accuracyHint(meters: Float): String = when {
    meters <= 10f -> "（很准）"
    meters <= 50f -> "（可用）"
    meters <= 500f -> "（较模糊）"
    else -> "（仅大致范围）"
}

private fun providerLabel(provider: String): String = when (provider) {
    "gps" -> "GPS 卫星"
    "network" -> "网络定位"
    "passive" -> "被动缓存"
    "fused" -> "融合定位"
    else -> provider
}
