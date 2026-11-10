/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.userdict

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.settingskit.Palette
import com.osfans.trime.ui.settingskit.SettingsPalette
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.margin
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityEndBottom
import splitties.views.imageDrawable
import splitties.views.recyclerview.verticalLayoutManager
import kotlin.math.min

/**
 * 「用户词典」列表页：浅灰（深色为黑）页面底色 + 白色圆角卡片行。
 *
 * 备份、导入、导出、Snackbar 与错误弹窗仍由 [UserDictionaryFragment] 触发，
 * 这里只替换列表外观并补上空状态。
 */
class UserDictListUi(
    override val ctx: Context,
    entries: Array<String>,
    initMoreButton: (ImageButton.(String) -> Unit) = {},
) : Ui {
    private val palette: Palette = Palette.of(resolveDarkTheme())

    private fun resolveDarkTheme(): Boolean =
        when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
            AppPrefs.Advanced.UiMode.DARK -> true
            AppPrefs.Advanced.UiMode.LIGHT -> false
            else -> SettingsPalette.isNightMode(ctx)
        }

    private fun dpOf(value: Int): Int = SettingsPalette.dp(ctx, value)

    val fab =
        view(::FloatingActionButton) {
            imageDrawable =
                drawable(R.drawable.ic_baseline_add_24)!!.apply {
                    setTint(palette.background)
                }
            backgroundTintList = ColorStateList.valueOf(SettingsPalette.SWITCH_ON_COLOR)
        }

    fun showSnackBar(text: String) {
        Snackbar.make(root, text, Snackbar.LENGTH_SHORT)
            .addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onShown(transientBottomBar: Snackbar) {
                        // snackbar is invisible when it attached to parent,
                        // but change visibility won't trigger `onDependentViewChanged`.
                        // so we need to update fab position when snackbar fully shown
                        // see [^1]
                        fab.translationY = -transientBottomBar.view.height.toFloat()
                    }
                },
            )
            .show()
    }

    val adapter by lazy {
        UserDictListAdapter(entries.toList(), initMoreButton).apply {
            palette = this@UserDictListUi.palette
        }
    }

    private val list =
        recyclerView {
            layoutManager = verticalLayoutManager()
            adapter = this@UserDictListUi.adapter
            clipToPadding = false
            setPadding(0, dpOf(8), 0, dpOf(88))
        }

    private val emptyBadge =
        TextView(ctx).apply {
            text = "＋"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.track)
                }
        }

    private val emptyTitle =
        TextView(ctx).apply {
            text = "还没有用户词典"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(palette.ink)
        }

    private val emptyHint =
        TextView(ctx).apply {
            text = "点击右下角的 +，导入或恢复词典文件。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
            setPadding(dpOf(40), dpOf(8), dpOf(40), 0)
        }

    private val emptyState =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                emptyBadge,
                LinearLayout.LayoutParams(dpOf(56), dpOf(56)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpOf(14)
                },
            )
            addView(
                emptyTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL },
            )
            addView(
                emptyHint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL },
            )
        }

    /** 导入/恢复后 adapter 用 submitList 换数据，这里同步空状态。 */
    private val dataObserver =
        object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = syncEmptyState()

            override fun onItemRangeInserted(
                positionStart: Int,
                itemCount: Int,
            ) = syncEmptyState()

            override fun onItemRangeRemoved(
                positionStart: Int,
                itemCount: Int,
            ) = syncEmptyState()

            override fun onItemRangeChanged(
                positionStart: Int,
                itemCount: Int,
            ) = syncEmptyState()

            override fun onItemRangeMoved(
                fromPosition: Int,
                toPosition: Int,
                itemCount: Int,
            ) = syncEmptyState()
        }

    init {
        adapter.registerAdapterDataObserver(dataObserver)
        syncEmptyState()
    }

    private fun syncEmptyState() {
        emptyState.isVisible = adapter.items.isEmpty()
    }

    private fun updateViewMargin(insets: WindowInsetsCompat? = null) {
        val windowInsets = (insets ?: ViewCompat.getRootWindowInsets(root)) ?: return
        val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
        fab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBars.bottom + dpOf(16)
        }
        // 底部多留 88dp，保证最后一张卡片能滚动到 FAB 上方
        list.bottomPadding = navBars.bottom + dpOf(88)
    }

    override val root = coordinatorLayout {
        backgroundColor = palette.background
        add(
            list,
            defaultLParams {
                height = matchParent
                width = matchParent
            },
        )
        add(
            emptyState,
            defaultLParams {
                height = wrapContent
                width = wrapContent
                gravity = Gravity.CENTER
            },
        )
        add(
            fab,
            defaultLParams {
                gravity = gravityEndBottom
                margin = dp(16)
                behavior =
                    object : HideBottomViewOnScrollBehavior<FloatingActionButton>() {
                        @SuppressLint("RestrictedApi")
                        override fun layoutDependsOn(
                            parent: CoordinatorLayout,
                            child: FloatingActionButton,
                            dependency: View,
                        ): Boolean = dependency is Snackbar.SnackbarLayout

                        override fun onDependentViewChanged(
                            parent: CoordinatorLayout,
                            child: FloatingActionButton,
                            dependency: View,
                        ): Boolean {
                            // [^1]: snackbar is invisible when it attached to parent
                            // update fab position only when snackbar is visible
                            if (dependency.isVisible) {
                                child.translationY = min(0f, dependency.translationY - dependency.height)
                                return true
                            }
                            return false
                        }

                        override fun onDependentViewRemoved(
                            parent: CoordinatorLayout,
                            child: FloatingActionButton,
                            dependency: View,
                        ) {
                            child.translationY = 0f
                        }
                    }
            },
        )
        doOnAttach { updateViewMargin() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
            updateViewMargin(windowInsets)
            windowInsets
        }
    }
}
