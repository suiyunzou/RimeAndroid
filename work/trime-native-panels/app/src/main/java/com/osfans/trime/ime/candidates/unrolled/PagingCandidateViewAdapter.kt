/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import com.osfans.trime.ime.candidates.IndexedCandidate
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidateViewHolder

open class PagingCandidateViewAdapter(
    val scope: ThemeScope,
) : PagingDataAdapter<IndexedCandidate, CandidateViewHolder>(diffCallback) {
    companion object {
        private val diffCallback =
            object : DiffUtil.ItemCallback<IndexedCandidate>() {
                override fun areItemsTheSame(
                    oldItem: IndexedCandidate,
                    newItem: IndexedCandidate,
                ): Boolean = oldItem.index == newItem.index && oldItem.candidate.text == newItem.candidate.text

                override fun areContentsTheSame(
                    oldItem: IndexedCandidate,
                    newItem: IndexedCandidate,
                ): Boolean = oldItem == newItem
            }
    }

    var offset: Int = 0
        private set

    var highlightedIndex: Int = -1
        private set

    fun refreshWith(offset: Int, highlightedIndex: Int) {
        this.offset = offset
        this.highlightedIndex = highlightedIndex
        refresh()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): CandidateViewHolder = CandidateViewHolder(CandidateItemUi(parent.context, scope))

    override fun onBindViewHolder(
        holder: CandidateViewHolder,
        position: Int,
    ) {
        val item = getItem(position) ?: return
        val idx = item.index
        val highlighted = idx == highlightedIndex
        holder.ui.update(item.candidate, highlighted)
        holder.text = item.candidate.text
        holder.comment = item.candidate.comment
        holder.idx = idx
        holder.candidateContext = item.context
    }
}
