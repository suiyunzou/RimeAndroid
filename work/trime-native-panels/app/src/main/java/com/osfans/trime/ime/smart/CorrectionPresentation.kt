// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.text.Normalizer

/** Metadata is emitted by the Rime filter, never inferred from candidate position. */
object CorrectionPresentation {
    const val PREFIX = "\u2063local:"
    data class Hint(val original: String, val corrected: String, val sentence: Boolean)

    fun parse(comment: String): Hint? {
        if (!comment.startsWith(PREFIX)) return null
        val fields = comment.removePrefix(PREFIX).split('|', limit = 3)
        if (fields.size != 3 || fields[0] !in setOf("correction", "sentence")) return null
        if (fields[1].length > 80 || fields[2].length > 160) return null
        if (!fields[1].all { it in 'a'..'z' || it == '\'' }) return null
        if (!foldPinyin(fields[2]).all { it in 'a'..'z' || it in " '" }) return null
        return Hint(fields[1], fields[2], fields[0] == "sentence")
    }

    /** Same tone-insensitive comparison as the Lua filter; keep ü distinct from u. */
    internal fun foldPinyin(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replace("u\u0308", "v")
        return decomposed.filterNot { it in "\u0304\u0301\u030c\u0300" }
    }

    /** Levenshtein backtrack identifies substitutions/deletions in the typed pinyin. */
    fun changedOriginalIndices(original: String, corrected: String): Set<Int> {
        val target = foldPinyin(corrected).replace(" ", "").replace("'", "")
        val source = original.replace("'", "")
        if (source.length > 80 || target.length > 160) return emptySet()
        val costs = Array(source.length + 1) { IntArray(target.length + 1) }
        for (i in source.indices) costs[i + 1][0] = i + 1
        for (j in target.indices) costs[0][j + 1] = j + 1
        for (i in 1..source.length) for (j in 1..target.length) {
            costs[i][j] = minOf(costs[i - 1][j] + 1, costs[i][j - 1] + 1,
                costs[i - 1][j - 1] + if (source[i - 1] == target[j - 1]) 0 else 1)
        }
        var i = source.length; var j = target.length
        val changed = mutableSetOf<Int>()
        val originalPositions = original.indices.filter { original[it] != '\'' }
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && costs[i][j] == costs[i - 1][j - 1] + (if (source[i - 1] == target[j - 1]) 0 else 1) -> {
                    if (source[i - 1] != target[j - 1]) changed.add(originalPositions[i - 1])
                    i--; j--
                }
                i > 0 && costs[i][j] == costs[i - 1][j] + 1 -> { changed.add(originalPositions[i - 1]); i-- }
                else -> { if (i > 0) changed.add(originalPositions[i - 1]); j-- }
            }
        }
        return changed
    }
}
