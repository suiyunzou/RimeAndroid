// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.CandidateContext
import com.osfans.trime.core.CompositionProto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class CandidateDeduplicatorTest : FunSpec({
    fun c(text: String, comment: String = "") = CandidateProto(text, comment, "")
    test("same text from different dictionaries retains first engine index") {
        val actual = CandidateDeduplicator.collect(arrayOf(c("甲","用户"),c("甲","系统"),c("乙"),c("甲"),c("丙")), 10)
        actual.map { it.index } shouldBe listOf(10,12,14)
        actual.map { it.candidate.text } shouldBe listOf("甲","乙","丙")
        actual.first().candidate.comment shouldBe "用户"
    }
    test("cross page dedup preserves original noncontiguous indices") {
        val seen = HashSet<String>()
        CandidateDeduplicator.collect(arrayOf(c("甲"),c("乙")),0,seen).map { it.index } shouldBe listOf(0,1)
        CandidateDeduplicator.collect(arrayOf(c("乙"),c("丙"),c("甲"),c("丁")),32,seen).map { it.index } shouldBe listOf(33,35)
        CandidateDeduplicator.collect(emptyArray(),64,seen) shouldBe emptyList()
    }
    test("candidate context rejects equal-text new response and schema changes") {
        val composition = CompositionProto(preedit="nihao")
        val context = CandidateContext(composition,"schema-a")
        context.matches(composition,"schema-a") shouldBe true
        context.matches(composition.copy(),"schema-a") shouldBe false
        context.matches(composition,"schema-b") shouldBe false
    }
})
