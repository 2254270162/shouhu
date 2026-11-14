package com.example.locateme.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 权限状态检查与上报。
 *
 * 为什么要上报：PDA 部署在现场，管理员看不到屏幕，
 * 出了问题常见原因就是某个权限被拒（尤其是"拒绝后不再询问"）。
 * 把状态报上去，后台就能直接指出缺哪个、怎么补。
 */
object PermissionReporter {

    /** 一项权限的说明与状态。 */
    data class Item(
        val key: String,
        val label: String,
        val granted: Boolean,
        val reason: String,
        /** 是否是特殊权限（无法弹窗，只能去设置页手动开）。 */
        val special: Boolean = false,
    )

    /** 收集本机运行所需的全部权限状态。 */
    fun collect(context: Context): List<Item> {
        val list = mutableListOf<Item>()

        list += check(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
            "location",
            "定位",
            "用于上报设备位置",
        )
        list += check(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "location_coarse",
            "大致定位",
            "定位权限的降级选项",
        )
        list += check(
            context,
            Manifest.permission.CAMERA,
            "camera",
            "相机",
            "用于远程拍照",
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += check(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
                "notification",
                "通知",
                "前台服务需要常驻通知；没有它守护会在通知栏不可见",
            )
            list += check(
                context,
                Manifest.permission.READ_MEDIA_IMAGES,
                "media",
                "相册",
                "用于同步照片",
            )
        } else {
            list += check(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                "media",
                "存储",
                "用于同步照片",
            )
        }

        // 使用情况访问是特殊权限，不能弹窗
        list += Item(
            key = "usage_access",
            label = "使用情况访问",
            granted = Telemetry.hasUsageAccess(context),
            reason = "用于读取前台应用；需在系统设置里手动开启，无法弹窗申请",
            special = true,
        )

        return list
    }

    private fun check(context: Context, permission: String, key: String, label: String, reason: String): Item {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return Item(key, label, granted, reason)
    }

    /** 转成可上报的 JSON。 */
    fun toJson(context: Context): JSONObject {
        val items = collect(context)
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("key", item.key)
                    .put("label", item.label)
                    .put("granted", item.granted)
                    .put("reason", item.reason)
                    .put("special", item.special),
            )
        }
        val missing = items.filter { !it.granted }
        return JSONObject()
            .put("items", arr)
            .put("missingCount", missing.size)
            .put("missingLabels", missing.joinToString("、") { it.label })
            // 只要有一个关键权限缺失，就标记为不完整
            .put("complete", missing.none { !it.special })
    }

    /**
     * 仍需申请的运行时权限（能弹窗的那些）。
     * 特殊权限和已被"拒绝且不再询问"的一并排除，避免反复弹窗惹恼用户。
     */
    fun missingRuntimePermissions(context: Context, activity: android.app.Activity?): Array<String> {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.READ_MEDIA_IMAGES
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return wanted.filter { p ->
            val granted = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            if (granted) return@filter false
            // 用户选了"不再询问"时，系统不会再弹，只能引导去设置页
            val shouldShow = activity?.let {
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, p)
            } ?: false
            // 从未请求过（shouldShow 为 false 且未授予）也要申请一次
            shouldShow || !hasRequestedBefore(context, p)
        }.toTypedArray()
    }

    /**
     * 把权限 key 列表转成 Android 权限常量。
     * 用于响应服务端下发的「请求这些权限」。
     */
    fun permissionsFor(keys: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (k in keys) {
            when (k) {
                "location" -> {
                    out += Manifest.permission.ACCESS_FINE_LOCATION
                    out += Manifest.permission.ACCESS_COARSE_LOCATION
                }
                "camera" -> out += Manifest.permission.CAMERA
                "notification" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        out += Manifest.permission.POST_NOTIFICATIONS
                    }
                }
                "media" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        out += Manifest.permission.READ_MEDIA_IMAGES
                    } else {
                        out += Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                }
                // usage_access 是特殊权限，无法弹窗，这里跳过
                else -> Unit
            }
        }
        return out.distinct()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("perm_tracking", Context.MODE_PRIVATE)

    private fun hasRequestedBefore(context: Context, permission: String): Boolean =
        prefs(context).getBoolean("asked_$permission", false)

    /** 记录已经请求过某权限，用于区分"从未问过"和"问了被拒"。 */
    fun markRequested(context: Context, permissions: Array<String>) {
        prefs(context).edit().apply {
            permissions.forEach { putBoolean("asked_$it", true) }
        }.apply()
    }
}
