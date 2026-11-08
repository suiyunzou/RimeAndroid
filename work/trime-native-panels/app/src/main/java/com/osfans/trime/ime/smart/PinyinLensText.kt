// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Keeps the actual caret visible even when raw composing input exceeds the lens. */
object PinyinLensText {
    fun window(preview: String, maxCodePoints: Int = 24): String {
        require(maxCodePoints > 0)
        val marker = preview.indexOf('|')
        if (marker < 0) return preview
        val raw = preview.removeRange(marker, marker + 1)
        val caret = CursorLensText.safeCaret(raw, marker)
        val count = raw.codePointCount(0, raw.length)
        val before = raw.codePointCount(0, caret)
        val startPoint = (before - maxCodePoints / 2).coerceAtLeast(0).coerceAtMost(maxOf(0, count - maxCodePoints))
        val endPoint = minOf(count, startPoint + maxCodePoints)
        val start = raw.offsetByCodePoints(0, startPoint)
        val end = raw.offsetByCodePoints(0, endPoint)
        return (if (start > 0) "…" else "") + raw.substring(start, caret) + "|" + raw.substring(caret, end) + if (end < raw.length) "…" else ""
    }

    fun matchingSuggestion(preview: String, hint: CorrectionPresentation.Hint?): String? {
        if (hint == null || hint.sentence) return null
        fun normalize(value: String) = value.replace("|", "").replace("'", "").replace(" ", "").replace("ü", "v")
        if (normalize(preview) != normalize(hint.original) || normalize(hint.original) == normalize(hint.corrected)) return null
        return hint.corrected
    }
}
