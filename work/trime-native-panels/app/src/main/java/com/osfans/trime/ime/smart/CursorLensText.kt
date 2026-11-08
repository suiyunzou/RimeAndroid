// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** UTF-16 editor offsets remain separate from the code-point radius of the lens. */
internal object CursorLensText {
    fun safeCaret(text: String, index: Int): Int {
        val at = index.coerceIn(0, text.length)
        return if (at > 0 && at < text.length && text[at].isLowSurrogate() && text[at-1].isHighSurrogate()) at-1 else at
    }
    fun around(text: String, index: Int, radius: Int = 6): Pair<String, String> {
        val caret = safeCaret(text, index)
        val left = text.lastIndexOf('\n', (caret-1).coerceAtLeast(0)).let { if (caret == 0) 0 else it+1 }
        val right = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val beforeCount = minOf(radius.coerceAtLeast(0), text.codePointCount(left, caret))
        val afterCount = minOf(radius.coerceAtLeast(0), text.codePointCount(caret, right))
        return text.substring(text.offsetByCodePoints(caret, -beforeCount), caret) to
            text.substring(caret, text.offsetByCodePoints(caret, afterCount))
    }
}
