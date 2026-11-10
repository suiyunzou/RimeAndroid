// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsDialogs
import com.osfans.trime.ui.settingskit.SettingsKitActivity
import com.osfans.trime.ui.settingskit.SettingsTopBar

/** Light counterpart of the reference's grouped sound/haptics page. */
class FeedbackTuningActivity : SettingsKitActivity() {
    private val prefs by lazy { FeedbackTuningPrefs(this) }
    private var backspace = false
    private var picker = false
    private var favoriteOnly = false
    private var toneCategory = 0
    private var previewGeneration = 0

    override fun onCreate(state: Bundle?) {
        backspace = state?.getBoolean("backspace") ?: false
        picker = state?.getBoolean("picker") ?: false
        favoriteOnly = state?.getBoolean("favoriteOnly") ?: false
        FeedbackTuningRuntime.prepare(applicationContext)
        super.onCreate(state)
        refreshTopBar()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("backspace", backspace)
        out.putBoolean("picker", picker)
        out.putBoolean("favoriteOnly", favoriteOnly)
    }

    override fun onStop() {
        previewGeneration++
        super.onStop()
    }

    override fun pageTitle(): String = if (picker) "音色" else "声音与触感"

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (picker) {
            picker = false
            rerenderPage(scrollToTop = true)
        } else {
            finish()
        }
    }

    override fun buildPage(builder: SettingsBuilder) {
        if (picker) pickerPage(builder) else settingsPage(builder)
    }

    /** 基类顶栏在 onCreate 时固定；切换子页后重建同一组件，返回胶囊改为页面内返回。 */
    @Suppress("DEPRECATION")
    private fun refreshTopBar() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val bar = SettingsTopBar.create(context = this, title = pageTitle(), palette = palette, onBack = { onBackPressed() })
        root.removeViewAt(0)
        root.addView(bar, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
    }

    private fun rerenderPage(scrollToTop: Boolean = false) {
        rerender()
        refreshTopBar()
        if (scrollToTop) {
            // 基类 rerender 会保留滚动位置，切换子页时回到页首。
            val scroll = builder.body.parent as? ScrollView ?: return
            scroll.post { scroll.scrollTo(0, 0) }
        }
    }

    private fun settingsPage(builder: SettingsBuilder) {
        builder.space(7)
        val switches = builder.group()
        builder.toggle(switches, "键盘触感", prefs.hapticEnabled) { prefs.hapticEnabled = it }
        builder.toggle(switches, "键盘声音", prefs.soundEnabled) { prefs.soundEnabled = it }
        builder.space(8)
        builder.note("遵循系统静音与触感设置。")

        builder.space(20)
        builder.section("音色与音量")
        val sound = builder.group()
        sound.addView(
            builder.segmentsView(listOf("普通按键", "退格"), if (backspace) 1 else 0) {
                backspace = it == 1
                rerenderPage()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply {
                setMargins(dp(10), dp(10), dp(10), dp(2))
            },
        )
        val profile = prefs.profile(backspace)
        builder.row(sound, "音色", FeedbackTuningProfile.TONES[profile.tone] ?: "系统") {
            picker = true
            favoriteOnly = false
            rerenderPage(scrollToTop = true)
        }
        slider(sound, "音量", profile.volume, 100) { prefs.save(backspace, prefs.profile(backspace).copy(volume = it)) }
        builder.row(sound, "筛选音色", "已收藏 ${prefs.favorites.size} 个") {
            picker = true
            favoriteOnly = true
            rerenderPage(scrollToTop = true)
        }
        builder.toggle(sound, "松手声音", profile.releaseSound) {
            prefs.save(backspace, prefs.profile(backspace).copy(releaseSound = it))
        }

        builder.space(20)
        builder.section("触感")
        val haptic = builder.group()
        builder.row(haptic, "按下", "${if (backspace) "退格" else "普通按键"}独立设置")
        builder.row(haptic, "触感类型", FeedbackTuningProfile.HAPTICS[profile.hapticStyle].orEmpty()) {
            val styles = FeedbackTuningProfile.HAPTICS
            SettingsDialogs.choose(
                this,
                "触感类型",
                styles.values.toList(),
                styles.keys.indexOf(prefs.profile(backspace).hapticStyle),
            ) { index ->
                prefs.save(backspace, prefs.profile(backspace).copy(hapticStyle = styles.keys.elementAt(index)))
                rerenderPage()
            }
        }
        slider(haptic, "力度", profile.pressStrength * 100 / 255, 100) {
            prefs.save(backspace, prefs.profile(backspace).copy(pressStrength = it * 255 / 100))
        }
        slider(haptic, "按下时长", profile.pressDuration, 40, " ms", 1) {
            prefs.save(backspace, prefs.profile(backspace).copy(pressDuration = it))
        }
        slider(haptic, "松手", profile.releaseStrength * 100 / 255, 100) {
            prefs.save(backspace, prefs.profile(backspace).copy(releaseStrength = it * 255 / 100))
        }
        slider(haptic, "松手时长", profile.releaseDuration, 40, " ms", 1) {
            prefs.save(backspace, prefs.profile(backspace).copy(releaseDuration = it))
        }
        val sample = builder.row(haptic, "试听与触感预览", "▶")
        sample.setOnClickListener { preview(sample) }

        builder.space(12)
        val typing = builder.group()
        val editor =
            EditText(this).apply {
                hint = "在这里试打"
                textSize = 18f
                setSingleLine(false)
                setTextColor(palette.ink)
                setHintTextColor(palette.muted)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                minLines = 2
                background = rounded(palette.track, 12)
                visibility = View.GONE
            }
        builder.row(typing, "试用键盘", "⌨") {
            editor.visibility = View.VISIBLE
            editor.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        typing.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        builder.row(typing, "音效来源", "查看") {
            SettingsDialogs.info(
                this,
                "音效来源",
                "机械轴与静电容录音：Mechvibes，MIT 许可。另有本项目合成音，CC0。\n\nhttps://github.com/hainguyents13/mechvibes",
            )
        }
    }

    private fun pickerPage(builder: SettingsBuilder) {
        builder.space(7)
        val filters = builder.group()
        filters.addView(
            builder.segmentsView(listOf("全部", "已收藏"), if (favoriteOnly) 1 else 0) {
                favoriteOnly = it == 1
                rerenderPage()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply {
                setMargins(dp(10), dp(10), dp(10), dp(2))
            },
        )
        filters.addView(
            builder.segmentsView(listOf("全部音色", "机械轴", "键盘", "合成"), toneCategory) {
                toneCategory = it
                rerenderPage()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply {
                setMargins(dp(10), dp(8), dp(10), dp(10))
            },
        )
        val entries =
            FeedbackTuningProfile.TONES.filterKeys { id ->
                (!favoriteOnly || id in prefs.favorites) &&
                    when (toneCategory) {
                        1 -> id in FeedbackTuningProfile.RECORDINGS && id != "topre"
                        2 -> id == "system" || id == "topre"
                        3 -> id in listOf("crisp", "soft", "deep")
                        else -> true
                    }
            }
        builder.space(12)
        builder.section("音色")
        val card = builder.group()
        if (entries.isEmpty()) builder.row(card, "暂无收藏")
        entries.forEach { (id, name) -> toneRow(card, id, name) }
    }

    /** 音色行：名称选用，▶ 试听，☆ 收藏，保持旧版三个独立点击区。 */
    private fun toneRow(
        parent: LinearLayout,
        id: String,
        name: String,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(8), 0)
            }
        val chosen = id == prefs.profile(backspace).tone
        val title =
            TextView(this).apply {
                text = (if (chosen) "✓ " else "") + name
                textSize = 17f
                setTextColor(palette.ink)
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "选择 $name"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.save(backspace, prefs.profile(backspace).copy(tone = id))
                    picker = false
                    rerenderPage(scrollToTop = true)
                }
            }
        row.addView(title, LinearLayout.LayoutParams(0, dp(54), 1f))
        val play =
            TextView(this).apply {
                text = "▶"
                textSize = 19f
                setTextColor(palette.muted)
                gravity = Gravity.CENTER
                contentDescription = "试听 $name"
                isClickable = true
                isFocusable = true
                setOnClickListener { preview(this, id) }
            }
        row.addView(play, LinearLayout.LayoutParams(dp(43), dp(50)))
        val favorite = id in prefs.favorites
        val star =
            TextView(this).apply {
                text = if (favorite) "★" else "☆"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(if (favorite) palette.ink else palette.muted)
                contentDescription = "收藏 $name"
                isSelected = favorite
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    prefs.favorites = if (favorite) prefs.favorites - id else prefs.favorites + id
                    rerenderPage()
                }
            }
        row.addView(star, LinearLayout.LayoutParams(dp(43), dp(50)))
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        value: Int,
        max: Int,
        unit: String = "%",
        minimum: Int = 0,
        change: (Int) -> Unit,
    ) {
        val row = builder.row(parent, label, "$value$unit")
        val output = row.getChildAt(1) as? TextView
        parent.addView(
            SeekBar(this).apply {
                this.max = max - minimum
                progress = value - minimum
                contentDescription = label
                progressTintList = ColorStateList.valueOf(palette.switchOn)
                thumbTintList = ColorStateList.valueOf(palette.group)
                progressBackgroundTintList = ColorStateList.valueOf(palette.track)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: SeekBar?,
                            n: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser) {
                                change(n + minimum)
                                output?.text = "${n + minimum}$unit"
                                row.contentDescription = "$label，${n + minimum}$unit"
                            }
                        }

                        override fun onStartTrackingTouch(bar: SeekBar?) {}

                        override fun onStopTrackingTouch(bar: SeekBar?) {}
                    },
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)).apply {
                setMargins(dp(12), 0, dp(12), dp(10))
            },
        )
    }

    private fun preview(
        view: View,
        tone: String? = null,
    ) {
        val key = if (backspace) KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_A
        val generation = ++previewGeneration
        FeedbackTuningRuntime.sound(this, key, previewTone = tone)
        FeedbackTuningRuntime.haptic(view, key)
        view.postDelayed(
            {
                if (generation == previewGeneration && !isFinishing && !isDestroyed) {
                    FeedbackTuningRuntime.sound(this, key, true, previewTone = tone)
                    FeedbackTuningRuntime.haptic(view, key, true)
                }
            },
            100,
        )
        if (!prefs.soundEnabled && !prefs.hapticEnabled) {
            Toast.makeText(this, "声音与触感均已关闭", Toast.LENGTH_SHORT).show()
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
