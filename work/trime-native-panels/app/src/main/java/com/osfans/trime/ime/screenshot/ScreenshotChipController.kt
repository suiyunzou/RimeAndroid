/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.screenshot

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * 组合 [ScreenshotWatcher] 与 [ScreenshotChipView] 的生命周期。
 *
 * 集成步骤（由宿主 InputView 调用）：
 * 1. 创建 controller；
 * 2. 键盘显示时 `attach(service, host)` 并调用 [onKeyboardShown]；
 * 3. 键盘隐藏时调用 [onKeyboardHidden]；
 * 4. 输入法视图销毁时调用 [detach]。
 *
 * [host] 建议传 `InputView` 的键盘根布局；controller 会在其中铺一层不拦截
 * 触摸的透明 overlay，把胶囊钉在 IME 窗口顶部的页眉空条左上角。
 *
 * 读取截图权限缺失时 [needsPermission] 为 true，并且不会启动监听；宿主应调用
 * [requestPermission] 拉起透明 Activity，授权成功后 controller 会自动补查最近截图。
 */
class ScreenshotChipController(
    private val colorsProvider: () -> ScreenshotChipColors = {
        ScreenshotChipColors.fromTheme(ColorManager.currentScope())
    },
) {
    constructor(colors: ScreenshotChipColors) : this({ colors })

    private val mainHandler = Handler(Looper.getMainLooper())

    private var service: TrimeInputMethodService? = null
    private var host: ViewGroup? = null
    private var overlay: FrameLayout? = null
    private var chip: ScreenshotChipView? = null
    private var watcher: ScreenshotWatcher? = null
    private var currentUri: Uri? = null
    private var keyboardVisible = false
    private var attached = false

    /** 权限缺失时回调，宿主可在此弹出自己的引导或直接调用 [requestPermission]。 */
    var onPermissionRequired: (() -> Unit)? = null

    /** 每次点击发送后的结果，便于宿主做统计或额外提示。 */
    var onSendResult: ((ScreenshotSender.Result) -> Unit)? = null

    val needsPermission: Boolean
        get() = service?.let { !ScreenshotPermission.hasPermission(it) } ?: false

    private val autoHideRunnable = Runnable { hideChip(animate = true) }

    private val permissionResultListener: (Boolean) -> Unit = { granted ->
        if (granted) {
            startWatching()
            watcher?.rescan()
        }
    }

    fun attach(
        service: TrimeInputMethodService,
        host: ViewGroup,
    ) {
        detach()
        this.service = service
        this.host = host
        keyboardVisible = true
        attached = true

        val overlay = FrameLayout(service).apply {
            isClickable = false
            isFocusable = false
            setWillNotDraw(true)
        }
        val layoutParams = createOverlayLayoutParams(host)
        if (layoutParams != null) {
            host.addView(overlay, layoutParams)
        } else {
            host.addView(overlay)
            Timber.w("ScreenshotChipController: unsupported host %s", host.javaClass.name)
        }
        this.overlay = overlay

        if (needsPermission) {
            mainHandler.post { onPermissionRequired?.invoke() }
        } else {
            startWatching()
        }
    }

    fun detach() {
        attached = false
        keyboardVisible = false
        mainHandler.removeCallbacks(autoHideRunnable)
        ScreenshotPermission.onResult(null)
        watcher?.release()
        watcher = null
        chip?.hideImmediately()
        chip = null
        overlay?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        overlay = null
        currentUri = null
        host = null
        service = null
    }

    fun onKeyboardShown() {
        if (!attached) return
        keyboardVisible = true
        if (needsPermission) {
            onPermissionRequired?.invoke()
            return
        }
        startWatching()
    }

    fun onKeyboardHidden() {
        keyboardVisible = false
        watcher?.stop()
        hideChip(animate = false)
    }

    /** 由宿主传入 Activity 申请权限；授权成功后自动补查最近截图。 */
    fun requestPermission(activity: Activity) {
        ScreenshotPermission.request(activity, permissionResultListener)
    }

    /**
     * 由输入法 Service 等非 Activity 的 Context 申请权限：
     * [ScreenshotPermission] 会自动补 `FLAG_ACTIVITY_NEW_TASK`。
     */
    fun requestPermission(context: Context) {
        ScreenshotPermission.request(context, permissionResultListener)
    }

    /** 主题切换后刷新胶囊配色。 */
    fun refreshColors() {
        chip?.setColors(colorsProvider())
    }

    fun isShowing(): Boolean = chip?.visibility == View.VISIBLE && currentUri != null

    private fun startWatching() {
        val service = service ?: return
        if (watcher == null) {
            watcher =
                ScreenshotWatcher(service) { uri, _ ->
                    onScreenshotDetected(uri)
                }
        }
        watcher?.start()
    }

    private fun onScreenshotDetected(uri: Uri) {
        if (!attached || !keyboardVisible) return
        val service = service ?: return
        if (!ScreenshotPermission.hasPermission(service)) {
            onPermissionRequired?.invoke()
            return
        }
        currentUri = uri
        showChip(uri)
    }

    private fun showChip(uri: Uri) {
        val chip = ensureChip() ?: return
        chip.bind(uri)
        chip.show()
        scheduleAutoHide()
    }

    private fun ensureChip(): ScreenshotChipView? {
        val overlay = overlay ?: return null
        chip?.let { return it }
        val view = ScreenshotChipView(overlay.context, colorsProvider())
        view.onSendClick = { sendCurrentScreenshot() }
        view.onDismissClick = { hideChip(animate = true) }
        view.onMoreClick = { showMoreMenu(view) }
        overlay.addView(
            view,
            FrameLayout.LayoutParams(
                dp(ScreenshotChipView.WIDTH_DP),
                dp(ScreenshotChipView.HEIGHT_DP),
                // Reference layout: a compact shortcut pinned to the top-left of
                // the IME window, inside the header strip above the keys.
                Gravity.TOP or Gravity.START,
            ).apply {
                marginStart = dp(CHIP_START_MARGIN_DP)
                topMargin = dp(CHIP_TOP_MARGIN_DP)
            },
        )
        chip = view
        return view
    }

    private fun sendCurrentScreenshot() {
        val service = service ?: return
        val uri = currentUri ?: return
        if (!ScreenshotPermission.hasPermission(service)) {
            hideChip(animate = false)
            onPermissionRequired?.invoke()
            return
        }
        val result = ScreenshotSender.send(service, uri)
        onSendResult?.invoke(result)
        if (result == ScreenshotSender.Result.FAILED) {
            Toast.makeText(service, "发送图片失败，请重试", Toast.LENGTH_SHORT).show()
        }
        hideChip(animate = true)
    }

    private fun showMoreMenu(anchor: View) {
        val service = service ?: return
        val uri = currentUri ?: return
        val popup = PopupMenu(service, anchor)
        popup.menu.add("分享到其他应用").setOnMenuItemClickListener {
            hideChip(animate = true)
            ScreenshotSender.share(service, uri)
            true
        }
        popup.menu.add("复制图片").setOnMenuItemClickListener {
            hideChip(animate = true)
            ScreenshotSender.copyToClipboard(service, uri)
            true
        }
        popup.show()
    }

    private fun hideChip(animate: Boolean) {
        mainHandler.removeCallbacks(autoHideRunnable)
        currentUri = null
        val chip = chip ?: return
        if (animate) chip.hide() else chip.hideImmediately()
    }

    private fun scheduleAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun createOverlayLayoutParams(host: ViewGroup): ViewGroup.LayoutParams? =
        when (host) {
            is ConstraintLayout ->
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }

            is FrameLayout ->
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )

            is LinearLayout ->
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )

            else -> null
        }

    private fun dp(value: Int): Int {
        val density = service?.resources?.displayMetrics?.density ?: 1f
        return (value * density).roundToInt()
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MS = 30_000L
        private const val CHIP_TOP_MARGIN_DP = 4
        private const val CHIP_START_MARGIN_DP = 8
    }
}
