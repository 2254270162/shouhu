package com.example.locateme.guard

/**
 * 服务器地址规范化。
 *
 * 用户可能输入各种形态：
 *   "192.168.1.100:8080"        → 补 http://
 *   "http://192.168.1.100:8080/"
 *   "https://example.com"
 *   "  http://10.0.0.5:8080/api  " → 去掉多余路径
 *
 * 设备端与监控端共用这套规则，避免两边对同一个地址理解不一致。
 */
object ServerAddress {

    /**
     * 规范化成 "scheme://host[:port]" 形式（无尾斜杠）。
     *
     * 协议判定规则：
     * - 已写 http:// 或 https:// → 保留原样；
     * - 内网地址（10.* / 192.168.* / 172.16-31.* / localhost / *.local）
     *   或纯 IP → 默认 http（局域网通常没有证书）；
     * - 其他域名 → 默认 https。
     */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        // 去掉末尾斜杠与多余路径，只保留 origin
        s = s.trimEnd('/')

        val hasScheme = s.startsWith("http://", ignoreCase = true) ||
            s.startsWith("https://", ignoreCase = true)

        if (!hasScheme) {
            val hostPart = s.substringBefore('/')
            val host = hostPart.substringBefore(':').lowercase()
            s = if (looksLocal(host)) "http://$s" else "https://$s"
        }

        // 丢掉 origin 之后的多余路径（例如 /api），只保留协议+主机+端口
        val schemeEnd = s.indexOf("://") + 3
        val rest = s.substring(schemeEnd)
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            s = s.substring(0, schemeEnd + slash)
        }

        return s.trimEnd('/')
    }

    /** 是否是内网/本机地址，这类默认走 http。 */
    private fun looksLocal(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return true
        // 纯 IPv4
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toIntOrNull() ?: -1
            val b = parts[1].toIntOrNull() ?: -1
            return a == 10 ||
                a == 127 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31)
        }
        return false
    }

    /**
     * 由 HTTP(S) 地址推导 WebSocket 地址。
     * http → ws，https → wss。
     */
    fun toWebSocket(serverUrl: String): String {
        val base = normalize(serverUrl)
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/ws"
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + "/ws"
            else -> "wss://$base/ws"
        }
    }
}
