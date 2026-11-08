// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.view.inputmethod.InputConnection

/** System editor operations only. Text bindings are handled by the keyboard commit path. */
object ActionKeyDispatcher {
    fun execute(connection: InputConnection?, action: String): Boolean {
        if (connection == null) return false
        if (action == "action:copy_all" || action == "action:clear_all") {
            return runCatching {
                // Let the editor select its full document, including text outside the IME's
                // surrounding-text window. A rejected selection must never delete at the caret.
                if (!connection.performContextMenuAction(android.R.id.selectAll)) return@runCatching false
                if (action == "action:copy_all") connection.performContextMenuAction(android.R.id.copy)
                else connection.commitText("", 1)
            }.getOrDefault(false)
        }
        val id = when (action.removePrefix("action:")) {
            "cut" -> android.R.id.cut
            "copy" -> android.R.id.copy
            "paste" -> android.R.id.paste
            "select_all" -> android.R.id.selectAll
            else -> return false
        }
        return runCatching { connection.performContextMenuAction(id) }.getOrDefault(false)
    }
}
