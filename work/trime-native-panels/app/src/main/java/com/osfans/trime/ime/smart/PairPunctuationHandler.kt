/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.smart

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Use only after Rime has finished composing. A true result means the edit was consumed. */
object PairPunctuationHandler {
    val pairs: Map<Char, Char> = mapOf(
        '(' to ')', '（' to '）', '《' to '》', '「' to '」', '【' to '】',
        '"' to '"', '\'' to '\'', '{' to '}', '｛' to '｝', '[' to ']', '“' to '”', '‘' to '’',
    )

    fun insertPair(connection: InputConnection?, openChar: Char): Boolean =
        connection?.let { insertPair(ConnectionEditor(it), openChar) } ?: false

    /** False leaves ordinary deletion to the caller; this method never deletes twice. */
    fun handleBackspace(connection: InputConnection?): Boolean =
        connection?.let { handleBackspace(ConnectionEditor(it)) } ?: false

    internal data class Selection(val start: Int, val end: Int)

    internal interface Editor {
        fun selection(): Selection?
        fun selectedText(): CharSequence?
        fun before(): CharSequence?
        fun after(): CharSequence?
        fun commit(text: String): Boolean
        fun select(position: Int): Boolean
        fun left(): Boolean
        fun deletePair(): Boolean
        fun beginBatch()
        fun endBatch()
    }

    internal fun insertPair(editor: Editor, openChar: Char): Boolean {
        val closeChar = pairs[openChar] ?: return false
        val selection = safely<Selection?>(null) { editor.selection() }
        safely(Unit) { editor.beginBatch() }
        try {
            if (!safely(false) { editor.commit("$openChar$closeChar") }) return false
            // The original selection identifies the insertion point even if the editor's
            // extracted-text snapshot lags behind commitText. Offsets are absolute here.
            val center = selection?.let { minOf(it.start, it.end).toLong() + 1 }
            val moved = center != null && center in 0..Int.MAX_VALUE.toLong() &&
                safely(false) { editor.select(center.toInt()) }
            if (!moved) safely(false) { editor.left() }
            // Never request fallback text insertion after a successful commit, even when
            // this editor rejects BOTH caret movement mechanisms.
            return true
        } finally {
            safely(Unit) { editor.endBatch() }
        }
    }

    internal fun handleBackspace(editor: Editor): Boolean {
        return try {
            val selection = editor.selection()
            if (selection != null && selection.start != selection.end) return false
            if (!editor.selectedText().isNullOrEmpty()) return false
            val before = editor.before()?.singleOrNull() ?: return false
            val after = editor.after()?.singleOrNull() ?: return false
            if (pairs[before] != after) return false
            editor.deletePair()
        } catch (_: Exception) {
            false
        }
    }

    private inline fun <T> safely(fallback: T, block: () -> T): T =
        try { block() } catch (_: Exception) { fallback }

    private class ConnectionEditor(private val connection: InputConnection) : Editor {
        override fun selection(): Selection? {
            val text = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return null
            if (text.startOffset < 0 || text.selectionStart < 0 || text.selectionEnd < 0) return null
            val start = text.startOffset.toLong() + text.selectionStart
            val end = text.startOffset.toLong() + text.selectionEnd
            if (start > Int.MAX_VALUE || end > Int.MAX_VALUE) return null
            return Selection(start.toInt(), end.toInt())
        }
        override fun selectedText(): CharSequence? = connection.getSelectedText(0)
        override fun before(): CharSequence? = connection.getTextBeforeCursor(1, 0)
        override fun after(): CharSequence? = connection.getTextAfterCursor(1, 0)
        override fun commit(text: String): Boolean = connection.commitText(text, 1)
        override fun select(position: Int): Boolean = connection.setSelection(position, position)
        override fun left(): Boolean {
            val down = safely(false) { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
            val up = safely(false) { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)) }
            return down && up
        }
        override fun deletePair(): Boolean = connection.deleteSurroundingText(1, 1)
        override fun beginBatch() { connection.beginBatchEdit() }
        override fun endBatch() { connection.endBatchEdit() }
    }
}
