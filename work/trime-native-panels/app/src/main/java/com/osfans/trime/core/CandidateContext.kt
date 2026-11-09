// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.core

/** A UI candidate page belongs to one emitted composition object, not just equal pinyin. */
class CandidateContext(val composition: CompositionProto, val schemaId: String) {
    fun matches(composition: CompositionProto, schemaId: String): Boolean =
        this.composition === composition && this.schemaId == schemaId
}
