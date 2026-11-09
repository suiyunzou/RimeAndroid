// SPDX-FileCopyrightText: 2015 - 2025 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates.unrolled

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.osfans.trime.core.CandidateContext
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.ime.candidates.CandidateDeduplicator
import com.osfans.trime.ime.candidates.IndexedCandidate

class CandidatesPagingSource(
    val rime: RimeSession,
    val total: Int,
    val offset: Int,
    precedingTexts: Set<String> = emptySet(),
    private val expected: CandidateContext,
) : PagingSource<Int, IndexedCandidate>() {
    private val seen = precedingTexts.toMutableSet()
    private val completedPages = HashMap<Int, LoadResult.Page<Int, IndexedCandidate>>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, IndexedCandidate> {
        val startIndex = params.key ?: offset
        completedPages[startIndex]?.let { return it }
        val candidates = rime.runOnReady { getCandidatesIfCurrent(startIndex, params.loadSize, expected) }
            ?: return LoadResult.Page(emptyList(), null, null)
        val nextIndex = startIndex + candidates.size
        val next = if (candidates.size < params.loadSize || (total >= 0 && nextIndex >= total)) null else nextIndex
        // This list starts after the visible strip; it only pages forward. Engine indices survive filtering.
        return LoadResult.Page(CandidateDeduplicator.collect(candidates, startIndex, seen, expected), null, next).also {
            completedPages[startIndex] = it
        }
    }

    override fun getRefreshKey(state: PagingState<Int, IndexedCandidate>) = null
}
