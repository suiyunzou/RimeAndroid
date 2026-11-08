// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import com.osfans.trime.ime.smart.SpatialCursorScrubber.Direction
import com.osfans.trime.ime.smart.SpatialCursorScrubber.Snapshot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class CursorEditor(var state: Snapshot? = Snapshot("abcd", 100, 2, 2)) : SpatialCursorScrubber.Editor {
    override val identity: Any = this
    val selections = mutableListOf<Int>()
    val arrows = mutableListOf<Direction>()
    var accepts = true
    var throws = false
    override fun snapshot() = state
    override fun select(position: Int): Boolean {
        if (throws) error("Editor disconnected")
        selections.add(position)
        return accepts
    }
    override fun arrow(direction: Direction): Boolean { arrows.add(direction); return true }
}

class SpatialCursorScrubberTest : FunSpec({
    test("document start does not send an arrow that may move focus outside the editor") {
        val editor = CursorEditor(Snapshot("abc", 0, 0, 0))
        val scrub = SpatialCursorScrubber.forEditor(1f, { editor })
        scrub.begin()
        scrub.move(-12f, 0f) shouldBe false
        editor.arrows shouldBe emptyList()
    }
    test("scales threshold and adds extracted text absolute offset") {
        val editor = CursorEditor()
        val scrub = SpatialCursorScrubber.forEditor(2f, { editor })
        scrub.begin()
        scrub.move(-23f, 0f) shouldBe false
        scrub.move(-1f, 0f) shouldBe true
        editor.selections shouldBe listOf(101)
    }
    test("tracks accepted positions even while extracted snapshots lag") {
        val editor = CursorEditor()
        val scrub = SpatialCursorScrubber.forEditor(1f, { editor })
        scrub.begin()
        scrub.move(-12f, 0f)
        scrub.move(-12f, 0f)
        editor.selections shouldBe listOf(101, 100)
    }
    test("steps across surrogate pairs atomically") {
        val editor = CursorEditor(Snapshot("a😀b", 20, 3, 3))
        val scrub = SpatialCursorScrubber.forEditor(1f, { editor })
        scrub.begin()
        scrub.move(-12f, 0f)
        scrub.move(12f, 0f)
        editor.selections shouldBe listOf(21, 23)
    }
    test("selection collapses before another horizontal step") {
        val editor = CursorEditor(Snapshot("abcd", 100, 1, 3))
        val scrub = SpatialCursorScrubber.forEditor(1f, { editor })
        scrub.begin()
        scrub.move(-24f, 0f)
        editor.selections shouldBe listOf(101, 100)
    }
    test("rejected or throwing selection falls back to arrows") {
        val editor = CursorEditor().apply { accepts = false }
        val scrub = SpatialCursorScrubber.forEditor(1f, { editor })
        scrub.begin()
        scrub.move(12f, 0f) shouldBe true
        editor.throws = true
        scrub.move(-12f, 0f) shouldBe true
        editor.arrows shouldBe listOf(Direction.RIGHT, Direction.LEFT)
    }
    test("vertical steps use direction callbacks and never guessed absolute positions") {
        val editor = CursorEditor()
        val ticks = mutableListOf<Direction>()
        val scrub = SpatialCursorScrubber.forEditor(1f, { editor }, ticks::add)
        scrub.begin()
        scrub.move(0f, -24f)
        editor.selections shouldBe emptyList()
        editor.arrows shouldBe listOf(Direction.UP, Direction.UP)
        ticks shouldBe editor.arrows
    }
    test("connection changes and end stop a held gesture") {
        var editor: CursorEditor? = CursorEditor()
        val scrub = SpatialCursorScrubber.forEditor(1f, { editor })
        scrub.begin()
        scrub.move(12f, 0f)
        editor = CursorEditor()
        scrub.move(12f, 0f) shouldBe false
        editor!!.selections shouldBe emptyList()
        scrub.begin()
        scrub.end()
        scrub.move(12f, 0f) shouldBe false
        editor = null
        scrub.begin()
        scrub.move(12f, 0f) shouldBe false
    }
})
