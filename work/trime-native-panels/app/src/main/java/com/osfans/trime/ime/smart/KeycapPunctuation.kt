// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/**
 * The punctuation text that a keycap corner shows.
 *
 * The corner cell is only 9-10dp wide, so the mark must be unambiguous and must match the
 * Chinese/ASCII mode that the key actually commits:
 * - Chinese mode shows the Chinese full-width mark, ASCII mode shows the ASCII mark.
 *   [localized] is the single-mark table shared with [KeyboardPunctuation], so the cap and
 *   the commit path cannot drift apart;
 * - a paired mark always shows its opening half ("》" -> "《", ")" -> "(") and a
 *   two-character pair such as "“”" collapses to its opening half, so the small cell never
 *   shows only a closing half.
 *
 * [display] is pure: no Android or Canvas state is touched.
 */
internal object KeycapPunctuation {
    /** ASCII mark -> Chinese full-width mark (Chinese mode). Shared with the commit path. */
    private val toChinese = mapOf(
        ',' to "，", '.' to "。", '?' to "？", '!' to "！", ':' to "：", ';' to "；",
        '(' to "（", ')' to "）", '[' to "【", ']' to "】", '{' to "｛", '}' to "｝",
        '"' to "“", '\'' to "‘",
    )

    /** Chinese full-width mark -> ASCII mark (ASCII mode). Shared with the commit path. */
    private val toAscii = mapOf(
        '，' to ",", '。' to ".", '？' to "?", '！' to "!", '：' to ":", '；' to ";",
        '（' to "(", '）' to ")", '【' to "[", '】' to "]", '｛' to "{", '｝' to "}",
        '“' to "\"", '”' to "\"", '‘' to "'", '’' to "'",
        '、' to ",", '…' to "...",
    )

    /**
     * Closing half -> opening half, in every language: the cell shows the direction the
     * pair opens, whichever mode is active. ASCII quotes are their own opening half.
     */
    private val openingOf = mapOf(
        '”' to '“', '’' to '‘', '》' to '《', '〉' to '〈', '」' to '「', '』' to '『',
        '】' to '【', '〕' to '〔', '）' to '（', '］' to '［', '｝' to '｛',
        ')' to '(', ']' to '[', '}' to '{', '>' to '<',
        '"' to '"', '\'' to '\'',
    )

    private val closingOf = openingOf.entries.associate { (closing, opening) -> opening to closing }

    /** Single marks that [KeyBindingHint] still draws with its pen; everything else uses the key font. */
    private const val handDrawn = "!?:;.,-+_/()\"'"

    /** Single-mark localization shared with the commit path ([KeyboardPunctuation]). */
    fun localized(text: String, ascii: Boolean): String {
        val mark = text.singleOrNull() ?: return text
        return (if (ascii) toAscii[mark] else toChinese[mark]) ?: text
    }

    /** What the keycap shows for [text]; [ascii] is the current input mode. */
    fun display(text: String, ascii: Boolean): String {
        if (text.isEmpty()) return text
        val single = text.singleOrNull()
        if (single != null) {
            val localized = localized(text, ascii)
            val mark = localized.singleOrNull() ?: return localized
            return openingHalf(mark).toString()
        }
        return pairedOpeningHalf(text, ascii) ?: text
    }

    /** True when the pen can draw this mark; Chinese full-width marks must use the font glyph. */
    fun isHandDrawnMark(mark: Char): Boolean = mark in handDrawn

    /** A single non-ASCII mark: the key font owns it and the pen shapes never apply. */
    fun isFullwidthMark(text: String): Boolean = text.singleOrNull()?.let { it.code > 0x7E } == true

    private fun openingHalf(mark: Char): Char = openingOf[mark] ?: mark

    /** "“”" -> "“", "<>" -> "<"; anything that is not exactly one pair stays as it is. */
    private fun pairedOpeningHalf(text: String, ascii: Boolean): String? {
        if (text.length != 2) return null
        // Decide on the raw characters first: two opening marks ("““") or a reversed pair
        // ("」「") is not a pair, even when ASCII mode would localize both to '"'.
        val paired = when {
            text[0] == text[1] && text[0] in "\"'" -> true
            else -> closingOf[openingHalf(text[0])] == text[1]
        }
        if (!paired) return null
        val first = localized(text[0].toString(), ascii).singleOrNull() ?: return null
        return openingHalf(first).toString()
    }
}
