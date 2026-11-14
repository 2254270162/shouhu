package com.example.locateme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * App 内的相机取景器：实时预览 + 前后摄像头切换 + 拍照。
 *
 * 用 CameraX 而不是调系统相机应用，这样"当前画面"直接显示在 App 里，不用跳出去。
 */
@Composable
fun CameraPanel(
    onCaptured: (File) -> Unit,
    onPickPhoto: () -> Unit,
    busy: Boolean,
    onRequestPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) cameraError = "没有相机权限，只能从相册选择"
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val repository = remember { PhotoRepository(context) }

    // 绑定相机到生命周期；切换前后摄像头或权限变化时重建绑定
    LaunchedEffect(lensFacing, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        runCatching { awaitCameraProvider(context) }
            .onFailure {
                cameraError = "相机初始化失败：${it.message}"
                return@LaunchedEffect
            }
            .onSuccess { provider ->
                cameraError = null
                runCatching {
                    provider.unbindAll()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                        preview,
                        imageCapture,
                    )
                }.onFailure {
                    // 很多设备没有前置摄像头，这时退回后置并提示
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        cameraError = "此设备没有可用的前置摄像头"
                        lensFacing = CameraSelector.LENS_FACING_BACK
                    } else {
                        cameraError = "相机启动失败：${it.message}"
                    }
                }
            }
    }

    // 离开界面时释放相机，避免抢占摄像头影响其他应用
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("需要相机权限才能预览", color = Color.White)
                    Text(
                        "授权后可以实时预览前后摄像头，并在拍照时写入当前坐标。",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(onClick = {
                        // 走系统权限弹窗；授予后重组会重新判断 hasPermission
                        onRequestPermission()
                    }) {
                        Text("授予相机权限")
                    }
                }
            }

            // 右上角切换前后摄像头
            if (hasPermission) {
                OutlinedButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                ) {
                    Text(if (lensFacing == CameraSelector.LENS_FACING_BACK) "前置" else "后置")
                }
            }

            if (capturing) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            }
        }

        cameraError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    capturing = true
                    cameraError = null
                    scope.launch {
                        runCatching { repository.capture(imageCapture) }
                            .onSuccess { file -> onCaptured(file) }
                            .onFailure { cameraError = "拍照失败：${it.message}" }
                        capturing = false
                    }
                },
                enabled = hasPermission && !busy && !capturing,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) { Text("拍照") }

            OutlinedButton(
                onClick = onPickPhoto,
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) { Text("从相册选择") }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** 把 ProcessCameraProvider 的 ListenableFuture 桥接成 suspend。 */
private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
