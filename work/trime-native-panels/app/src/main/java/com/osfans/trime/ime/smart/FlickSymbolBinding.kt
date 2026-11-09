// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

object FlickSymbolBinding {
    /** Null means normal letter behavior; empty means an explicitly disabled text binding. */
    fun lockedText(binding: String?, defaultSymbol: String?): String? = when {
        binding == null -> defaultSymbol
        binding.startsWith("text:") -> binding.removePrefix("text:")
        else -> null
    }
}
