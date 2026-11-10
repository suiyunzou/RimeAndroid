/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.annotation.CallSuper
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.ui.common.OnItemChangedListener
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.ui.settingskit.Palette

open class SchemaListAdapter(
    items: List<SchemaItem>,
) : BaseQuickAdapter<SchemaItem, SchemaListAdapter.ViewHolder>(items) {
    var multiselect = false
        private set

    /** 列表配色由 [SchemaListUi] 注入，用于刷新卡片与勾选框的日夜模式颜色。 */
    var palette: Palette? = null

    /** 拖动排序后通知页面把顺序标记为待持久化。 */
    var onOrderChanged: (() -> Unit)? = null

    private val selected = mutableListOf<SchemaItem>()

    private var listener: OnItemChangedListener<SchemaItem>? = null

    fun removeItemChangedListener() {
        listener = null
    }

    fun addOnItemChangedListener(x: OnItemChangedListener<SchemaItem>) {
        listener = listener?.let { OnItemChangedListener.merge(it, x) } ?: x
    }

    private var onBackPressedCallback: OnBackPressedCallback? = null

    private var mainViewModel: MainViewModel? = null

    fun setViewModel(model: MainViewModel) {
        mainViewModel = model
    }

    inner class ViewHolder(
        val entryUi: SchemaListEntryUi,
    ) : RecyclerView.ViewHolder(entryUi.root) {
        val checkBox = entryUi.checkBox
        val nameText = entryUi.nameText
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(SchemaListEntryUi(context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: SchemaItem?,
    ) {
        item ?: return
        with(holder) {
            palette?.let { entryUi.applyPalette(it) }
            nameText.text = item.name
            entryUi.bindSummary(item.id)
            // 先清空监听再回填状态，避免复用 ViewHolder 时把上一条目的选中状态写错
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isVisible = multiselect
            checkBox.isChecked = selected.any { it.id == item.id }
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                select(item, isChecked)
            }
            nameText.setOnClickListener {
                if (multiselect) checkBox.toggle()
            }
        }
    }

    /**
     * 上下拖动排序：位置交换交给 BaseQuickAdapter 维护，随后由页面负责持久化。
     * 多选模式下不允许拖动，避免和批量删除的操作混淆。
     */
    fun moveItem(
        fromPosition: Int,
        toPosition: Int,
    ): Boolean {
        if (multiselect) return false
        if (fromPosition == toPosition) return false
        if (fromPosition !in items.indices || toPosition !in items.indices) return false
        move(fromPosition, toPosition, null)
        onOrderChanged?.invoke()
        return true
    }

    private fun select(
        item: SchemaItem,
        shouldSelect: Boolean,
    ) {
        if (shouldSelect) {
            if (selected.indexOf(item) == -1) selected.add(item)
        } else {
            selected.remove(item)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    open fun enterMultiSelect(onBackPressedDispatcher: OnBackPressedDispatcher) {
        mainViewModel?.let {
            if (multiselect) {
                return
            }
            onBackPressedCallback =
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        exitMultiSelect()
                    }
                }
            onBackPressedDispatcher.addCallback(onBackPressedCallback!!)
            it.enableToolbarDeleteButton {
                deleteSelected()
                exitMultiSelect()
            }
            it.hideToolbarEditButton()
            multiselect = true
            notifyDataSetChanged()
        }
    }

    private fun deleteSelected() {
        if (!multiselect || selected.isEmpty()) {
            return
        }
        selected.forEach { remove(it) }
        listener?.onItemRemovedBatch(selected)
    }

    @SuppressLint("NotifyDataSetChanged")
    open fun exitMultiSelect() {
        mainViewModel?.let {
            if (!multiselect) {
                return
            }
            onBackPressedCallback?.remove()
            it.disableToolbarDeleteButton()
            multiselect = false
            selected.clear()
            notifyDataSetChanged()
            if (items.isNotEmpty()) {
                it.showToolbarEditButton()
            }
        }
    }

    private fun addInternal(
        idx: Int,
        item: SchemaItem,
    ) {
        listener?.onItemAdded(idx, item)
        mainViewModel?.showToolbarEditButton()
    }

    @CallSuper
    override fun add(data: SchemaItem, commitCallback: Runnable?) {
        super.add(data, commitCallback)
        addInternal(items.size, data)
    }

    @CallSuper
    override fun add(position: Int, data: SchemaItem, commitCallback: Runnable?) {
        super.add(position, data, commitCallback)
        addInternal(position, data)
    }

    @CallSuper
    override fun addAll(collection: Collection<SchemaItem>, commitCallback: Runnable?) {
        super.addAll(collection, commitCallback)
        listener?.onItemAddedBatch(collection.toList())
        mainViewModel?.showToolbarEditButton()
    }

    /** This also valid for [BaseQuickAdapter.remove] */
    @CallSuper
    override fun removeAt(position: Int, commitCallback: Runnable?) {
        listener?.onItemRemoved(position, items[position])
        super.removeAt(position, commitCallback)
        if (items.isEmpty()) {
            mainViewModel?.hideToolbarEditButton()
        }
    }
}
