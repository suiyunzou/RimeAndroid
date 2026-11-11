// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Temporary fast-typing instrumentation. Every touch decision from DOWN to commit
 * is logged under one tag so an on-device timeline can be reconstructed with:
 *
 *   adb logcat -s FASTTYPE
 *
 * Flip [ENABLED] off (or delete this object with its call sites) before shipping.
 */
object FastTypeTrace {
    const val ENABLED = false
    const val TAG = "FASTTYPE"

    private val counter = AtomicInteger()

    /** Monotonic press id; a DOWN and its UP share one seq. */
    fun next(): Int = if (ENABLED) counter.incrementAndGet() else 0

    fun log(message: String) {
        if (ENABLED) Log.d(TAG, message)
    }
}
