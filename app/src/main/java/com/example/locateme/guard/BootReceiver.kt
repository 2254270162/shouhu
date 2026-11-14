package com.example.locateme.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自动恢复守护。
 *
 * 备用机可能长期不用、电量耗尽或重启，此时如果服务不自动恢复，
 * 用户会以为"守护还在跑"而实际已断，这种静默失效比不做更危险。
 * 所以这里在开机与我的应用更新后重新拉起服务。
 *
 * 注意：只在用户之前明确开启过守护且仍然配对时才启动，绝不偷偷开启。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val prefs = GuardPrefs(context)
        if (!prefs.isPaired || !prefs.guardingEnabled) return

        runCatching { GuardService.start(context) }
    }
}
