// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/**
 * Commit-time punctuation localization: only single keyboard punctuation marks are localized;
 * addresses and phrases stay literal. The table lives in [KeycapPunctuation], which also folds
 * paired marks to their opening half for the keycap; the commit path never folds.
 */
internal object KeyboardPunctuation {
    fun localized(text: String, ascii: Boolean): String = KeycapPunctuation.localized(text, ascii)

    fun isLocalizable(text: String): Boolean = localized(text, false) != text || localized(text, true) != text
}
