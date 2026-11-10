package com.osfans.trime.ui.main

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.localrules.LocalRuleStore
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsDialogs
import com.osfans.trime.ui.settingskit.SettingsKitState
import com.osfans.trime.ui.settingskit.SettingsPageFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「本地规则」页：iOS 分组卡片样式。
 *
 * 只替换呈现层：读取快照、保存、[LocalRuleStore] 校验、上移/下移、删除、草稿恢复、busy 状态、
 * 失败提示、Toast、clean_rules_changed 运行时开关与 onSaveInstanceState 均沿用原实现。
 *
 * 编辑对话框仍用普通 Android 文本框：输入法直接编辑这些字段，不会落回上一个宿主应用。
 */
class LocalRulesFragment : SettingsPageFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val store get() = LocalRuleStore(DataManager.userDataDir)
    private val kinds = listOf("shortcut", "next", "email")
    private val titles = listOf("快捷候选", "上屏后推荐", "邮箱后缀")
    private var selected = 0
    private var rules: LocalRuleStore.Snapshot<LocalRuleStore.Rule>? = null
    private var domains: LocalRuleStore.Snapshot<String>? = null
    private var busy = false
    private var loading = true
    private var loadError: String? = null
    private var addButton: TextView? = null
    private var segmentsCard: LinearLayout? = null
    private var editor: AlertDialog? = null
    private var triggerField: EditText? = null
    private var valuesField: EditText? = null
    private var draft: Draft? = null

    private data class Draft(val original: String?, val trigger: String, val values: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getInt("tab") ?: 0
        if (savedInstanceState?.getBoolean("editing") == true) {
            draft = Draft(savedInstanceState.getString("original"), savedInstanceState.getString("trigger").orEmpty(), savedInstanceState.getString("values").orEmpty())
        }
    }

    override fun buildPage(builder: SettingsBuilder) {
        builder.space(12)
        val segmentCard =
            builder.group().apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
        segmentsCard = segmentCard
        segmentCard.addView(
            builder.segmentsView(titles, selected) { index ->
                if (busy || index == selected) return@segmentsView
                selected = index
                loadError = null
                reload()
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)),
        )
        builder.space(4)
        builder.note(description())
        builder.space(12)
        addButton =
            builder.button(if (selected == 2) "添加邮箱后缀" else "添加规则") {
                if (!busy) showEditor(Draft(null, "", ""))
            }
        builder.space(8)
        builder.section(titles[selected])
        val card = builder.group()
        when {
            loadError != null -> {
                builder.row(card, "读取失败", summary = "${loadError}，原文件保持不变。")
                builder.row(card, "重新读取") { if (!busy) reload() }
            }

            loading -> builder.row(card, "正在读取…")

            else -> {
                val groups = groups()
                if (groups.isEmpty()) {
                    builder.row(
                        card,
                        if (selected == 2) "暂无邮箱后缀" else "暂无规则",
                        summary = "点击上方按钮添加。",
                    )
                } else {
                    val keys = groups.map { it.first }
                    groups.forEachIndexed { index, (trigger, values) ->
                        ruleRow(builder, card, keys, index, trigger, values)
                    }
                }
            }
        }
        builder.note("点按一行可编辑；右侧 ⋮ 可上移、下移或删除。")
        setBusy(busy)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.disableTopOptionsMenu()
        reload(restoreDraft = true)
    }

    private fun description(): String =
        when (selected) {
            0 -> "输入缩写或符号后显示候选。一个触发词可添加多个候选，按填写顺序排列。"
            1 -> "选词上屏后显示后续推荐。一个触发词可添加多个推荐。"
            else -> "输入 @ 后显示这些后缀，按列表顺序排列。"
        }

    private fun groups(): List<Pair<String, List<String>>> =
        if (selected == 2) {
            domains?.items.orEmpty().map { it to emptyList<String>() }
        } else {
            rules?.items.orEmpty()
                .filter { it.kind == kinds[selected] }
                .groupBy { it.trigger }
                .map { (trigger, rows) -> trigger to rows.map { it.value } }
        }

    private fun ruleRow(
        builder: SettingsBuilder,
        card: LinearLayout,
        keys: List<String>,
        index: Int,
        trigger: String,
        values: List<String>,
    ) {
        val title = if (selected == 2) "@$trigger" else trigger
        val summary = values.joinToString(" · ").takeIf { it.isNotEmpty() }
        val row =
            builder.row(
                parent = card,
                title = title,
                summary = summary,
                onClick = {
                    if (!busy) showEditor(Draft(trigger, trigger, values.joinToString("\n")))
                },
            )
        row.contentDescription = SettingsKitState.rowDescription(title, summary.orEmpty())
        summary?.let { capSummaryLines(row, 3) }
        val menu =
            TextView(requireContext()).apply {
                text = "⋮"
                textSize = 20f
                setTextColor(palette.muted)
                gravity = Gravity.CENTER
                contentDescription = "$title 的操作"
                isClickable = true
                isFocusable = true
                alpha = if (busy) 0.5f else 1f
                setOnClickListener { anchor ->
                    if (busy) return@setOnClickListener
                    showRowMenu(anchor, keys, index, trigger, values)
                }
            }
        // 放在 › 箭头之前，读作「⋮ ›」。
        val insertAt = if (row.childCount > 0) row.childCount - 1 else 0
        row.addView(menu, insertAt, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    /** 旧版候选副标题最多显示 3 行；builder.row 的副标题默认不限行。 */
    private fun capSummaryLines(
        row: LinearLayout,
        lines: Int,
    ) {
        val texts = row.getChildAt(0) as? LinearLayout ?: return
        val summary = texts.getChildAt(1) as? TextView ?: return
        summary.maxLines = lines
        summary.ellipsize = TextUtils.TruncateAt.END
    }

    private fun showRowMenu(
        anchor: View,
        keys: List<String>,
        index: Int,
        trigger: String,
        values: List<String>,
    ) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("编辑").setOnMenuItemClickListener {
                showEditor(Draft(trigger, trigger, values.joinToString("\n")))
                true
            }
            if (index > 0) {
                menu.add("上移").setOnMenuItemClickListener {
                    reorder(keys, index, -1)
                    true
                }
            }
            if (index < keys.lastIndex) {
                menu.add("下移").setOnMenuItemClickListener {
                    reorder(keys, index, 1)
                    true
                }
            }
            menu.add("删除").setOnMenuItemClickListener {
                SettingsDialogs.confirm(
                    requireContext(),
                    "删除规则？",
                    if (selected == 2) "@$trigger" else "$trigger 的所有候选将被删除。",
                    "删除",
                ) { remove(trigger) }
                true
            }
            show()
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        addButton?.apply {
            isEnabled = !value && dataReady()
            alpha = if (value) 0.6f else 1f
        }
        segmentsCard?.apply {
            isEnabled = !value
            alpha = if (value) 0.6f else 1f
        }
        editor?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !value
        editor?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !value
        editor?.setCancelable(!value)
    }

    private fun dataReady(): Boolean = if (selected == 2) domains != null else rules != null

    private fun reload(restoreDraft: Boolean = false) {
        loadError = null
        loading = true
        setBusy(true)
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { if (selected == 2) domains = store.loadDomains() else rules = store.loadRules() }
            }
            loading = false
            result.onSuccess {
                setBusy(false)
                render()
                if (restoreDraft) draft?.let(::showEditor)
            }.onFailure {
                if (selected == 2) domains = null else rules = null
                loadError = it.message ?: "无法读取规则"
                setBusy(false)
                render()
            }
        }
    }

    /** 重建整页；保存后保留滚动位置，避免列表跳回顶部。 */
    private fun render(keepScroll: Boolean = false) {
        val scroll = view as? ScrollView ?: return
        if (!isAdded) return
        val position = scroll.scrollY
        rerender()
        if (keepScroll) {
            scroll.post {
                if (scroll.parent != null) scroll.scrollTo(0, position)
            }
        }
    }

    private fun label(text: String) =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }

    private fun showEditor(value: Draft) {
        if (busy || editor != null) return
        draft = value
        val ctx = requireContext()
        val content =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
            }
        content.addView(label(if (selected == 2) "邮箱域名" else if (selected == 0) "触发缩写或符号" else "上屏的词句"))
        triggerField =
            EditText(ctx).apply {
                setSingleLine(true)
                inputType =
                    when (selected) {
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
            valuesField =
                EditText(ctx).apply {
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
        val dialog =
            AlertDialog.Builder(ctx)
                .setTitle(if (value.original == null) "添加${titles[selected]}" else "编辑${titles[selected]}")
                .setView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(content) })
                .setNegativeButton("取消") { _, _ -> draft = null }
                .setPositiveButton("保存", null)
                .create()
        editor = dialog
        dialog.setOnCancelListener { draft = null }
        dialog.setOnDismissListener {
            editor = null
            triggerField = null
            valuesField = null
        }
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

    private fun reorder(
        keys: List<String>,
        index: Int,
        delta: Int,
    ) {
        val order = keys.toMutableList().apply { add(index + delta, removeAt(index)) }
        if (selected == 2) save(domainsToSave = order) else {
            val kind = kinds[selected]
            val grouped = rules!!.items.filter { it.kind == kind }.groupBy { it.trigger }
            save(rulesToSave = rules!!.items.filterNot { it.kind == kind } + order.flatMap { grouped.getValue(it) })
        }
    }

    private fun save(
        rulesToSave: List<LocalRuleStore.Rule>? = null,
        domainsToSave: List<String>? = null,
        closeEditor: Boolean = false,
    ) {
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
                render(keepScroll = true)
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
        addButton = null
        segmentsCard = null
        super.onDestroyView()
    }
}
