// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Only single keyboard punctuation marks are localized; addresses and phrases stay literal. */
internal object KeyboardPunctuation {
    private val english = ",.?!:;()[]{}\"'"
    private val chinese = "，。？！：；（）【】｛｝“‘"

    fun localized(text: String, ascii: Boolean): String {
        val mark = text.singleOrNull() ?: return text
        return if (ascii) {
            when (mark) {
                '”' -> "\""
                '’' -> "'"
                '、' -> ","
                '…' -> "..."
                else -> chinese.indexOf(mark).takeIf { it >= 0 }?.let { english[it].toString() } ?: text
            }
        } else english.indexOf(mark).takeIf { it >= 0 }?.let { chinese[it].toString() } ?: text
    }

    fun isLocalizable(text: String): Boolean = localized(text, false) != text || localized(text, true) != text
}
