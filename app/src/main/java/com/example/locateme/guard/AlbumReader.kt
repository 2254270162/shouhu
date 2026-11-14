package com.example.locateme.guard

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/** 相册里的一条记录。 */
data class AlbumItem(
    val uri: Uri,
    val photoId: String,
    val takenAt: Long,
    val latitude: Double?,
    val longitude: Double?,
)

/**
 * 读取相册最近的照片。
 *
 * 用 MediaStore 查询而不申请 READ_MEDIA_IMAGES：在 Android 13+ 上，
 * 应用只能看到自己创建的媒体文件，除非用户显式授权。
 * 所以这里检索不到时返回空列表，由界面提示用户去授权，
 * 而不是假装"相册是空的"。
 */
object AlbumReader {

    /** 是否已获得读取图片的权限（Android 13+ 是 READ_MEDIA_IMAGES）。 */
    fun hasImagePermission(context: Context): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun recentPhotos(context: Context, limit: Int): List<AlbumItem> {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val items = mutableListOf<AlbumItem>()

        runCatching {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext() && items.size < limit) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val taken = cursor.getLong(takenCol).takeIf { it > 0 }
                        ?: (cursor.getLong(addedCol) * 1000)
                    val (lat, lon) = readGps(context, uri)
                    items += AlbumItem(
                        uri = uri,
                        photoId = "gal_$id",
                        takenAt = taken,
                        latitude = lat,
                        longitude = lon,
                    )
                }
            }
        }

        return items
    }

    /** 读一张图片的 GPS；没有则返回 (null, null)。 */
    private fun readGps(context: Context, uri: Uri): Pair<Double?, Double?> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
            val lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
            if (lat == null || lon == null) return@use null to null
            parseDms(lat, exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) == "S") to
                parseDms(lon, exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) == "W")
        } ?: (null to null)
    }.getOrDefault(null to null)

    private fun parseDms(raw: String, negative: Boolean): Double {
        val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size < 2) return 0.0
        val value = parts[0] + parts.getOrElse(1) { 0.0 } / 60.0 + parts.getOrElse(2) { 0.0 } / 3600.0
        return if (negative) -value else value
    }
}
