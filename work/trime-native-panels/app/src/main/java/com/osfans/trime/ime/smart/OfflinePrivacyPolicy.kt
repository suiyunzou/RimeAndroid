// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Pure masks mirror Android InputType/EditorInfo; usable in local JVM tests. */
object OfflinePrivacyPolicy {
    fun isSensitive(inputType: Int, imeOptions: Int): Boolean {
        val kind = inputType and 0xf
        val variation = inputType and 0xff0
        return imeOptions and 0x01000000 != 0 ||
            kind == 1 && variation in setOf(0x80, 0x90, 0xe0) ||
            kind == 2 && variation == 0x10
    }
    @Volatile var sensitiveEditor = false
        private set
    fun updateEditor(inputType: Int, imeOptions: Int) {
        sensitiveEditor = isSensitive(inputType, imeOptions)
    }
    fun finishEditor() { sensitiveEditor = false }
}
