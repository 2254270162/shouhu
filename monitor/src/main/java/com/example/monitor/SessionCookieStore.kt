package com.example.monitor

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Session Cookie 存储。
 *
 * 监控端改用管理员登录后，登录态靠 Cookie 维持。
 * OkHttp 需要一个 CookieJar 来自动收下 Set-Cookie 并在后续请求带上。
 *
 * 这里做了两件事：
 * 1. 内存里保存 Cookie，保证同一次运行内所有请求共享登录态；
 * 2. 持久化到 SharedPreferences，App 重启后不用重新登录。
 *
 * 为什么只持久化「值」而不是整个 Cookie 对象：
 * 服务端下发的是简单的 session=token，没有复杂属性，
 * 存值 + 域名即可重建，避免序列化整个对象带来的兼容问题。
 */
class SessionCookieStore(private val prefs: MonitorPrefs) : CookieJar {

    private val cookies = mutableMapOf<String, MutableList<Cookie>>()

    init {
        // 启动时恢复上次的 Cookie
        prefs.sessionCookie?.takeIf { it.isNotBlank() }?.let { saved ->
            prefs.serverUrl.takeIf { it.isNotBlank() }?.let { base ->
                runCatching {
                    val url = base.toHttpUrl()
                    val host = url.host
                    // 服务端 Cookie 没有显式过期时间，用会话级表示
                    val cookie = Cookie.Builder()
                        .name("session")
                        .value(saved)
                        .hostOnlyDomain(host)
                        .path("/")
                        .build()
                    cookies.getOrPut(host) { mutableListOf() }.add(cookie)
                }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val store = this.cookies.getOrPut(url.host) { mutableListOf() }
        for (c in cookies) {
            // 同名 Cookie 覆盖，避免堆积
            store.removeAll { it.name == c.name }
            store.add(c)
        }

        // 持久化 session 的值
        cookies.firstOrNull { it.name == "session" }?.let {
            prefs.sessionCookie = it.value
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookies[url.host]?.filter { it.matches(url) } ?: emptyList()
    }

    /** 是否有可用的会话。 */
    fun hasSession(): Boolean =
        cookies.values.any { list -> list.any { it.name == "session" } } ||
            !prefs.sessionCookie.isNullOrBlank()

    /** 清空登录态（退出登录时调用）。 */
    fun clear() {
        cookies.clear()
        prefs.sessionCookie = null
    }

    /** 给 Coil 加载照片用的 Cookie 头。 */
    fun cookieHeader(): String {
        val url = prefs.serverUrl.takeIf { it.isNotBlank() }
            ?.let { runCatching { it.toHttpUrl() }.getOrNull() }
            ?: return ""
        return loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
    }
}
