/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.osfans.trime.R

/**
 * Small dialogs used by the migrated settings rows.
 *
 * The reference puts lightweight single-choice tasks in a menu / inline row, form-like tasks in a
 * modal, and list pages in pushed pages. These helpers cover the "row opens a modal" cases that
 * the old `androidx.preference` dialogs used to provide.
 */
object SettingsDialogs {
    fun choose(
        context: Context,
        title: String,
        labels: List<CharSequence>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        if (labels.isEmpty()) return
        AlertDialog
            .Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected.coerceIn(0, labels.lastIndex)) { dialog, index ->
                dialog.dismiss()
                onPick(index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun text(
        context: Context,
        title: String,
        initial: String,
        hint: String,
        multiline: Boolean = false,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        message: String? = null,
        onSave: (String) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), 0)
            }
        val edit =
            EditText(context).apply {
                setText(initial)
                this.hint = hint
                this.inputType = inputType
                if (multiline) {
                    isSingleLine = false
                    minLines = 3
                    maxLines = 8
                    gravity = Gravity.TOP or Gravity.START
                } else {
                    setSingleLine(true)
                    imeOptions = EditorInfo.IME_ACTION_DONE
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
        message?.let {
            content.addView(
                TextView(context).apply {
                    text = it
                    textSize = 13f
                    setPadding(0, 0, 0, dp(6))
                },
            )
        }
        content.addView(edit, LinearLayout.LayoutParams(-1, -2))
        AlertDialog
            .Builder(context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSave(edit.text.toString()) }
            .show()
        edit.requestFocus()
    }

    fun number(
        context: Context,
        title: String,
        initial: Int,
        min: Int,
        max: Int,
        unit: String = "",
        onSave: (Int) -> Unit,
    ) {
        text(
            context = context,
            title = if (unit.isBlank()) title else "$title（$min–$max $unit）",
            initial = initial.toString(),
            hint = initial.toString(),
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
        ) { raw ->
            val parsed = raw.trim().toIntOrNull() ?: return@text
            if (parsed in min..max) onSave(parsed)
        }
    }

    fun slider(
        context: Context,
        title: String,
        value: Int,
        min: Int,
        max: Int,
        step: Int = 1,
        unit: String = "",
        defaultValue: Int = value,
        defaultLabel: String? = null,
        useMinAsDefault: Boolean = false,
        onSave: (Int) -> Unit,
    ) {
        val safeStep = step.coerceAtLeast(1)
        val span = (max - min).coerceAtLeast(0)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun textFor(v: Int) =
            if (defaultLabel != null && v == (if (useMinAsDefault) min else defaultValue)) {
                defaultLabel
            } else {
                "$v $unit".trim()
            }
        fun progressFor(v: Int) = ((v - min) / safeStep).coerceIn(0, span / safeStep)
        fun valueFor(p: Int) = (p * safeStep) + min
        val valueLabel =
            TextView(context).apply {
                text = textFor(value)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(12))
            }
        val seekBar =
            SeekBar(context).apply {
                this.max = span / safeStep
                progress = progressFor(value)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            valueLabel.text = textFor(valueFor(progress))
                        }

                        override fun onStartTrackingTouch(bar: SeekBar?) {}

                        override fun onStopTrackingTouch(bar: SeekBar?) {}
                    },
                )
            }
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), 0)
                addView(valueLabel, LinearLayout.LayoutParams(-1, -2))
                addView(seekBar, LinearLayout.LayoutParams(-1, -2))
            }
        val builder =
            AlertDialog
                .Builder(context)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ -> onSave(valueFor(seekBar.progress)) }
                .setNegativeButton(android.R.string.cancel, null)
        builder.setNeutralButton(R.string.default_) { _, _ -> onSave(defaultValue) }
        builder.show()
    }

    fun confirm(
        context: Context,
        title: String,
        message: String,
        positive: String,
        onConfirm: () -> Unit,
    ) {
        AlertDialog
            .Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .show()
    }

    fun info(
        context: Context,
        title: String,
        message: String,
    ) {
        AlertDialog
            .Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Divider-free row for dialogs that need a little vertical rhythm. */
    fun spacer(context: Context, heightDp: Int = 8): View =
        View(context).apply { layoutParams = LinearLayout.LayoutParams(1, (heightDp * context.resources.displayMetrics.density).toInt()) }
}
