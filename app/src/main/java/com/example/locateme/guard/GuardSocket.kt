package com.example.locateme.guard

import android.os.Build
import com.example.locateme.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 被监控端与服务端的长连接。
 *
 * 设计要点：
 * 1. 断线自动重连，指数退避（2s → 4s → 8s …封顶 60s），避免弱网下疯狂重试耗电；
 * 2. 连接状态对外暴露，界面据此显示"已连接/重连中"，而不是让用户猜；
 * 3. 收到指令后交给 [onCommand] 处理，处理结果统一回执，避免服务端空等超时。
 */
class GuardSocket(
    private val prefs: GuardPrefs,
    private val scope: CoroutineScope,
    private val onCommand: suspend (JSONObject) -> JSONObject,
    private val onStateChanged: (State) -> Unit,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不设读超时
        .pingInterval(25, TimeUnit.SECONDS) // WebSocket 协议层心跳，保活 NAT 映射
        .build()

    private var webSocket: WebSocket? = null
    private var loopJob: Job? = null
    private var heartbeatJob: Job? = null
    private var attempt = 0

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                if (!prefs.guardingEnabled || !prefs.isPaired) {
                    onStateChanged(State.DISCONNECTED)
                    break
                }
                connectOnce()
                // connectOnce 返回意味着连接已结束，进入退避重连
                if (!isActive) break
                attempt = (attempt + 1).coerceAtMost(6)
                val backoff = (2_000L shl (attempt - 1)).coerceAtMost(60_000L)
                onStateChanged(State.RECONNECTING)
                delay(backoff)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        attempt = 0
        runCatching { webSocket?.close(1000, "user stopped") }
        webSocket = null
        onStateChanged(State.DISCONNECTED)
    }

    /** 用现有长连接主动上报一条消息；未连接返回 false，由调用方走 HTTP 兜底。 */
    fun send(type: String, payload: org.json.JSONObject): Boolean {
        val ws = webSocket ?: return false
        return runCatching {
            val msg = org.json.JSONObject(payload.toString()).put("type", type)
            ws.send(msg.toString())
        }.getOrDefault(false)
    }

    /**
     * 应用层心跳。
     *
     * 服务端日志显示连接稳定地每分钟断开一次——这是 Cloudflare 隧道对
     * 长时间无数据连接的回收，OkHttp 的 pingInterval 帧不够（中间层可能不转发）。
     * 这里额外每 30 秒发一条应用层 ping，保证链路上始终有数据流过。
     */
    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val sent = runCatching { ws.send("""{"type":"ping"}""") }.getOrDefault(false)
                if (!sent) break
            }
        }
    }

    private suspend fun connectOnce() {
        val deviceId = prefs.deviceId ?: return
        val token = prefs.deviceToken ?: return
        onStateChanged(State.CONNECTING)

        val request = Request.Builder().url(GuardApi.wsUrl(prefs.serverUrl)).build()
        val openedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val closedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                attempt = 0
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("deviceId", deviceId)
                    .put("token", token)
                    .put("name", prefs.deviceName)
                    .put("model", Build.MODEL ?: "Android")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                ws.send(hello.toString())
                onStateChanged(State.CONNECTED)
                openedSignal.complete(Unit)
                startHeartbeat(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "hello_ack" -> Unit
                    "command" -> scope.launch {
                        val commandId = msg.optString("commandId")
                        val result = runCatching { onCommand(msg) }
                            .getOrElse { e ->
                                JSONObject().put("ok", false).put("error", e.message ?: "执行失败")
                            }
                        result.put("type", "command_result").put("commandId", commandId)
                        runCatching { ws.send(result.toString()) }
                    }
                    else -> Unit
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
                openedSignal.complete(Unit)
                closedSignal.complete(Unit)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                openedSignal.complete(Unit)
                closedSignal.complete(Unit)
            }
        }

        webSocket = client.newWebSocket(request, listener)

        // 连接建立必须有超时：否则网络半通时 onOpen 永远不来，
        // openedSignal 一直挂起，重连循环就彻底卡死了（这正是设备长期显示离线的成因）。
        val opened = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { openedSignal.await() }
        if (opened == null) {
            runCatching { webSocket?.cancel() }
            webSocket = null
            onStateChanged(State.RECONNECTING)
            return
        }

        // 已连上：挂到断开为止，由外层循环决定是否重连
        closedSignal.await()
    }

    companion object {
        /** 建连超时；超过就放弃这条连接换下一个重试。 */
        private const val CONNECT_TIMEOUT_MS = 25_000L
    }
}
