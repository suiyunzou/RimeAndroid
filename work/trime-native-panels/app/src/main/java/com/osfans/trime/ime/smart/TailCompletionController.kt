// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.os.SystemClock
import com.osfans.trime.core.CandidateContext
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.daemon.RimeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Owns the lifetime of the independent suggestion, never a candidate index. */
class TailCompletionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val rime: RimeSession,
    private val enabled: () -> Boolean,
    private val render: (String?, (() -> Unit)?) -> Unit,
) {
    private var pending: Job? = null
    private var visible = false
    @Volatile private var generation = 0L

    fun clear() {
        generation++
        pending?.cancel()
        pending = null
        if (visible) {
            visible = false
            render(null, null)
        }
    }

    fun update(expected: CandidateContext?, primary: Array<CandidateProto>) {
        clear()
        if (expected == null || expected.schemaId != TailCompletion.SCHEMA ||
            expected.composition.length == 0 || !enabled()
        ) return
        val ticket = generation
        val started = SystemClock.uptimeMillis()
        pending = scope.launch {
            delay(TailCompletion.PAUSE_MS)
            if (!enabled() || generation != ticket) return@launch
            val prior = TailCompletionPrior.get(context)
            val queryStarted = SystemClock.uptimeMillis()
            val suggestion = rime.runOnReady {
                findTailCompletion(expected, prior, primary.map { it.text }.toTypedArray()) { generation == ticket }
            } ?: return@launch
            if (!enabled() || generation != ticket) return@launch
            visible = true
            render(suggestion.text) {
                if (enabled() && generation == ticket) {
                    scope.launch {
                        rime.runOnReady {
                            commitTailCompletion(expected, suggestion) { generation == ticket }
                        }
                    }
                }
            }
            val shown = SystemClock.uptimeMillis()
            Timber.d("Tail completion visible: sinceCandidatesMs=%d queryMs=%d", shown - started, shown - queryStarted)
        }
    }
}

private object TailCompletionPrior {
    private val lock = Mutex()
    private var cached: Map<String, Int>? = null

    suspend fun get(context: Context): Map<String, Int> = lock.withLock {
        cached ?: withContext(Dispatchers.IO) {
            context.assets.open("glide/chinese.tsv").bufferedReader().use(TailCompletion::readPrior)
        }.also { cached = it }
    }
}
