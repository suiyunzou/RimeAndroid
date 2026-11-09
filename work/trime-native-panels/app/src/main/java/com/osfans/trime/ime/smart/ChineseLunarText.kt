// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Display names only; date conversion belongs to the platform Chinese calendar. */
object ChineseLunarText {
    private const val STEMS = "甲乙丙丁戊己庚辛壬癸"
    private const val BRANCHES = "子丑寅卯辰巳午未申酉戌亥"
    private val months = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private const val DIGITS = "一二三四五六七八九十"

    fun format(cycleYear: Int, month: Int, day: Int, leapMonth: Boolean, hour: Int, includeTime: Boolean): String {
        require(cycleYear in 1..60 && month in 1..12 && day in 1..30 && hour in 0..23)
        val year = cycleYear - 1
        val dayName = when (day) {
            in 1..10 -> "初${DIGITS[day - 1]}"
            in 11..19 -> "十${DIGITS[day - 11]}"
            20 -> "二十"
            in 21..29 -> "廿${DIGITS[day - 21]}"
            else -> "三十"
        }
        val date = "${STEMS[year % 10]}${BRANCHES[year % 12]}年${if (leapMonth) "闰" else ""}${months[month - 1]}月$dayName"
        return if (includeTime) "$date ${BRANCHES[((hour + 1) / 2) % 12]}时" else date
    }
}
