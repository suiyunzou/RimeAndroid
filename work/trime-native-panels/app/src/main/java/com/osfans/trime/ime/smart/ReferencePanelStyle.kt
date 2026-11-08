package com.osfans.trime.ime.smart

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.theme.ThemeScope
import splitties.dimensions.dp

/** Shared compact, rounded controls; all surfaces follow the current keyboard palette. */
object ReferencePanelStyle {
    enum class Role { KEY, SUBTLE, ACTIVE, DELETE, PERCENT }

    fun button(context: Context, scope: ThemeScope, label: String, click: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 17f
        typeface = scope.theme.fonts.key
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(context.dp(5), 0, context.dp(5), 0)
        setOnClickListener { click() }
        apply(this, scope)
    }

    fun apply(button: Button, scope: ThemeScope, role: Role = Role.KEY, radius: Int = 6) {
        val foreground = scope.colors.keyTextColor
        val base = runCatching { scope.colors.keyBackColor }.getOrDefault(Color.GRAY)
        val light = ColorUtils.calculateLuminance(base) > .45
        val keyboard = runCatching { scope.colors.keyboardBackColor }.getOrDefault(base)
        val back = when (role) {
            Role.KEY -> base
            Role.SUBTLE -> ColorUtils.blendARGB(base, keyboard, .5f)
            Role.ACTIVE -> Color.rgb(48, 125, 225)
            Role.DELETE -> ColorUtils.blendARGB(base, Color.rgb(196, 57, 78), if (light) .17f else .55f)
            Role.PERCENT -> ColorUtils.blendARGB(base, Color.rgb(72, 135, 112), if (light) .14f else .5f)
        }
        button.setTextColor(when (role) {
            Role.ACTIVE -> Color.WHITE
            Role.DELETE -> if (light) Color.rgb(163, 42, 57) else Color.WHITE
            Role.PERCENT -> if (light) Color.rgb(47, 105, 84) else Color.WHITE
            else -> foreground
        })
        val shape = GradientDrawable().apply { setColor(back); cornerRadius = button.context.dp(radius).toFloat() }
        button.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground, 35)), shape, null)
    }
}
