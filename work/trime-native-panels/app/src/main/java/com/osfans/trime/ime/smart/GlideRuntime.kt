// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Assets are read once locally; callers must check editor identity again after suspension. */
object GlideRuntime {
    private val decoders = mutableMapOf<Boolean, GlideDecoder>()
    suspend fun decode(context: Context, trace: List<GlideDecoder.Point>, keys: List<GlideDecoder.KeyCenter>, englishOnly: Boolean = false): List<GlideDecoder.Candidate> = withContext(Dispatchers.Default) {
        val engine = synchronized(this@GlideRuntime) {
            decoders.getOrPut(englishOnly) {
                val assets = context.applicationContext.assets
                val words = mutableListOf<GlideDecoder.Entry>()
                val sources = if (englishOnly) listOf("english.tsv") else listOf("english.tsv", "chinese.tsv")
                for (source in sources) assets.open("glide/$source").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val fields = line.split('\t')
                        if (fields.size >= 2) words.add(GlideDecoder.Entry(fields[0], fields[1], fields.getOrNull(2)?.toLongOrNull() ?: 0))
                    }
                }
                GlideDecoder(words as Collection<GlideDecoder.Entry>)
            }
        }
        engine.decode(trace, keys)
    }
}
