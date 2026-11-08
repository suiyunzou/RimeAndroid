/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.os.Parcelable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.SymbolHistory
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import org.kodein.di.DI
import org.kodein.di.instance

class LiquidWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    ResidentWindow {
    override val allowCandidates: Boolean get() = verticalCategories
    override val showTitle: Boolean get() = verticalCategories
    override val title: String get() = if (verticalCategories) "符号" else ""
    override val titleTextSize: Float?
        get() = if (verticalCategories) (scope.theme.style.candidateTextSize - 2f).coerceAtLeast(1f) else null

    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()

    private lateinit var liquidLayout: LiquidLayout
    private val symbolHistory = SymbolHistory(180)
    private val verticalCategories get() = scope.theme.liquidKeyboard.verticalCategories
    private val contentPositions = mutableMapOf<Int, Parcelable>()
    private var selectedCategory = -1
    private var renderedCategory = -1
    var currentDataType: LiquidKeyboard.DataType = LiquidKeyboard.DataType.SINGLE
        private set

    private val adapter by lazy {
        LiquidAdapter(scope, allowClick = {
            !verticalCategories ||
                (::liquidLayout.isInitialized &&
                    renderedCategory == selectedCategory &&
                    !liquidLayout.recyclerView.isLayoutRequested &&
                    (liquidLayout.recyclerView as ScrollSafeRecyclerView).canActivateItem)
        }) {
            when (currentDataType) {
                LiquidKeyboard.DataType.SYMBOL -> {
                    if (verticalCategories) commitNativeSymbol(this.text) else triggerSymbolInput(this.altText)
                }

                LiquidKeyboard.DataType.TABS -> {
                    val realPosition = scope.theme.liquidKeyboard.getTagList()
                        .indexOfFirst { it.label == this.text }
                    setDataByIndex(realPosition)
                }

                else -> {
                    commitNativeSymbol(this.text)
                    if (currentDataType != LiquidKeyboard.DataType.HISTORY) {
                        symbolHistory.insert(this.text)
                        symbolHistory.save()
                    }
                }
            }
        }
    }

    private fun commitNativeSymbol(text: String) {
        // Enhanced themes declare their own private Rime chords. Use the same
        // insertion path for a symbol and for that symbol in native history.
        // No other theme gains a dependency on these application-specific keys.
        if (verticalCategories && text.length == 1 && text[0] in "@#$%&*+=") {
            val action = "CleanLocalSign" + text[0].code
            if (action in scope.theme.presetKeys) {
                commonKeyboardActionListener.listener.onAction(scope.theme.resolveAction(action))
                return
            }
        }
        if (verticalCategories) {
            // A literal tile replaces an unconfirmed composition. Keep Rime in
            // sync with that direct commit so its old candidates cannot linger.
            // Queue with the private symbol chords to preserve rapid tap order.
            service.postRimeJob {
                clearComposition()
                if (text.length != 1 || !com.osfans.trime.ime.smart.PairPunctuationHandler.insertPair(service.currentInputConnection, text[0])) service.commitText(text)
            }
        } else {
            if (text.length != 1 || !com.osfans.trime.ime.smart.PairPunctuationHandler.insertPair(service.currentInputConnection, text[0])) service.commitText(text)
        }
    }

    private val mainLayoutManager: RecyclerView.LayoutManager by lazy {
        if (verticalCategories) GridLayoutManager(context, 6) else FlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
        }
    }

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = LiquidWindow

    override fun onCreateView(): View = LiquidLayout(context, scope, commonKeyboardActionListener).apply {
        liquidLayout = this
        val categories = categoriesUi
        if (categories != null) {
            categories.setTags(scope.theme.liquidKeyboard.getTagList())
            categories.setOnCategoryClickListener { i ->
                setDataByIndex(i)
            }
        } else {
            tabsUi.apply {
                setTags(scope.theme.liquidKeyboard.getTagList())
                setOnTabClickListener { i -> setDataByIndex(i) }
            }
        }
        recyclerView.apply {
            layoutManager = mainLayoutManager
            this.adapter = this@LiquidWindow.adapter
        }
    }

    // An external empty view still lets TabUi apply the native back button and
    // clear its lifetime correctly. Returning null would retain old bar visibility.
    override fun onCreateBarView(): View = if (verticalCategories) View(context) else liquidLayout.tabsUi.root

    override fun onAttached() {}

    override fun onDetached() {
        if (verticalCategories) {
            saveContentPosition()
            liquidLayout.recyclerView.stopScroll()
            liquidLayout.categoriesUi?.root?.stopScroll()
        }
    }

    override fun refreshColors() {
        if (::liquidLayout.isInitialized) liquidLayout.refreshColors()
    }

    fun setDataByIndex(i: Int) {
        val tag = scope.theme.liquidKeyboard.getTagList().getOrNull(i) ?: return
        if (verticalCategories) {
            saveContentPosition()
            liquidLayout.recyclerView.stopScroll()
            liquidLayout.categoriesUi?.activate(i)
            if (selectedCategory == i && tag.type != LiquidKeyboard.DataType.HISTORY) return
            // A second finger may change the left category while the first
            // still holds a right-side item. Its eventual UP cannot select
            // a newly rebound symbol, even after the diff has finished.
            (liquidLayout.recyclerView as ScrollSafeRecyclerView).cancelActiveGesture()
            selectedCategory = i
        }
        currentDataType = tag.type
        if (!verticalCategories) liquidLayout.tabsUi.activateTab(i)
        when (tag.type) {
            LiquidKeyboard.DataType.HISTORY -> {
                symbolHistory.load()
                submitData(symbolHistory.toOrderedList().map { LiquidKeyboard.KeyItem(it) }, i)
            }

            else -> {
                val data = scope.theme.liquidKeyboard.getDataByIndex(i)
                submitData(data, i)
            }
        }
    }

    private fun saveContentPosition() {
        if (renderedCategory < 0 || liquidLayout.recyclerView.isLayoutRequested) return
        mainLayoutManager.onSaveInstanceState()?.let { contentPositions[renderedCategory] = it }
    }

    private fun submitData(data: List<LiquidKeyboard.KeyItem>, category: Int) {
        if (!verticalCategories) {
            adapter.submitList(data)
            return
        }
        adapter.submitList(data) {
            if (selectedCategory == category) {
                renderedCategory = category
                contentPositions[category]?.let(mainLayoutManager::onRestoreInstanceState)
                    ?: mainLayoutManager.scrollToPosition(0)
            }
        }
    }

    private fun triggerSymbolInput(symbol: String) {
        rime.launchOnReady {
            val (isAsciiMode, isAsciiPunch) = it.statusCached.run { isAsciiMode to isAsciiPunct }
            if (isAsciiMode) it.setRuntimeOption("ascii_mode", false)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punct", false)
            it.clearComposition()
            it.simulateKeySequence(symbol)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punct", true)
            ContextCompat.getMainExecutor(service).execute {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }
}
