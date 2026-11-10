// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** The opt-in emulator build packages this adapter alongside its existing Rime JNI library. */
object TailCompletionNative {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("tail_completion")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    private val quietSession = ThreadLocal<Boolean>()
    fun suppressNotification(type: Int): Boolean =
        (type == 1 || type == 2) && quietSession.get() == true

    internal fun <T> withQuietSession(block: () -> T): T {
        val previous = quietSession.get()
        quietSession.set(true)
        try {
            return block()
        } finally {
            if (previous == null) quietSession.remove() else quietSession.set(previous)
        }
    }

    internal fun query(spelling: String, tail: String, traditional: Boolean): String? =
        if (available) queryNative(spelling, tail, traditional) else null

    private external fun queryNative(spelling: String, tail: String, traditional: Boolean): String?
}
