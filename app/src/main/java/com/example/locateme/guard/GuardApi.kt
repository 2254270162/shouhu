package com.example.locateme.guard

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 服务端 HTTP 客户端（设备侧）。
 *
 * 不用 OkHttp 是为了让被监控端依赖更少、安装包更小；
 * 这里只有几个固定接口，用 HttpURLConnection 足够。
 */
class GuardApi(private val prefs: GuardPrefs) {

    /**
     * 用组密钥自动注册（免配对码）。
     *
     * 适用场景：APK 编译时预置了服务器地址和组密钥，
     * 几十台设备装完打开就能用，不用逐台填配对码。
     *
     * 每台设备仍然拿到独立令牌；同一台设备重装后
     * （installId 不变）会复用原记录，不会产生重复设备。
     */
    fun enroll(serverUrl: String, groupKey: String, deviceName: String, installId: String): ClaimResult {
        val body = JSONObject()
            .put("groupKey", groupKey)
            .put("deviceName", deviceName)
            .put("installId", installId)
            .put("model", android.os.Build.MODEL ?: "Android")
            .put("appVersion", com.example.locateme.BuildConfig.VERSION_NAME)

        val url = URL("$serverUrl/api/enroll")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299 && json?.optBoolean("ok") == true) {
                ClaimResult.Success(
                    deviceId = json.getString("deviceId"),
                    deviceToken = json.getString("deviceToken"),
                )
            } else {
                ClaimResult.Failure(json?.optString("message")?.ifBlank { null } ?: "注册失败（HTTP $code）")
            }
        } catch (e: Exception) {
            ClaimResult.Failure("注册失败：${e.message}")
        }
    }

    /** 用配对码换取设备令牌。 */
    fun claim(code: String, deviceName: String, model: String, appVersion: String): ClaimResult {
        val body = JSONObject()
            .put("code", code.trim())
            .put("deviceName", deviceName)
            .put("model", model)
            .put("appVersion", appVersion)
        val (status, text) = postJson("/api/pairing/claim", body, null, null)
        return runCatching {
            val json = JSONObject(text)
            if (status in 200..299 && json.optBoolean("ok")) {
                ClaimResult.Success(
                    deviceId = json.getString("deviceId"),
                    deviceToken = json.getString("deviceToken"),
                )
            } else {
                ClaimResult.Failure(json.optString("message").ifBlank { "配对失败（HTTP $status）" })
            }
        }.getOrElse { ClaimResult.Failure("服务器返回异常（HTTP $status）") }
    }

    /** 上报位置；WS 断开时的兜底通道。 */
    fun reportLocation(location: JSONObject): Boolean {
        val deviceId = prefs.deviceId ?: return false
        val token = prefs.deviceToken ?: return false
        val body = JSONObject().put("location", location)
        val (status, _) = postJson("/api/devices/$deviceId/location", body, token, null)
        return status in 200..299
    }

    /** 上报运行状态；WS 断开时的兜底通道。 */
    fun reportTelemetry(telemetry: JSONObject): Boolean {
        val deviceId = prefs.deviceId ?: return false
        val token = prefs.deviceToken ?: return false
        val body = JSONObject().put("telemetry", telemetry)
        val (status, _) = postJson("/api/devices/$deviceId/telemetry", body, token, null)
        return status in 200..299
    }

    /**
     * 上传一张照片。
     *
     * 关键：图片二进制直接作为请求体，不做 base64。
     * base64 会让体积膨胀约 33%，一张 3MB 的照片要多传 1MB。
     * 元数据（来源、坐标、时间）放 query，保持 body 是纯字节。
     */
    fun uploadPhoto(
        photoId: String,
        bytes: ByteArray,
        source: String,
        latitude: Double?,
        longitude: Double?,
        takenAt: Long,
    ): UploadResult {
        val deviceId = prefs.deviceId
        val token = prefs.deviceToken
        if (deviceId == null || token == null) return UploadResult.Failure("尚未配对")

        val query = buildString {
            append("?photoId=").append(enc(photoId))
            append("&source=").append(enc(source))
            append("&takenAt=").append(takenAt)
            if (latitude != null) append("&lat=").append(latitude)
            if (longitude != null) append("&lon=").append(longitude)
        }
        return try {
            val conn = (URL(prefs.serverUrl + "/api/devices/$deviceId/photos$query").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    doOutput = true
                    setFixedLengthStreamingMode(bytes.size)
                    setRequestProperty("Content-Type", "image/jpeg")
                    setRequestProperty("X-Device-Token", token)
                }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) UploadResult.Success(photoId)
            else UploadResult.Failure("上传失败 HTTP $code")
        } catch (e: Exception) {
            UploadResult.Failure("上传异常：${e.message}")
        }
    }

    /** 设备主动解绑：请求服务端删除自己的记录。 */
    fun unpairSelf(deviceId: String, token: String): Boolean {
        return try {
            val conn = (URL(prefs.serverUrl + "/api/devices/$deviceId/self").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("X-Device-Token", token)
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write("{}".toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /** 拉取服务端下发的运行配置。失败时返回 null，由调用方沿用本地默认值。 */
    fun fetchConfig(): org.json.JSONObject? {
        val deviceId = prefs.deviceId ?: return null
        val token = prefs.deviceToken ?: return null
        return try {
            val conn = (URL(prefs.serverUrl + "/api/devices/$deviceId/config").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("X-Device-Token", token)
            }
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            conn.disconnect()
            if (text.isBlank()) return null
            val json = JSONObject(text)
            if (json.optBoolean("ok")) json.optJSONObject("config") else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 拉取服务端登记的待处理权限请求。
     *
     * 返回要请求的权限 key 列表；没有待办返回空列表。
     * 取走后服务端会标记为已消费，不会重复弹。
     */
    fun fetchPendingPermissionRequest(): List<String> {
        val deviceId = prefs.deviceId ?: return emptyList()
        val token = prefs.deviceToken ?: return emptyList()
        return try {
            val conn = (URL(prefs.serverUrl + "/api/devices/$deviceId/pending-permission").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("X-Device-Token", token)
            }
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            conn.disconnect()
            if (text.isBlank()) return emptyList()
            val json = JSONObject(text)
            val pending = json.optJSONObject("pending") ?: return emptyList()
            val arr = pending.optJSONArray("keys") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 健康检查：配对前先确认服务器地址填对了。 */
    fun health(serverUrl: String): Boolean = try {
        val conn = (URL("$serverUrl/api/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (e: Exception) {
        false
    }

    private fun postJson(path: String, body: JSONObject, deviceToken: String?, key: String?): Pair<Int, String> {
        val conn = (URL(prefs.serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            deviceToken?.let { setRequestProperty("X-Device-Token", it) }
            key?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            code to text
        } catch (e: IOException) {
            conn.disconnect()
            -1 to ""
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    sealed interface ClaimResult {
        data class Success(val deviceId: String, val deviceToken: String) : ClaimResult
        data class Failure(val message: String) : ClaimResult
    }

    sealed interface UploadResult {
        data class Success(val photoId: String) : UploadResult
        data class Failure(val message: String) : UploadResult
    }

    companion object {
        /** WebSocket 地址由 HTTP 地址推导，省得用户填两个。 */
        fun wsUrl(serverUrl: String): String = ServerAddress.toWebSocket(serverUrl)

        fun timeoutSeconds(seconds: Long) = TimeUnit.SECONDS.toMillis(seconds)
    }
}
