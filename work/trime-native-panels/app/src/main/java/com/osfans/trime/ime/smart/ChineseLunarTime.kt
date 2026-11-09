// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import androidx.annotation.RequiresApi
import java.util.Date
import java.util.Locale

/** Chinese civil date uses Beijing time and changes date at midnight, including late 子时. */
@RequiresApi(24)
object ChineseLunarTime {
    private var cachedMinute = Long.MIN_VALUE
    private var cachedDate = ""
    private var cachedDateTime = ""

    @Synchronized
    fun format(now: Date, includeTime: Boolean): String {
        val minute = Math.floorDiv(now.time, 60_000L)
        if (minute != cachedMinute) {
            val calendar = ChineseCalendar(TimeZone.getTimeZone("Asia/Shanghai"), Locale.CHINA).apply { time = now }
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val leap = calendar.get(Calendar.IS_LEAP_MONTH) == 1
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            cachedDate = ChineseLunarText.format(year, month, day, leap, hour, false)
            cachedDateTime = ChineseLunarText.format(year, month, day, leap, hour, true)
            cachedMinute = minute
        }
        return if (includeTime) cachedDateTime else cachedDate
    }
}
