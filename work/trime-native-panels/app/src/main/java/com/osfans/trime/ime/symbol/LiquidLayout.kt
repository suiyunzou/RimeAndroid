// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.symbol

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.bottomToTopOf
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToBottomOf
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView

@SuppressLint("ViewConstructor")
class LiquidLayout(
    context: Context,
    private val scope: ThemeScope,
    commonKeyboardActionListener: CommonKeyboardActionListener,
) : ConstraintLayout(context) {
    private val theme: Theme get() = scope.theme

    // TODO: 继承一个键盘视图嵌入到这里，而不是自定义一个视图
    // The fixed-key items, kept so their colors can be refreshed on scheme switches.
    private val fixedKeyItems = mutableListOf<LiquidItemUi>()

    private val fixedKeyBar =
        constraintLayout {
            val fixedKeys =
                if (theme.liquidKeyboard.verticalCategories) emptyList() else theme.liquidKeyboard.fixedKeyBar.keys
            if (fixedKeys.isNotEmpty()) {
                val btns =
                    Array(fixedKeys.size) { index ->
                        val presetKeyName = fixedKeys[index]
                        val presetKey = theme.presetKeys[presetKeyName]
                        val ui = LiquidItemUi(context, scope)
                        fixedKeyItems += ui
                        // This bar does not use KeyView's dynamic Enter label resolver.
                        // Do not expose the theme placeholder as user-visible text.
                        ui.mainText.text = when (val label = presetKey?.label.orEmpty()) {
                            "enter_labels" -> "↵"
                            else -> label
                        }
                        ui.root.apply {
                            isRepeatable = presetKey?.repeatable ?: false
                            onClick = {
                                commonKeyboardActionListener.listener.onAction(
                                    theme.resolveAction(presetKeyName),
                                )
                            }
                        }
                        return@Array ui.root
                    }
                val marginX = theme.liquidKeyboard.marginX
                when (theme.liquidKeyboard.fixedKeyBar.position) {
                    LiquidKeyboard.KeyBar.Position.LEFT,
                    LiquidKeyboard.KeyBar.Position.RIGHT,
                    -> {
                        btns.forEachIndexed { i, btn ->
                            add(
                                btn,
                                lParams(wrapContent, matchConstraints) {
                                    if (i == 0) {
                                        topOfParent()
                                    } else {
                                        topMargin = dp(marginX).toInt()
                                        below(btns[i - 1])
                                    }
                                    if (i == btns.size - 1) {
                                        bottomOfParent()
                                    } else {
                                        bottomMargin = dp(marginX).toInt()
                                        above(btns[i + 1])
                                    }
                                },
                            )
                        }
                    }

                    LiquidKeyboard.KeyBar.Position.TOP,
                    LiquidKeyboard.KeyBar.Position.BOTTOM,
                    -> {
                        btns.forEachIndexed { i, btn ->
                            add(
                                btn,
                                lParams(wrapContent, matchConstraints) {
                                    if (i == 0) {
                                        startOfParent()
                                    } else {
                                        leftMargin = dp(marginX).toInt()
                                        after(btns[i - 1])
                                    }
                                    if (i == btns.size - 1) {
                                        endOfParent()
                                    } else {
                                        rightMargin = dp(marginX).toInt()
                                        before(btns[i + 1])
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

    val recyclerView: RecyclerView =
        (if (theme.liquidKeyboard.verticalCategories) {
            ScrollSafeRecyclerView(context)
        } else {
            recyclerView {}
        }).apply {
            val space = dp(theme.liquidKeyboard.marginX.toInt())
            addItemDecoration(SpacesItemDecoration(space))
            setPadding(space)
        }

    val tabsUi = LiquidTabsUi(context, scope)
    val categoriesUi = if (theme.liquidKeyboard.verticalCategories) LiquidCategoriesUi(context, scope) else null

    val root = view(::FrameLayout) {
        val categories = categoriesUi
        if (categories == null) {
            add(recyclerView, lParams(matchParent, matchParent))
        } else {
            val columns = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(categories.root, LinearLayout.LayoutParams(context.dp(76), matchParent))
                addView(recyclerView, LinearLayout.LayoutParams(0, matchParent, 1f))
            }
            add(columns, lParams(matchParent, matchParent))
        }
    }

    init {
        if (theme.liquidKeyboard.verticalCategories) {
            add(
                root,
                lParams(matchConstraints, matchConstraints) {
                    startOfParent()
                    endOfParent()
                    topOfParent()
                    bottomOfParent()
                },
            )
        } else when (theme.liquidKeyboard.fixedKeyBar.position) {
            LiquidKeyboard.KeyBar.Position.TOP -> {
                add(
                    root,
                    lParams {
                        centerHorizontally()
                        topToBottomOf(fixedKeyBar)
                        bottomOfParent()
                    },
                )
                add(
                    fixedKeyBar,
                    lParams(wrapContent, wrapContent) {
                        centerHorizontally()
                        topOfParent()
                        bottomToTopOf(root)
                    },
                )
            }

            LiquidKeyboard.KeyBar.Position.BOTTOM -> {
                add(
                    root,
                    lParams {
                        centerHorizontally()
                        topOfParent()
                        bottomToTopOf(fixedKeyBar)
                    },
                )
                add(
                    fixedKeyBar,
                    lParams(wrapContent, wrapContent) {
                        centerHorizontally()
                        topToBottomOf(root)
                        bottomOfParent()
                    },
                )
            }

            LiquidKeyboard.KeyBar.Position.LEFT -> {
                add(
                    root,
                    lParams {
                        centerVertically()
                        startToEndOf(fixedKeyBar)
                        endOfParent()
                    },
                )
                add(
                    fixedKeyBar,
                    lParams(wrapContent, matchConstraints) {
                        centerVertically()
                        startOfParent()
                        endToStartOf(root)
                    },
                )
            }

            LiquidKeyboard.KeyBar.Position.RIGHT -> {
                add(
                    root,
                    lParams {
                        centerVertically()
                        startOfParent()
                        endToStartOf(fixedKeyBar)
                    },
                )
                add(
                    fixedKeyBar,
                    lParams(wrapContent, matchConstraints) {
                        centerVertically()
                        startToEndOf(root)
                        endOfParent()
                    },
                )
            }
        }
    }

    fun refreshColors() {
        fixedKeyItems.forEach { it.refreshColors() }
        tabsUi.refreshColors()
        categoriesUi?.refreshColors()
        recyclerView.adapter?.notifyDataSetChanged()
    }
}
