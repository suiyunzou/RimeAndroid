// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.CandidateContext

/** Keep engine indices when dictionaries produce the same visible text. */
data class IndexedCandidate(val index: Int, val candidate: CandidateProto, val context: CandidateContext? = null)

object CandidateDeduplicator {
    fun collect(candidates: Array<CandidateProto>, start: Int = 0, seen: MutableSet<String> = HashSet(), context: CandidateContext? = null): List<IndexedCandidate> =
        candidates.mapIndexedNotNull { offset, candidate ->
            if (seen.add(candidate.text)) IndexedCandidate(start + offset, candidate, context) else null
        }
}
