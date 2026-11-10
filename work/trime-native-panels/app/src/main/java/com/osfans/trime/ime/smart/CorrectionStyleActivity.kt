// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsKitActivity

/** The six observed choices use exactly the same renderer as live correction metadata. */
class CorrectionStyleActivity : SettingsKitActivity() {
    private val store by lazy { getSharedPreferences("smart_input", MODE_PRIVATE) }

    /** 一行选择的视图引用，选中时就地刷新，保持滚动位置。 */
    private data class Choice(
        val id: String,
        val row: LinearLayout,
        val title: TextView,
        val sample: TextView,
        val mark: TextView,
    )

    private val choices = mutableListOf<Choice>()
    private var headerSample: TextView? = null

    override fun pageTitle(): String = "纠错样式"

    override fun buildPage(builder: SettingsBuilder) {
        choices.clear()
        headerSample = null
        val selected = CorrectionStyleSpans.selected(this)
        builder.space(7)

        // 预览卡：顶部一行纠错示例，下面是键盘外观
        val previewCard = builder.group()
        val preview =
            FrameLayout(this).apply {
                background = rounded(palette.track, 20)
                clipToOutline = true
                contentDescription = "纠错样式实时预览"
            }
        preview.addView(
            SmartKeyboardPreview(this).apply {
                dark = palette.dark
                isClickable = false
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(2), dp(8), 0)
                setBackgroundColor(palette.track)
            }
        val sample =
            TextView(this).apply {
                text = CorrectionStyleSpans.render(CorrectionStyle.plan("womeitiandouyongshirifa", "womeitiandouyongshurufa", selected))
                textSize = 10f
                setTextColor(palette.ink)
            }
        headerSample = sample
        header.addView(sample, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)))
        header.addView(
            TextView(this).apply {
                text = "我每天都用输入法"
                textSize = 17f
                setTextColor(palette.ink)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(23)),
        )
        preview.addView(
            header,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(37), Gravity.TOP),
        )
        previewCard.addView(
            preview,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(224)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            },
        )

        builder.space(12)
        builder.section("样式")
        val card = builder.group()
        CorrectionStyle.labels.forEach { (id, title) ->
            val chosen = id == selected
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(12), 0)
                    contentDescription = title
                    isClickable = true
                    isFocusable = true
                    isSelected = chosen
                    setOnClickListener {
                        store.edit().putString("correction_style", id).apply()
                        applySelection(id)
                    }
                }
            val words = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name =
                TextView(this).apply {
                    text = title
                    textSize = 17f
                    setTextColor(palette.ink)
                    gravity = Gravity.CENTER_VERTICAL
                    typeface = if (chosen) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
            val styleSample =
                TextView(this).apply {
                    text = CorrectionStyleSpans.render(CorrectionStyle.plan("qugio", "quguo", id))
                    textSize = 23f
                    setTextColor(palette.ink)
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER_VERTICAL
                }
            words.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
            words.addView(styleSample, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
            row.addView(words, LinearLayout.LayoutParams(0, dp(80), 1f))
            val mark =
                TextView(this).apply {
                    text = if (chosen) "●" else "○"
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setTextColor(if (chosen) palette.ink else palette.muted)
                    contentDescription = if (chosen) "已选择" else "未选择"
                }
            row.addView(mark, LinearLayout.LayoutParams(dp(34), dp(56)))
            card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(80)))
            choices += Choice(id, row, name, styleSample, mark)
        }
    }

    /** 只刷新预览文字与选择标记，不重建页面，滚动位置与点击状态都不丢。 */
    private fun applySelection(selected: String) {
        headerSample?.text =
            CorrectionStyleSpans.render(CorrectionStyle.plan("womeitiandouyongshirifa", "womeitiandouyongshurufa", selected))
        choices.forEach { choice ->
            val chosen = choice.id == selected
            choice.row.isSelected = chosen
            choice.title.typeface = if (chosen) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            choice.sample.text = CorrectionStyleSpans.render(CorrectionStyle.plan("qugio", "quguo", choice.id))
            choice.mark.text = if (chosen) "●" else "○"
            choice.mark.setTextColor(if (chosen) palette.ink else palette.muted)
            choice.mark.contentDescription = if (chosen) "已选择" else "未选择"
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
