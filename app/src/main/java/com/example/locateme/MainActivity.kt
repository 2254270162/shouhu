package com.example.locateme

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.locateme.guard.AdminGate
import com.example.locateme.guard.GuardScreen
import com.example.locateme.guard.GuardViewModel
import com.example.locateme.guard.PowerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 被监控端主界面。
 *
 * 访问控制分两层，各管各的（很重要，别混在一起）：
 *
 * 1. **剪贴板 Key = 决定显示哪个页面**
 *    剪贴板（含最近历史）里出现 Key，App 就切到管理页；
 *    没有 Key 就停在操作页。
 *
 * 2. **口令 = 决定能否真正进入管理页**
 *    即使 Key 把界面切到了管理页，仍然需要输入口令才解锁内容。
 *    Key 只负责"把门打开"，口令才是"能不能进去"。
 *
 * 这样设计的好处：
 * - 日常使用时用户根本看不到管理页（默认操作页）；
 * - 管理员复制 Key 后界面自动切过去，但还要输口令，Key 泄露也不至于被直接改配置；
 * - 口令永远可用，不会被剪贴板配置连累。
 *
 * 注意：Android 10+ 禁止后台读剪贴板，所以必须在 Activity 前台时读，
 * 这正好是 onResume 的时机。
 */
class MainActivity : ComponentActivity() {

    private var viewModelRef: GuardViewModel? = null

    /** 管理页是否已通过口令解锁。 */
    private val configUnlocked = MutableStateFlow(false)

    /** 首次权限申请只做一次，避免每次切回前台都弹窗。 */
    private var hasRequestedInitialPermissions = false

    /** 上一次处理过的 AdminGate 请求时间戳，用于去重。 */
    private var lastAdminRequest = 0L

    /**
     * 页面状态。
     *
     * 只有两个页面：操作页（默认）和管理页。
     * 切到管理页的唯一途径是**服务端下发 open_admin 指令**，
     * 设备端不再自行根据剪贴板判断——那样容易受网络、缓存、
     * 剪贴板被清理等因素影响，出现不确定的跳转。
     */
    private var page by mutableStateOf(Page.OPERATION)

    private enum class Page {
        /** 操作页（默认） */
        OPERATION,
        /** 管理页。未解锁时显示口令验证，已解锁时显示内容 */
        CONFIG,
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val denied = granted.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            val names = denied.joinToString("、") { labelOf(it) }
            Toast.makeText(
                this,
                "未授予：$names\n相关功能不可用，可在系统设置里补充授权",
                Toast.LENGTH_LONG,
            ).show()
        }
        viewModelRef?.refresh()
    }

    /** 权限常量转中文名，提示更易读。 */
    private fun labelOf(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "定位"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "大致定位"
        Manifest.permission.CAMERA -> "相机"
        Manifest.permission.POST_NOTIFICATIONS -> "通知"
        Manifest.permission.READ_MEDIA_IMAGES -> "相册"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "存储"
        else -> permission.substringAfterLast('.')
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                val vm: GuardViewModel = viewModel()
                viewModelRef = vm
                val state by vm.state.collectAsStateWithLifecycle()
                val unlocked by configUnlocked.collectAsStateWithLifecycle()

                // 监听服务端下发的「打开管理页」请求。
                // 这是进入管理页的唯一途径。
                val adminRequest by AdminGate.request.collectAsStateWithLifecycle()
                LaunchedEffect(adminRequest) {
                    if (adminRequest > 0 && adminRequest != lastAdminRequest) {
                        lastAdminRequest = adminRequest
                        AdminGate.consume()
                        configUnlocked.value = false
                        page = Page.CONFIG
                    }
                }

                // 启动时拉一次配置：把操作页地址、管理口令同步到内存。
                // 界面在地址就绪前显示加载中，不显示"设备已就绪"那种过渡页。
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { vm.awaitRemoteConfigLoaded() }
                }

                if (page == Page.CONFIG) {
                    if (unlocked) {
                        ConfigPage(
                            vm = vm,
                            onExit = {
                                page = Page.OPERATION
                                configUnlocked.value = false
                            },
                        )
                    } else {
                        UnlockGate(
                            vm = vm,
                            onCancel = {
                                page = Page.OPERATION
                                configUnlocked.value = false
                            },
                            onUnlocked = { configUnlocked.value = true },
                        )
                    }
                } else {
                    OperationPage(
                        operationUrl = state.operationUrl,
                        vm = vm,
                    )
                }
            }
        }
    }

    /**
     * 每次进入前台：刷新状态、补拉起服务、检查是否有待处理的管理请求。
     *
     * 不再根据剪贴板判断页面——页面切换由服务端指令决定。
     */
    override fun onResume() {
        super.onResume()
        val vm = viewModelRef ?: return
        vm.refresh()
        vm.ensureServiceRunning()

        // 首次打开就申请权限
        if (!hasRequestedInitialPermissions) {
            hasRequestedInitialPermissions = true
            requestRuntimePermissions()
        }

        // 记录剪贴板历史（供服务端查看，不参与页面判断）
        vm.syncClipboardHistory()

        // 检查服务端是否要求打开管理页
        checkAdminRequest()
    }

    /**
     * 检查是否有待处理的「打开管理页」请求。
     *
     * 请求来自服务端下发的 open_admin 指令，由 GuardService 收到后
     * 写入 AdminGate。这里消费它并切换页面。
     */
    private fun checkAdminRequest() {
        if (AdminGate.hasPending() && !configUnlocked.value) {
            AdminGate.consume()
            configUnlocked.value = false
            page = Page.CONFIG
        }
    }

    /**
     * 申请守护运行所需的运行时权限。
     *
     * 只申请真正用得到的：定位、相机、通知、相册。
     * 「使用情况访问权限」是特殊权限，无法弹窗，由配置页的按钮引导到设置页。
     */
    private fun requestRuntimePermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_IMAGES
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        permissionLauncher.launch(missing.toTypedArray())
    }
}

/**
 * 操作页：默认界面。有配置地址时显示网页，否则显示简洁状态。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OperationPage(
    operationUrl: String,
    vm: GuardViewModel,
) {
    /*
     * 当前显示的地址。
     *
     * 启动时用本地缓存的地址（ViewModel 已从 prefs 读入），所以不会先
     * 显示"设备已就绪"再跳成网页。服务端配置返回后如果地址有变，
     * 这里才会切换并重新加载。
     */
    var lockedUrl by remember { mutableStateOf<String?>(null) }

    if (operationUrl.isNotBlank() && lockedUrl != operationUrl) {
        lockedUrl = operationUrl
    }

    val url = lockedUrl

    Box(Modifier.fillMaxSize()) {
        if (url != null) {
            /*
             * 网页加载状态管理。
             *
             * 关键问题：很多业务系统会用自定义 scheme 跳转（如 baiduboxapp://、
             * weixin://），WebView 不认识就会抛 ERR_UNKNOWN_URL_SCHEME 并
             * 显示一屏吓人的错误页。
             *
             * 正确做法：
             * 1. shouldOverrideUrlLoading 拦下非 http(s) 的 scheme，不外跳也不报错；
             * 2. 加载失败时显示"加载失败 + 重试"，而不是系统错误页；
             * 3. 加载期间显示进度，让用户知道在动。
             */
            var loading by remember { mutableStateOf(true) }
            var loadFailed by remember { mutableStateOf(false) }
            var webViewRef by remember { mutableStateOf<WebView?>(null) }

            // 加载耗时计数，用于判断是不是网络太慢
            var webLoadSeconds by remember { mutableStateOf(0) }
            LaunchedEffect(loading) {
                webLoadSeconds = 0
                if (loading) {
                    while (loading && webLoadSeconds < 30) {
                        delay(1000)
                        webLoadSeconds++
                    }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): Boolean {
                                val target = request?.url?.toString() ?: return false
                                // 只允许 http(s)；其它 scheme 静默忽略，
                                // 避免跳到百度 App、微信等外部应用或显示错误页
                                return !(target.startsWith("http://") || target.startsWith("https://"))
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loadFailed = true
                                    loading = false
                                }
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                loading = true
                                loadFailed = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                loading = false
                            }
                        }
                        webViewRef = this
                        loadUrl(url)
                    }
                },
                update = { view ->
                    // 地址真的变了才重新加载，避免配置刷新导致页面重置
                    if (url.isNotBlank() && view.url != url) {
                        loading = true
                        loadFailed = false
                        view.loadUrl(url)
                    }
                },
            )

            /*
             * 加载层：始终显示转圈，加载失败时也保持。
             *
             * 之前失败时会切成一屏错误提示，实际体验更差——
             * 用户看到"打不开"什么也做不了，还以为是设备坏了。
             * 现在失败也继续转圈，后台每 8 秒自动重试，
             * 网络恢复后自然就进去了。
             */
            if (loading || loadFailed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(14.dp))
                        Text("内容加载中", style = MaterialTheme.typography.bodySmall)

                        // 长时间没加载完，提示可能是网络问题
                        if (webLoadSeconds >= 12 || loadFailed) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "网络不佳，请更换网络！",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            /*
             * 加载失败时不显示错误页，继续显示加载中的转圈。
             *
             * 为什么不给错误提示和重试按钮：
             * 这是被监控端，界面主要给现场使用者看。
             * 看到一个"打不开、请联系管理员"的页面，既解决不了问题，
             * 也会让人以为设备坏了。保持转圈 + 自动重试更符合预期。
             *
             * 重试策略：失败后等 8 秒自动重试，无限循环，直到网络恢复。
             */
            LaunchedEffect(loadFailed) {
                if (loadFailed) {
                    delay(8000)
                    loadFailed = false
                    loading = true
                    webViewRef?.reload()
                }
            }
        } else {
            /*
             * 没有操作地址时：始终显示加载中。
             *
             * 不再显示"设备已就绪"这类文字页——那是多余的一屏，
             * 用户看到它闪一下再跳走只会觉得 App 有问题。
             *
             * 地址由本地缓存 + 服务端配置提供，正常情况下很快就有了；
             * 拿不到就一直转圈（用户明确要求这么做），
             * 超过 15 秒才提示网络问题。
             */
            LoadingScreen(
                message = "正在加载…",
                onTimeout = { /* 保持加载态，等地址到位 */ },
            )
        }
    }
}

/**
 * 加载中页面。
 *
 * 用于「还没拿到操作页地址」的阶段。
 *
 * 超过 4 秒提示网络问题——这是用户明确要求的：
 * 转圈时不要只是干等，要告诉用户可能是网络不行。
 *
 * 不做自动跳转兜底：地址没拿到就继续等，
 * 因为随便跳到别的页面会让用户看到不该看的东西。
 */
@Composable
private fun LoadingScreen(message: String, onTimeout: () -> Unit) {
    var seconds by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (seconds < 15) {
            delay(1000)
            seconds++
        }
        onTimeout()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)

        if (seconds >= 4) {
            Spacer(Modifier.height(10.dp))
            Text(
                "网络不佳，请更换网络！",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "可切换到移动数据或其它 WiFi 后重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 解锁门：已经从操作页切到管理页，但还没输口令。
 *
 * 为什么单独做成一个页面而不是弹窗：
 * 弹窗容易被误触关掉，而这里必须明确"输入口令"或"返回"，
 * 避免用户以为已经进了管理页。
 */
@Composable
private fun UnlockGate(
    vm: GuardViewModel,
    onCancel: () -> Unit,
    onUnlocked: () -> Unit,
) {
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("管理验证", style = MaterialTheme.typography.headlineSmall)
        Text(
            "请输入管理口令以进入管理页面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text("口令") },
            singleLine = true,
            isError = passwordError != null,
            modifier = Modifier.fillMaxWidth(),
        )

        passwordError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (vm.verifyHiddenPassword(passwordInput)) {
                    onUnlocked()
                } else {
                    passwordError = "口令不正确"
                }
            }) { Text("确定") }

            OutlinedButton(onClick = onCancel) { Text("返回") }
        }
    }
}

/** 配置页。 */
@Composable
private fun ConfigPage(
    vm: GuardViewModel,
    onExit: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? MainActivity

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设备配置", style = MaterialTheme.typography.titleLarge)
            Box(Modifier.weight(1f))
            TextButton(onClick = onExit) { Text("退出") }
        }

        GuardScreen(
            state = state,
            onServerUrlChange = vm::onServerUrlChange,
            onDeviceNameChange = vm::onDeviceNameChange,
            onPairCodeChange = vm::onPairCodeChange,
            onClaim = { vm.claim() },
            onToggleGuarding = { vm.toggleGuarding() },
            onAllowCaptureChange = vm::onAllowCaptureChange,
            onUnpair = vm::unpair,
            onRefresh = vm::refresh,
            onOpenUsageAccess = vm::openUsageAccessSettings,
            onOpenBatterySettings = { activity?.let { PowerHelper.requestIgnoreBatteryOptimizations(it) } },
            onOpenAutoStart = { activity?.let { PowerHelper.openAutoStartSettings(it) } },
        )

        // 剪贴板诊断：排查"为什么剪贴板解锁不生效"
        ClipboardDiagnostics(vm)
    }
}

/**
 * 剪贴板诊断面板。
 *
 * 目的是把"看不见的状态"摊开：当前剪贴板是什么、历史里有几条、
 * 服务端下发的 Key 是什么、是否匹配。
 * 没有它，用户只能猜"为什么没解锁成功"。
 */
/**
 * 剪贴板诊断面板。
 *
 * 注意：剪贴板内容已不再参与管理页判定（改由服务端指令控制），
 * 这里保留是为了让管理员能查看设备最近复制的内容，
 * 排查"设备读到的是什么"这类问题。
 */
@Composable
private fun ClipboardDiagnostics(vm: GuardViewModel) {
    var refreshFlag by remember { mutableStateOf(0) }

    // refreshFlag 变化时重新读取
    val current = remember(refreshFlag) { vm.currentClipboard() }
    val history = remember(refreshFlag) { vm.clipboardHistory() }

    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("剪贴板内容", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            InfoRow("当前剪贴板", current?.let { maskIfLong(it) } ?: "空或不可读")
            InfoRow("历史记录", "${history.size} 条")

            if (history.isNotEmpty()) {
                Text("最近记录：", style = MaterialTheme.typography.bodySmall)
                history.take(10).forEachIndexed { i, item ->
                    Text(
                        "${i + 1}. ${maskIfLong(item)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            androidx.compose.material3.OutlinedButton(onClick = {
                vm.syncClipboardHistory()
                refreshFlag++
            }) { Text("刷新") }

            Text(
                "说明：打开 App 时会自动把当前剪贴板记入历史，最多保留 10 条。" +
                    "管理页由管理员在后台发起，设备端不支持自助打开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 长文本遮蔽显示，避免诊断页把敏感内容完整暴露。 */
private fun maskIfLong(text: String): String {
    if (text.length <= 20) return text
    return text.take(8) + "…" + text.takeLast(4) + "（${text.length} 字符）"
}

/** 诊断页的键值行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}
