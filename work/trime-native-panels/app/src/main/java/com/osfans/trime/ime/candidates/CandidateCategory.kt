// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.smart.CorrectionPresentation

/**
 * Display-only category of a candidate.
 *
 * The category decides the text color of a row. It never reorders candidates:
 * the engine index ([IndexedCandidate.index]), deduplication, paging and the
 * unrolled list all keep the order the engine returned. Reordering here would
 * desynchronize the unrolled window, which pages by engine index.
 */
enum class CandidateCategory {
    /** Ordinary dictionary/symbol candidate; uses the theme text color. */
    Text,

    /**
     * Prediction/completion produced for an empty input.
     *
     * Only reachable when the native binding starts exposing candidates' type.
     * The JNI conversion in `app/src/main/jni/librime_jni/objconv.h` currently
     * maps text/comment/label only, so [CandidatePresentation.classify] has to
     * fall back to the response composition to spot idle suggestions.
     */
    Prediction,

    /** Local typo correction (`\u2063local:correction|...` comment). */
    Correction,

    /** Local whole-sentence promotion (`\u2063local:sentence|...` comment). */
    Sentence,

    /** Candidate made only of emoji code points. */
    Emoji,
    ;

    /**
     * Correction, sentence and prediction rows are one visual group: they may
     * share the accent color, but only while they stay a single contiguous run.
     */
    val isEmphasisGroup: Boolean
        get() = this == Prediction || this == Correction || this == Sentence
}

/**
 * Pure classifiers and styling rules for the candidate strip.
 *
 * The input is a single engine response; nothing here mutates or reorders it.
 */
object CandidatePresentation {
    /**
     * Classify one candidate.
     *
     * [engineType] is the native `Candidate::type` name when a future binding
     * exposes it (`sentence`, `prediction`, `completion`, `correction`). This
     * tree's JNI conversion does not carry it yet, so callers pass `null` and
     * the classification relies on the local filter comment and the glyphs.
     */
    fun classify(
        candidate: CandidateProto,
        engineType: String? = null,
    ): CandidateCategory {
        when (engineType) {
            "sentence" -> return CandidateCategory.Sentence
            "prediction", "completion" -> return CandidateCategory.Prediction
            "correction" -> return CandidateCategory.Correction
        }
        CorrectionPresentation.parse(candidate.comment)?.let { hint ->
            return if (hint.sentence) CandidateCategory.Sentence else CandidateCategory.Correction
        }
        if (isEmojiOnly(candidate.text)) return CandidateCategory.Emoji
        return CandidateCategory.Text
    }

    /**
     * True when [text] contains emoji glyphs and nothing else.
     *
     * Mixed text (`"😀你好"`) and kaomoji (`"（＾▽＾）"`) stay ordinary text:
     * the strip should not single out a partially emoji candidate.
     */
    fun isEmojiOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        val keycap = text.indexOf('\u20E3') >= 0
        var index = 0
        var sawEmoji = false
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            when {
                // Zero-width joiner, variation selectors and skin tones only
                // modify a neighbouring glyph.
                codePoint == ZERO_WIDTH_JOINER ||
                    codePoint in VARIATION_SELECTORS ||
                    codePoint in EMOJI_MODIFIERS -> Unit

                codePoint == COMBINING_KEYCAP -> sawEmoji = true

                isEmojiCodePoint(codePoint) -> sawEmoji = true

                // "1️⃣" is a digit followed by FE0F + U+20E3.
                keycap && (codePoint in '0'.code..'9'.code || codePoint == '#'.code || codePoint == '*'.code) -> sawEmoji = true

                else -> return false
            }
        }
        return sawEmoji
    }

    /**
     * True when the emphasis group forms one run in the given order, so all
     * rows of that group can share the accent color without alternating with
     * ordinary rows.
     *
     * When the engine interleaves correction and ordinary candidates there is
     * no contiguous emphasis group; callers then keep every row in the normal
     * color instead of painting only some of them.
     */
    fun accentRunIsContiguous(categories: List<CandidateCategory>): Boolean {
        var previousEmphasis = -2
        categories.forEachIndexed { index, category ->
            if (!category.isEmphasisGroup) return@forEachIndexed
            if (previousEmphasis >= 0 && index != previousEmphasis + 1) return false
            previousEmphasis = index
        }
        return true
    }

    /**
     * True when a response is idle prediction left over from an editor the host
     * already cleared.
     *
     * [suppressIdle] is only set by the external-edit detection in the input
     * method service; a plain commit keeps prediction suggestions visible.
     * [compositionLength] comes from the response context, not from the cached
     * status, because the cached status still describes the previous key when
     * the candidate message arrives. `null` means the response carries no
     * identity and must not be hidden.
     */
    fun shouldHideIdleCandidates(
        suppressIdle: Boolean,
        compositionLength: Int?,
        candidateCount: Int,
    ): Boolean = suppressIdle && candidateCount > 0 && compositionLength == 0

    private fun isEmojiCodePoint(codePoint: Int): Boolean = when {
        codePoint == 0x00A9 || codePoint == 0x00AE || codePoint == 0x203C || codePoint == 0x2049 ||
            codePoint == 0x2122 || codePoint == 0x2139 -> true

        codePoint == 0x24C2 -> true

        codePoint == 0x25AA || codePoint == 0x25AB || codePoint == 0x25B6 || codePoint == 0x25C0 ||
            codePoint in 0x25FB..0x25FE -> true

        codePoint in 0x2600..0x27BF -> true
        codePoint in 0x2934..0x2935 -> true
        codePoint in 0x2B00..0x2BFF -> true

        codePoint == 0x3030 || codePoint == 0x303D || codePoint == 0x3297 || codePoint == 0x3299 -> true

        // Emoji planes: pictographs, regional indicators, flags and keycaps.
        codePoint in 0x1F000..0x1FAFF -> true

        else -> false
    }

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val COMBINING_KEYCAP = 0x20E3
    private val VARIATION_SELECTORS = 0xFE00..0xFE0F
    private val EMOJI_MODIFIERS = 0x1F3FB..0x1F3FF
}
