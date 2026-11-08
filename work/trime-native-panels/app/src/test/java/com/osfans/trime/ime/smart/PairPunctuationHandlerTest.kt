/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private class PairEditor : PairPunctuationHandler.Editor {
    var range: PairPunctuationHandler.Selection? = PairPunctuationHandler.Selection(100, 100)
    var selected: String? = null
    var previous: String? = "("
    var next: String? = ")"
    var commitWorks = true
    var selectWorks = true
    var deleteWorks = true
    var failSelection = false
    var committed: String? = null
    var caret: Int? = null
    var leftCount = 0
    var deletes = 0
    var batches = 0
    override fun selection(): PairPunctuationHandler.Selection? {
        if (failSelection) error("editor disconnected")
        return range
    }
    override fun selectedText() = selected
    override fun before() = previous
    override fun after() = next
    override fun commit(text: String): Boolean { committed = text; return commitWorks }
    override fun select(position: Int): Boolean { caret = position; return selectWorks }
    override fun left(): Boolean { leftCount++; return true }
    override fun deletePair(): Boolean { deletes++; return deleteWorks }
    override fun beginBatch() { batches++ }
    override fun endBatch() { batches-- }
}

class PairPunctuationHandlerTest : StringSpec({
    "every supported pair commits once and positions its center" {
        PairPunctuationHandler.pairs.forEach { (open, close) ->
            val editor = PairEditor()
            PairPunctuationHandler.insertPair(editor, open) shouldBe true
            editor.committed shouldBe "$open$close"
            editor.caret shouldBe 101
            editor.batches shouldBe 0
        }
    }
    "replacement uses the smaller selection endpoint" {
        val editor = PairEditor().apply { range = PairPunctuationHandler.Selection(20, 10) }
        PairPunctuationHandler.insertPair(editor, '(') shouldBe true
        editor.caret shouldBe 11
    }
    "unknown or rejected absolute selection falls back to one left key" {
        for (editor in listOf(PairEditor().apply { range = null }, PairEditor().apply { selectWorks = false }, PairEditor().apply { failSelection = true })) {
            PairPunctuationHandler.insertPair(editor, '(') shouldBe true
            editor.leftCount shouldBe 1
        }
    }
    "failed commit and unsupported characters do not move the cursor" {
        val editor = PairEditor().apply { commitWorks = false }
        PairPunctuationHandler.insertPair(editor, '(') shouldBe false
        editor.leftCount shouldBe 0
        editor.caret shouldBe null
        editor.batches shouldBe 0
        editor.committed = null
        PairPunctuationHandler.insertPair(editor, 'a') shouldBe false
        editor.committed shouldBe null
    }
    "each empty pair is deleted by one surrounding-text edit" {
        PairPunctuationHandler.pairs.forEach { (open, close) ->
            val editor = PairEditor().apply { previous = open.toString(); next = close.toString() }
            PairPunctuationHandler.handleBackspace(editor) shouldBe true
            editor.deletes shouldBe 1
        }
    }
    "nonempty mismatched missing and selected ranges retain normal deletion" {
        for (editor in listOf(
            PairEditor().apply { previous = "x" }, PairEditor().apply { next = "]" },
            PairEditor().apply { previous = null }, PairEditor().apply { next = "" },
            PairEditor().apply { selected = "word" },
            PairEditor().apply { range = PairPunctuationHandler.Selection(1, 2) },
            PairEditor().apply { failSelection = true },
        )) {
            PairPunctuationHandler.handleBackspace(editor) shouldBe false
            editor.deletes shouldBe 0
        }
    }
    "rejected pair deletion is not reported as consumed" {
        val editor = PairEditor().apply { deleteWorks = false }
        PairPunctuationHandler.handleBackspace(editor) shouldBe false
        editor.deletes shouldBe 1
    }
})
