package com.example.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val vm: MonitorViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()

                MonitorScreen(
                    state = state,
                    onServerUrlChange = vm::onServerUrlChange,
                    onUsernameChange = vm::onUsernameChange,
                    onPasswordChange = vm::onPasswordChange,
                    onLogin = vm::login,
                    onLogout = vm::logout,
                    onRefresh = vm::refreshDevices,
                    onCreateCode = vm::createPairingCode,
                    onLocate = vm::locate,
                    onCapture = { id, lens -> vm.capture(id, lens) },
                    onSyncAlbum = vm::syncAlbum,
                    onOpenPhotos = vm::loadPhotos,
                    onDeleteDevice = vm::deleteDevice,
                    onRefreshTelemetry = vm::refreshTelemetry,
                    onOpenLogs = { id -> vm.openLogs(id) },
                    onReadClipboard = { id -> vm.readClipboard(id) },
                    onRequestPermissions = { id -> vm.requestPermissions(id, listOf("location", "camera", "notification", "media")) },
                    onOpenAdmin = { id -> vm.openAdmin(id) },
                    onGroupFilter = vm::setGroupFilter,
                    onBuild = vm::buildApk,
                    onLoadEnrollment = vm::loadEnrollment,
                    onSaveEnrollment = vm::saveEnrollment,
                    onRotateKey = vm::rotateGroupKey,
                    onFilterLogs = { event ->
                        vm.state.value.logsDeviceId?.let { id -> vm.openLogs(id, event) }
                    },
                    onCloseLogs = vm::closeLogs,
                    onCloseClipboard = vm::closeClipboard,
                    photoUrl = vm::photoUrl,
                    authHeader = vm::authHeader,
                    onPromptShown = vm::consumePrompt,
                )
            }
        }
    }
}
