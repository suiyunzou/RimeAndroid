/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.userdict

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.ui.settingskit.Palette

class UserDictListAdapter(
    items: List<String>,
    val initMoreButton: (ImageButton.(String) -> Unit) = {},
) : BaseQuickAdapter<String, UserDictListAdapter.ViewHolder>(items) {
    /** 列表配色由 [UserDictListUi] 注入，用于刷新卡片与 ⋮ 的日夜模式颜色。 */
    var palette: Palette? = null

    class ViewHolder(
        val entryUi: UserDictListEntryUi,
    ) : RecyclerView.ViewHolder(entryUi.root) {
        val nameText = entryUi.nameText
        val moreButton = entryUi.moreButton
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(UserDictListEntryUi(context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: String?,
    ) {
        val name = item ?: return
        palette?.let { holder.entryUi.applyPalette(it) }
        holder.nameText.text = name
        holder.moreButton.contentDescription = "$name 更多操作"
        initMoreButton(holder.moreButton, name)
    }
}
