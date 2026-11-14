package com.example.locateme

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** 一次定位过程中的进度状态，供界面展示。 */
sealed interface LocateProgress {
    data object Idle : LocateProgress
    /** 正在等待系统返回坐标；[source] 例如"GPS"、"网络"。 */
    data class Searching(val source: String) : LocateProgress
    /** 已拿到坐标，正在解析中文地址。 */
    data object ResolvingAddress : LocateProgress
}

/** 逆地理编码结果。 */
data class AddressInfo(
    val text: String?,
    val provider: String,
)

/**
 * 定位能力的唯一入口。
 *
 * 设计要点：
 * 1. 优先用 `getCurrentLocation`（缓存优先、功耗低），失败再退回一次性的 `requestSingleUpdate`；
 * 2. 只要用户授予了"大致位置"（COARSE）也能工作，此时系统只会返回模糊坐标；
 * 3. 支持"只授予大致位置"的降级路径，避免直接抛出 SecurityException；
 * 4. 坐标转地址是可选能力，失败不影响经纬度展示。
 */
class LocationRepository(private val context: Context) {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasLocationPermission(): Boolean = grantedPrecision() != Precision.NONE

    /** -1 表示完全未授权；0 表示仅大致位置；1 表示精确位置。 */
    fun grantedPrecision(): Precision {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) return Precision.FINE
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (coarse) Precision.COARSE else Precision.NONE
    }

    fun isLocationEnabled(): Boolean {
        val m = manager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            m.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            (m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        }
    }

    /**
     * 获取一次定位。优先使用高精度（GPS + 网络融合），
     * 若高精度在 [highAccuracyTimeoutMs] 内没结果，自动降级为任意可用定位源。
     */
    suspend fun locate(
        onProgress: (LocateProgress) -> Unit = {},
    ): Result<Location> {
        val m = manager ?: return Result.failure(IllegalStateException("此设备不支持定位服务"))
        val precision = grantedPrecision()
        if (precision == Precision.NONE) {
            return Result.failure(SecurityException("尚未授予定位权限"))
        }
        if (!isLocationEnabled()) {
            return Result.failure(IllegalStateException("系统定位服务已关闭，请在设置中开启"))
        }

        onProgress(LocateProgress.Searching("GPS / 网络定位"))
        val highAccuracy = withTimeoutOrNull(highAccuracyTimeoutMs) {
            requestOnce(m, LocationManager.GPS_PROVIDER).getOrNull()
        }
        if (highAccuracy != null) return Result.success(highAccuracy)

        onProgress(LocateProgress.Searching("网络定位"))
        val fallback = withTimeoutOrNull(highAccuracyTimeoutMs / 2) {
            requestOnce(m, LocationManager.NETWORK_PROVIDER).getOrNull()
        } ?: lastKnown(m)

        return if (fallback != null) {
            Result.success(fallback)
        } else {
            Result.failure(IllegalStateException("暂时拿不到位置，请到开阔处或检查定位开关"))
        }
    }

    /** 用 getCurrentLocation 取一次；失败时退回 requestSingleUpdate。 */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(m: LocationManager, provider: String): Result<Location> {
        // 注意：LocationManager.allProviders 是 API 31 才有的属性，低版本只能用 getProviders(false)
        val available = runCatching { m.getProviders(false).contains(provider) }.getOrDefault(false)
        if (!available) {
            return Result.failure(IllegalStateException("$provider 不可用"))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val viaCurrent = suspendCancellableCoroutine<Result<Location>> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    m.getCurrentLocation(
                        provider,
                        signal,
                        context.mainExecutor,
                    ) { location ->
                        if (cont.isActive) cont.resume(Result.success(location))
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }
            }
            if (viaCurrent.isSuccess) return viaCurrent
        }

        return requestSingleUpdate(m, provider)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(m: LocationManager, provider: String): Result<Location> {
        val timeoutMs = 12_000L
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<Location>> { cont ->
                // 一次性回调后必须注销监听，否则会一直持有 Activity 上下文
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { m.removeUpdates(this) }
                        if (cont.isActive) cont.resume(Result.success(location))
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) {
                        runCatching { m.removeUpdates(this) }
                        if (cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException("$provider 已关闭")))
                        }
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { m.removeUpdates(listener) }
                }
                try {
                    @Suppress("DEPRECATION")
                    m.requestSingleUpdate(provider, listener, context.mainLooper)
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }
            }
        } ?: Result.failure(IllegalStateException("$provider 超时无响应"))
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(m: LocationManager): Location? {
        val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { m.getLastKnownLocation(provider) }.getOrNull() }
        // 只接受 10 分钟内的缓存，避免显示多年前的位置
        val fresh = candidates.filter { System.currentTimeMillis() - it.time < 10 * 60 * 1000 }
        return (fresh.ifEmpty { candidates }).maxByOrNull { it.time }
    }

    /**
     * 坐标 → 中文地址。
     *
     * 按顺序尝试多个服务，任一成功即返回：
     * 1. 高德 Web 服务（若在 local.properties 配置了 AMAP_KEY，国内最准、最全）；
     * 2. BigDataCloud 免 Key 接口（国内可直连，返回中文行政区名）；
     * 3. OpenStreetMap Nominatim（国外准确，国内部分网络环境会被 DNS 污染）。
     *
     * 全部失败返回 null，由界面提示；经纬度展示不受影响。
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): AddressInfo? = withContext(Dispatchers.IO) {
        for (provider in reverseGeocodeProviders) {
            val info = runCatching { provider(lat, lon) }.getOrNull()
            if (info != null) return@withContext info
        }
        null
    }

    /** 免 Key 服务：BigDataCloud，实测国内网络可直连且支持中文。 */
    private fun bigDataCloud(lat: Double, lon: Double): AddressInfo? {
        val url = String.format(
            Locale.US,
            "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.6f&longitude=%.6f&localityLanguage=zh",
            lat,
            lon,
        )
        val json = JSONObject(httpGet(url) ?: return null)
        // 从大到小拼接：省 + 市 + 区县（同名去重，避免"湖北省 湖北省"）
        val parts = listOf(
            json.optString("principalSubdivision"),
            json.optString("city"),
            json.optString("locality"),
        ).filter { it.isNotBlank() }.distinct()
        if (parts.isEmpty()) return null
        return AddressInfo(text = parts.joinToString(" "), provider = "BigDataCloud")
    }

    /** OpenStreetMap Nominatim，免 Key 但对网络环境敏感。 */
    private fun nominatim(lat: Double, lon: Double): AddressInfo? {
        val url = String.format(
            Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=zh-CN&lat=%.6f&lon=%.6f",
            lat,
            lon,
        )
        val json = JSONObject(httpGet(url) ?: return null)
        val display = json.optString("display_name").takeIf { it.isNotBlank() }
        if (display == null) return null
        val addr = json.optJSONObject("address")
        val readable = if (addr != null) {
            val parts = listOf(
                addr.optString("state"),
                addr.optString("city"),
                addr.optString("city_district"),
                addr.optString("suburb"),
                addr.optString("road"),
                addr.optString("house_number"),
            ).filter { it.isNotBlank() }.distinct()
            // 中文地址从大到小拼接，去重后比 display_name 更符合阅读习惯
            parts.joinToString("").ifBlank { display }
        } else {
            display
        }
        return AddressInfo(text = readable, provider = "OpenStreetMap")
    }

    /** 高德 Web 服务；只有配置了 Key 才会启用。 */
    private fun amap(lat: Double, lon: Double): AddressInfo? {
        val key = BuildConfig.AMAP_KEY
        if (key.isBlank()) return null
        val url = String.format(
            Locale.US,
            "https://restapi.amap.com/v3/geocode/regeo?key=%s&location=%.6f,%.6f&extensions=base",
            key,
            lon,
            lat, // 高德用"经度,纬度"顺序，写反会跑到海里
        )
        val json = JSONObject(httpGet(url) ?: return null)
        if (json.optString("status") != "1") return null
        val regeo = json.optJSONObject("regeocode") ?: return null
        val formatted = regeo.optString("formatted_address").takeIf { it.isNotBlank() } ?: return null
        return AddressInfo(text = formatted, provider = "高德地图")
    }

    private fun httpGet(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "LocateMeApp/1.0 (android sample)")
            setRequestProperty("Accept-Language", "zh-CN")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private val reverseGeocodeProviders: List<(Double, Double) -> AddressInfo?> =
        listOf(::amap, ::bigDataCloud, ::nominatim) // 高德 Key 未配置时会自动跳过

    companion object {
        /** 高精度定位的等待上限；超时后降级，避免用户干等。 */
        private const val highAccuracyTimeoutMs = 15_000L
    }
}

/** 权限精度等级。 */
enum class Precision { NONE, COARSE, FINE }
