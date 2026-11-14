package com.example.monitor

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 设备概要，供列表展示。 */
data class DeviceSummary(
    val id: String,
    val name: String,
    val model: String?,
    val online: Boolean,
    val lastSeenAt: Long?,
    val lastLocation: LocationInfo?,
    val lastTelemetry: TelemetryInfo?,
    val photoCount: Int,
    /** 所属分组 ID，未分组为 null。 */
    val groupId: String? = null,
    /** 分组名称，用于列表展示。 */
    val groupName: String? = null,
    /** 分组颜色。 */
    val groupColor: String? = null,
)

/** 设备运行状态。 */
data class TelemetryInfo(
    val batteryLevel: Int?,
    val temperatureC: Double?,
    val charging: Boolean?,
    val chargingStatus: String?,
    val pluggedType: String?,
    val screenOn: Boolean?,
    val powerSaveMode: Boolean?,
    val memoryUsedPercent: Int?,
    val memoryUsedBytes: Long?,
    val memoryTotalBytes: Long?,
    val storageUsedPercent: Int?,
    val storageAvailableBytes: Long?,
    val foregroundApp: String?,
    val foregroundPackage: String?,
    val foregroundUnavailableReason: String?,
    val updatedAt: Long?,
)

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val provider: String?,
    val timestamp: Long?,
    val isApproximate: Boolean,
)

data class PhotoItem(
    val id: String,
    val source: String,
    val takenAt: Long,
    val size: Long,
    val latitude: Double?,
    val longitude: Double?,
)

/** 设备分组。 */
data class DeviceGroup(
    val id: String,
    val name: String,
    val color: String,
    val deviceCount: Int,
    val onlineCount: Int,
)

/** 构建状态。 */
data class BuildStatus(
    val running: Boolean,
    val ok: Boolean?,
    val outputName: String?,
    val outputSize: Long,
    val error: String?,
    val log: String,
)

/** 预置注册配置。 */
data class EnrollmentInfo(
    val enabled: Boolean,
    val groupKey: String,
    val maxDevices: Int,
    val serverUrl: String,
    val groupId: String?,
)

/** 一条设备日志。 */
data class LogEntry(
    val ts: Long,
    val event: String,
    val level: String,
    val message: String,
    val summary: String?,
)

/** 设备剪贴板读取结果。 */
data class ClipboardResult(
    val text: String?,
    val length: Int,
    /** 设备在后台时读不到实时内容，返回的是历史记录。 */
    val fromHistory: Boolean,
    val history: List<String>,
)

/**
 * 监控端访问服务端的客户端。
 *
 * 认证方式：**管理员账号密码登录 + Session Cookie**，与网页端一致。
 * 之前用的是 Key（Bearer token），那套接口只服务于监控端，
 * 无法复用网页端已有的分组、预置注册、编译等功能，所以统一到 Session。
 *
 * Cookie 由 OkHttp 的 CookieJar 自动管理，登录成功后后续请求自动携带。
 * 照片交给 Coil 加载时需要手动带上 Cookie。
 */
class MonitorApi(private val prefs: MonitorPrefs) {

    /** 内存 + 持久化的 Cookie 存储，保证 App 重启后登录态还在。 */
    private val cookieStore = SessionCookieStore(prefs)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .cookieJar(cookieStore)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * 登录：用账号密码换取 Session。
     *
     * @return 成功返回用户名，失败返回原因
     */
    fun login(serverUrl: String, username: String, password: String): Result<String> {
        val base = serverUrl.trimEnd('/')
        return try {
            // 先确认地址可达且确实是本服务
            val health = Request.Builder().url("$base/api/health").build()
            client.newCall(health).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(IOException("服务器返回 HTTP ${resp.code}"))
                val text = resp.body?.string().orEmpty()
                if (!JSONObject(text).optBoolean("ok")) {
                    return Result.failure(IOException("这不是设备守护服务器"))
                }
            }

            // 登录，Cookie 会被 CookieJar 自动收下
            val body = JSONObject()
                .put("username", username.trim())
                .put("password", password)
                .toString()
                .toRequestBody(jsonType)

            val req = Request.Builder().url("$base/api/admin/login").post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()

                if (!resp.isSuccessful || json?.optBoolean("ok") != true) {
                    val msg = json?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "登录失败（HTTP ${resp.code}）"
                    return Result.failure(IOException(msg))
                }

                // 登录成功后记住地址与账号，方便下次直接进
                prefs.serverUrl = base
                prefs.username = username.trim()
                Result.success(json.optString("username", username.trim()))
            }
        } catch (e: Exception) {
            Result.failure(IOException(friendly(e)))
        }
    }

    /** 退出登录，同时清掉本地 Cookie。 */
    fun logout(): Result<Unit> {
        runCatching {
            val req = Request.Builder().url(url("/api/admin/logout")).post(
                JSONObject().toString().toRequestBody(jsonType),
            ).build()
            client.newCall(req).execute().close()
        }
        cookieStore.clear()
        return Result.success(Unit)
    }

    /** 当前是否还持有登录态（Cookie 存在）。 */
    fun hasSession(): Boolean = cookieStore.hasSession()

    /** 验证当前 Session 是否仍然有效。 */
    fun checkSession(): Boolean {
        if (!hasSession()) return false
        return try {
            val (ok, _) = getJson("/api/admin/status")
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun listDevices(): Result<List<DeviceSummary>> {
        val (ok, json) = getJson("/api/admin/devices")
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "获取设备失败" }))
        val arr = json.optJSONArray("devices") ?: return Result.success(emptyList())
        val list = buildList {
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val loc = d.optJSONObject("lastLocation")
                add(
                    DeviceSummary(
                        id = d.getString("id"),
                        name = d.optString("name", "未命名"),
                        model = d.optString("model").takeIf { it.isNotBlank() },
                        online = d.optBoolean("online"),
                        lastSeenAt = if (d.has("lastSeenAt")) d.optLong("lastSeenAt") else null,
                        lastLocation = loc?.let {
                            LocationInfo(
                                latitude = it.getDouble("latitude"),
                                longitude = it.getDouble("longitude"),
                                accuracy = it.optDouble("accuracy").takeIf { v -> !v.isNaN() },
                                provider = it.optString("provider").takeIf { p -> p.isNotBlank() },
                                timestamp = it.optLong("timestamp"),
                                isApproximate = it.optBoolean("isApproximate"),
                            )
                        },
                        lastTelemetry = d.optJSONObject("lastTelemetry")?.let(::parseTelemetry),
                        photoCount = d.optInt("photoCount"),
                        groupId = d.optJSONObject("group")?.optString("id")?.takeIf { it.isNotBlank() },
                        groupName = d.optJSONObject("group")?.optString("name")?.takeIf { it.isNotBlank() },
                        groupColor = d.optJSONObject("group")?.optString("color")?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        return Result.success(list)
    }

    /** 解析服务端返回的遥测对象；字段缺失时保持 null，不臆造 0。 */
    private fun parseTelemetry(t: org.json.JSONObject): TelemetryInfo {
        val battery = t.optJSONObject("battery")
        val screen = t.optJSONObject("screen")
        val memory = t.optJSONObject("memory")
        val storage = t.optJSONObject("storage")
        val fg = t.optJSONObject("foregroundApp")

        return TelemetryInfo(
            batteryLevel = battery?.takeIf { it.has("level") }?.optInt("level"),
            temperatureC = battery?.takeIf { it.has("temperatureC") }?.optDouble("temperatureC"),
            charging = battery?.takeIf { it.has("charging") }?.optBoolean("charging"),
            chargingStatus = battery?.optString("status")?.takeIf { it.isNotBlank() },
            pluggedType = battery?.optString("pluggedType")?.takeIf { it.isNotBlank() },
            screenOn = screen?.takeIf { it.has("on") }?.optBoolean("on"),
            powerSaveMode = screen?.takeIf { it.has("powerSaveMode") }?.optBoolean("powerSaveMode"),
            memoryUsedPercent = memory?.takeIf { it.has("usedPercent") }?.optInt("usedPercent"),
            memoryUsedBytes = memory?.takeIf { it.has("usedBytes") }?.optLong("usedBytes"),
            memoryTotalBytes = memory?.takeIf { it.has("totalBytes") }?.optLong("totalBytes"),
            storageUsedPercent = storage?.takeIf { it.has("usedPercent") }?.optInt("usedPercent"),
            storageAvailableBytes = storage?.takeIf { it.has("availableBytes") }?.optLong("availableBytes"),
            foregroundApp = fg?.optString("appLabel")?.takeIf { it.isNotBlank() },
            foregroundPackage = fg?.optString("packageName")?.takeIf { it.isNotBlank() },
            foregroundUnavailableReason = if (fg != null && !fg.optBoolean("available")) {
                fg.optString("reason").takeIf { it.isNotBlank() } ?: "未授权"
            } else {
                null
            },
            updatedAt = t.optLong("updatedAt").takeIf { it > 0 },
        )
    }

    /** 主动拉取一次运行状态。 */
    fun refreshTelemetry(deviceId: String): Result<TelemetryInfo?> {
        val (ok, json) = postJson("/api/admin/devices/$deviceId/telemetry/refresh", JSONObject())
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "获取状态失败" }))
        if (!json.optBoolean("ok")) {
            return Result.failure(IOException(json.optString("message").ifBlank { "设备未响应" }))
        }
        val t = json.optJSONObject("telemetry") ?: return Result.success(null)
        return Result.success(parseTelemetry(t))
    }

    /** 读取设备日志，可按事件类型过滤。 */
    fun listLogs(deviceId: String, event: String? = null, limit: Int = 200): Result<List<LogEntry>> {
        val qs = buildString {
            append("?limit=").append(limit)
            if (!event.isNullOrBlank()) append("&event=").append(java.net.URLEncoder.encode(event, "UTF-8"))
        }
        val (ok, json) = getJson("/api/admin/devices/$deviceId/logs$qs")
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "获取日志失败" }))
        val arr = json.optJSONArray("logs") ?: return Result.success(emptyList())
        val list = buildList {
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                add(
                    LogEntry(
                        ts = e.optLong("ts"),
                        event = e.optString("event", "unknown"),
                        level = e.optString("level", "info"),
                        message = e.optString("message", ""),
                        summary = e.optJSONObject("detail")?.optString("summary")?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        return Result.success(list)
    }

    /** 生成配对码，供被监控端输入。 */
    fun createPairingCode(): Result<Pair<String, Long>> {
        val (ok, json) = postJson("/api/admin/pairing/code", JSONObject())
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "生成配对码失败" }))
        val code = json.optString("code")
        if (code.isBlank()) return Result.failure(IOException("服务器没有返回配对码"))
        return Result.success(code to json.optLong("expiresAt"))
    }

    /** 让设备立刻定位一次。 */
    fun locate(deviceId: String): Result<LocationInfo?> {
        val (ok, json) = postJson("/api/admin/devices/$deviceId/locate", JSONObject())
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "定位失败" }))
        if (!json.optBoolean("ok")) {
            return Result.failure(IOException(json.optString("message").ifBlank { "设备未响应" }))
        }
        val loc = json.optJSONObject("location") ?: return Result.success(null)
        return Result.success(
            LocationInfo(
                latitude = loc.getDouble("latitude"),
                longitude = loc.getDouble("longitude"),
                accuracy = loc.optDouble("accuracy").takeIf { v -> !v.isNaN() },
                provider = loc.optString("provider").takeIf { it.isNotBlank() },
                timestamp = loc.optLong("timestamp"),
                isApproximate = loc.optBoolean("isApproximate"),
            ),
        )
    }

    /** 远程拍照，返回 photoId。 */
    fun capture(deviceId: String, lens: String): Result<String> {
        val (ok, json) = postJson("/api/admin/devices/$deviceId/capture", JSONObject().put("lens", lens))
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "拍照失败" }))
        if (!json.optBoolean("ok")) {
            return Result.failure(IOException(json.optString("message").ifBlank { "设备执行失败" }))
        }
        val photoId = json.optJSONObject("data")?.optString("photoId").orEmpty()
        if (photoId.isBlank()) return Result.failure(IOException("设备没有回传照片"))
        return Result.success(photoId)
    }

    /** 让设备同步相册。 */
    fun syncAlbum(deviceId: String, limit: Int = 20): Result<Int> {
        val (ok, json) = postJson(
            "/api/admin/devices/$deviceId/sync-album",
            JSONObject().put("limit", limit),
        )
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "同步失败" }))
        if (!json.optBoolean("ok")) {
            return Result.failure(IOException(json.optString("message").ifBlank { "设备执行失败" }))
        }
        return Result.success(json.optInt("synced"))
    }

    fun listPhotos(deviceId: String): Result<List<PhotoItem>> {
        val (ok, json) = getJson("/api/admin/devices/$deviceId/photos")
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "获取照片失败" }))
        val arr = json.optJSONArray("photos") ?: return Result.success(emptyList())
        val list = buildList {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                add(
                    PhotoItem(
                        id = p.getString("id"),
                        source = p.optString("source", "gallery"),
                        takenAt = p.optLong("takenAt"),
                        size = p.optLong("size"),
                        latitude = if (p.isNull("latitude")) null else p.optDouble("latitude"),
                        longitude = if (p.isNull("longitude")) null else p.optDouble("longitude"),
                    ),
                )
            }
        }
        return Result.success(list)
    }

    /** 解绑设备。 */
    fun deleteDevice(deviceId: String): Result<Unit> {
        return try {
            // Cookie 由 CookieJar 自动附加
            val req = Request.Builder()
                .url(url("/api/admin/devices/$deviceId"))
                .delete()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("解绑失败 HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(IOException(friendly(e)))
        }
    }

    /** 读取设备剪贴板。 */
    fun readClipboard(deviceId: String): Result<ClipboardResult> {
        val (ok, json) = postJson("/api/admin/devices/$deviceId/clipboard", JSONObject())
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "读取失败" }))
        if (!json.optBoolean("ok")) {
            return Result.failure(IOException(json.optString("error").ifBlank { "设备未响应" }))
        }
        val history = json.optJSONArray("history")?.let { arr ->
            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } ?: emptyList()

        return Result.success(
            ClipboardResult(
                text = json.optString("text").takeIf { it.isNotBlank() },
                length = json.optInt("length"),
                fromHistory = json.optBoolean("fromHistory"),
                history = history,
            ),
        )
    }

    /** 请求设备补充权限（登记到服务端，设备下次进前台时弹窗）。 */
    fun requestPermissions(deviceId: String, keys: List<String>): Result<String> {
        val body = JSONObject().put("keys", org.json.JSONArray(keys))
        val (ok, json) = postJson("/api/admin/devices/$deviceId/request-permissions", body)
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "请求失败" }))
        if (!json.optBoolean("ok")) {
            return Result.failure(IOException(json.optString("message").ifBlank { "设备未响应" }))
        }
        return Result.success(json.optString("message", "已登记"))
    }

    /** 让设备打开管理页（服务端下发 open_admin 指令）。 */
    fun openAdmin(deviceId: String): Result<String> {
        val (ok, json) = postJson("/api/admin/devices/$deviceId/open-admin", JSONObject())
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "发送失败" }))
        if (!json.optBoolean("ok")) {
            return Result.failure(IOException(json.optString("message").ifBlank { "设备未响应" }))
        }
        return Result.success(json.optString("message", "已通知设备打开管理页"))
    }

    /** 列出所有分组。 */
    fun listGroups(): Result<List<DeviceGroup>> {
        val (ok, json) = getJson("/api/admin/groups")
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "获取分组失败" }))
        val arr = json.optJSONArray("groups") ?: return Result.success(emptyList())
        return Result.success(
            buildList {
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONObject(i)
                    add(
                        DeviceGroup(
                            id = g.optString("id"),
                            name = g.optString("name"),
                            color = g.optString("color", "#4f46e5"),
                            deviceCount = g.optInt("deviceCount"),
                            onlineCount = g.optInt("onlineCount"),
                        ),
                    )
                }
            },
        )
    }

    /** 创建设备分组。 */
    fun createGroup(name: String): Result<Unit> {
        val (ok, json) = postJson("/api/admin/groups", JSONObject().put("name", name))
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "创建失败" }))
        return Result.success(Unit)
    }

    /** 把设备分配到分组（groupId 为 null 表示移出）。 */
    fun assignGroup(deviceId: String, groupId: String?): Result<Unit> {
        val body = JSONObject().apply {
            if (groupId == null) put("groupId", JSONObject.NULL) else put("groupId", groupId)
        }
        val (ok, json) = postJson("/api/admin/devices/$deviceId/group", body)
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "分组失败" }))
        return Result.success(Unit)
    }

    /** 读取预置注册配置。 */
    fun getEnrollment(): Result<EnrollmentInfo> {
        val (ok, json) = getJson("/api/admin/enrollment")
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "获取配置失败" }))
        val e = json.optJSONObject("enrollment") ?: return Result.failure(IOException("服务器未返回配置"))
        return Result.success(
            EnrollmentInfo(
                enabled = e.optBoolean("enabled"),
                groupKey = e.optString("groupKey"),
                maxDevices = e.optInt("maxDevices"),
                serverUrl = e.optString("serverUrl"),
                groupId = e.optString("groupId").takeIf { it.isNotBlank() },
            ),
        )
    }

    /** 保存预置注册配置。 */
    fun saveEnrollment(
        enabled: Boolean,
        groupKey: String,
        serverUrl: String,
        maxDevices: Int,
        groupId: String?,
    ): Result<Unit> {
        val body = JSONObject()
            .put("enabled", enabled)
            .put("groupKey", groupKey)
            .put("serverUrl", serverUrl)
            .put("maxDevices", maxDevices)
            .put("groupId", groupId ?: JSONObject.NULL)
        val (ok, json) = postJson("/api/admin/enrollment", body)
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "保存失败" }))
        return Result.success(Unit)
    }

    /** 生成新的组密钥。 */
    fun rotateGroupKey(): Result<String> {
        val (ok, json) = postJson("/api/admin/enrollment/rotate", JSONObject())
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "生成失败" }))
        val key = json.optString("groupKey")
        if (key.isBlank()) return Result.failure(IOException("服务器没有返回组密钥"))
        return Result.success(key)
    }

    /** 启动编译。 */
    fun startBuild(module: String, enroll: Boolean): Result<Unit> {
        val body = JSONObject().put("module", module).put("enroll", enroll)
        val (ok, json) = postJson("/api/admin/build", body)
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "编译请求被拒绝" }))
        return Result.success(Unit)
    }

    /** 查询编译状态。 */
    fun buildStatus(): Result<BuildStatus> {
        val (ok, json) = getJson("/api/admin/build/status")
        if (!ok) return Result.failure(IOException(json.optString("message").ifBlank { "获取状态失败" }))
        val s = json.optJSONObject("status") ?: json
        val out = s.optJSONObject("output")
        return Result.success(
            BuildStatus(
                running = s.optBoolean("running"),
                ok = if (s.isNull("ok")) null else s.optBoolean("ok"),
                outputName = out?.optString("name")?.takeIf { it.isNotBlank() },
                outputSize = out?.optLong("size") ?: 0L,
                error = s.optString("error").takeIf { it.isNotBlank() },
                log = s.optString("log"),
            ),
        )
    }

    /** 照片的完整地址与鉴权头，交给 Coil 直接加载。 */
    fun photoUrl(deviceId: String, photoId: String) =
        "${prefs.serverUrl}/api/devices/$deviceId/photos/$photoId"

    /**
     * 给 Coil 加载照片用的 Cookie 头。
     *
     * 照片请求不走 JSON 通道，需要手动带 Cookie 才能通过鉴权。
     */
    fun authHeader(): String = cookieStore.cookieHeader()

    // ---------- 内部工具 ----------

    private fun url(path: String) = prefs.serverUrl.trimEnd('/') + path

    private fun getJson(path: String): Pair<Boolean, JSONObject> = try {
        // Cookie 由 CookieJar 自动附加，不需要手动设置 Authorization
        val req = Request.Builder().url(url(path)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            (resp.isSuccessful && json.optBoolean("ok", true)) to json
        }
    } catch (e: Exception) {
        false to JSONObject().put("message", friendly(e))
    }

    private fun postJson(path: String, body: JSONObject): Pair<Boolean, JSONObject> = try {
        val req = Request.Builder()
            .url(url(path))
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            (resp.isSuccessful && json.optBoolean("ok", true)) to json
        }
    } catch (e: Exception) {
        false to JSONObject().put("message", friendly(e))
    }

    private fun deleteJson(path: String): Pair<Boolean, JSONObject> = try {
        val req = Request.Builder().url(url(path)).delete().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            (resp.isSuccessful && json.optBoolean("ok", true)) to json
        }
    } catch (e: Exception) {
        false to JSONObject().put("message", friendly(e))
    }

    /** 把网络异常翻译成用户能采取行动的话。 */
    private fun friendly(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("Unable to resolve host", true) || msg.contains("UnknownHost", true) ->
                "解析不到服务器域名，请检查地址"
            msg.contains("timeout", true) -> "连接服务器超时，请检查网络"
            msg.contains("Failed to connect", true) || msg.contains("Connection refused", true) ->
                "连不上服务器，请检查地址和端口"
            msg.contains("SSL", true) || msg.contains("certificate", true) ->
                "HTTPS 证书校验失败"
            else -> "网络错误：$msg"
        }
    }
}
