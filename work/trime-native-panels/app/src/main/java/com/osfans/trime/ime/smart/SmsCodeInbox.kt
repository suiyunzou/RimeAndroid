// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local storage: never writes a code or a message to disk, clipboard or logs. */
object SmsCodeInbox {
    private val state = MutableStateFlow<SmsCodeEntry?>(null)
    val updates = state.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { state.value = null }

    fun isAvailable(context: Context): Boolean = SmartInputPrefs(context).smsCodeSuggestion &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun offer(message: String) {
        val code = SmsCodeParser.extract(message) ?: return
        handler.removeCallbacks(expire)
        state.value = SmsCodeEntry(code, SystemClock.elapsedRealtime())
        handler.postDelayed(expire, SmsCodeEntry.LIFETIME_MS)
    }

    fun current(): SmsCodeEntry? = state.value?.takeIf { it.isFresh(SystemClock.elapsedRealtime()) }

    fun clear() {
        handler.removeCallbacks(expire)
        state.value = null
    }
}
