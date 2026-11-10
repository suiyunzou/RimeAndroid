/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.content.Context
import android.os.Build
import android.widget.LinearLayout
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.prefs.PreferenceDelegateUi

/**
 * Renders one [PreferenceDelegateProvider] (the same model that fed the old
 * `androidx.preference` pages) as the grouped inset list of the reference design.
 *
 * Pages keep their behaviour: every switch/row writes the very same delegate and calls
 * [PreferenceDelegateProvider.notifyChange], so existing listeners, visibility rules and side
 * effects still run. Subclasses only choose section titles and key order.
 */
abstract class DelegateSettingsPageFragment(
    private val provider: PreferenceDelegateProvider,
) : SettingsPageFragment() {
    protected data class Section(
        val title: String?,
        val keys: List<String>,
    )

    private val changeListener = PreferenceDelegateProvider.OnChangeListener { rerender() }

    /** Sections for this page; default keeps the provider declaration order in one card. */
    protected open fun sections(): List<Section> =
        listOf(Section(null, provider.preferenceDelegatesUi.map { it.key }))

    /** Lets a page replace the default editor for one key (e.g. a theme or sound picker). */
    protected open fun customClick(key: String): (() -> Unit)? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        provider.registerOnChangeListener(changeListener)
    }

    override fun onDestroy() {
        provider.unregisterOnChangeListener(changeListener)
        super.onDestroy()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AppPrefs.defaultInstance().syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }

    final override fun buildPage(builder: SettingsBuilder) {
        val uiByKey = provider.preferenceDelegatesUi.associateBy { it.key }
        sections().forEach { section ->
            val rows = section.keys.mapNotNull { uiByKey[it] }
            if (rows.isEmpty()) return@forEach
            builder.space(12)
            section.title?.let { builder.section(it) }
            val card = builder.group()
            rows.forEach { render(builder, card, it) }
        }
        buildExtra(builder)
    }

    /** Lets a page append rows that do not come from the delegate provider. */
    protected open fun buildExtra(builder: SettingsBuilder) {}

    private fun render(
        builder: SettingsBuilder,
        card: LinearLayout,
        ui: PreferenceDelegateUi<*>,
    ) {
        val context = requireContext()
        val title = context.getString(ui.title)
        val summary = ui.summary?.let { context.getString(it) }
        val enabled = ui.isEnabled()
        val custom = customClick(ui.key)
        when (ui) {
            is PreferenceDelegateUi.Switch -> {
                val current = currentValue(ui.key) as? Boolean ?: ui.defaultValue
                val switch =
                    builder.toggle(card, title, current, summary = summary) { checked ->
                        setValue(ui.key, checked)
                    }
                if (!enabled) {
                    (switch.parent as? android.view.View)?.apply {
                        this.isEnabled = false
                        alpha = 0.45f
                        setOnClickListener(null)
                    }
                    switch.isEnabled = false
                }
            }

            is PreferenceDelegateUi.StringLike -> {
                builder.row(card, title, summary = summary)
                applyEnabled(card.getChildAt(card.childCount - 1), enabled)
            }

            is PreferenceDelegateUi.EditText -> {
                val current = currentValue(ui.key)?.toString().orEmpty()
                val row =
                    builder.row(card, title, current.ifEmpty { summary.orEmpty() }) {
                        custom?.invoke() ?: SettingsDialogs.text(
                            context = context,
                            title = title,
                            initial = current,
                            hint = summary.orEmpty(),
                            multiline = false,
                            message = ui.message?.let { context.getString(it) },
                        ) { value -> setValue(ui.key, value) }
                    }
                applyEnabled(row, enabled)
            }

            is PreferenceDelegateUi.EditTextInt -> {
                val current = (currentValue(ui.key) as? Int) ?: ui.defaultValue
                val row =
                    builder.row(card, title, "${current}${ui.unit}") {
                        SettingsDialogs.number(
                            context = context,
                            title = title,
                            initial = current,
                            min = ui.min,
                            max = ui.max,
                            unit = ui.unit,
                        ) { value -> setValue(ui.key, value) }
                    }
                applyEnabled(row, enabled)
            }

            is PreferenceDelegateUi.SeekBarInt -> {
                val current = (currentValue(ui.key) as? Int) ?: ui.defaultValue
                val row =
                    builder.row(card, title, seekBarLabel(context, ui, current)) {
                        SettingsDialogs.slider(
                            context = context,
                            title = title,
                            value = current,
                            min = ui.min,
                            max = ui.max,
                            step = ui.step,
                            unit = ui.unit,
                            defaultValue = ui.defaultValue,
                            defaultLabel = ui.defaultLabel?.let { context.getString(it) },
                            useMinAsDefault = ui.useMinAsDefault,
                        ) { value -> setValue(ui.key, value) }
                    }
                applyEnabled(row, enabled)
            }

            is PreferenceDelegateUi.StringList<*> -> renderStringList(builder, card, ui, title, enabled, custom)
            is PreferenceDelegateUi.UniversalStringList<*> -> renderUniversalList(builder, card, ui, title, enabled, custom)
        }
    }

    private fun renderStringList(
        builder: SettingsBuilder,
        card: LinearLayout,
        ui: PreferenceDelegateUi.StringList<*>,
        title: String,
        enabled: Boolean,
        custom: (() -> Unit)?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val typed = ui as PreferenceDelegateUi.StringList<Any>
        val context = requireContext()
        val current = currentValue(ui.key)?.toString()
        val serialized = typed.entryValues.map { typed.serializer.serialize(it) }
        val index = serialized.indexOf(current).coerceAtLeast(0)
        val labels = typed.entryLabels.map { context.getString(it) }
        val display = labels.getOrElse(index) { current.orEmpty() }
        val row =
            builder.row(card, title, display) {
                custom?.invoke() ?: SettingsDialogs.choose(
                    context = context,
                    title = title,
                    labels = labels,
                    selected = index,
                ) { picked -> setValue(ui.key, typed.entryValues[picked]) }
            }
        applyEnabled(row, enabled)
    }

    private fun renderUniversalList(
        builder: SettingsBuilder,
        card: LinearLayout,
        ui: PreferenceDelegateUi.UniversalStringList<*>,
        title: String,
        enabled: Boolean,
        custom: (() -> Unit)?,
    ) {
        val context = requireContext()
        val current = currentValue(ui.key)?.toString().orEmpty()
        val values = ui.entryValues()
        val labels = ui.entryLabels(context)
        val index = values.indexOf(current)
        val display = labels.getOrElse(index) { current.ifEmpty { labels.firstOrNull()?.toString().orEmpty() } }.toString()
        val row =
            builder.row(card, title, display) {
                custom?.invoke() ?: SettingsDialogs.choose(
                    context = context,
                    title = title,
                    labels = labels,
                    selected = index.coerceAtLeast(0),
                ) { picked -> setValue(ui.key, values[picked]) }
            }
        applyEnabled(row, enabled)
    }

    private fun seekBarLabel(
        context: Context,
        ui: PreferenceDelegateUi.SeekBarInt,
        value: Int,
    ): String {
        val defaultLabel = ui.defaultLabel?.let { context.getString(it) }
        val isDefault = defaultLabel != null && value == (if (ui.useMinAsDefault) ui.min else ui.defaultValue)
        return if (isDefault) defaultLabel.orEmpty() else "$value ${ui.unit}".trim()
    }

    private fun currentValue(key: String): Any? = provider.preferenceDelegates[key]?.getValue()

    @Suppress("UNCHECKED_CAST")
    private fun setValue(
        key: String,
        value: Any,
    ) {
        (provider.preferenceDelegates[key] as? PreferenceDelegate<Any>)?.setValue(value)
        provider.notifyChange(key)
    }

    private fun applyEnabled(
        view: android.view.View?,
        enabled: Boolean,
    ) {
        view ?: return
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.45f
        if (!enabled) view.setOnClickListener(null)
    }
}
