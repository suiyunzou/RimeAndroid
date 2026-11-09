// SPDX-FileCopyrightText: 2015 - 2025 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates.compact

import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CandidateContext
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.CandidateDeduplicator
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import kotlin.math.abs

class CompactCandidateDelegate(override val di: DI) : DIAware, InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val inputView: InputView by instance()
    private val bar: InputBarDelegate by instance()
    private val windows: BoardWindowManager by instance()
    private val offsets = MutableSharedFlow<Int>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val unrolledCandidateOffset = offsets.asSharedFlow()
    private val seen = HashSet<String>()
    private var generation = 0
    private var nextEngineIndex = 0
    private var endReached = true
    private var loading = false
    private var lastOffsetEmission = -1 to -1
    private var composition = CompositionProto()
    private var schemaId = rime.run { schemaCached.schemaId }
    var candidateContext = CandidateContext(composition, schemaId)
        private set

    val adapter by lazy {
        CompactCandidateViewAdapter(scope).apply {
            setOnItemClickListener { _, _, position ->
                val item = items.getOrNull(position) ?: return@setOnItemClickListener
                val expected = item.context ?: return@setOnItemClickListener
                rime.launchOnReady { it.selectCandidateIfCurrent(item.index, expected) }
            }
            setOnItemLongClickListener { _, view, position ->
                items.getOrNull(position)?.let {
                    inputView.showCandidateActionMenu(it.index, it.candidate.text, view, global = true, comment = it.candidate.comment, expected = it.context)
                }
                true
            }
        }
    }

    val layoutManager: LinearLayoutManager by lazy {
        object : LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) {
            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                refreshUnrolled()
                if (childCount > 0 && !view.canScrollHorizontally(1)) loadMore()
            }
        }
    }

    private fun refreshUnrolled() {
        val last = layoutManager.findLastVisibleItemPosition()
        val offset = adapter.items.getOrNull(last)?.index?.plus(1) ?: 0
        val emission = generation to offset
        if (emission != lastOffsetEmission) {
            lastOffsetEmission = emission
            offsets.tryEmit(offset)
        }
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to (endReached && last >= adapter.itemCount - 1),
        )
    }

    fun precedingTexts(offset: Int): Set<String> = adapter.items.asSequence()
        .takeWhile { it.index < offset }.map { it.candidate.text }.toSet()

    fun refreshColors() { adapter.notifyDataSetChanged() }

    val view: RecyclerView by lazy {
        RecyclerView(context).apply {
            id = R.id.candidate_view
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
            isHorizontalScrollBarEnabled = false
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            contentDescription = "候选词，左右滑动浏览，向下滑动展开"
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    refreshUnrolled()
                    if (dx > 0 && this@CompactCandidateDelegate.layoutManager.findLastVisibleItemPosition() >=
                        candidateCount() - 4) loadMore()
                }
            })
            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                private var startX = 0f
                private var startY = 0f
                private var opened = false
                private val slop = ViewConfiguration.get(context).scaledTouchSlop
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) { startX = e.x; startY = e.y; opened = false }
                    if (e.actionMasked == MotionEvent.ACTION_MOVE && !opened && e.y - startY > slop * 2 &&
                        e.y - startY > abs(e.x - startX) * 1.5f) {
                        opened = true
                        windows.attachWindow(FlexboxUnrolledCandidateWindow(di))
                        return true
                    }
                    return false
                }
            })
        }
    }

    private fun candidateCount(): Int = adapter.itemCount

    private fun loadMore() {
        if (loading || endReached) return
        loading = true
        val requestedGeneration = generation
        val start = nextEngineIndex
        val expected = candidateContext
        rime.launchOnReady { api ->
            val batch = api.getCandidatesIfCurrent(start, 32, expected)
            view.post {
                if (requestedGeneration != generation) return@post
                loading = false
                if (batch == null) return@post
                nextEngineIndex += batch.size
                endReached = batch.size < 32 || (adapter.total >= 0 && nextEngineIndex >= adapter.total)
                val added = CandidateDeduplicator.collect(batch, start, seen, expected)
                adapter.updateCandidates(adapter.items + added, adapter.total, adapter.highlightedIdx)
                refreshUnrolled()
                // Duplicate-only pages must not strand the scroll position.
                if (!endReached && added.isEmpty()) loadMore()
            }
        }
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        generation++
        loading = false
        view.stopScroll()
        seen.clear()
        nextEngineIndex = data.candidates.size
        endReached = data.total >= 0 && nextEngineIndex >= data.total
        candidateContext = CandidateContext(composition, schemaId)
        adapter.updateCandidates(CandidateDeduplicator.collect(data.candidates, seen = seen, context = candidateContext), data.total, data.highlighted)
        layoutManager.scrollToPositionWithOffset(0, 0)
        if (data.candidates.isEmpty()) offsets.tryEmit(0)
    }

    fun captureComposition(data: CompositionProto) { composition = data }

    override fun onRimeSchemaUpdated(schema: SchemaItem) { schemaId = schema.id }
}
