package com.osfans.trime.ime.smart

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsKitActivity
import com.osfans.trime.ui.settingskit.SettingsPalette
import java.util.Locale

/** Height belongs to the letter keys; symbol and emoji browsers retain their own surface. */
class ReferenceHeightActivity : SettingsKitActivity() {
    private val prefs by lazy { SmartInputPrefs(this) }
    private lateinit var preview: SmartKeyboardPreview
    private lateinit var valueLabel: TextView
    private var presetControl: LinearLayout? = null
    private val presetLabels = listOf("紧凑", "标准", "加高")
    private val presetHeights = listOf(176, 212, 244)

    override fun pageTitle(): String = "键盘高度"

    @Suppress("ClickableViewAccessibility")
    override fun buildPage(builder: SettingsBuilder) {
        builder.space(7)
        val card = builder.group()
        // 拖动手柄：按住并上下拖动调整字母区高度
        val handle =
            FrameLayout(this).apply {
                contentDescription = "拖动调整键盘高度"
                isClickable = true
            }
        handle.addView(
            View(this).apply { background = rounded(palette.muted, 2) },
            FrameLayout.LayoutParams(dp(38), dp(4), Gravity.CENTER),
        )
        card.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        preview =
            SmartKeyboardPreview(this).apply {
                dark = palette.dark
                showGestureHints = false
                keyboardBodyHeight = prefs.referenceKeyboardHeight
                isClickable = false
            }
        card.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        var downY = 0f
        var startHeight = 212
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startHeight = prefs.referenceKeyboardHeight
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE ->
                    update((startHeight + (downY - event.rawY) / resources.displayMetrics.density).toInt(), false)

                MotionEvent.ACTION_UP -> {
                    update(preview.keyboardBodyHeight, true)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    update(startHeight, false)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }

        // 数值行与步进器
        val valueRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(10), 0)
            }
        valueLabel =
            TextView(this).apply {
                textSize = 17f
                setTextColor(palette.ink)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
            }
        valueRow.addView(valueLabel, LinearLayout.LayoutParams(0, dp(52), 1f))
        val stepper =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(palette.track, 18)
                gravity = Gravity.CENTER_VERTICAL
            }
        listOf("−" to -4, "+" to 4).forEach { (name, delta) ->
            stepper.addView(
                TextView(this).apply {
                    text = name
                    textSize = 25f
                    setTextColor(palette.ink)
                    gravity = Gravity.CENTER
                    contentDescription = if (delta < 0) "降低键盘高度" else "增加键盘高度"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { update(prefs.referenceKeyboardHeight + delta, true) }
                },
                LinearLayout.LayoutParams(dp(42), dp(34)),
            )
        }
        valueRow.addView(stepper, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        card.addView(valueRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        builder.space(12)
        builder.section("预设")
        presetControl =
            builder.segments(presetLabels, presetHeights.indexOf(prefs.referenceKeyboardHeight)) { index ->
                update(presetHeights[index], true)
            }
        builder.space(12)
        builder.note("拖动预览上边缘调整高度，松手保存。下次打开键盘生效。")
        builder.space(6)
        builder.note("仅调整字母按键区。表情、符号、剪贴板和底部区域保持原高度。")
        update(prefs.referenceKeyboardHeight, false)
    }

    private fun update(
        height: Int,
        save: Boolean,
    ) {
        val value = height.coerceIn(144, 320)
        if (save) prefs.referenceKeyboardHeight = value
        preview.keyboardBodyHeight = value
        valueLabel.text = String.format(Locale.getDefault(), "按键高度  %.1f dp", value / 4f)
        paintPresets(presetHeights.indexOf(value))
    }

    /** 分段控件按当前高度重新着色；整页重建会打断拖动，所以只改视图。 */
    private fun paintPresets(selected: Int) {
        val control = presetControl ?: return
        control.background = rounded(palette.track, SettingsPalette.RADIUS_PILL)
        presetLabels.forEachIndexed { index, name ->
            val item = control.getChildAt(index) as? TextView ?: return@forEachIndexed
            val chosen = index == selected
            item.setTextColor(if (chosen) palette.ink else palette.muted)
            item.background = if (chosen) rounded(palette.selected, SettingsPalette.RADIUS_SEGMENT) else null
            item.setTypeface(Typeface.DEFAULT, if (chosen) Typeface.BOLD else Typeface.NORMAL)
            item.contentDescription = if (chosen) "$name，已选中" else name
            item.isSelected = chosen
        }
    }

    private fun rounded(
        color: Int,
        radiusDp: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
}
