/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedDispatcher
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.osfans.trime.R
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.common.OnItemChangedListener
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
 * 「输入方案」列表页：浅灰（深色为黑）页面底色 + 白色圆角卡片行。
 *
 * 行为保持不变：添加、删除、撤销 Snackbar、多选删除、持久化到 Rime；
 * 新增的拖动排序只在非多选模式下生效，长按整行即可开始。
 */
class SchemaListUi(
    override val ctx: Context,
    private val initialEntries: List<SchemaItem>,
    private val contentSource: SchemaListUi.() -> Array<SchemaItem>,
) : Ui {
    private val palette: Palette = Palette.of(resolveDarkTheme())

    private var shouldShowFab = false

    private fun resolveDarkTheme(): Boolean =
        when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
            AppPrefs.Advanced.UiMode.DARK -> true
            AppPrefs.Advanced.UiMode.LIGHT -> false
            else -> SettingsPalette.isNightMode(ctx)
        }

    private fun dpOf(value: Int): Int = SettingsPalette.dp(ctx, value)

    private val fab =
        view(::FloatingActionButton) {
            imageDrawable =
                drawable(R.drawable.ic_baseline_add_24)!!.apply {
                    setTint(palette.background)
                }
            backgroundTintList = ColorStateList.valueOf(SettingsPalette.SWITCH_ON_COLOR)
        }

    private var suspendUndo = false

    private fun showUndoSnackBar(
        text: String,
        action: () -> Unit,
    ) {
        if (suspendUndo) return
        Snackbar
            .make(root, text, Snackbar.LENGTH_SHORT)
            .setAction(R.string.undo) {
                suspendUndo = true
                action.invoke()
                suspendUndo = false
            }.addCallback(
                object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onShown(transientBottomBar: Snackbar) {
                        // snackbar is invisible when it attached to parent,
                        // but change visibility won't trigger `onDependentViewChanged`.
                        // so we need to update fab position when snackbar fully shown
                        // see [^1]
                        fab.translationY = -transientBottomBar.view.height.toFloat()
                    }
                },
            ).show()
    }

    val adapter =
        object : SchemaListAdapter(initialEntries) {
            init {
                addOnItemChangedListener(
                    object : OnItemChangedListener<SchemaItem> {
                        override fun onItemAdded(
                            idx: Int,
                            item: SchemaItem,
                        ) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.added_x, item.name)) {
                                remove(item)
                            }
                        }

                        override fun onItemRemoved(
                            idx: Int,
                            item: SchemaItem,
                        ) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.removed_x, item.name)) {
                                add(idx, item)
                            }
                        }

                        override fun onItemAddedBatch(items: List<SchemaItem>) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.added_n_items, items.size)) {
                                items.forEach { remove(it) }
                            }
                        }

                        override fun onItemRemovedBatch(items: List<SchemaItem>) {
                            updateFAB()
                            showUndoSnackBar(ctx.getString(R.string.removed_n_items, items.size)) {
                                items.forEach { add(it) }
                            }
                        }
                    },
                )
            }

            override fun enterMultiSelect(onBackPressedDispatcher: OnBackPressedDispatcher) {
                if (shouldShowFab) {
                    fab.hide()
                }
                super.enterMultiSelect(onBackPressedDispatcher)
                updateSortHint()
            }

            override fun exitMultiSelect() {
                if (shouldShowFab) {
                    fab.show()
                }
                super.exitMultiSelect()
                updateSortHint()
            }
        }.apply { palette = this@SchemaListUi.palette }

    private val list =
        recyclerView {
            layoutManager = verticalLayoutManager()
            adapter = this@SchemaListUi.adapter
            clipToPadding = false
            setPadding(0, dpOf(8), 0, dpOf(88))
        }

    private val sectionText =
        TextView(ctx).apply {
            text = "已启用的方案"
            textSize = 15f
            setTextColor(palette.muted)
        }

    private val sortHint =
        TextView(ctx).apply {
            text = "长按拖动排序"
            textSize = 13f
            setTextColor(palette.muted)
            isVisible = false
        }

    private val header =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpOf(16), dpOf(12), dpOf(16), dpOf(6))
            addView(
                sectionText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                sortHint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private val content =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                header,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f },
            )
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
            text = "还没有已启用的输入方案"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(palette.ink)
        }

    private val emptyHint =
        TextView(ctx).apply {
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

    /** 长按整行拖动排序；多选模式下由 [SchemaListAdapter.moveItem] 拒绝移动。 */
    private val touchHelper =
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun isLongPressDragEnabled(): Boolean = !adapter.multiselect

                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int =
                    if (adapter.multiselect) {
                        0
                    } else {
                        makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                    }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                        return false
                    }
                    return adapter.moveItem(from, to)
                }

                override fun onSwiped(
                    viewHolder: RecyclerView.ViewHolder,
                    direction: Int,
                ) = Unit
            },
        ).apply { attachToRecyclerView(list) }

    fun updateFAB() {
        val source = contentSource(this)
        if (source.isEmpty()) {
            shouldShowFab = false
            fab.hide()
        } else {
            shouldShowFab = true
            fab.show()
            fab.setOnClickListener {
                val items = source.map { it.name }.toTypedArray()
                val checked = BooleanArray(items.size) { false }
                AlertDialog
                    .Builder(ctx)
                    .setTitle(R.string.enable_schemata)
                    .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val collection = source.filterIndexed { index, _ -> checked[index] }
                        if (collection.isEmpty()) return@setPositiveButton
                        if (collection.size == 1) {
                            adapter.add(collection[0])
                        } else {
                            adapter.addAll(collection)
                        }
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        updateEmptyState(source.isEmpty())
    }

    /** 空状态与排序提示都跟随列表条数变化，避免删空后还留着旧提示。 */
    private fun updateEmptyState(noAvailable: Boolean) {
        val empty = adapter.items.isEmpty()
        emptyState.isVisible = empty
        if (empty) {
            emptyHint.text =
                if (noAvailable) {
                    "没有可用的输入方案，请先在用户目录放入 prelude 与方案文件，再回来添加。"
                } else {
                    "点击右下角的 +，从可用方案中启用。"
                }
        }
        updateSortHint()
    }

    /** 多选模式下不能拖动，排序提示同时隐藏，避免误导。 */
    private fun updateSortHint() {
        sortHint.isVisible = !adapter.multiselect && adapter.items.size > 1
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

    override val root =
        coordinatorLayout {
            backgroundColor = palette.background
            add(
                content,
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
            updateFAB()
        }
}
