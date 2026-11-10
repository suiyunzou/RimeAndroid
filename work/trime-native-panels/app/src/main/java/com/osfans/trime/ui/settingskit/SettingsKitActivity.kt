/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.osfans.trime.data.prefs.AppPrefs

/**
 * Base for standalone settings activities (dictionary, model, privacy, feedback, ...).
 *
 * Provides the same scaffold as [SettingsPageFragment]: page-coloured background, top bar with a
 * centred title and white 「‹ 返回」 capsule, and a scrollable [SettingsBuilder]. Subclasses only
 * describe their cards in [buildPage].
 */
abstract class SettingsKitActivity : Activity() {
    protected lateinit var palette: Palette
    protected lateinit var builder: SettingsBuilder
    private lateinit var scroll: ScrollView

    override fun onCreate(state: Bundle?) {
        // Platform theme follows day/night so the platform dialogs used by these pages match the
        // palette instead of always rendering dark.
        val dark = resolveDarkTheme()
        setTheme(
            if (dark) {
                android.R.style.Theme_Material_NoActionBar
            } else {
                android.R.style.Theme_Material_Light_NoActionBar
            },
        )
        super.onCreate(state)
        palette = Palette.of(dark)
        builder = SettingsBuilder(this, palette.dark)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(palette.background)
                fitsSystemWindows = true
            }
        root.addView(
            SettingsTopBar.create(this, pageTitle(), palette, { finish() }),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)),
        )
        scroll =
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setPadding(dp(16), dp(7), dp(16), dp(24))
            }
        scroll.addView(builder.body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (!palette.dark && Build.VERSION.SDK_INT >= 26) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                0
            }
        buildPage(builder)
    }

    protected abstract fun pageTitle(): String

    protected abstract fun buildPage(builder: SettingsBuilder)

    protected fun resolveDarkTheme(): Boolean =
        when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
            AppPrefs.Advanced.UiMode.DARK -> true
            AppPrefs.Advanced.UiMode.LIGHT -> false
            else -> SettingsPalette.isNightMode(this)
        }

    protected fun dp(value: Int): Int = SettingsPalette.dp(this, value)

    /** Rebuilds the page in place; used after a toggle that changes other rows' availability. */
    protected fun rerender() {
        val previousY = scroll.scrollY
        val context = this
        palette = Palette.of(resolveDarkTheme())
        builder = SettingsBuilder(context, palette.dark)
        scroll.removeAllViews()
        scroll.addView(builder.body)
        buildPage(builder)
        scroll.post { scroll.scrollTo(0, previousY) }
    }
}
