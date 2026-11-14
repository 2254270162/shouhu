package com.example.locateme.guard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 被监控端的配对与连接设置。
 *
 * 存在 MODE_PRIVATE 的 SharedPreferences 里；配对令牌只在本机保存，
 * 用户解绑时整份清空。
 */
class GuardPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("guard_settings", Context.MODE_PRIVATE)

    /**
     * 服务器地址。
     *
     * 默认空字符串，由用户在界面上填写局域网地址（如 http://192.168.1.100:8080）。
     * 不内置任何域名：这个是内网部署的工具，写死公网地址既不适用，
     * 也会把开发用的服务器暴露给每个安装包。
     */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SERVER, ServerAddress.normalize(value)) }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_TOKEN, value) }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, defaultName()) ?: defaultName()
        set(value) = prefs.edit { putString(KEY_DEVICE_NAME, value) }

    /** 用户是否已同意开启守护（显式开关，关闭后立刻停止上报与建连） */
    var guardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 是否允许监控端远程触发拍照/相册同步；关掉后设备只上报位置 */
    var allowRemoteCapture: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_CAPTURE, true)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_CAPTURE, value) }

    /** 最近一次上报位置的时间，界面展示用 */
    var lastReportAt: Long
        get() = prefs.getLong(KEY_LAST_REPORT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_REPORT, value) }

    /**
     * 后台运行模式，由服务端下发。
     * foreground / hybrid / background，默认 hybrid。
     */
    var backgroundMode: String
        get() = prefs.getString(KEY_BG_MODE, "hybrid") ?: "hybrid"
        set(value) = prefs.edit { putString(KEY_BG_MODE, value) }

    /** 上报间隔（秒），由服务端下发。 */
    var reportIntervalSec: Int
        get() = prefs.getInt(KEY_INTERVAL, 300)
        set(value) = prefs.edit { putInt(KEY_INTERVAL, value) }

    /** 设备安装标识，重装后保持不变，用于自动注册时复用设备记录。 */
    var installId: String?
        get() = prefs.getString(KEY_INSTALL_ID, null)
        set(value) = prefs.edit { putString(KEY_INSTALL_ID, value) }

    /**
     * 操作页地址的本地缓存。
     *
     * 为什么要缓存：这个值来自服务端配置，正常情况下要等网络请求返回。
     * 不缓存的话，每次冷启动都会先显示"设备已就绪"文字页，
     * 等配置回来再跳成网页——用户看到的是界面闪一下。
     *
     * 缓存后启动即可用上次的地址，网络回来再更新（地址变了才刷新 WebView）。
     */
    var operationUrl: String
        get() = prefs.getString(KEY_OPERATION_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_OPERATION_URL, value) }

    /** 上次成功显示过的地址，用于判断地址是否真的变了。 */
    var lastLoadedUrl: String
        get() = prefs.getString(KEY_LAST_LOADED_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_LOADED_URL, value) }

    val isPaired: Boolean
        get() = !deviceId.isNullOrBlank() && !deviceToken.isNullOrBlank()

    /** 解绑：清掉身份，但保留服务器地址方便下次配对 */
    fun unpair() {
        val server = serverUrl
        prefs.edit { clear() }
        serverUrl = server
        guardingEnabled = false
    }

    private fun defaultName(): String {
        val model = android.os.Build.MODEL ?: "Android"
        return "${model}（备用机）"
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_ENABLED = "guarding_enabled"
        const val KEY_ALLOW_CAPTURE = "allow_remote_capture"
        const val KEY_LAST_REPORT = "last_report_at"
        const val KEY_BG_MODE = "background_mode"
        const val KEY_INTERVAL = "report_interval_sec"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_OPERATION_URL = "operation_url"
        const val KEY_LAST_LOADED_URL = "last_loaded_url"
    }
}
