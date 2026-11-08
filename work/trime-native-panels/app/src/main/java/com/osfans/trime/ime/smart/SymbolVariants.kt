// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Unicode strings are kept whole, including variation selectors and joined emoji. */
object SymbolVariants {
    fun forKey(label: String): List<String> {
        if (label.length == 1 && label[0] in '0'..'9') {
            val n = label[0] - '0'
            // Reference 03:19–03:23: digit, Chinese, financial, full-width,
            // circled, parenthesized, full stop, negative circle, enclosed Chinese.
            val reference = if (n == 0) {
                // Unicode has no parenthesized zero or enclosed Chinese zero.
                listOf("0", "〇", "零", "０", "⓪", "(0)", "0.", "⓿")
            } else listOf(
                label, "一二三四五六七八九"[n - 1].toString(),
                "壹贰叁肆伍陆柒捌玖"[n - 1].toString(),
                (0xff10 + n).toChar().toString(),
                (0x2460 + n - 1).toChar().toString(),
                (0x2474 + n - 1).toChar().toString(),
                (0x2488 + n - 1).toChar().toString(),
                (0x2776 + n - 1).toChar().toString(),
                (0x3280 + n - 1).toChar().toString(),
                (0x3220 + n - 1).toChar().toString(),
            )
            // Keep existing shortcuts after the ten reference choices.
            return reference + listOf(
                "⁰¹²³⁴⁵⁶⁷⁸⁹"[n].toString(),
                (0x2080 + n).toChar().toString(), "$label\uFE0F\u20E3",
            )
        }
        return punctuation[label].orEmpty()
    }
    private val punctuation = buildMap<String, List<String>> {
        listOf(
            listOf(".", "。", "．", "…", "⋯", "·"),
            listOf(",", "，", "、", "､", "﹐"),
            listOf("!", "！", "‼", "❗", "❕"),
            listOf("?", "？", "¿", "‽", "⁉"),
            listOf(":", "：", "∶", ";", "；"),
            listOf(";", "；", "﹔", ":", "："),
            listOf("-", "－", "−", "–", "—", "_", "＿"),
            listOf("/", "／", "\\", "＼", "÷", "∕"),
            listOf("+", "＋", "±", "∓", "⊕"),
            listOf("=", "＝", "≠", "≈", "≡", "≤", "≥"),
            listOf("%", "％", "‰", "‱"),
            listOf("$", "＄", "¥", "￥", "€", "£", "₩", "₹"),
            listOf("(", "（", "[", "【", "{", "「", "《", "〈"),
            listOf(")", "）", "]", "】", "}", "」", "》", "〉"),
            listOf("\"", "“", "”", "„", "「", "『", "″"),
            listOf("'", "‘", "’", "‚", "′", "＇"),
            listOf("@", "＠", "©", "®", "™", "℠"),
            listOf("#", "＃", "♯", "№"),
            listOf("*", "＊", "×", "✳", "★", "☆"),
        ).forEach { group -> group.forEach { putIfAbsent(it, group) } }
    }
    private fun codePoints(start: Int, end: Int): List<String> = (start..end).map { String(Character.toChars(it)) }
    val categories: Map<String, List<String>> = linkedMapOf(
        "罗马" to (codePoints(0x2160, 0x217f) + listOf("ↀ", "ↁ", "ↂ")),
        "希腊" to (codePoints(0x391, 0x3a9).filter { it != "\u03a2" } + codePoints(0x3b1, 0x3c9)),
        "希腊粗体" to codePoints(0x1d6a8, 0x1d6e1),
        "希腊斜体" to codePoints(0x1d6e2, 0x1d71b),
        "部首" to codePoints(0x2f00, 0x2fd5),
        "几何" to codePoints(0x25a0, 0x25ef),
        "特殊" to (listOf("←", "↑", "→", "↓", "↔", "↕", "⇒", "⇔", "∞", "∑", "∏", "√", "∫", "∂", "∇", "∈", "∉", "∅", "⊂", "⊃", "∩", "∪", "≈", "≠", "≤", "≥", "℃", "℉", "°", "±", "‰", "©", "®", "™", "§", "¶", "†", "‡", "♠", "♥", "♦", "♣", "♪", "♫", "✓", "✗") + listOf("(", "（", "《", "「", "【", "[", "{", "“", "\"", "'")),
        "颜文字" to listOf("(＾▽＾)", "(≧▽≦)", "(๑•̀ㅂ•́)و✧", "(｡･ω･｡)", "(ง •̀_•́)ง", "(づ｡◕‿‿◕｡)づ", "(╥﹏╥)", "(；′⌒`)", "(￣▽￣)ノ", "(｡•́︿•̀｡)", "(¬_¬)", "(－_－) zzZ", "(⊙_⊙)", "¯\\_(ツ)_/¯", "( •̀ ω •́ )✧", "(๑´ڡ`๑)", "(｡♥‿♥｡)", "(╯°□°）╯︵ ┻━┻"),
        "Emoji" to listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤔", "😢", "😭", "😤", "🥺", "🥹", "🙃", "🙂", "😉", "🤩", "🥳", "😴", "🤯", "👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿", "👎", "👏", "🙏", "💪", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "🔥", "✨", "🎉", "🎂", "🌹", "🌈", "☀️", "🌙", "⭐", "🐱", "🐶", "🐼", "🍎", "🍉", "🍰", "☕", "🍻", "⚽", "🏀", "🚗", "✈️", "🏠", "📱", "💻", "✅", "❌", "⚠️", "👨‍👩‍👧‍👦", "👩‍💻", "👨‍💻", "🏳️‍🌈"),
    )
}
