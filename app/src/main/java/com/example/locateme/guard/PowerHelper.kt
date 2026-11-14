package com.example.locateme.guard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活工具。
 *
 * 为什么需要：安卓（尤其 MIUI/EMUI）在息屏后会冻结后台进程、切断网络，
 * 表现为「一息屏就离线」。这不是代码 bug，是系统省电策略。
 *
 * 三层应对，从最关键到辅助：
 * 1. 申请「忽略电池优化」白名单——这是最有效的一步，不申请的话
 *    Doze 模式下网络会被完全切断；
 * 2. 申请「后台弹出界面」「自启动」等厂商权限（跳转设置页引导）；
 * 3. 持有 PARTIAL_WAKE_LOCK 维持 CPU，让心跳定时器能按时触发。
 */
object PowerHelper {

    /**
     * 当前应用是否已在电池优化白名单里。
     * 不在白名单时，息屏几分钟后系统就会进入 Doze，网络和定时器都被限制。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求忽略电池优化。
     *
     * 不同 ROM 对这个 Intent 的支持不一致：
     * 先尝试带包名的（会直接弹出该应用的授权框），
     * 不支持时退回列表页，再不支持则打开应用详情页让用户自己找。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return

        // 1. 直接弹框（Android 6.0+ 标准方式）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(direct) }.isSuccess) return
        }

        // 2. 电池优化列表页
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(list) }.isSuccess) return

        // 3. 应用详情页兜底
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /**
     * 跳转到厂商的自启动/后台管理页面。
     *
     * 这些是各家 ROM 私有页面，没有统一标准；按常见机型的组件名逐个尝试，
     * 全都失败就打开应用详情页。
     */
    fun openAutoStartSettings(context: Context) {
        val candidates = listOf(
            // 小米 / Redmi
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // OPPO / 一加 / 真我
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            // vivo
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            // 三星
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )

        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                setClassName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }

        // 兜底：打开应用详情页
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /** 是否是 MIUI 系（小米/红米），用于显示针对性的提示文案。 */
    fun isMiui(): Boolean {
        val brand = Build.MANUFACTURER.lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    }
}
