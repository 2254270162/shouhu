package com.example.locateme.guard

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 无界面拍照。
 *
 * CameraX 必须绑定 LifecycleOwner，而 Service 不是。
 * 这里用一个短命的 LifecycleRegistry 宿主：绑定 → 拍照 → 立即解绑，
 * 避免相机被长期占用导致其他应用无法使用摄像头。
 */
object CameraCapture {

    private val executor = Executors.newSingleThreadExecutor()

    /** 绑定相机后等待 3A 稳定的时间；太短会拍到黑图，太长又让远程指令变慢。 */
    private const val WARMUP_MS = 1200L

    /** 用一个可销毁的生命周期宿主承载一次拍摄。 */
    private class OneShotOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        /**
         * LifecycleRegistry 强制要求在主线程切换状态，
         * 否则抛 "Method setCurrentState must be called on the main thread"。
         * 这里统一 post 到主线程，调用方无需关心线程。
         */
        fun start() {
            runOnMain {
                if (registry.currentState != Lifecycle.State.DESTROYED) {
                    registry.currentState = Lifecycle.State.RESUMED
                }
            }
        }

        fun finish() {
            runOnMain {
                if (registry.currentState != Lifecycle.State.DESTROYED) {
                    registry.currentState = Lifecycle.State.DESTROYED
                }
            }
        }

        private fun runOnMain(block: () -> Unit) {
            val handler = Handler(Looper.getMainLooper())
            if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
        }
    }

    suspend fun captureOnce(context: Context, lens: String): File = suspendCancellableCoroutine { cont ->
        val owner = OneShotOwner()
        owner.start()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                var provider: ProcessCameraProvider? = null
                try {
                    provider = future.get()
                    provider.unbindAll()

                    val selector = if (lens == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    // 关键：后台拍照必须让画面管线真正跑起来，否则 3A 不启动、拍出全黑。
                    // 离屏 Surface 在部分机型上不生效，改用 ImageAnalysis：
                    // 它会持续把帧交给分析器，能稳定驱动相机曝光与对焦。
                    val analysis = androidx.camera.core.ImageAnalysis.Builder()
                        .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy -> proxy.close() }

                    provider.bindToLifecycle(owner, selector, analysis, imageCapture)

                    // 部分机型在预览首帧前拍照仍是黑的，延后启动快门让 3A 稳定。
                    // 这里在回调线程里，只能用 Handler 延时，不能用 delay()。
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            val file = File.createTempFile("remote_", ".jpg", context.cacheDir)
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()

                            imageCapture.takePicture(
                                options,
                                executor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        runCatching { provider?.unbindAll() }
                                        owner.finish()
                                        if (cont.isActive) cont.resume(file) else file.delete()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        runCatching { provider?.unbindAll() }
                                        owner.finish()
                                        file.delete()
                                        if (cont.isActive) {
                                            cont.resumeWithException(IllegalStateException(describe(exception)))
                                        }
                                    }
                                },
                            )
                        },
                        WARMUP_MS,
                    )
                } catch (e: Exception) {
                    runCatching { provider?.unbindAll() }
                    owner.finish()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(describe(e)))
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** 把底层异常翻译成用户能看懂、能据此行动的话。 */
    private fun describe(e: Throwable): String {
        val text = e.message ?: ""
        return when {
            text.contains("permission", true) || text.contains("Security", true) ->
                "设备未授予相机权限，无法远程拍照"
            text.contains("CAMERA_IN_USE", true) || text.contains("in use", true) ->
                "摄像头正被其他应用占用"
            text.contains("NO_CAMERA", true) || text.contains("not found", true) ||
                text.contains("CameraUnavailable", true) ->
                "这台设备没有可用摄像头"
            else -> "拍照失败：$text"
        }
    }
}
