package com.example.locateme.guard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「打开管理页」指令通道。
 *
 * 设计原则：**管理页开不开，由服务端决定，不在设备端自行判断**。
 *
 * 为什么这样更合理：
 * - 设备端判断（比如看剪贴板有没有 Key）容易受网络、缓存、
 *   剪贴板被系统清理等因素影响，出现"明明没 Key 却进了管理页"
 *   或"有 Key 却进不去"的不确定行为；
 * - 服务端判断则有明确的授权来源（管理员操作），
 *   而且可以记录谁在什么时候对哪台设备发起了操作。
 *
 * 实现方式：服务端下发 open_admin 指令 → 设备收到后在
 * 下次进入前台（或正处前台时立刻）切到管理页，仍需口令解锁。
 *
 * 使用一次性请求语义：用 consume() 清空，避免 Activity 重建时
 * 读到旧请求又跳一次管理页。
 */
object AdminGate {

    private val _request = MutableStateFlow(0L)

    /** 请求时间戳，0 表示没有待处理请求。 */
    val request = _request.asStateFlow()

    /** 记录一次打开请求。 */
    fun requestOpen() {
        _request.value = System.currentTimeMillis()
    }

    /** 消费掉请求，避免重复触发。 */
    fun consume() {
        _request.value = 0L
    }

    /** 是否有待处理的请求。 */
    fun hasPending(): Boolean = _request.value > 0
}
