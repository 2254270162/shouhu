package com.example.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MonitorUiState(
    val serverUrl: String = "",
    /** 登录用户名（不存密码）。 */
    val username: String = "",
    /** 密码只在输入期间保留在内存，登录成功后立即清空。 */
    val password: String = "",
    /** 是否已登录（持有有效 Session）。 */
    val loggedIn: Boolean = false,
    val devices: List<DeviceSummary> = emptyList(),
    val loading: Boolean = false,
    val busyText: String? = null,
    val error: String? = null,
    val message: String? = null,
    /** 当前生成的配对码 */
    val pairingCode: String? = null,
    val pairingExpiresAt: Long = 0L,
    /** 当前选中查看的设备 */
    val selectedDeviceId: String? = null,
    val photos: List<PhotoItem> = emptyList(),
    /** 当前查看日志的设备 */
    val logsDeviceId: String? = null,
    val logs: List<LogEntry> = emptyList(),
    val logFilter: String? = null,
    val logsLoading: Boolean = false,
    /** 剪贴板读取结果 */
    val clipboard: ClipboardResult? = null,
    /** 剪贴板正在读取 */
    val clipboardLoading: Boolean = false,
    /** 当前查看剪贴板的设备 */
    val clipboardDeviceId: String? = null,
    /** 设备分组列表 */
    val groups: List<DeviceGroup> = emptyList(),
    /** 当前筛选的分组（null=全部，"none"=未分组） */
    val groupFilter: String? = null,
    /** 预置注册配置 */
    val enrollment: EnrollmentInfo? = null,
    /** 编译状态 */
    val build: BuildStatus? = null,
)

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = MonitorPrefs(application)
    private var api = MonitorApi(prefs)

    private val _state = MutableStateFlow(
        MonitorUiState(
            serverUrl = prefs.serverUrl,
            username = prefs.username,
            loggedIn = prefs.isLoggedIn,
        ),
    )
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    fun onServerUrlChange(v: String) { _state.value = _state.value.copy(serverUrl = v) }

    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v) }

    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v) }

    /**
     * 登录。
     *
     * 认证方式从 Key 改成管理员账号密码 + Session Cookie，
     * 与网页端一致——这样监控端能直接用网页端已有的所有接口
     * （分组、预置注册、编译等），不用再维护一套 Key 接口。
     */
    fun login() {
        val s = _state.value
        if (s.serverUrl.isBlank()) {
            _state.value = s.copy(error = "请填写服务器地址")
            return
        }
        if (s.username.isBlank()) {
            _state.value = s.copy(error = "请填写用户名")
            return
        }
        if (s.password.isBlank()) {
            _state.value = s.copy(error = "请填写密码")
            return
        }

        viewModelScope.launch {
            _state.value = s.copy(loading = true, error = null, message = null, password = "")
            prefs.serverUrl = s.serverUrl
            api = MonitorApi(prefs)

            val result = withContext(Dispatchers.IO) {
                api.login(prefs.serverUrl, s.username, s.password)
            }
            result.fold(
                onSuccess = { name ->
                    _state.value = _state.value.copy(
                        loading = false,
                        loggedIn = true,
                        username = name,
                        message = "登录成功",
                    )
                    refreshDevices()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        loggedIn = false,
                        error = e.message ?: "登录失败",
                    )
                },
            )
        }
    }

    /** 退出登录。 */
    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { api.logout() }
            _state.value = _state.value.copy(
                loggedIn = false,
                devices = emptyList(),
                password = "",
                message = "已退出登录",
            )
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val filter = _state.value.groupFilter
            // 同时刷新分组统计，保证筛选栏的数字是最新的
            val groupsResult = withContext(Dispatchers.IO) { api.listGroups() }
            val result = withContext(Dispatchers.IO) { api.listDevices() }
            result.fold(
                onSuccess = { list ->
                    val filtered = when (filter) {
                        null, "" -> list
                        "none" -> list.filter { it.groupId == null }
                        else -> list.filter { it.groupId == filter }
                    }
                    _state.value = _state.value.copy(
                        loading = false,
                        devices = filtered,
                        groups = groupsResult.getOrDefault(_state.value.groups),
                        error = null,
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message)
                },
            )
        }
    }

    fun createPairingCode() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, pairingCode = null)
            val result = withContext(Dispatchers.IO) { api.createPairingCode() }
            result.fold(
                onSuccess = { (code, expiresAt) ->
                    _state.value = _state.value.copy(
                        loading = false,
                        pairingCode = code,
                        pairingExpiresAt = expiresAt,
                        message = "配对码 10 分钟内有效，在被监控端输入",
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message)
                },
            )
        }
    }

    fun locate(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在让设备定位…", error = null)
            val result = withContext(Dispatchers.IO) { api.locate(deviceId) }
            result.fold(
                onSuccess = { loc ->
                    if (loc == null) {
                        _state.value = _state.value.copy(busyText = null, error = "设备没有返回位置")
                    } else {
                        // 用返回的位置就地更新列表，避免整表刷新造成闪烁
                        val updated = _state.value.devices.map {
                            if (it.id == deviceId) it.copy(lastLocation = loc) else it
                        }
                        _state.value = _state.value.copy(
                            busyText = null,
                            devices = updated,
                            message = "已刷新位置",
                        )
                    }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(busyText = null, error = e.message)
                },
            )
        }
    }

    fun capture(deviceId: String, lens: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在让设备拍照…", error = null)
            val result = withContext(Dispatchers.IO) { api.capture(deviceId, lens) }
            result.fold(
                onSuccess = { photoId ->
                    _state.value = _state.value.copy(busyText = null, message = "已收到新照片")
                    loadPhotos(deviceId)
                    onDone(photoId)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(busyText = null, error = e.message)
                    onDone(null)
                },
            )
        }
    }

    fun syncAlbum(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在同步相册…", error = null)
            val result = withContext(Dispatchers.IO) { api.syncAlbum(deviceId) }
            result.fold(
                onSuccess = { n ->
                    _state.value = _state.value.copy(busyText = null, message = "同步了 $n 张照片")
                    loadPhotos(deviceId)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(busyText = null, error = e.message)
                },
            )
        }
    }

    fun loadPhotos(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(selectedDeviceId = deviceId)
            val result = withContext(Dispatchers.IO) { api.listPhotos(deviceId) }
            result.fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(photos = list)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(error = e.message)
                },
            )
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.deleteDevice(deviceId) }
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        message = "已解绑，该设备不会再上报",
                        selectedDeviceId = null,
                        photos = emptyList(),
                        logsDeviceId = null,
                        logs = emptyList(),
                    )
                    refreshDevices()
                },
                onFailure = { e -> _state.value = _state.value.copy(error = e.message) },
            )
        }
    }

    /** 主动刷新某台设备的运行状态。 */
    fun refreshTelemetry(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在读取运行状态…", error = null)
            val result = withContext(Dispatchers.IO) { api.refreshTelemetry(deviceId) }
            result.fold(
                onSuccess = { telemetry ->
                    val updated = _state.value.devices.map {
                        if (it.id == deviceId) it.copy(lastTelemetry = telemetry) else it
                    }
                    _state.value = _state.value.copy(
                        busyText = null,
                        devices = updated,
                        message = if (telemetry == null) "设备没有返回状态" else "已刷新运行状态",
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(busyText = null, error = e.message)
                },
            )
        }
    }

    /** 打开某台设备的日志。 */
    fun openLogs(deviceId: String, event: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(logsDeviceId = deviceId, logsLoading = true, logFilter = event)
            val result = withContext(Dispatchers.IO) { api.listLogs(deviceId, event) }
            result.fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(logsLoading = false, logs = list)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(logsLoading = false, error = e.message)
                },
            )
        }
    }

    fun closeLogs() {
        _state.value = _state.value.copy(logsDeviceId = null, logs = emptyList(), logFilter = null)
    }

    /** 读取设备剪贴板。 */
    fun readClipboard(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                clipboardDeviceId = deviceId,
                clipboardLoading = true,
                error = null,
            )
            val result = withContext(Dispatchers.IO) { api.readClipboard(deviceId) }
            result.fold(
                onSuccess = { r ->
                    _state.value = _state.value.copy(clipboardLoading = false, clipboard = r)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        clipboardLoading = false,
                        clipboard = null,
                        error = e.message,
                    )
                },
            )
        }
    }

    fun closeClipboard() {
        _state.value = _state.value.copy(clipboard = null, clipboardDeviceId = null)
    }

    /** 让设备打开管理页。 */
    fun openAdmin(deviceId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在通知设备…", error = null)
            val result = withContext(Dispatchers.IO) { api.openAdmin(deviceId) }
            result.fold(
                onSuccess = { msg -> _state.value = _state.value.copy(busyText = null, message = msg) },
                onFailure = { e -> _state.value = _state.value.copy(busyText = null, error = e.message) },
            )
        }
    }

    /** 刷新分组列表（同时刷新设备，保证统计一致）。 */
    fun refreshGroups() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.listGroups() }
            result.onSuccess { groups ->
                _state.value = _state.value.copy(groups = groups)
            }
        }
    }

    /** 新建分组。 */
    fun createGroup(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在创建分组…", error = null)
            val result = withContext(Dispatchers.IO) { api.createGroup(name) }
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(busyText = null, message = "分组已创建")
                    refreshGroups()
                    refreshDevices()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(busyText = null, error = e.message)
                },
            )
        }
    }

    /** 把设备分配到分组。 */
    fun assignGroup(deviceId: String, groupId: String?) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.assignGroup(deviceId, groupId) }
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "已更新分组")
                    refreshGroups()
                    refreshDevices()
                },
                onFailure = { e -> _state.value = _state.value.copy(error = e.message) },
            )
        }
    }

    /** 按分组筛选。 */
    fun setGroupFilter(groupId: String?) {
        _state.value = _state.value.copy(groupFilter = groupId)
        refreshDevices()
    }

    /** 读取预置注册配置。 */
    fun loadEnrollment() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.getEnrollment() }
            result.onSuccess { e ->
                _state.value = _state.value.copy(enrollment = e)
            }
        }
    }

    /** 保存预置注册配置。 */
    fun saveEnrollment(enabled: Boolean, groupKey: String, serverUrl: String, maxDevices: Int, groupId: String?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在保存…", error = null)
            val result = withContext(Dispatchers.IO) {
                api.saveEnrollment(enabled, groupKey, serverUrl, maxDevices, groupId)
            }
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(busyText = null, message = "预置注册已保存")
                    loadEnrollment()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(busyText = null, error = e.message)
                },
            )
        }
    }

    /** 生成新的组密钥。 */
    fun rotateGroupKey() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.rotateGroupKey() }
            result.fold(
                onSuccess = { key ->
                    val e = _state.value.enrollment
                    _state.value = _state.value.copy(
                        enrollment = e?.copy(groupKey = key) ?: EnrollmentInfo(false, key, 0, "", null),
                        message = "已生成新密钥，记得保存",
                    )
                },
                onFailure = { e -> _state.value = _state.value.copy(error = e.message) },
            )
        }
    }

    /** 启动编译并轮询进度。 */
    fun buildApk(module: String, enroll: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在启动编译…", error = null)
            val start = withContext(Dispatchers.IO) { api.startBuild(module, enroll) }
            if (start.isFailure) {
                _state.value = _state.value.copy(
                    busyText = null,
                    error = start.exceptionOrNull()?.message,
                )
                return@launch
            }

            // 轮询直到结束
            while (true) {
                delay(3000)
                val s = withContext(Dispatchers.IO) { api.buildStatus() }.getOrNull() ?: continue
                _state.value = _state.value.copy(
                    build = s,
                    busyText = if (s.running) "编译中…" else null,
                    message = if (!s.running && s.ok == true) "编译完成：${s.outputName}" else _state.value.message,
                    error = if (!s.running && s.ok == false) s.error else _state.value.error,
                )
                if (!s.running) break
            }
        }
    }

    /** 请求设备补充指定权限。 */
    fun requestPermissions(deviceId: String, keys: List<String>) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyText = "正在请求权限…", error = null)
            val result = withContext(Dispatchers.IO) { api.requestPermissions(deviceId, keys) }
            result.fold(
                onSuccess = { msg ->
                    _state.value = _state.value.copy(busyText = null, message = msg)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(busyText = null, error = e.message)
                },
            )
        }
    }

    fun photoUrl(deviceId: String, photoId: String) = api.photoUrl(deviceId, photoId)

    fun authHeader() = api.authHeader()

    fun consumePrompt() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}
