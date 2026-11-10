// SPDX-FileCopyrightText: 2015 - 2025 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidatePresentation
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.IndexedCandidate
import splitties.dimensions.dp

class CompactCandidateViewAdapter(val scope: ThemeScope) : BaseQuickAdapter<IndexedCandidate, CandidateViewHolder>() {
    // Hash-based stable IDs collided for repeated dictionary entries.
    var total: Int = -1
        private set
    var highlightedIdx: Int = -1
        private set

    /**
     * Whether the correction/sentence group may use the accent color.
     *
     * The adapter is the only place that sees the whole response, so it decides
     * once per update: the accent is enabled only when the emphasis rows form a
     * single contiguous run in engine order. Candidates are never reordered.
     */
    private var allowAccent: Boolean = false

    fun updateCandidates(data: List<IndexedCandidate>, total: Int, highlightedIndex: Int) {
        this.total = total
        highlightedIdx = highlightedIndex
        allowAccent = CandidatePresentation.accentRunIsContiguous(
            data.map { CandidatePresentation.classify(it.candidate) },
        )
        submitList(data, null)
    }

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): CandidateViewHolder =
        CandidateViewHolder(CandidateItemUi(context, scope, compact = true)).also {
            it.ui.root.minimumWidth = context.dp(40)
            it.ui.root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int, item: IndexedCandidate?) {
        item ?: return
        holder.ui.update(item.candidate, item.index == highlightedIdx, allowAccent)
        holder.text = item.candidate.text
        holder.comment = item.candidate.comment
        holder.idx = item.index
        holder.candidateContext = item.context
    }
}
