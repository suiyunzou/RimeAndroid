/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.util.navigateWithAnim

/**
 * Base for every settings page migrated to the grouped inset list.
 *
 * It owns the page scaffold only: page-coloured background, 16 dp side padding, bottom breathing
 * room and one [SettingsBuilder]. Subclasses describe their cards in [buildPage]. The top bar
 * (title + back capsule) belongs to `MainActivity`, so nested pages automatically share it.
 */
abstract class SettingsPageFragment : Fragment() {
    protected lateinit var palette: Palette
    protected lateinit var builder: SettingsBuilder

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        palette = Palette.of(resolveDarkTheme())
        builder = SettingsBuilder(context, palette.dark)
        val scroll =
            ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(palette.background)
                setPadding(dp(16), dp(8), dp(16), dp(28))
            }
        scroll.addView(builder.body)
        buildPage(builder)
        return scroll
    }

    protected abstract fun buildPage(builder: SettingsBuilder)

    protected fun resolveDarkTheme(): Boolean =
        when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
            AppPrefs.Advanced.UiMode.DARK -> true
            AppPrefs.Advanced.UiMode.LIGHT -> false
            else -> SettingsPalette.isNightMode(requireContext())
        }

    protected fun dp(value: Int): Int = SettingsPalette.dp(requireContext(), value)

    protected fun navigate(route: NavigationRoute) {
        findNavController().navigateWithAnim(route)
    }

    /** Rebuilds the whole page, matching the reference pages' immediate visual feedback. */
    protected fun rerender() {
        val scroll = view as? ScrollView ?: return
        val previousY = scroll.scrollY
        val context = requireContext()
        palette = Palette.of(resolveDarkTheme())
        builder = SettingsBuilder(context, palette.dark)
        scroll.removeAllViews()
        scroll.setBackgroundColor(palette.background)
        scroll.addView(builder.body)
        buildPage(builder)
        scroll.post { scroll.scrollTo(0, previousY) }
    }
}
