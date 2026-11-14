package com.example.monitor

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 监控端本地配置。
 *
 * 认证方式已从 Key 改为管理员账号密码 + Session，
 * 所以这里存的是服务器地址、用户名、Cookie 值。
 */
class MonitorPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, BuildConfig.DEFAULT_SERVER) ?: BuildConfig.DEFAULT_SERVER
        set(value) = prefs.edit { putString(KEY_SERVER, ServerAddress.normalize(value)) }

    /** 上次登录的用户名，仅用于回填输入框，不存密码。 */
    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_USERNAME, value.trim()) }

    /**
     * Session Cookie 的值。
     *
     * 存的是 token 本身而不是完整 Cookie 对象：
     * 服务端下发的就是简单的 session=xxx，没有复杂属性，
     * 存值足够重建，避免序列化整个对象带来的兼容问题。
     *
     * 注意：这等同于登录凭证，请勿泄露。退出登录时会被清空。
     */
    var sessionCookie: String?
        get() = prefs.getString(KEY_SESSION, null)
        set(value) = prefs.edit { putString(KEY_SESSION, value) }

    /** 是否已登录（有地址 + 有会话）。 */
    val isLoggedIn: Boolean
        get() = serverUrl.isNotBlank() && !sessionCookie.isNullOrBlank()

    /** 清空登录态。 */
    fun clearSession() {
        prefs.edit { remove(KEY_SESSION) }
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_SESSION = "session_cookie"
    }
}
