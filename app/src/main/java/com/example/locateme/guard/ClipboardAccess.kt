package com.example.locateme.guard

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray

/**
 * 剪贴板读取与历史记录。
 *
 * 为什么需要历史：Android 没有公开的"剪贴板历史"API，只能自己记。
 * 实际场景是：管理员复制了 Key，接着又复制了别的（比如发给同事），
 * 回头再打开 App 时剪贴板已经不是 Key 了。记最近 N 条就能覆盖这种情况。
 *
 * 记录方式：App 每次在前台读到剪贴板变化就追加到本地历史，
 * 最多保留 10 条，去重，最新在前。
 */
object ClipboardAccess {

    private const val PREFS = "clipboard_history"
    private const val KEY_HISTORY = "items"
    private const val MAX_HISTORY = 10

    /**
     * 读取当前剪贴板文本并清洗。
     * @return 清洗后的文本；读不到或无内容返回 null
     */
    fun readText(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null

            val raw = clip.getItemAt(0).coerceToText(context)?.toString() ?: return null
            sanitize(raw).ifBlank { null }
        } catch (e: Exception) {
            // 部分 ROM 在后台或锁屏时禁止读取，属于正常限制
            null
        }
    }

    /**
     * 清洗剪贴板内容。
     *
     * 去掉首尾空白、换行、常见引号包裹。
     * 用户在聊天软件里复制时常会带上这些，不清掉会导致比对失败。
     */
    fun sanitize(raw: String): String {
        var s = raw.trim()

        // 去掉首尾成对的引号（"xxx" 或 'xxx' 或 “xxx”）
        val quotes = listOf("\"" to "\"", "'" to "'", "“" to "”", "「" to "」")
        for ((open, close) in quotes) {
            if (s.length >= 2 && s.startsWith(open) && s.endsWith(close)) {
                s = s.substring(open.length, s.length - close.length).trim()
                break
            }
        }

        // 折叠内部换行与制表符（复制多行文本时常见）
        s = s.replace(Regex("[\\r\\n\\t]+"), "").trim()
        return s
    }

    /**
     * 记录一条剪贴板内容到历史。
     *
     * 去重：相同内容只保留一份，并移到最前。
     * 截断：只保留最近 MAX_HISTORY 条。
     */
    fun recordClipboard(context: Context, text: String) {
        if (text.isBlank()) return
        val history = getHistory(context).toMutableList()

        // 已存在则先移除，再插到最前，实现"最近使用优先"
        history.removeAll { it == text }
        history.add(0, text)

        val trimmed = history.take(MAX_HISTORY)
        saveHistory(context, trimmed)
    }

    /** 读取历史（最新在前）。 */
    fun getHistory(context: Context): List<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 写入历史。 */
    fun saveHistory(context: Context, items: List<String>) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray()
            items.take(MAX_HISTORY).forEach { arr.put(it) }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }

    /**
     * 把当前剪贴板追加到历史。进入前台时调用一次即可。
     * @return 当前剪贴板内容（已清洗）
     */
    fun syncAndGetCurrent(context: Context): String? {
        val text = readText(context) ?: return null
        recordClipboard(context, text)
        return text
    }

    /**
     * 校验是否有 Key 匹配。
     *
     * 匹配范围：当前剪贴板 + 历史记录。
     * 这样即使管理员复制完 Key 又复制了别的内容，只要历史里还有 Key 就能通过。
     *
     * @return 匹配到的 Key；没有匹配返回 null
     */
    fun findMatchingKey(context: Context, expectedKey: String, useHistory: Boolean = true): String? {
        if (expectedKey.isBlank()) return null
        val expected = expectedKey.trim()

        val current = readText(context)
        if (current != null && constantTimeEquals(current, expected)) {
            recordClipboard(context, current)
            return current
        }

        if (useHistory) {
            val history = getHistory(context)
            for (item in history) {
                if (constantTimeEquals(item, expected)) return item
            }
        }
        return null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        // 长度不等直接失败；长度相同则逐字节比完，不提前返回
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    /** 把内容写入剪贴板（给"生成 Key 后一键复制"用）。 */
    fun writeText(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(android.content.ClipData.newPlainText("key", text))
        }
    }

    /** 清空历史。 */
    fun clearHistory(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
        }
    }
}
