package com.osfans.trime.ui.main

import android.os.Bundle
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.localrules.LocalRuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.resources.styledColor

/** Ordinary Android text fields: the IME edits these fields, never the previous host application. */
class LocalRulesFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val store get() = LocalRuleStore(DataManager.userDataDir)
    private val kinds = listOf("shortcut", "next", "email")
    private val titles = listOf("快捷候选", "上屏后推荐", "邮箱后缀")
    private var selected = 0
    private var rules: LocalRuleStore.Snapshot<LocalRuleStore.Rule>? = null
    private var domains: LocalRuleStore.Snapshot<String>? = null
    private var busy = false
    private var list: LinearLayout? = null
    private var addButton: Button? = null
    private var tabs: List<Button> = emptyList()
    private var editor: AlertDialog? = null
    private var triggerField: EditText? = null
    private var valuesField: EditText? = null
    private var draft: Draft? = null

    private data class Draft(val original: String?, val trigger: String, val values: String)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getInt("tab") ?: 0
        if (savedInstanceState?.getBoolean("editing") == true) {
            draft = Draft(savedInstanceState.getString("original"), savedInstanceState.getString("trigger").orEmpty(), savedInstanceState.getString("values").orEmpty())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
            val tabRow = LinearLayout(ctx)
            tabs = titles.mapIndexed { index, title ->
                Button(ctx).apply {
                    text = title
                    textSize = 13f
                    minWidth = 0
                    setPadding(dp(2), 0, dp(2), 0)
                    setOnClickListener { if (selected != index) { selected = index; reload() } }
                    tabRow.addView(this, LinearLayout.LayoutParams(0, dp(52), 1f))
                }
            }
            addView(tabRow)
            addButton = Button(ctx).apply {
                text = "添加规则"
                isEnabled = false
                setOnClickListener { showEditor(Draft(null, "", "")) }
            }
            addView(addButton)
            list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.disableTopOptionsMenu()
        reload(restoreDraft = true)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        tabs.forEachIndexed { index, button ->
            button.isEnabled = !value
            button.isSelected = index == selected
            button.setTextColor(requireContext().styledColor(if (index == selected) android.R.attr.colorAccent else android.R.attr.textColorPrimary))
            button.setTypeface(null, if (index == selected) Typeface.BOLD else Typeface.NORMAL)
            button.alpha = if (value) 0.6f else 1f
        }
        addButton?.isEnabled = !value && if (selected == 2) domains != null else rules != null
        editor?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !value
        editor?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !value
        editor?.setCancelable(!value)
    }

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    private fun reload(restoreDraft: Boolean = false) {
        setBusy(true)
        list?.removeAllViews()
        list?.addView(label("正在读取…"))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { if (selected == 2) domains = store.loadDomains() else rules = store.loadRules() }
            }
            setBusy(false)
            result.onSuccess {
                render()
                if (restoreDraft) draft?.let(::showEditor)
            }.onFailure {
                if (selected == 2) domains = null else rules = null
                addButton?.isEnabled = false
                list?.removeAllViews()
                list?.addView(label("读取失败：${it.message ?: "无法读取规则"}\n原文件保持不变。"))
                list?.addView(Button(requireContext()).apply { text = "重新读取"; setOnClickListener { reload() } })
            }
        }
    }

    private fun render() {
        val parent = list ?: return
        parent.removeAllViews()
        addButton?.text = if (selected == 2) "添加邮箱后缀" else "添加规则"
        parent.addView(label(when (selected) {
            0 -> "输入缩写或符号后显示候选。一个触发词可添加多个候选，按填写顺序排列。"
            1 -> "选词上屏后显示后续推荐。一个触发词可添加多个推荐。"
            else -> "输入 @ 后显示这些后缀，按列表顺序排列。"
        }))
        val groups: List<Pair<String, List<String>>> = if (selected == 2) {
            domains?.items.orEmpty().map { it to emptyList() }
        } else {
            rules?.items.orEmpty().filter { it.kind == kinds[selected] }.groupBy { it.trigger }.map { (trigger, rows) -> trigger to rows.map { it.value } }
        }
        if (groups.isEmpty()) parent.addView(label("暂无规则，点击上方按钮添加。"))
        groups.forEachIndexed { index, (trigger, values) ->
            val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(8), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                addView(TextView(context).apply { text = if (selected == 2) "@$trigger" else trigger; textSize = 18f })
                if (values.isNotEmpty()) addView(TextView(context).apply { text = values.joinToString(" · "); textSize = 14f; maxLines = 3 })
                contentDescription = "编辑 $trigger"
                setOnClickListener { if (!busy) showEditor(Draft(trigger, trigger, values.joinToString("\n"))) }
            }
            row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(requireContext()).apply {
                text = "⋮"
                textSize = 22f
                minWidth = 0
                setPadding(0, 0, 0, 0)
                contentDescription = "$trigger 的操作"
                setOnClickListener { anchor ->
                    if (busy) return@setOnClickListener
                    PopupMenu(requireContext(), anchor).apply {
                        menu.add("编辑").setOnMenuItemClickListener { showEditor(Draft(trigger, trigger, values.joinToString("\n"))); true }
                        if (index > 0) menu.add("上移").setOnMenuItemClickListener { reorder(groups.map { it.first }, index, -1); true }
                        if (index < groups.lastIndex) menu.add("下移").setOnMenuItemClickListener { reorder(groups.map { it.first }, index, 1); true }
                        menu.add("删除").setOnMenuItemClickListener {
                            AlertDialog.Builder(requireContext()).setTitle("删除规则？").setMessage(if (selected == 2) "@$trigger" else "$trigger 的所有候选将被删除。")
                                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> remove(trigger) }.show()
                            true
                        }
                        show()
                    }
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            parent.addView(row)
        }
    }

    private fun showEditor(value: Draft) {
        if (busy || editor != null) return
        draft = value
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        content.addView(label(if (selected == 2) "邮箱域名" else if (selected == 0) "触发缩写或符号" else "上屏的词句"))
        triggerField = EditText(ctx).apply {
            setSingleLine(true)
            inputType = when (selected) {
                // KeyboardWindow temporarily enters ASCII mode for visible-password fields,
                // then restores the user's previous mode when the candidate field receives focus.
                0 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                2 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (selected == 0) {
                imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_FORCE_ASCII
                typeface = Typeface.DEFAULT
            }
            hint = when (selected) { 0 -> "例如：dz"; 1 -> "例如：地址"; else -> "例如：example.com" }
            setText(value.trigger)
            contentDescription = if (selected == 2) "邮箱域名" else "触发内容"
        }
        content.addView(triggerField)
        if (selected != 2) {
            content.addView(label("候选内容（每行一个，顺序即显示顺序）"))
            valuesField = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
                hint = if (selected == 0) "地址\n公司地址" else "收到\n谢谢"
                setText(value.values)
                contentDescription = "候选内容，每行一个"
            }
            content.addView(valuesField)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (value.original == null) "添加${titles[selected]}" else "编辑${titles[selected]}")
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton("取消") { _, _ -> draft = null }
            .setPositiveButton("保存", null)
            .create()
        editor = dialog
        dialog.setOnCancelListener { draft = null }
        dialog.setOnDismissListener { editor = null; triggerField = null; valuesField = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { saveEditor(value.original) }
        }
        dialog.show()
    }

    private fun saveEditor(original: String?) {
        val trigger = triggerField?.text?.toString().orEmpty()
        val values = valuesField?.text?.toString().orEmpty()
        draft = Draft(original, trigger, values)
        runCatching {
            if (selected == 2) {
                val domain = LocalRuleStore.normalizeDomain(trigger)
                val current = domains!!.items
                require(current.none { it == domain && it != original }) { "此邮箱后缀已存在" }
                val updated = current.toMutableList()
                if (original == null) updated.add(domain) else updated[updated.indexOf(original)] = domain
                save(domainsToSave = updated, closeEditor = true)
            } else {
                val kind = kinds[selected]
                val candidates = values.split('\n').map { it.removeSuffix("\r") }.filter { line -> line.any { it != ' ' } }
                require(candidates.isNotEmpty()) { "请填写至少一个候选内容" }
                val additions = candidates.map { LocalRuleStore.normalizeRule(LocalRuleStore.Rule(kind, trigger, it)) }
                val normalizedTrigger = additions.first().trigger
                val current = rules!!.items
                require(current.none { it.kind == kind && it.trigger == normalizedTrigger && it.trigger != original }) { "此触发词已存在，请直接编辑原规则" }
                val insertion = current.indexOfFirst { it.kind == kind && it.trigger == original }.let { if (it < 0) current.size else it }
                val updated = current.filterNot { it.kind == kind && it.trigger == original }.toMutableList()
                updated.addAll(insertion.coerceAtMost(updated.size), additions)
                LocalRuleStore.validateRules(updated)
                save(rulesToSave = updated, closeEditor = true)
            }
        }.onFailure { valuesField?.error = it.message; triggerField?.error = it.message }
    }

    private fun remove(trigger: String) {
        if (selected == 2) save(domainsToSave = domains!!.items.filterNot { it == trigger })
        else save(rulesToSave = rules!!.items.filterNot { it.kind == kinds[selected] && it.trigger == trigger })
    }

    private fun reorder(keys: List<String>, index: Int, delta: Int) {
        val order = keys.toMutableList().apply { add(index + delta, removeAt(index)) }
        if (selected == 2) save(domainsToSave = order) else {
            val kind = kinds[selected]
            val grouped = rules!!.items.filter { it.kind == kind }.groupBy { it.trigger }
            save(rulesToSave = rules!!.items.filterNot { it.kind == kind } + order.flatMap { grouped.getValue(it) })
        }
    }

    private fun save(rulesToSave: List<LocalRuleStore.Rule>? = null, domainsToSave: List<String>? = null, closeEditor: Boolean = false) {
        if (busy) return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (rulesToSave != null) rules = store.saveRules(rulesToSave, rules!!)
                    if (domainsToSave != null) domains = store.saveDomains(domainsToSave, domains!!)
                }
            }
            setBusy(false)
            result.onSuccess {
                viewModel.rime.runIfReady {
                    setRuntimeOption("clean_rules_changed", !getRuntimeOption("clean_rules_changed"))
                }
                if (closeEditor) { draft = null; editor?.dismiss() }
                render()
                Toast.makeText(requireContext(), "已保存，下次输入生效", Toast.LENGTH_SHORT).show()
            }.onFailure {
                val message = it.message ?: "保存失败，原规则未改动"
                if (editor != null) triggerField?.error = message
                else AlertDialog.Builder(requireContext()).setTitle("无法保存").setMessage(message).setPositiveButton("重新读取") { _, _ -> reload() }.show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", selected)
        draft?.let {
            outState.putBoolean("editing", true)
            outState.putString("original", it.original)
            outState.putString("trigger", triggerField?.text?.toString() ?: it.trigger)
            outState.putString("values", valuesField?.text?.toString() ?: it.values)
        }
    }

    override fun onDestroyView() {
        editor?.dismiss()
        editor = null
        triggerField = null
        valuesField = null
        list = null
        addButton = null
        tabs = emptyList()
        super.onDestroyView()
    }
}
