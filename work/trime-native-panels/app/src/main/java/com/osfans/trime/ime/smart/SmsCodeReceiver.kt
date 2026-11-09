// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !SmsCodeInbox.isAvailable(context)) return
        // getMessagesFromIntent preserves multipart order. Do not query the SMS database.
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (parts.isEmpty() || parts.size > 32) return
        val sender = parts.first().originatingAddress
        if (parts.any { it.originatingAddress != sender }) return
        SmsCodeInbox.offer(parts.joinToString("") { it.messageBody.orEmpty() })
    }
}
