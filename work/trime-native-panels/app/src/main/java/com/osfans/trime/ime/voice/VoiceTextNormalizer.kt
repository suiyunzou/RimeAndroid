// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

/**
 * Post-processing for the raw sherpa-onnx transcript.
 *
 * The bundled transducer model emits Chinese numerals ("一三八") and no
 * punctuation at all. Dictation in a chat app needs "138" and commas at the
 * places where the speaker actually paused, so both are derived locally:
 *
 * * [normalizeNumbers] rewrites Chinese number spans into Arabic digits.
 * * [punctuate] inserts "，" after the text that was already recognized when
 *   the microphone heard a pause, and terminates the sentence.
 */
object VoiceTextNormalizer {
    private val digitValues = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val unitValues = mapOf('十' to 10, '百' to 100, '千' to 1000)
    private val bigUnits = mapOf('万' to 10_000L, '亿' to 100_000_000L)
    private const val NUMBER_CHARS = "零〇一二三四五六七八九两十百千万亿"

    // "一" before a measure word is a quantity, not a stray character.
    private val measureWords = listOf(
        "个", "岁", "点", "元", "块", "号", "月", "日", "年", "分钟", "秒",
        "次", "位", "名", "只", "斤", "公里", "米", "度", "分", "层", "楼", "杯",
    )

    /** Rewrites Chinese numerals inside [text] as Arabic digits. */
    fun normalizeNumbers(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = index
            while (index < text.length && text[index] in NUMBER_CHARS) index++
            if (start == index) {
                out.append(text[index])
                index++
                continue
            }
            val span = text.substring(start, index)
            val followedByMeasure = measureWords.any { text.startsWith(it, index) }
            val converted = convertSpan(span, followedByMeasure, previous = out.lastOrNull())
            out.append(converted)
        }
        return out.toString()
    }

    private fun convertSpan(span: String, followedByMeasure: Boolean, previous: Char?): String {
        val hasUnit = span.any { it in unitValues || it in bigUnits }
        if (hasUnit) {
            val value = parseChineseNumber(span)
            return if (value == null) span else value.toString()
        }
        // Pure digit sequence: "一三八" is read digit by digit (phone numbers,
        // years, codes), so keep every digit. A single digit only becomes
        // Arabic when it counts something ("三个" -> "3个").
        if (span.length >= 2) return span.map { digitValues[it] ?: return span }.joinToString("")
        return if (followedByMeasure || previous == '第') {
            digitValues[span[0]]?.toString() ?: span
        } else {
            span
        }
    }

    private fun parseChineseNumber(span: String): Long? {
        // Split on the largest units first so 亿/万 nest correctly.
        val yi = span.lastIndexOf('亿')
        if (yi >= 0) {
            val leftText = span.substring(0, yi)
            val left = if (leftText.isEmpty()) 1L else parseChineseNumber(leftText) ?: return null
            val rightText = span.substring(yi + 1)
            val right = if (rightText.isEmpty()) 0L else parseChineseNumber(rightText) ?: return null
            return left * 100_000_000L + right
        }
        val wan = span.lastIndexOf('万')
        if (wan >= 0) {
            val leftText = span.substring(0, wan)
            val left = if (leftText.isEmpty()) 1L else parseChineseNumber(leftText) ?: return null
            val rightText = span.substring(wan + 1)
            val right = if (rightText.isEmpty()) 0L else parseChineseNumber(rightText) ?: return null
            return left * 10_000L + right
        }
        return parseSection(span)
    }

    private fun parseSection(span: String): Long? {
        var section = 0L
        var digit = 0
        var sawDigit = false
        for (char in span) {
            when {
                char in digitValues -> {
                    digit = digitValues.getValue(char)
                    sawDigit = true
                }

                char in unitValues -> {
                    val unit = unitValues.getValue(char)
                    if (digit == 0) digit = 1
                    section += digit.toLong() * unit
                    digit = 0
                    sawDigit = true
                }

                else -> return null
            }
        }
        if (!sawDigit) return null
        return section + digit
    }

    /**
     * Adds "，" wherever the speaker paused and keeps the remaining text as one
     * sentence. [pausePrefixes] holds the transcript snapshot at each pause,
     * longest-first matching keeps inserts in document order.
     */
    fun punctuate(text: String, pausePrefixes: List<String>): String {
        if (text.isEmpty()) return text
        val isEnglish = text.none { it in '\u4e00'..'\u9fff' }
        val comma = if (isEnglish) ", " else "，"
        val sentenceEnd = if (isEnglish) "." else "。"
        val punctuationSet = "，。！？、；：,.!?;:"
        val builder = StringBuilder(text)
        var insertAt = 0
        for (prefix in pausePrefixes) {
            if (prefix.length < 3) continue
            val position = text.indexOf(prefix, insertAt)
            if (position < 0) continue
            val end = position + prefix.length
            if (end <= insertAt) continue
            insertAt = end
            if (end >= builder.length) continue
            val next = builder[end]
            if (next in punctuationSet || next == ' ') continue
            builder.insert(end, comma)
        }
        val result = builder.toString().trimEnd()
        return if (result.last() in "。！？!?….") result else result + sentenceEnd
    }
}
