package com.example.locateme.guard

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * 隐藏配置页的访问控制。
 *
 * 设计目标：防止普通使用者误改设置或恶意解绑，同时不影响设备正常使用。
 *
 * 两道门：
 * 1. 手势：顺时针依次点右上 → 右下 → 左下 → 左上，重复若干轮，累计 N 次；
 * 2. 口令：手势通过后弹出输入框，口令正确才进配置页。
 *
 * 口令存在服务端配置里，设备端校验；也支持设备本地设置的口令作为覆盖。
 */
class HiddenAccess(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("guard_hidden_access", Context.MODE_PRIVATE)

    /** 设备本地设置的口令哈希；为空表示用服务端下发的口令。 */
    var localPasswordHash: String?
        get() = prefs.getString(KEY_PWD_HASH, null)
        set(value) = prefs.edit { putString(KEY_PWD_HASH, value) }

    /** 设备默认口令，服务端没配时用它。 */
    private val defaultPassword = "8888"

    /**
     * 校验口令。
     *
     * 优先用设备本地设置的，其次用服务端下发的，最后回落到默认口令。
     * 这样即使服务端配置丢失，设备也不会彻底锁死。
     */
    fun verifyPassword(input: String, serverPassword: String): Boolean {
        if (input.isBlank()) return false

        val local = localPasswordHash
        if (local != null) {
            return hash(input) == local
        }

        val expected = serverPassword.ifBlank { defaultPassword }
        return input == expected
    }

    fun setLocalPassword(password: String) {
        localPasswordHash = if (password.isBlank()) null else hash(password)
    }

    fun hasLocalPassword(): Boolean = localPasswordHash != null

    private fun hash(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 手势状态机。
     *
     * 顺序固定：右上(0) → 右下(1) → 左下(2) → 左上(3)，循环。
     * 点错了就重置计数，避免乱点凑巧触发。
     */
    class GestureTracker(private val requiredClicks: Int) {
        private var count = 0
        private var expectedCorner = 0

        /** 记录一次点击，返回是否已达成目标次数。 */
        fun onCorner(index: Int): Boolean {
            if (index != expectedCorner) {
                // 点错角：重置，但如果这次正好是起点则从 1 开始
                count = if (index == 0) 1 else 0
                expectedCorner = if (index == 0) 1 else 0
                return false
            }
            count++
            expectedCorner = (expectedCorner + 1) % 4
            return count >= requiredClicks
        }

        fun reset() {
            count = 0
            expectedCorner = 0
        }

        /** 当前进度，用于给用户微弱反馈（不显示数字，避免被察觉）。 */
        fun isInProgress(): Boolean = count > 0
    }

    companion object {
        private const val KEY_PWD_HASH = "local_password_hash"
    }
}
