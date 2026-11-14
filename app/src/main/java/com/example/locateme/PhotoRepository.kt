package com.example.locateme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一张照片的来源。 */
enum class PhotoSource { CAMERA, GALLERY }

/** 照片及其附带信息。 */
data class PhotoInfo(
    val uri: Uri,
    val source: PhotoSource,
    /** 照片 EXIF 里的经纬度；没有则为 null。 */
    val exifLatitude: Double? = null,
    val exifLongitude: Double? = null,
    /** 照片拍摄时间（毫秒），取自 EXIF；没有则 null。 */
    val takenAt: Long? = null,
    /** 缩略图，供界面预览；解码失败时为 null。 */
    val thumbnail: Bitmap? = null,
    /** 已把当前定位写入照片 EXIF 时为 true。 */
    val taggedWithLocation: Boolean = false,
)

/**
 * 照片存取。
 *
 * 关键点：
 * 1. **不申请存储权限**：相机写入应用私有目录，相册走系统 SAF 选择器，
 *    在 Android 13+ 上也不需要 READ_MEDIA_IMAGES。
 * 2. 解码按上限缩放并校正 EXIF 旋转，避免大图 OOM 和照片显示歪。
 */
class PhotoRepository(private val context: Context) {

    private val authority = "${context.packageName}.fileprovider"

    fun createCaptureFile(): File {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        return File.createTempFile("capture_", ".jpg", dir)
    }

    fun uriForFile(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    /**
     * 让 CameraX 把照片写到临时文件。
     * 用 suspend 包装是为了让 ViewModel 能以顺序代码等待拍摄结果。
     */
    suspend fun capture(imageCapture: ImageCapture): File =
        suspendCancellableCoroutine { cont ->
            val file = createCaptureFile()
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                ContextCompatExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                },
            )
            cont.invokeOnCancellation { file.delete() }
        }

    /** 读取照片：EXIF 位置、拍摄时间、缩略图。 */
    suspend fun readPhoto(uri: Uri, source: PhotoSource): PhotoInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val exif = runCatching { readExif(uri) }.getOrNull()
        PhotoInfo(
            uri = uri,
            source = source,
            exifLatitude = exif?.latitude,
            exifLongitude = exif?.longitude,
            takenAt = exif?.takenAt,
            thumbnail = decodeThumbnail(uri),
        )
    }

    /**
     * 把当前坐标写进照片 EXIF（GPS 经纬度 + 海拔 + 时间）。
     * 只有应用自己目录下的文件能改；相册返回的只读 Uri 会返回 false。
     */
    suspend fun tagWithLocation(uri: Uri, snapshot: LocationSnapshot): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = fileForUri(uri) ?: return@runCatching false
                if (!file.canWrite()) return@runCatching false
                val exif = ExifInterface(file.absolutePath)
                writeGps(exif, snapshot)
                exif.saveAttributes()
                true
            }.getOrDefault(false)
        }

    private fun writeGps(exif: ExifInterface, snapshot: LocationSnapshot) {
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(snapshot.latitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (snapshot.latitude >= 0) "N" else "S")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(snapshot.longitude))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (snapshot.longitude >= 0) "E" else "W")
        snapshot.altitudeMeters?.let {
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${it}/1")
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
        }
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDate())
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTime())
        // 用户备注里记录精度，读图时能判断可信程度
        exif.setAttribute(
            ExifInterface.TAG_USER_COMMENT,
            "LocateMe accuracy=±${snapshot.accuracyMeters.toInt()}m provider=${snapshot.provider}",
        )
    }

    /** 十进制度 → EXIF 要求的度/分/秒字符串，如 "30/1,17/1,1234/10000"。 */
    private fun toDms(decimal: Double): String {
        val abs = kotlin.math.abs(decimal)
        val degrees = abs.toInt()
        val minutesFull = (abs - degrees) * 60
        val minutes = minutesFull.toInt()
        val seconds = ((minutesFull - minutes) * 60 * 10000).toInt()
        return "$degrees/1,$minutes/1,$seconds/10000"
    }

    private fun gpsDate(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            java.util.Locale.US,
            "%04d:%02d:%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    private fun gpsTime(): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return String.format(
            java.util.Locale.US,
            "%02d/1,%02d/1,%02d/1",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    private fun readExif(uri: Uri): ExifData {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            return ExifData(
                latitude = readGps(exif, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, "S"),
                longitude = readGps(exif, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, "W"),
                takenAt = exif.dateTimeOriginal,
            )
        } ?: throw IOException("无法打开图片")
    }

    /**
     * 手动解析 EXIF 的"度/分/秒"字符串。
     * 不用 ExifInterface.latLong 是因为它在新版本才有，手动解析兼容性更好。
     */
    private fun readGps(exif: ExifInterface, valueTag: String, refTag: String, negativeRef: String): Double? {
        val raw = exif.getAttribute(valueTag) ?: return null
        val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size < 2) return null
        val degrees = parts[0]
        val minutes = parts.getOrElse(1) { 0.0 }
        val seconds = parts.getOrElse(2) { 0.0 }
        val decimal = degrees + minutes / 60.0 + seconds / 3600.0
        val ref = exif.getAttribute(refTag)
        return if (ref.equals(negativeRef, ignoreCase = true)) -decimal else decimal
    }

    /** 解码缩略图，最长边限制在 1024px，并校正 EXIF 旋转。 */
    private fun decodeThumbnail(uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_THUMB && bounds.outHeight / (sample * 2) >= MAX_THUMB) {
            sample *= 2
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = rotationOf(uri)
        if (rotation == 0f) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            android.graphics.Matrix().apply { postRotate(rotation) }, true,
        )
    }.getOrNull()

    private fun rotationOf(uri: Uri): Float = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)

    /** 把 ImageProxy 转成 Bitmap，用于不需要落盘的场景（当前拍照走后存盘，保留备用）。 */
    fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? = runCatching {
        val buffer: ByteBuffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** 只有自己缓存目录里的文件才能直接按路径改 EXIF。 */
    private fun fileForUri(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        if (uri.authority != authority) return null
        // content://<authority>/photos/capture_xxx.jpg -> cacheDir/photos/capture_xxx.jpg
        val relative = uri.path?.trimStart('/') ?: return null
        val file = File(context.cacheDir, relative)
        return if (file.exists()) file else null
    }

    private data class ExifData(
        val latitude: Double?,
        val longitude: Double?,
        val takenAt: Long?,
    )

    private companion object {
        const val MAX_THUMB = 1024
    }
}

/** 把 Executor 包一层，避免 CameraX 在调用线程要求上的兼容问题。 */
private class ContextCompatExecutor(private val context: Context) : java.util.concurrent.Executor {
    private val main = android.os.Handler(context.mainLooper)
    override fun execute(command: Runnable) {
        main.post(command)
    }
}
