package com.osfans.trime.ime.smart

/** On-device word cards. Unedited fragments retain their original source offsets. */
object SmartClipboardTokenizer {
    const val MAX_ANALYSIS_CHARS = 16_384
    private const val MERGE_SEPARATOR = " "
    enum class Kind { TEXT, NUMBER, CODE, URL, AMOUNT }
    enum class Filter { ALL, NUMBERS, URLS }

    data class Token(
        val text: String,
        val kind: Kind,
        val start: Int,
        val end: Int,
        /** UTF-16 boundaries reported by the local word segmenter, relative to this card. */
        val wordBoundaries: List<Int> = emptyList(),
        val mergedParts: List<Token> = emptyList(),
    ) {
        val isMerged: Boolean get() = mergedParts.isNotEmpty()
        private val mergeSplits = if (!isMerged) emptyList() else buildList {
            var offset = 0
            for (index in 0 until mergedParts.lastIndex) {
                offset += mergedParts[index].text.length
                add(offset)
                offset += MERGE_SEPARATOR.length
            }
        }

        /** A divider is BETWEEN characters, never through a Chinese character or surrogate pair. */
        val splitPoints: List<Int>
            get() {
                if (isMerged) return mergeSplits
                if (kind == Kind.NUMBER || kind == Kind.AMOUNT) return emptyList()
                val schemeEnd = text.indexOf("://").let { if (it >= 0) it + 3 else 0 }
                val separators = text.indices.filter {
                    it > 0 && it < text.lastIndex &&
                        if (kind == Kind.URL) it >= schemeEnd && text[it] == '/'
                        else text[it] in "-_/ :："
                }
                if (kind == Kind.URL) return separators.take(1)
                // Keep an order/pickup code intact until the user has separated its label.
                val scriptBoundary = codePointBoundaries(text).firstOrNull {
                    isSafeBoundary(text, it) && isHan(text.codePointBefore(it)) != isHan(text.codePointAt(it)) &&
                        (isHan(text.codePointBefore(it)) || isHan(text.codePointAt(it)))
                }
                if (scriptBoundary != null) return listOf(scriptBoundary)
                if (separators.isNotEmpty()) return separators.take(1)
                val wordBoundary = wordBoundaries.firstOrNull { isSafeBoundary(text, it) }
                if (wordBoundary != null) return listOf(wordBoundary)
                val boundaries = codePointBoundaries(text)
                return if (allHan(text) && boundaries.isNotEmpty()) {
                    listOf(boundaries[(boundaries.size - 1) / 2])
                } else emptyList()
            }
    }

    private val url = Regex("https?://[^\\s<>\\\"「」『』，。；！？（）]+", RegexOption.IGNORE_CASE)
    private val numeric = Regex("(?<![A-Za-z0-9_.+-])[+-]?\\d+(?:\\.\\d+)?(?![A-Za-z0-9_.+-]|\\p{M})")
    private val decimal = Regex("[+-]?\\d+(?:\\.\\d+)?")
    private val code = Regex("[A-Za-z0-9]+(?:[-_/][A-Za-z0-9]+)*")
    // Keep grammatical particles separate from adjacent unknown compounds.
    private const val GRAMMATICAL_SINGLETONS = "已未正将又再很更最不无有在被把给与及和或的地得了着过"

    // Unicode Han blocks, including extension planes; compatible with Android API 21.
    private fun isHan(codePoint: Int) = codePoint in 0x3400..0x4dbf || codePoint in 0x4e00..0x9fff ||
        codePoint in 0xf900..0xfaff || codePoint in 0x20000..0x2ee5f ||
        codePoint in 0x2f800..0x2fa1f || codePoint in 0x30000..0x3347f

    private fun allHan(text: String): Boolean {
        if (text.isEmpty()) return false
        var cursor = 0
        while (cursor < text.length) {
            val cp = text.codePointAt(cursor)
            if (!isHan(cp)) return false
            cursor += Character.charCount(cp)
        }
        return true
    }
    private fun isSafeBoundary(text: String, index: Int) =
        index > 0 && index < text.length && !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) &&
            !isCombining(text.codePointAt(index)) && text.codePointAt(index) != 0x200d && text.codePointBefore(index) != 0x200d

    private fun codePointBoundaries(text: String): List<Int> {
        val result = mutableListOf<Int>()
        var cursor = 0
        while (cursor < text.length) {
            cursor += Character.charCount(text.codePointAt(cursor))
            if (cursor < text.length) result += cursor
        }
        return result
    }

    private fun isCombining(cp: Int): Boolean = Character.getType(cp) in setOf(
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
    ) || cp in 0xfe00..0xfe0f || cp in 0xe0100..0xe01ef || cp in 0x1f3fb..0x1f3ff || cp in 0xe0020..0xe007f

    /** Preserve emoji modifiers, flag pairs, and ZWJ chains as a single selectable card. */
    private fun clusterEnd(text: String, start: Int, end: Int): Int {
        val first = text.codePointAt(start)
        var cursor = start + Character.charCount(first)
        if (first in 0x1f1e6..0x1f1ff && cursor < end && text.codePointAt(cursor) in 0x1f1e6..0x1f1ff) {
            cursor += Character.charCount(text.codePointAt(cursor))
        }
        while (cursor < end) {
            val cp = text.codePointAt(cursor)
            when {
                isCombining(cp) -> cursor += Character.charCount(cp)
                cp == 0x200d && cursor + 1 < end -> {
                    cursor++
                    cursor += Character.charCount(text.codePointAt(cursor))
                }
                else -> break
            }
        }
        return cursor
    }

    private fun classify(value: String): Kind = when {
        decimal.matches(value) -> if ('.' in value) Kind.AMOUNT else Kind.NUMBER
        code.matches(value) -> Kind.CODE
        else -> Kind.TEXT
    }

    /** Locate ICU words without trusting the callback to return every source character. */
    private fun localWords(text: String, words: (String) -> List<String>): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var offset = 0
        words(text).forEach { word ->
            if (word.isEmpty()) return@forEach
            val index = text.indexOf(word, offset)
            if (index < 0) return@forEach
            if (index > offset) spans += offset until index
            spans += index until index + word.length
            offset = index + word.length
        }
        if (offset < text.length) spans += offset until text.length
        // ICU dictionaries vary by Android version. Merge adjacent unknown Han singletons,
        // while preserving grammatical particles and recognized multi-character words.
        val result = mutableListOf<IntRange>()
        var index = 0
        fun unknownSingleton(span: IntRange): Boolean {
            val value = text.substring(span)
            return value.codePointCount(0, value.length) == 1 && isHan(value.codePointAt(0)) &&
                value !in GRAMMATICAL_SINGLETONS
        }
        while (index < spans.size) {
            val current = spans[index]
            val next = spans.getOrNull(index + 1)
            if (next != null && current.last + 1 == next.first && unknownSingleton(current) && unknownSingleton(next)) {
                result += current.first..next.last
                index += 2
            } else {
                result += current
                index++
            }
        }
        return result
    }

    fun tokenize(text: String, words: (String) -> List<String> = { listOf(it) }): List<Token> {
        val limit = minOf(text.length, MAX_ANALYSIS_CHARS).let {
            if (it < text.length && it > 0 && text[it - 1].isHighSurrogate()) it - 1 else it
        }
        val input = text.substring(0, limit)
        if (input.isBlank()) return emptyList()
        val result = mutableListOf<Token>()

        fun appendPhrase(start: Int, end: Int) {
            if (start >= end) return
            val value = input.substring(start, end)
            val spans = localWords(value, words)
            if (allHan(value)) {
                spans.forEach { span ->
                    val word = value.substring(span)
                    result += Token(word, Kind.TEXT, start + span.first, start + span.last + 1)
                }
            } else {
                // Mixed labels and codes remain a parent card with progressively finer dividers.
                result += Token(value, classify(value), start, end, spans.drop(1).map { it.first })
            }
        }

        fun appendText(start: Int, end: Int) {
            var runStart = start
            var cursor = start
            while (cursor < end) {
                val cp = input.codePointAt(cursor)
                val size = clusterEnd(input, cursor, end) - cursor
                val connector = cp.toChar() in "-_/'" && cursor > runStart && cursor + size < end &&
                    input[cursor - 1].isLetterOrDigit() && input[cursor + size].isLetterOrDigit()
                if (!Character.isLetterOrDigit(cp) && !connector) {
                    appendPhrase(runStart, cursor)
                    if (!Character.isWhitespace(cp)) result += Token(input.substring(cursor, cursor + size), Kind.TEXT, cursor, cursor + size)
                    runStart = cursor + size
                }
                cursor += size
            }
            appendPhrase(runStart, end)
        }

        fun appendNonUrl(start: Int, end: Int) {
            var cursor = start
            numeric.findAll(input.substring(start, end)).forEach { match ->
                val absolute = start + match.range.first
                appendText(cursor, absolute)
                result += Token(match.value, classify(match.value), absolute, absolute + match.value.length)
                cursor = absolute + match.value.length
            }
            appendText(cursor, end)
        }

        var cursor = 0
        url.findAll(input).forEach { match ->
            val value = match.value.trimEnd('.', ',', ';', '!', '?', ')')
            appendNonUrl(cursor, match.range.first)
            result += Token(value, Kind.URL, match.range.first, match.range.first + value.length)
            cursor = match.range.first + value.length
        }
        appendNonUrl(cursor, input.length)
        return result
    }

    fun filter(tokens: List<Token>, filter: Filter): List<Token> = tokens.mapNotNull {
        when (filter) {
            Filter.ALL -> it
            Filter.NUMBERS -> it.takeIf { token -> token.kind in setOf(Kind.NUMBER, Kind.AMOUNT) && decimal.matches(token.text) }
            Filter.URLS -> it.takeIf { token -> token.kind == Kind.URL }
        }
    }

    /** Refine at one visible divider. Chinese boundaries retain both sides in full. */
    fun split(token: Token, at: Int): List<Token> {
        val boundary = token.splitPoints.indexOf(at)
        if (boundary < 0) return listOf(token)
        if (token.isMerged) {
            val cut = boundary + 1
            return listOf(group(token.mergedParts.take(cut)), group(token.mergedParts.drop(cut)))
        }
        fun fragment(start: Int, end: Int): Token? {
            val raw = token.text.substring(start, end)
            val value = raw.trim()
            if (value.isEmpty()) return null
            val relativeStart = start + raw.indexOf(value)
            val absolute = token.start + relativeStart
            return Token(
                value, if (token.kind == Kind.URL) Kind.URL else classify(value), absolute, absolute + value.length,
                token.wordBoundaries.map { it - relativeStart }.filter { isSafeBoundary(value, it) },
            )
        }
        val skipSeparator = token.kind != Kind.URL && token.text[at] in "-_/ :："
        return listOfNotNull(fragment(0, at), fragment(if (skipSeparator) at + 1 else at, token.text.length))
    }

    private fun group(parts: List<Token>): Token =
        if (parts.size == 1) parts.single() else Token(
            parts.joinToString(MERGE_SEPARATOR) { it.text }, Kind.TEXT, -1, -1, mergedParts = parts,
        )

    fun merge(left: Token, right: Token): Token =
        group(left.mergedParts.ifEmpty { listOf(left) } + right.mergedParts.ifEmpty { listOf(right) })

    /** Undo a merge in place; ordinary refinement retains the parent and reveals its fragments. */
    fun expand(tokens: List<Token>, parent: Token, at: Int): List<Token> {
        val index = tokens.indexOfFirst { it === parent }
        if (index < 0) return tokens
        if (parent.isMerged) {
            val parts = split(parent, at)
            return if (parts.size == 2) tokens.take(index) + parts + tokens.drop(index + 1) else tokens
        }
        val parts = split(parent, at).filter { part ->
            part != parent && tokens.none { it.start == part.start && it.end == part.end && it.text == part.text }
        }
        if (parts.isEmpty()) return tokens
        return tokens.take(index + 1) + parts + tokens.drop(index + 1)
    }
}




