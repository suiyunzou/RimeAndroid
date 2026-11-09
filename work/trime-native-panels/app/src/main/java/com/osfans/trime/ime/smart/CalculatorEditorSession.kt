// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Owns only text written by this calculator, never arbitrary surrounding editor text. */
class CalculatorEditorSession {
    data class Snapshot(val text: String, val offset: Int, val start: Int, val end: Int) {
        fun valid() = offset >= 0 && start in offset..offset + text.length && end in offset..offset + text.length
    }
    interface Editor {
        fun snapshot(): Snapshot?
        fun replace(start: Int, end: Int, text: String): Boolean
    }
    var expression = ""
        private set
    private var owned = ""
    private var expected: Snapshot? = null
    private var submitted = false
    val preview: String get() = runCatching { CalculatorEngine.calculate(expression).preview() }.getOrDefault("")

    fun reset() { expression = ""; owned = ""; expected = null; submitted = false }

    fun input(label: String, editor: Editor): Boolean {
        val before = editor.snapshot()?.takeIf { it.valid() }
            ?: throw IllegalStateException("无法核对输入框位置，已停止写入")
        // A different selection or externally edited context ends ownership. Do not delete old text.
        if (expected != null && before != expected) reset()
        if (submitted && label !in listOf("=", "C", "⌫")) {
            expression = ""; owned = ""; submitted = false
        }
        var nextExpression = expression
        var nextSubmitted = false
        val replacement = when (label) {
            "C" -> { nextExpression = ""; "" }
            "⌫" -> { nextExpression = owned.dropLast(1); nextExpression }
            "=" -> {
                if (submitted) return true
                val result = CalculatorEngine.calculate(expression)
                nextSubmitted = true
                expression + result.relation + result.value
            }
            "空格", "换行" -> owned + if (label == "空格") " " else "\n"
            else -> {
                require(expression.length + label.length <= CalculatorEngine.MAX_LENGTH) { "算式过长" }
                nextExpression += label
                nextExpression
            }
        }
        if (owned.isEmpty() && label in listOf("C", "⌫")) return true
        val start = if (owned.isNotEmpty()) before.end - owned.length else minOf(before.start, before.end)
        val end = if (owned.isNotEmpty()) before.end else maxOf(before.start, before.end)
        require((start >= before.offset && before.start == before.end) || owned.isEmpty()) { "输入位置已改变" }
        if (owned.isNotEmpty()) require(before.text.substring(start - before.offset, end - before.offset) == owned) { "算式已被修改" }
        if (!editor.replace(start, end, replacement)) { reset(); return false }
        expected = Snapshot(before.text.replaceRange(start - before.offset, end - before.offset, replacement),
            before.offset, start + replacement.length, start + replacement.length)
        expression = nextExpression
        owned = replacement
        submitted = nextSubmitted
        if (label == "空格" || label == "换行") reset()
        return true
    }
}
