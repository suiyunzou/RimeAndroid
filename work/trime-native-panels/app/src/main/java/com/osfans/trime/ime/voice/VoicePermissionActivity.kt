// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Invisible activity whose only job is requesting `RECORD_AUDIO`.
 *
 * `XXPermissions` (and the platform API) refuse to run from an input method
 * service because there is no activity context. Calling it from
 * [OfflineVoiceManager] crashed the whole IME process on the first microphone
 * tap, so the request moved here and the result is handed back to the service.
 */
class VoicePermissionActivity : Activity() {
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            deliver(true)
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        deliver(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() {
        // The system may finish the activity without a result callback.
        deliver(false)
        super.onDestroy()
    }

    private fun deliver(granted: Boolean) {
        if (delivered) return
        delivered = true
        callback?.invoke(granted)
        callback = null
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_CODE = 0x7a11

        private var callback: ((Boolean) -> Unit)? = null

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun request(context: Context, onResult: (Boolean) -> Unit) {
            callback = onResult
            val intent = Intent(context, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
