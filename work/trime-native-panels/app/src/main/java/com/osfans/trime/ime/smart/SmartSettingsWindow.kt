// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.DragEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.height.HeightActivity
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/** IME-local settings. No focused EditText: phrase drafts cannot leak into the host editor. */
class SmartSettingsWindow(di: DI) : BoardWindow.BarBoardWindow(di) {
    private fun dp(value: Int): Int = context.dp(value)
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val windows: BoardWindowManager by instance()
    private val prefs by lazy { SmartInputPrefs(context) }
    override val title = "智能输入设置"
    private val labels = mutableListOf<TextView>()
    private val buttons = mutableListOf<Button>()
    private val checks = mutableListOf<CheckBox>()
    private lateinit var wheelRows: LinearLayout
    private lateinit var bindingLabel: TextView
    private lateinit var phraseLabel: TextView
    private lateinit var message: TextView
    private var selectedKey = "a"
    private var selectedUp = false
    private var phrase = ""
    private var upper = false
    private var dragId: String? = null
    private val keyIds = (('a'..'z').map { it.toString() } + ('0'..'9').map { it.toString() } +
        listOf("space", "shift", "backspace", "enter", ",", ".", ";", ":", "'", "\"", "(", ")", "[", "]", "-", "_", "/", "?", "!", "@"))

    private fun label(value: String, size: Float = 14f) = TextView(context).apply {
        text = value
        textSize = size
        setPadding(dp(6), dp(5), dp(6), dp(5))
        labels.add(this)
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(3), dp(3), dp(3), dp(3))
        setOnClickListener { action() }
        buttons.add(this)
    }
    private fun row(parent: LinearLayout, entries: List<Pair<String, () -> Unit>>) {
        parent.addView(LinearLayout(context).apply {
            entries.forEach { (name, action) ->
                addView(button(name, action), LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun spinner(items: List<String>, selected: Int, change: (Int) -> Unit): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(items.indices))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { change(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private fun toggle(parent: LinearLayout, text: String, value: Boolean, change: (Boolean) -> Unit) {
        parent.addView(CheckBox(context).apply {
            this.text = text
            isChecked = value
            checks.add(this)
            setOnCheckedChangeListener { _, checked -> change(checked) }
        })
    }
    private val content by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
            row(this, listOf(
                "返回键盘" to { windows.attachWindow(KeyboardWindow) },
                "显示设置" to { windows.attachWindow(SmartDisplaySettingsWindow(di)) },
                "键盘高度" to {
                    runCatching { service.startActivity(Intent(service, HeightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { message.text = "无法打开高度设置" }
                },
            ))
            message = label("设置即时保存。长按轮盘行，再拖到目标行可排序。")
            message.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(message)
            toggle(this, "邻键纠错", prefs.correction) {
                prefs.correction = it
                service.postRimeJob { SmartLanguageOptions.apply(this, prefs) }
            }
            toggle(this, "候选词表情", prefs.candidateEmoji) {
                prefs.candidateEmoji = it
                service.postRimeJob { SmartLanguageOptions.apply(this, prefs) }
            }
            addView(label("轮盘功能 · 保留 1 至 6 项", 17f))
            wheelRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(wheelRows)
            buildWheelRows()
            addView(label("轮盘外观"))
            val styles = listOf("rainbow", "band", "beads", "arc")
            addView(spinner(listOf("彩色流光", "光带", "珠点", "纯弧段"), styles.indexOf(prefs.wheelStyle).coerceAtLeast(0)) { prefs.wheelStyle = styles[it] })
            toggle(this, "点亮轮盘原点", prefs.originGlow) { prefs.originGlow = it }
            toggle(this, "光标跟随手指", prefs.followFinger) { prefs.followFinger = it }
            addView(label("按键上滑 / 下滑动作", 17f))
            addView(spinner(keyIds.map { keyName(it) }, 0) { selectedKey = keyIds[it]; showBinding() })
            addView(spinner(listOf("下滑", "上滑"), 0) { selectedUp = it == 1; showBinding() })
            bindingLabel = label("")
            addView(bindingLabel)
            row(this, listOf(
                "剪切" to { saveBinding("action:cut") },
                "复制" to { saveBinding("action:copy") },
                "粘贴" to { saveBinding("action:paste") },
                "全选" to { saveBinding("action:select_all") },
            ))
            row(this, listOf("恢复此手势默认" to { saveBinding(null) }, "X 剪切 / V 粘贴" to {
                if (prefs.binding("x", false) == null) prefs.setBinding("x", false, "action:cut")
                if (prefs.binding("v", false) == null) prefs.setBinding("v", false, "action:paste")
                message.text = "已补充 X 下滑剪切、V 下滑粘贴；已有绑定保留"
                showBinding()
            }))
            addView(label("自定义符号 / 短语（仅保存在此面板）", 16f))
            phraseLabel = label("", 18f)
            phraseLabel.setTextIsSelectable(true)
            addView(phraseLabel)
            row(this, listOf(
                "从剪贴板填入" to {
                    phrase = runCatching {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString().take(251) else ""
                    }.getOrDefault("")
                    showPhrase()
                },
                "读取已有短语" to { phrase = prefs.binding(selectedKey, selectedUp)?.takeIf { it.startsWith("text:") }?.removePrefix("text:") ?: ""; showPhrase() },
            ))
            listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "()[]{}<>!?", ",.;:'\"/@_-", "《》【】「」￥%&*").forEach { chars ->
                row(this, chars.map { c -> c.toString() to { appendPhrase(if (upper) c.uppercaseChar().toString() else c.toString()) } })
            }
            row(this, listOf(
                "大小写" to { upper = !upper; message.text = if (upper) "短语编辑：大写" else "短语编辑：小写" },
                "空格" to { appendPhrase(" ") },
                "⌫" to { if (phrase.isNotEmpty()) phrase = phrase.substring(0, phrase.offsetByCodePoints(phrase.length, -1)); showPhrase() },
                "清空" to { phrase = ""; showPhrase() },
            ))
            row(this, listOf("保存为此手势短语" to {
                if (phrase.isEmpty()) message.text = "请先填写符号或短语" else saveBinding("text:$phrase")
            }))
            addView(label("中文短语可先在应用中输入并复制，再用“从剪贴板填入”。此面板不会将编辑草稿写入应用。", 12f))
            showBinding()
            showPhrase()
        }
    }
    private val root by lazy { ScrollView(context).apply { isFillViewport = true; addView(content) } }

    private fun keyName(key: String) = when (key) { "space" -> "空格"; "shift" -> "Shift"; "backspace" -> "退格"; "enter" -> "回车"; else -> key.uppercase() }
    private fun showBinding() {
        if (!::bindingLabel.isInitialized) return
        val value = prefs.binding(selectedKey, selectedUp)
        val name = when (value) {
            "action:cut" -> "剪切"; "action:copy" -> "复制"; "action:paste" -> "粘贴"; "action:select_all" -> "全选"
            null -> "主题默认动作"; else -> value.removePrefix("text:")
        }
        bindingLabel.text = "${keyName(selectedKey)} ${if (selectedUp) "上滑" else "下滑"}：$name"
    }
    private fun saveBinding(value: String?) {
        prefs.setBinding(selectedKey, selectedUp, value)
        showBinding()
        message.text = "已保存 ${keyName(selectedKey)} ${if (selectedUp) "上滑" else "下滑"}动作"
    }
    private fun showPhrase() { if (::phraseLabel.isInitialized) phraseLabel.text = phrase.ifEmpty { "（短语草稿为空）" } }
    private fun appendPhrase(value: String) { phrase = (phrase + value).take(251); showPhrase() }

    @Suppress("DEPRECATION")
    private fun buildWheelRows() {
        wheelRows.removeAllViews()
        val enabled = prefs.wheelSlots.ifEmpty { listOf("settings") }
        if (prefs.wheelSlots.isEmpty()) prefs.wheelSlots = enabled
        val ordered = enabled + SmartInputPrefs.ACTIONS.filter { it !in enabled }
        ordered.forEach { id ->
            val line = LinearLayout(context)
            val check = CheckBox(context).apply {
                text = "≡  ${SmartInputPrefs.LABELS[id]}${if (id in enabled) "  · ${enabled.indexOf(id) + 1}" else ""}"
                isChecked = id in enabled
                contentDescription = "轮盘${SmartInputPrefs.LABELS[id]}，长按拖动排序"
                setTextColor(scope.colors.keyTextColor)
                setOnCheckedChangeListener { view, checked ->
                    val current = prefs.wheelSlots.toMutableList()
                    if (!checked && current.size == 1 && id in current) {
                        view.isChecked = true
                        message.text = "轮盘至少保留一项"
                    } else {
                        if (checked && id !in current && current.size < 6) current.add(id) else if (!checked) current.remove(id)
                        prefs.wheelSlots = current
                        wheelRows.post { buildWheelRows() }
                    }
                }
            }
            val start: (View) -> Boolean = { source ->
                if (id !in prefs.wheelSlots) {
                    message.text = "先勾选此项，再拖动排序"
                    true
                } else {
                    dragId = id
                    val data = ClipData.newPlainText("轮盘排序", id)
                    val shadow = View.DragShadowBuilder(source)
                    val started = if (Build.VERSION.SDK_INT >= 24) source.startDragAndDrop(data, shadow, id, 0) else source.startDrag(data, shadow, id, 0)
                    if (!started) dragId = null
                    started
                }
            }
            check.setOnLongClickListener { start(it) }
            line.setOnLongClickListener { start(it) }
            line.addView(check, LinearLayout.LayoutParams(-1, dp(44)))
            line.setOnDragListener { view, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> dragId != null && event.localState == dragId
                    DragEvent.ACTION_DRAG_ENTERED -> { view.alpha = 0.5f; true }
                    DragEvent.ACTION_DRAG_EXITED -> { view.alpha = 1f; true }
                    DragEvent.ACTION_DROP -> {
                        view.alpha = 1f
                        val from = dragId
                        val order = prefs.wheelSlots.toMutableList()
                        if (from != null && from != id && from in order && id in order) {
                            val target = order.indexOf(id)
                            order.remove(from)
                            order.add(target.coerceAtMost(order.size), from)
                            prefs.wheelSlots = order
                            message.text = "轮盘顺序已保存"
                        }
                        wheelRows.post { buildWheelRows() }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> { view.alpha = 1f; dragId = null; true }
                    else -> true
                }
            }
            wheelRows.addView(line)
        }
    }
    override fun onCreateView(): View = root.also { refreshColors() }
    override fun onAttached() { refreshColors() }
    override fun onDetached() { dragId = null; phrase = "" }
    override fun refreshColors() {
        root.background = scope.drawable("keyboard_back_color")
        labels.forEach { it.setTextColor(scope.colors.keyTextColor) }
        checks.forEach { it.setTextColor(scope.colors.keyTextColor) }
        buttons.forEach {
            it.setTextColor(scope.colors.keyTextColor)
            it.background = scope.decorDrawable("key_back_color", cornerRadius = dp(5).toFloat())
        }
        if (::wheelRows.isInitialized) buildWheelRows()
    }
}
