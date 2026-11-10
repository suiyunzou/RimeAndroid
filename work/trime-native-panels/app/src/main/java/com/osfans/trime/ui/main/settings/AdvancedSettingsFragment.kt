/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

/**
 * The 「高级」 page.
 *
 * Besides the preferences owned by [AppPrefs.Advanced], this page is the single home for the
 * scheme / deploy / backup entry points: 「输入方案」, 「部署」, 「数据目录与备份」 and
 * 「词库更新数据」 all live in one group here, so users no longer have to hunt for the deploy
 * button in the toolbar or for the schema and profile pages elsewhere.
 *
 * The page stays inside the `androidx.preference` idiom ([PreferenceDelegateFragment] +
 * [PreferenceCategory]); the visual restyle of the native pages is a later phase.
 */
class AdvancedSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().advanced) {

    private val viewModel: MainViewModel by activityViewModels()

    private val uiMode = AppPrefs.defaultInstance().advanced.uiMode

    private val showAppIcon = AppPrefs.defaultInstance().advanced.showAppIcon

    @Keep
    private val onUiModeChange = PreferenceDelegate.OnChangeListener<AppPrefs.Advanced.UiMode> { _, v ->
        val mode = when (v) {
            AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    @Keep
    private val onShowAppIconChange = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        showAppIcon(requireContext(), v)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiMode.registerOnChangeListener(onUiModeChange)
        showAppIcon.registerOnChangeListener(onShowAppIconChange)
    }

    override fun onDestroy() {
        uiMode.unregisterOnChangeListener(onUiModeChange)
        showAppIcon.unregisterOnChangeListener(onShowAppIconChange)
        super.onDestroy()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        super.onPreferenceUiCreated(screen)
        // Appended after the preferences created by the delegate, so no existing item is moved
        // or removed by adding this group.
        screen.addCategory(R.string.advanced_group_scheme_deploy_backup) {
            addSchemeDeployBackupEntries()
        }
    }

    /**
     * Deploy once the keyboard engine is ready.
     *
     * Deliberately delegated to [MainViewModel.deploy], the very same call the toolbar button of
     * the main page uses, so this page can never drift away from the toolbar behaviour.
     */
    private fun deploy() = viewModel.deploy()

    private fun PreferenceCategory.addSchemeDeployBackupEntries() {
        isIconSpaceReserved = false
        // Resolved once from the fragment: the preferences below are created on this
        // PreferenceCategory, which is not a Context, so it cannot resolve strings itself.
        val ctx = context
        addDestinationPreference(
            R.string.advanced_entry_scheme,
            R.string.advanced_entry_scheme_summary,
            R.drawable.ic_round_view_list_24,
            NavigationRoute.SchemaList,
        )
        addPreference(
            R.string.deploy,
            ctx.getString(R.string.advanced_entry_deploy_summary),
            R.drawable.ic_baseline_refresh_reversed_24,
        ) {
            deploy()
        }
        addDestinationPreference(
            R.string.advanced_entry_backup,
            R.string.advanced_entry_backup_summary,
            R.drawable.ic_baseline_snippet_folder_24,
            NavigationRoute.Profile,
        )
        addPreference(
            R.string.advanced_entry_word_library,
            R.string.advanced_entry_word_library_summary,
            R.drawable.ic_baseline_book_24,
        ) {
            // TODO(task-4): open the unified word library page once WordLibraryActivity exists,
            // e.g. startActivity(Intent(requireContext(), WordLibraryActivity::class.java)).
            // Until then this row only carries an explanation, and stays tappable on purpose so
            // the entry point is already in place.
            // NOTE: WordLibraryActivity is not referenced yet on purpose -- a hard reference
            // would not compile before task-4 lands.
        }
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @StringRes summary: Int,
        icon: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, summary, icon) {
            navigateTo(route)
        }
    }

    private fun navigateTo(route: NavigationRoute) {
        val navController: NavController = findNavController()
        navController.navigateWithAnim(route)
    }

    companion object {
        private const val SETTINGS_ACTIVITY_NAME = "com.osfans.trime.MainLauncherAlias"

        fun showAppIcon(context: Context, enable: Boolean) {
            if (enable) {
                com.osfans.trime.ime.smart.SmartIconSwitcher.select(context, "default")
                return
            }
            val state = if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, SETTINGS_ACTIVITY_NAME),
                state,
                PackageManager.DONT_KILL_APP,
            )
            for (name in listOf("com.osfans.trime.BlueLauncherAlias", "com.osfans.trime.GreenLauncherAlias")) {
                context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, name), state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
