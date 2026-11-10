/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.ime.smart.AutoUpdatePolicy
import com.osfans.trime.ime.smart.PackInstaller
import com.osfans.trime.ime.smart.PackStore
import com.osfans.trime.ime.smart.UpdateResult
import com.osfans.trime.ime.smart.WordLibraryActivity
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 公开词包的后台自动更新。
 *
 * 策略层 [AutoUpdatePolicy] 只输出纯数据约束；本类负责把约束映射成 WorkManager 的
 * [Constraints] **并真正入队** —— 这是「自动更新」从"只存开关状态"变成实际行为的接缝。
 *
 * 默认**关闭**：只有用户在「词库」页打开「自动更新」后才会入队；
 * 关闭时取消已存在的唯一任务。
 *
 * 断网/坏包时旧词包必定保持不变（由 [PackInstaller] 的原子替换保证）。
 */
class PackAutoUpdateWork(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val policy = currentPolicy(applicationContext)
        if (!policy.enabled) {
            Timber.i("PackAutoUpdate: 未开启，跳过")
            return Result.success()
        }
        // 「仅 Wi-Fi」由 WorkManager 的网络约束强制（Manifest 已恢复
        // ACCESS_NETWORK_STATE；此前该权限被 tools:node="remove" 移除，
        // 导致带连通性约束的任务被系统拒绝调度）。
        return doUpdate()
    }

    private suspend fun doUpdate(): Result =
        runCatching {
            val root: File = DataManager.userDataDir
            val store = PackStore(root) { from, to ->
                if (!from.renameTo(to)) throw java.io.IOException("rename failed")
            }
            val installer = PackInstaller(root, store, PackInstaller.HttpFetcher())
            when (val result = installer.checkAndUpdate(WordLibraryActivity.MANIFEST_URL)) {
                is UpdateResult.Success -> {
                    Timber.i("PackAutoUpdate 成功：%s", result.display())
                    Result.success()
                }
                is UpdateResult.NoUpdate -> {
                    Timber.i("PackAutoUpdate：已是最新")
                    Result.success()
                }
                is UpdateResult.NetworkError -> {
                    // 可恢复：保留旧版，交给 WorkManager 退避重试。
                    Timber.w("PackAutoUpdate 网络不可用，保留旧版：%s", result.display())
                    Result.retry()
                }
                is UpdateResult.BadPack -> {
                    // 校验失败不属于瞬态故障，重试大概率仍失败；保留旧版并结束。
                    Timber.w("PackAutoUpdate 词包校验失败，保留旧版：%s", result.display())
                    Result.success()
                }
                is UpdateResult.Cancelled -> {
                    Timber.i("PackAutoUpdate 已取消，旧版未改动")
                    Result.success()
                }
            }
        }.getOrElse {
            Timber.e(it, "PackAutoUpdate 异常")
            Result.retry()
        }

    companion object {
        private const val PERIODIC_PACK_UPDATE_KEY = "periodic_pack_update"

        /**
         * 「自动更新」开关与 [WordLibraryActivity] 使用完全相同的键**和同一个存储文件**
         * （`smart_input`），避免两处各读各的造成分歧。
         */
        const val KEY_AUTO_UPDATE = "word_library_auto_update"

        /** 与 `WordLibraryActivity.autoPrefs` 一致的存储名。 */
        private const val PREFS_NAME = "smart_input"

        /** 读取当前生效策略；未开启时返回 [AutoUpdatePolicy.DEFAULT]（关闭）。 */
        fun currentPolicy(context: Context): AutoUpdatePolicy {
            val enabled = runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_UPDATE, false)
            }.getOrDefault(false)
            return if (enabled) AutoUpdatePolicy.enabled() else AutoUpdatePolicy.DEFAULT
        }

        /** 按当前开关状态入队或取消后台更新任务。 */
        fun sync(context: Context) {
            start(context, currentPolicy(context))
        }

        fun start(context: Context, policy: AutoUpdatePolicy = currentPolicy(context)) {
            val normalized = policy.normalized()
            val instance = WorkManager.getInstance(context)
            if (!normalized.shouldSchedule) {
                instance.cancelUniqueWork(PERIODIC_PACK_UPDATE_KEY)
                Timber.i("PackAutoUpdate canceled（策略关闭）")
                return
            }
            val constraints =
                Constraints
                    .Builder()
                    // 仅 Wi-Fi（不计流量）时联网才更新。Manifest 已恢复 ACCESS_NETWORK_STATE，
                    // 该约束才能被系统正常调度。
                    .setRequiredNetworkType(
                        if (normalized.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    ).setRequiresBatteryNotLow(normalized.requiresBatteryNotLow)
                    .build()
            val hours =
                normalized.intervalHours.coerceIn(
                    AutoUpdatePolicy.MIN_INTERVAL_HOURS,
                    AutoUpdatePolicy.MAX_INTERVAL_HOURS,
                )
            val request =
                PeriodicWorkRequestBuilder<PackAutoUpdateWork>(hours.toLong(), TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
            instance.enqueueUniquePeriodicWork(
                PERIODIC_PACK_UPDATE_KEY,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Timber.i("PackAutoUpdate scheduled：每 %d 小时，unmetered=%b", hours, normalized.unmeteredOnly)
        }
    }
}
