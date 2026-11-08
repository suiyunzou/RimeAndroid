package com.osfans.trime.ime.smart

import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.height.HeightActivity
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

class SmartDisplaySettingsWindow(di: DI) : BoardWindow.BarBoardWindow(di) {
    private fun dp(value: Int): Int = context.dp(value)
    private val service: TrimeInputMethodService by instance()
    private val manager: BoardWindowManager by instance()
    private val scope: ThemeScope by instance()
    private val prefs by lazy { SmartInputPrefs(context) }
    override val title = "显示与布局"
    private val labels = mutableListOf<TextView>()
    private val buttons = mutableListOf<Button>()
    private val content by lazy { LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) } }
    private val root by lazy { ScrollView(context).apply { addView(content) } }
    private fun label(text: String) = TextView(context).apply {
        this.text = text; textSize = 15f; setPadding(dp(4), dp(6), dp(4), dp(6)); labels.add(this); content.addView(this)
    }
    private fun button(text: String, action: () -> Unit) {
        val view = Button(context).apply { this.text = text; textSize = 14f; setOnClickListener { action() } }
        buttons.add(view); content.addView(view, LinearLayout.LayoutParams(-1, dp(44)))
    }
    private fun check(text: String, value: Boolean, action: (Boolean) -> Unit) {
        val view = CheckBox(context).apply { this.text = text; textSize = 15f; isChecked = value; setOnCheckedChangeListener { _, checked -> action(checked) } }
        labels.add(view); content.addView(view, LinearLayout.LayoutParams(-1, dp(44)))
    }
    private fun build() {
        if (content.childCount > 0) return
        label("候选字大小与粗细在下一次候选刷新时生效")
        val sizeLabel = label("字号：${prefs.candidateSize.takeIf { it > 0 }?.toString() ?: "跟随主题"}")
        listOf(16, 20, 24, 28, 32, 0).forEach { size -> button(if (size == 0) "字号跟随主题" else "$size sp") {
            prefs.candidateSize = size; sizeLabel.text = "字号：${if (size == 0) "跟随主题" else "$size sp"}"
        } }
        check("候选字加粗", prefs.candidateBold) { prefs.candidateBold = it }
        check("始终显示大写字母键帽", prefs.uppercaseLabels) { prefs.uppercaseLabels = it }
        check("候选词表情（支持此选项的方案）", prefs.candidateEmoji) { enabled ->
            prefs.candidateEmoji = enabled
            service.postRimeJob { setRuntimeOption("emoji", enabled) }
        }
        check("跟随系统浅色／深色", ThemeManager.prefs.followSystemDayNight.getValue()) { enabled ->
            ThemeManager.prefs.followSystemDayNight.setValue(enabled)
            if (enabled) {
                AppPrefs.defaultInstance().advanced.uiMode.setValue(AppPrefs.Advanced.UiMode.AUTO)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ColorManager.onSystemNightModeChange(night)
            refreshColors()
        }
        button("键盘高度与布局预览") {
            runCatching { service.startActivity(Intent(service, HeightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(context, "无法打开高度设置", Toast.LENGTH_SHORT).show() }
        }
        button("使用九宫格拼音") { prefs.layout = "t9"; manager.attachWindow(T9Window(di)) }
        button("使用 26 键全键盘") { prefs.layout = "qwerty"; manager.attachWindow(KeyboardWindow) }
        label("应用图标")
        listOf("default" to "默认", "blue" to "蓝色", "green" to "绿色").forEach { (id, name) -> button(name) {
            val success = SmartIconSwitcher.select(context, id)
            Toast.makeText(context, if (success) "已更换图标，桌面可能稍后刷新" else "图标切换失败", Toast.LENGTH_SHORT).show()
        } }
        label("配色方案（包含候选高亮与按键底色）")
        scope.theme.presetColorSchemes.forEach { (id, scheme) -> button(scheme["name"] ?: id) {
            ColorManager.setColorScheme(id); refreshColors()
        } }
        button("返回快捷设置") { manager.attachWindow(SmartSettingsWindow(di)) }
        button("返回键盘") { manager.attachWindow(KeyboardWindow) }
    }
    override fun onCreateView(): View = root.also { build(); refreshColors() }
    override fun onAttached() { refreshColors() }
    override fun onDetached() {}
    override fun refreshColors() {
        root.background = scope.drawable("keyboard_back_color")
        labels.forEach { it.setTextColor(scope.colors.keyTextColor) }
        buttons.forEach { it.setTextColor(scope.colors.keyTextColor); it.background = scope.decorDrawable("key_back_color", cornerRadius = dp(5).toFloat()) }
    }
}
