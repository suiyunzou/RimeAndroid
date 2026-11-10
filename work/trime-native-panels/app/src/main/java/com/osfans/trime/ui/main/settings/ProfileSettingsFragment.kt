/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsDialogs
import com.osfans.trime.ui.settingskit.SettingsPageFragment
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.buildDocumentsProviderIntent
import com.osfans.trime.util.customFormatTimeInDefault
import com.osfans.trime.util.toast
import io.github.whiredplanck.storageaccess.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「配置」页面：数据存储方式、外部目录、同步与维护。
 *
 * 行为与旧的 androidx.preference 页面保持一致：
 * - 存储方式切换到「外部同步」且尚未授权目录时，先弹出选目录提示，取消则静默回滚；
 * - 外部目录行只在外部同步模式下可点，目录路径以副标题展示；
 * - 后台同步开关控制间隔行是否可编辑，间隔保存后按需重启后台同步任务；
 * - 维护区保留「浏览应用数据目录」与旧的多选重置流程。
 *
 * 页面顶栏（标题与返回胶囊）由 MainActivity 提供，这里只构建卡片内容。
 */
class ProfileSettingsFragment : SettingsPageFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val prefs = AppPrefs.defaultInstance().profile

    /** 与旧页面同一个目录选择库、同一个 Activity Result 入口。 */
    private val storageAccess = StorageAccess(this)

    /** 正在建立「外部同步」：用户取消选目录或导入失败都要回滚到应用专属存储。 */
    private var pendingExternalSyncSetup = false

    /** 首次 onResume 时页面刚构建完，只有之后回到页面才需要重建。 */
    private var pageShown = false

    override fun buildPage(builder: SettingsBuilder) {
        val ctx = requireContext()
        val mode = prefs.dataStorageMode.getValue()

        // 数据存储
        builder.space(12)
        builder.section(ctx.getString(R.string.storage))
        builder.group().let { card ->
            builder.row(
                card,
                ctx.getString(R.string.data_storage_mode),
                // 存储方式名称较长（英文尤其明显），放在副标题里避免挤占标题。
                summary = ctx.getString(mode.stringRes),
            ) { chooseDataStorageMode() }
            val pathRow =
                builder.row(
                    card,
                    ctx.getString(R.string.user_data_dir),
                    // 目录可能很长，放在副标题里换行，不挤占主标题。
                    summary = provideDataPathSummary(),
                ) { promptSelectAnotherDirectory() }
            // 与旧页面一致：只有外部同步模式才能重新选目录。
            applyEnabled(pathRow, mode == DataStorageMode.EXTERNAL_SYNC)
        }

        // 同步
        val backgroundSyncEnabled = prefs.periodicBackgroundSync.getValue()
        builder.space(12)
        builder.section(ctx.getString(R.string.synchronization))
        builder.group().let { card ->
            builder.row(card, ctx.getString(R.string.sync_user_data_immediately)) { syncNow() }
            builder.toggle(
                card,
                ctx.getString(R.string.periodic_background_sync),
                backgroundSyncEnabled,
                summary = backgroundSyncStatus().takeIf { backgroundSyncEnabled },
            ) { enabled ->
                prefs.periodicBackgroundSync.setValue(enabled)
                rerender()
            }
            val intervalRow =
                builder.row(
                    card,
                    ctx.getString(R.string.periodic_background_sync_interval),
                    prefs.periodicBackgroundSyncInterval.getValue().toString(),
                ) { editSyncInterval() }
            // 与旧页面一致：后台同步关闭时，间隔行置灰不可编辑。
            applyEnabled(intervalRow, backgroundSyncEnabled)
        }

        // 维护
        builder.space(12)
        builder.section(ctx.getString(R.string.maintenance))
        builder.group().let { card ->
            builder.row(
                card,
                ctx.getString(R.string.browse_app_data_dir),
                summary = DataManager.userDataDir.absolutePath,
            ) {
                runCatching {
                    ctx.startActivity(buildDocumentsProviderIntent())
                }.onFailure {
                    ctx.toast(R.string.browse_app_data_dir_failed)
                }
            }
            builder.row(
                card,
                ctx.getString(R.string.reset),
                summary = ctx.getString(R.string.reset_hint),
            ) { showResetDialog() }
        }
        builder.space(8)
    }

    override fun onResume() {
        super.onResume()
        if (pageShown) {
            // 目录授权、存储方式可能在系统设置页被改动，回到页面时重建一次。
            rerender()
        } else {
            pageShown = true
        }
        val ctx = requireContext()
        if (UserDataManager.useExternalSync() && !UserDataManager.hasExternalAccess()) {
            ctx.toast(R.string.data_path_permission_revoked)
        }
    }

    // ---------------------------------------------------------------------
    // 数据存储
    // ---------------------------------------------------------------------

    /** 旧页面的 ListPreference：切换即写入，并按新模式决定是否补选目录。 */
    private fun chooseDataStorageMode() {
        val ctx = requireContext()
        val modes = DataStorageMode.entries
        SettingsDialogs.choose(
            context = ctx,
            title = ctx.getString(R.string.data_storage_mode),
            labels = modes.map { ctx.getString(it.stringRes) },
            selected = modes.indexOf(prefs.dataStorageMode.getValue()).coerceAtLeast(0),
        ) { index -> applyDataStorageMode(modes[index]) }
    }

    private fun applyDataStorageMode(mode: DataStorageMode) {
        val oldMode = prefs.dataStorageMode.getValue()
        prefs.dataStorageMode.setValue(mode)
        if (
            oldMode == DataStorageMode.APP_STORAGE &&
            mode == DataStorageMode.EXTERNAL_SYNC &&
            !UserDataManager.hasExternalAccess()
        ) {
            promptExternalSyncFolderSelection()
        }
        // 切回应用专属时保留已选目录与授权，切回外部同步无需重新选择。
        rerender()
    }

    /** 外部目录的显示文本：去掉公共存储前缀，与旧页面的 summary 完全一致。 */
    private fun provideDataPathSummary(): String? {
        val externalRimeDir = prefs.externalRimeTreeUri.getValue()
        if (externalRimeDir.isEmpty()) return null
        val dirUri = Uri.parse(externalRimeDir)
        val docId = DocumentsContract.getTreeDocumentId(dirUri)
        return if (docId.contains(':')) {
            val (volume, rel) = docId.split(':', limit = 2)
            when (volume) {
                "raw" -> rel
                "primary" -> "/$rel"
                else -> "/storage/$volume/$rel"
            }
        } else {
            docId
        }.removePrefix("/storage/emulated/0")
    }

    private fun promptSelectAnotherDirectory() {
        AlertDialog
            .Builder(requireContext())
            .setMessage(R.string.select_another_directory_to_sync)
            .setPositiveButton(R.string.select_another_directory) { _, _ ->
                // 当前目录要等用户确实选中新目录后才替换。
                pickDataPath()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptExternalSyncFolderSelection() {
        AlertDialog
            .Builder(requireContext())
            .setMessage(R.string.external_sync_select_folder_message)
            .setPositiveButton(R.string.setup__select_data_path) { _, _ ->
                pendingExternalSyncSetup = true
                pickDataPath()
            }.setNegativeButton(android.R.string.cancel) { _, _ -> cancelExternalSyncSetup() }
            .setOnCancelListener { cancelExternalSyncSetup() }
            .show()
    }

    /** 用户放弃选择目录时回滚到应用专属存储。 */
    private fun cancelExternalSyncSetup() {
        prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
        rerender()
    }

    /** 建立外部同步失败时回滚，并说明原因。 */
    private fun fallbackToAppStorage() {
        prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
        AlertDialog
            .Builder(requireContext())
            .setMessage(R.string.external_sync_fallback_app_storage)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun pickDataPath() {
        lifecycleScope.launch {
            val picked = storageAccess.pickDirectory()
            if (picked == null) {
                // 取消选择不打扰用户；未完成的外部同步切换静默回滚。
                if (pendingExternalSyncSetup) {
                    pendingExternalSyncSetup = false
                    cancelExternalSyncSetup()
                }
                return@launch
            }
            val ctx = requireContext()
            val externalSyncSetup = pendingExternalSyncSetup
            pendingExternalSyncSetup = false
            withLoadingDialog(ctx) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        UserDataManager.persistTreeUri(picked.uri)
                        viewModel.rime.runOnReady { deploy() }
                    }
                }.onSuccess {
                    ctx.toast(R.string.setup__data_path_imported)
                }.onFailure {
                    if (externalSyncSetup) {
                        fallbackToAppStorage()
                    } else {
                        // 保留用户选中的目录，持续失败时由部署流程自行回退。
                        ctx.toast(R.string.setup__data_path_import_failed)
                    }
                }
            }
            rerender()
        }
    }

    // ---------------------------------------------------------------------
    // 同步
    // ---------------------------------------------------------------------

    private fun syncNow() {
        val ctx = requireContext()
        lifecycleScope.launch {
            withLoadingDialog(ctx) {
                runCatching {
                    viewModel.rime.runOnReady { syncUserData() }
                }.onSuccess { success ->
                    ctx.toast(
                        when {
                            !success -> R.string.sync_user_data_failure

                            UserDataManager.useExternalSync() -> R.string.sync_user_data_success_external

                            else -> R.string.sync_user_data_success
                        },
                    )
                }.onFailure {
                    ctx.toast(R.string.sync_user_data_failure)
                }
            }
        }
    }

    /** 后台同步状态副标题，与旧页面的 summaryProvider 文案一致。 */
    private fun backgroundSyncStatus(): String {
        val lastTime = prefs.lastBackgroundSyncTime.getValue()
        val lastStatus = prefs.lastBackgroundSyncStatus.getValue()
        val timeText = if (lastTime != 0L) customFormatTimeInDefault("yyyy-MM-dd HH:mm", lastTime) else "N/A"
        val statusText = if (lastTime != 0L) getString(if (lastStatus) R.string.success else R.string.failure) else "N/A"
        return getString(R.string.periodic_background_sync_status, timeText, statusText)
    }

    private fun editSyncInterval() {
        val ctx = requireContext()
        val current = prefs.periodicBackgroundSyncInterval.getValue()
        SettingsDialogs.text(
            context = ctx,
            title = ctx.getString(R.string.periodic_background_sync_interval),
            initial = current.toString(),
            hint = current.toString(),
            inputType = InputType.TYPE_CLASS_NUMBER,
        ) { raw ->
            // 与旧 EditTextIntPreference 一致：非数字忽略，低于下限按下限保存。
            val parsed = raw.trim().toIntOrNull()
            if (parsed != null) {
                prefs.periodicBackgroundSyncInterval.setValue(parsed.coerceAtLeast(MIN_SYNC_INTERVAL_MINUTES))
                if (prefs.periodicBackgroundSync.getValue()) {
                    viewModel.restartBackgroundSyncWork.value = true
                }
                rerender()
            }
        }
    }

    // ---------------------------------------------------------------------
    // 维护
    // ---------------------------------------------------------------------

    /** 旧页面的重置流程：多选内置 shared 资源，覆盖写入共享目录。 */
    private fun showResetDialog() {
        val ctx = requireContext()
        val items = ctx.assets.list("shared") ?: return
        val checked = BooleanArray(items.size) { false }
        AlertDialog
            .Builder(ctx)
            .setTitle(R.string.reset)
            .setMultiChoiceItems(items, checked) { _, id, isChecked ->
                checked[id] = isChecked
            }.setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    var res = true
                    withLoadingDialog(ctx) {
                        withContext(Dispatchers.IO) {
                            res =
                                items
                                    .filterIndexed { index, _ -> checked[index] }
                                    .fold(true) { acc, asset ->
                                        val destPath = DataManager.sharedDataDir.resolve(asset).absolutePath
                                        ResourceUtils
                                            .copyFile("shared/$asset", destPath)
                                            .fold({ acc and true }, { acc and false })
                                    }
                        }
                    }
                    ctx.toast(if (res) R.string.reset_success else R.string.reset_failure)
                }
            }.show()
    }

    private fun applyEnabled(
        view: View,
        enabled: Boolean,
    ) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.45f
        if (!enabled) view.setOnClickListener(null)
    }

    companion object {
        /** 后台同步间隔下限（分钟），与旧 EditTextIntPreference 的 min 一致。 */
        private const val MIN_SYNC_INTERVAL_MINUTES = 15
    }
}
