// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/**
 * 公开词包自动更新策略。默认**关闭**，必须由用户在设置里主动打开。
 *
 * 这里刻意不引用 androidx.work：只输出纯数据约束，由 UI/Worker 层自行映射成
 * WorkManager 的 Constraints。这样策略逻辑可以在 JVM 单测里直接跑。
 */
data class AutoUpdatePolicy(
    /** 总开关。默认 false —— 任何联网行为都必须由用户显式同意。 */
    val enabled: Boolean = false,
    /** 是否只在 UNMETERED（Wi-Fi 等不计流量）网络下更新。开启时恒为 true。 */
    val unmeteredOnly: Boolean = true,
    /** 是否要求设备已联网。开启时恒为 true（否则自动更新毫无意义）。 */
    val requiresNetwork: Boolean = true,
    /** 是否允许漫游网络。默认不允许。 */
    val allowRoaming: Boolean = false,
    /** 是否要求电量不低。默认要求，避免后台下载耗电。 */
    val requiresBatteryNotLow: Boolean = true,
    /** 两次自动检查之间的最小间隔（小时）。[MIN_INTERVAL_HOURS]..[MAX_INTERVAL_HOURS]。 */
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
) {
    /** 非空表示可以交给 WorkManager；空表示不注册任何后台任务。 */
    val shouldSchedule: Boolean get() = enabled

    /** 供 UI 展示的约束摘要，例如「仅 Wi-Fi · 已联网 · 电量充足」。 */
    val constraintsLabel: String
        get() = buildList {
            add(if (unmeteredOnly) "仅 Wi-Fi（不计流量）" else "任意网络")
            if (requiresNetwork) add("需已联网")
            if (!allowRoaming) add("不使用漫游")
            if (requiresBatteryNotLow) add("电量充足")
        }.joinToString(" · ")

    fun normalized(): AutoUpdatePolicy = copy(
        // 开启自动更新时，联网与不计流量是硬性前提，不能被单独关掉。
        unmeteredOnly = if (enabled) true else unmeteredOnly,
        requiresNetwork = if (enabled) true else requiresNetwork,
        intervalHours = intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS),
    )

    companion object {
        const val MIN_INTERVAL_HOURS = 6
        const val MAX_INTERVAL_HOURS = 24 * 30
        const val DEFAULT_INTERVAL_HOURS = 24

        /** 出厂默认：关闭，其余约束按开启时的默认值预置好，UI 打开开关即可直接生效。 */
        val DEFAULT = AutoUpdatePolicy(enabled = false).normalized()

        /** 用户打开开关后的策略，统一收敛所有"必须为真"的约束。 */
        fun enabled(intervalHours: Int = DEFAULT_INTERVAL_HOURS): AutoUpdatePolicy =
            AutoUpdatePolicy(enabled = true, intervalHours = intervalHours).normalized()

        /** 当前网络是否满足策略（由调用方用 ConnectivityManager 的结果回答）。 */
        fun permitsNetwork(policy: AutoUpdatePolicy, unmetered: Boolean, connected: Boolean, roaming: Boolean = false): Boolean =
            policy.enabled && connected && (!policy.requiresNetwork || connected) &&
                (!policy.unmeteredOnly || unmetered) && (policy.allowRoaming || !roaming)
    }
}
