package com.example.monitor

/**
 * 服务器地址规范化（监控端）。
 *
 * 与设备端 ServerAddress 规则保持一致，确保两端对同一个地址理解相同。
 * 用户可能输入：
 *   "192.168.1.100:8080"  → 内网自动补 http://
 *   "example.com"         → 公网自动补 https://
 *   "http://x.com/api/"   → 去掉多余路径与尾斜杠
 */
object ServerAddress {

    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        s = s.trimEnd('/')

        val hasScheme = s.startsWith("http://", ignoreCase = true) ||
            s.startsWith("https://", ignoreCase = true)

        if (!hasScheme) {
            val hostPart = s.substringBefore('/')
            val host = hostPart.substringBefore(':').lowercase()
            s = if (looksLocal(host)) "http://$s" else "https://$s"
        }

        val schemeEnd = s.indexOf("://") + 3
        val rest = s.substring(schemeEnd)
        val slash = rest.indexOf('/')
        if (slash >= 0) s = s.substring(0, schemeEnd + slash)

        return s.trimEnd('/')
    }

    private fun looksLocal(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toIntOrNull() ?: -1
            val b = parts[1].toIntOrNull() ?: -1
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
        }
        return false
    }
}
