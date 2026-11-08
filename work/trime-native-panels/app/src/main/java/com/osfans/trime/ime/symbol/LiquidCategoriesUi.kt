/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.util.roundedRippleDrawable
import splitties.dimensions.dp

/** A stable-position directory; selecting a category never scrolls this list. */
class LiquidCategoriesUi(
    private val context: Context,
    private val scope: ThemeScope,
) {
    private var tags: List<LiquidKeyboard.Tag> = emptyList()
    private var selected = -1
    private var onSelect: ((Int) -> Unit)? = null

    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    private val adapter: RecyclerView.Adapter<Holder> = object : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(TextView(context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(46))
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = scope.theme.fonts.candidate
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(context.dp(5), 0, context.dp(5), 0)
            })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val active = selected == position
            holder.text.text = tags[position].label
            holder.text.setTextColor(
                if (active) scope.colors.hilitedCandidateTextColor else scope.colors.candidateTextColor,
            )
            holder.text.background = roundedRippleDrawable(
                scope.colors.hilitedCandidateBackColor,
                context.dp(6).toFloat(),
                if (active) scope.colors.hilitedCandidateBackColor else Color.TRANSPARENT,
            )
            holder.text.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (root.canActivateItem && index != RecyclerView.NO_POSITION) onSelect?.invoke(index)
            }
        }

        override fun getItemCount(): Int = tags.size
    }

    val root: ScrollSafeRecyclerView = ScrollSafeRecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@LiquidCategoriesUi.adapter
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    fun setTags(items: List<LiquidKeyboard.Tag>) {
        tags = items
        adapter.notifyDataSetChanged()
    }

    fun setOnCategoryClickListener(listener: (Int) -> Unit) {
        onSelect = listener
    }

    fun activate(index: Int) {
        if (selected == index) return
        val previous = selected
        selected = index
        if (previous in tags.indices) adapter.notifyItemChanged(previous)
        if (index in tags.indices) adapter.notifyItemChanged(index)
    }

    fun refreshColors() = adapter.notifyDataSetChanged()
}
