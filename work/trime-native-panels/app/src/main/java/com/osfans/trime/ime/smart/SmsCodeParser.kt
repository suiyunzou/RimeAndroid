// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Extract only short codes near an explicit verification keyword. No message is retained. */
object SmsCodeParser {
    private val keyword = Regex("验证码|驗證碼|校验码|校驗碼|动态码|動態碼|短信码|短信碼|一次性密码|一次性密碼|(?<![A-Za-z])(?i:verification\\s+code|security\\s+code|one[- ]time\\s+(?:password|code)|passcode|otp|login\\s+code)(?![A-Za-z])")
    private val token = Regex("(?<![A-Za-z0-9])([A-Za-z0-9]{4,8})(?![A-Za-z0-9])")
    private val nonCodePrefix = Regex("(?:尾号|尾號|尾数|尾數|手机号码?|手機號碼?|联系电话|客服|热线|熱線|拨打|撥打|致电|致電|账户|賬戶|账号|帳號|订单号?|訂單號?|金额|金額|余额|餘額|流水号|发送到|发送至|發送到|發送至|(?i:call|phone|telephone|account|order|balance))[\\s：:是为為#-]{0,6}$")

    fun extract(message: String): String? {
        if (message.length !in 1..8192) return null
        val anchors = keyword.findAll(message).toList()
        if (anchors.isEmpty()) return null
        data class Match(val code: String, val distance: Int, val after: Boolean)
        val matches = token.findAll(message).mapNotNull { match ->
            val code = match.value
            if (code.none { it in '0'..'9' }) return@mapNotNull null
            val prefix = message.substring((match.range.first - 24).coerceAtLeast(0), match.range.first)
            if (nonCodePrefix.containsMatchIn(prefix)) return@mapNotNull null
            val nearest = anchors.map { anchor ->
                val after = match.range.first > anchor.range.last
                val distance = if (after) match.range.first - anchor.range.last - 1
                    else anchor.range.first - match.range.last - 1
                Match(code, distance, after)
            }.filter { it.distance in 0..32 }.minByOrNull { it.distance } ?: return@mapNotNull null
            // Dates, prices and pieces of phone/account numbers are not codes.
            val before = message.getOrNull(match.range.first - 1)
            val after = message.getOrNull(match.range.last + 1)
            if (before in listOf('+', '.', '/', '-') || after in listOf('.', '/', '-') &&
                message.getOrNull(match.range.last + 2)?.isDigit() == true) return@mapNotNull null
            val suffix = message.substring(match.range.last + 1).trimStart()
            if (suffix.startsWith("元") || suffix.startsWith("年") || suffix.startsWith("分钟")) return@mapNotNull null
            nearest
        }.sortedWith(compareBy<Match> { it.distance }.thenByDescending { it.after }).toList()
        return matches.firstOrNull()?.code
    }
}

data class SmsCodeEntry(val code: String, val receivedAt: Long) {
    fun isFresh(now: Long): Boolean = now >= receivedAt && now - receivedAt < LIFETIME_MS

    companion object {
        const val LIFETIME_MS = 5 * 60 * 1000L
    }
}
