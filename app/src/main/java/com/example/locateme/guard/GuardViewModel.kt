package com.example.locateme.guard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** 守护页状态。 */
data class GuardUiState(
    val serverUrl: String = "",
    val deviceName: String = "",
    val paired: Boolean = false,
    val guarding: Boolean = false,
    val allowRemoteCapture: Boolean = true,
    val lastReportAt: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val pairCode: String = "",
    /** 是否已授予「使用情况访问权限」，决定能否读前台应用。 */
    val usageAccess: Boolean = false,
    /** 默认操作页面地址（由服务端下发），空则显示内置状态页。 */
    val operationUrl: String = "",
    /** 服务端下发的管理口令。 */
    val adminPassword: String = "",
)

class GuardViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = GuardPrefs(application)
    private val api = GuardApi(prefs)
    private val hiddenAccess = HiddenAccess(application)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<GuardUiState> = _state.asStateFlow()

    init {
        // 启动时拉一次服务端配置，拿到操作地址、手势次数、口令
        reloadRemoteConfig()
        // 如果 APK 预置了注册信息且还没配对，自动注册
        autoEnrollIfPossible()
    }

    /**
     * 自动注册：APK 编译时预置了服务器地址和组密钥时生效。
     *
     * 这是「几十台设备不想逐台填配对码」的解决方案：
     * 装完打开 App 就完成注册，用户什么都不用做。
     *
     * 前提：服务端开了预置注册、构建时填了组密钥。
     * 两者缺一就不会自动注册，仍需手动填配对码。
     */
    private fun autoEnrollIfPossible() {
        // 已配对就不重复注册
        if (prefs.isPaired) return
        // 编译时没预置信息，跳过
        val enrollServer = com.example.locateme.BuildConfig.ENROLL_SERVER
        val enrollKey = com.example.locateme.BuildConfig.ENROLL_GROUP_KEY
        if (enrollServer.isBlank() || enrollKey.isBlank()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = "正在自动注册…")

            // 预置的地址优先于本地已存的
            prefs.serverUrl = enrollServer

            val installId = ensureInstallId()
            val result = withContext(Dispatchers.IO) {
                api.enroll(
                    serverUrl = enrollServer,
                    groupKey = enrollKey,
                    deviceName = prefs.deviceName,
                    installId = installId,
                )
            }

            when (result) {
                is GuardApi.ClaimResult.Success -> {
                    prefs.deviceId = result.deviceId
                    prefs.deviceToken = result.deviceToken
                    _state.value = _state.value.copy(
                        busy = false,
                        paired = true,
                        serverUrl = enrollServer,
                        deviceName = prefs.deviceName,
                        message = "已自动注册，点「开始守护」即可",
                    )
                    // 注册完直接开始守护，真正做到"装完就能用"
                    toggleGuarding()
                }
                is GuardApi.ClaimResult.Failure -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = "自动注册失败：${result.message}\n请手动填写配对码",
                    )
                }
            }
        }
    }

    /**
     * 设备身份标识：重装后仍能复用同一条设备记录。
     *
     * 关键问题：这个值不能存在 SharedPreferences 里。
     * 卸载重装会清掉应用数据，installId 就变了，
     * 服务端会当成新设备再注册一台——装几次就有几台重复设备。
     *
     * 所以要落在「卸载重装后仍然存在」的地方：
     * 1. 优先用 Android ID（设备首次开机生成，恢复出厂才会变）；
     * 2. 拼上包名，避免同一台机器上不同包名的应用互相顶替；
     * 3. 如果 Android ID 拿不到（部分 ROM 返回空或全 0），
     *    退回 SharedPreferences 保存的 UUID。
     */
    private fun ensureInstallId(): String {
        val cached = prefs.installId
        if (!cached.isNullOrBlank()) return cached

        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                getApplication<android.app.Application>().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()

        val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            // 拼包名，避免同机多应用冲突；前缀标明来源便于排查
            "aid-${androidId}-${getApplication<android.app.Application>().packageName}"
        } else {
            "uuid-${java.util.UUID.randomUUID()}"
        }

        prefs.installId = id
        return id
    }

    /**
     * 拉取服务端配置：操作页地址、隐藏手势次数、口令。
     *
     * 这些配置由管理员在后台改，设备启动或进前台时同步，
     * 不需要重装 APK 就能改变设备行为。
     */
    fun reloadRemoteConfig() {
        viewModelScope.launch {
            runCatching {
                val cfg = withContext(Dispatchers.IO) { api.fetchConfig() } ?: return@runCatching
                applyConfig(cfg)
            }
        }
    }

    /** 把服务端配置写入状态，并缓存地址供下次冷启动使用。 */
    private fun applyConfig(cfg: JSONObject) {
        val url = cfg.optString("operationUrl", "")
        // 只在服务端给了非空地址时更新缓存，避免一次网络异常把缓存清掉
        if (url.isNotBlank()) prefs.operationUrl = url

        _state.value = _state.value.copy(
            operationUrl = url.ifBlank { prefs.operationUrl },
            adminPassword = cfg.optString("adminPassword", ""),
        )
        _configLoaded.value = true
    }

    /** 配置是否已成功拉取过一次。 */
    private val _configLoaded = MutableStateFlow(false)

    /**
     * 等待配置加载完成（带 8 秒超时）。
     *
     * 管理页是否打开已由服务端指令决定，不再依赖配置判断。
     * 这里保留是因为操作页地址（operationUrl）需要配置到位后才知道。
     */
    suspend fun awaitRemoteConfigLoaded() {
        if (_configLoaded.value) return
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000) {
                runCatching {
                    val cfg = api.fetchConfig() ?: return@runCatching
                    applyConfig(cfg)
                }
            }
        }
    }

    /** 把当前剪贴板同步进历史，进入前台时调用。 */
    fun syncClipboardHistory() {
        ClipboardAccess.syncAndGetCurrent(getApplication())
    }

    /** 读取剪贴板历史（最新在前），供诊断页展示。 */
    fun clipboardHistory(): List<String> = ClipboardAccess.getHistory(getApplication())

    /** 当前剪贴板内容（已清洗）。 */
    fun currentClipboard(): String? = ClipboardAccess.readText(getApplication())

    /** 校验管理口令。 */
    fun verifyHiddenPassword(input: String): Boolean =
        hiddenAccess.verifyPassword(input, _state.value.adminPassword)

    private fun loadState() = GuardUiState(
        serverUrl = prefs.serverUrl,
        deviceName = prefs.deviceName,
        paired = prefs.isPaired,
        guarding = prefs.guardingEnabled,
        allowRemoteCapture = prefs.allowRemoteCapture,
        lastReportAt = prefs.lastReportAt,
        usageAccess = Telemetry.hasUsageAccess(getApplication()),
        // 用本地缓存的地址初始化，避免冷启动时先显示兜底文字页再跳成网页
        operationUrl = prefs.operationUrl,
    )

    /** 拉取服务端登记的待处理权限请求。 */
    fun fetchPendingPermissionRequest(): List<String> = api.fetchPendingPermissionRequest()

    /** 打开系统的「使用情况访问权限」设置页，授权后才能读前台应用。 */
    fun openUsageAccessSettings() {
        Telemetry.openUsageAccessSettings(getApplication())
    }

    /** 从设置页返回时重新检查授权状态。 */
    fun recollectUsageAccess() {
        val granted = Telemetry.hasUsageAccess(getApplication())
        _state.value = _state.value.copy(usageAccess = granted)
    }

    /**
     * 进入界面时调用：如果用户之前开着守护，但服务因重启/被杀而停了，这里补拉起。
     * 只依据用户已保存的选择，不会擅自开启。
     */
    fun ensureServiceRunning() {
        if (prefs.isPaired && prefs.guardingEnabled) {
            val context = getApplication<Application>()
            GuardService.start(context)
        }
    }

    fun refresh() {
        _state.value = loadState().copy(
            message = _state.value.message,
            error = _state.value.error,
            pairCode = _state.value.pairCode,
        )
    }

    fun onServerUrlChange(value: String) {
        _state.value = _state.value.copy(serverUrl = value)
    }

    fun onDeviceNameChange(value: String) {
        _state.value = _state.value.copy(deviceName = value)
    }

    fun onPairCodeChange(value: String) {
        // 只允许数字，最多 6 位，避免用户误输入
        _state.value = _state.value.copy(pairCode = value.filter { it.isDigit() }.take(6))
    }

    fun onAllowCaptureChange(value: Boolean) {
        prefs.allowRemoteCapture = value
        _state.value = _state.value.copy(allowRemoteCapture = value)
    }

    /**
     * 用配对码完成绑定。
     *
     * @param onSuccess 配对成功后的回调（界面在这里申请运行时权限）
     */
    fun claim(onSuccess: () -> Unit = {}) {
        val s = _state.value
        val code = s.pairCode.trim()
        if (code.length != 6) {
            _state.value = s.copy(error = "配对码是 6 位数字")
            return
        }
        if (s.serverUrl.isBlank()) {
            _state.value = s.copy(error = "请填写服务器地址，例如 http://192.168.1.100:8080")
            return
        }

        viewModelScope.launch {
            _state.value = s.copy(busy = true, error = null, message = null)
            prefs.serverUrl = s.serverUrl
            val ok = withContext(Dispatchers.IO) { api.health(prefs.serverUrl) }
            if (!ok) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "连不上服务器，请检查地址和网络",
                )
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                api.claim(
                    code = code,
                    deviceName = s.deviceName.ifBlank { prefs.deviceName },
                    model = android.os.Build.MODEL ?: "Android",
                    appVersion = com.example.locateme.BuildConfig.VERSION_NAME,
                )
            }
            when (result) {
                is GuardApi.ClaimResult.Success -> {
                    prefs.deviceId = result.deviceId
                    prefs.deviceToken = result.deviceToken
                    prefs.deviceName = s.deviceName.ifBlank { prefs.deviceName }
                    _state.value = _state.value.copy(
                        busy = false,
                        paired = true,
                        pairCode = "",
                        deviceName = prefs.deviceName,
                        message = "配对成功，点「开始守护」才会开始上报",
                    )
                    // 配对成功即申请运行时权限：此时用户已经知道用途，更容易理解
                    onSuccess()
                }
                is GuardApi.ClaimResult.Failure -> {
                    _state.value = _state.value.copy(busy = false, error = result.message)
                }
            }
        }
    }

    /** 开始/停止守护。 */
    fun toggleGuarding() {
        val s = _state.value
        if (!s.paired) {
            _state.value = s.copy(error = "请先配对设备")
            return        }
        val context = getApplication<Application>()
        if (s.guarding) {
            GuardService.stop(context)
            prefs.guardingEnabled = false
            _state.value = _state.value.copy(guarding = false, message = "已停止守护")
        } else {
            prefs.guardingEnabled = true
            GuardService.start(context)
            _state.value = _state.value.copy(guarding = true, message = "守护已开始，通知栏会显示状态")
        }
    }

    /** 解绑：清除本机身份并从服务器删除设备。 */
    fun unpair() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            GuardService.stop(getApplication())
            withContext(Dispatchers.IO) {
                // 主动通知服务器删除，避免监控端还挂着一条僵尸记录
                val deviceId = prefs.deviceId
                val token = prefs.deviceToken
                if (deviceId != null && token != null) {
                    runCatching { api.unpairSelf(deviceId, token) }
                }
            }
            prefs.unpair()
            _state.value = loadState().copy(message = "已解绑，本机不再上报任何数据")
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}
