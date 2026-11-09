// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class CalculatorEditorSessionTest : FunSpec({
    class Editor(var text: String, var start: Int = text.length, var end: Int = start, val offset: Int = 0) : CalculatorEditorSession.Editor {
        var available = true
        override fun snapshot() = if (available) CalculatorEditorSession.Snapshot(text, offset, start + offset, end + offset) else null
        override fun replace(start: Int, end: Int, text: String): Boolean {
            this.text = this.text.replaceRange(start - offset, end - offset, text)
            this.start = start - offset + text.length; this.end = this.start
            return true
        }
    }
    test("every digit and operator writes in order and equals appends the result") {
        val editor = Editor("existing:")
        val session = CalculatorEditorSession()
        listOf("7", "9", "+", "3").forEach { session.input(it, editor) shouldBe true }
        editor.text shouldBe "existing:79+3"
        session.preview shouldBe "=82"
        session.input("=", editor)
        editor.text shouldBe "existing:79+3=82"
        session.input("=", editor)
        editor.text shouldBe "existing:79+3=82"
        session.input("⌫", editor)
        editor.text shouldBe "existing:79+3=8"
        session.input("C", editor)
        editor.text shouldBe "existing:"
    }
    test("nonempty selection and middle insertion preserve surrounding text") {
        val editor = Editor("leftOLDright", 4, 7, offset = 100)
        val session = CalculatorEditorSession()
        listOf("1", "+", "2", "=").forEach { session.input(it, editor) }
        editor.text shouldBe "left1+2=3right"
        session.input("C", editor)
        editor.text shouldBe "leftright"
    }
    test("moving the cursor releases ownership and backspace cannot erase old text") {
        val editor = Editor("prefix")
        val session = CalculatorEditorSession()
        session.input("7", editor)
        editor.start = 0; editor.end = 0
        session.input("⌫", editor)
        editor.text shouldBe "prefix7"
        session.input("2", editor)
        editor.text shouldBe "2prefix7"
        session.input("C", editor)
        editor.text shouldBe "prefix7"
    }
    test("external changes and unavailable snapshots never delete previous text") {
        val editor = Editor("")
        val session = CalculatorEditorSession()
        session.input("8", editor)
        editor.text = "x"; editor.start = 1; editor.end = 1
        session.input("C", editor)
        editor.text shouldBe "x"
        editor.available = false
        shouldThrow<IllegalStateException> { session.input("1", editor) }
        editor.text shouldBe "x"
    }
    test("separators close ownership and a new calculation preserves the previous equation") {
        val editor = Editor("")
        val session = CalculatorEditorSession()
        listOf("1", "+", "2", "=", "换行", "√", "(", "1", "2", ")", "=").forEach { session.input(it, editor) }
        editor.text shouldBe "1+2=3\n√(12)≈3.464101615137755"
        session.reset()
        session.input("C", editor)
        editor.text shouldBe "1+2=3\n√(12)≈3.464101615137755"
    }
    test("a rapid ordered batch does not drop keys") {
        val editor = Editor("")
        val session = CalculatorEditorSession()
        val text = "1234567890".repeat(20)
        text.forEach { session.input(it.toString(), editor) }
        editor.text shouldBe text
    }
})
