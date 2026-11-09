// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.rippleDrawable
import splitties.dimensions.dp

class SmsCodeSuggestionUi(context: Context, private val scope: ThemeScope) {
    val fill = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
    }
    val dismiss = TextView(context).apply {
        text = "×"
        textSize = 24f
        gravity = Gravity.CENTER
        contentDescription = "关闭验证码提示"
    }
    val root = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(fill, LinearLayout.LayoutParams(0, -1, 1f))
        addView(dismiss, LinearLayout.LayoutParams(context.dp(40), -1))
    }

    init { refreshColors() }

    fun show(code: String?) {
        fill.text = if (code == null) "" else "填入 $code"
        fill.contentDescription = if (code == null) "短信验证码" else "填入验证码 $code"
    }

    fun refreshColors() {
        fill.setTextColor(scope.colors.candidateTextColor)
        dismiss.setTextColor(scope.colors.candidateTextColor)
        fill.background = rippleDrawable(scope.colors.hilitedCandidateBackColor)
    }
}
