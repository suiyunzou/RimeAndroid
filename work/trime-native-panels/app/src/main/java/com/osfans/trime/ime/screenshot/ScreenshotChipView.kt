/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.screenshot

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.theme.ThemeScope
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 截图后的“发送图片”胶囊。
 *
 * 布局：左侧 24dp 圆角缩略图 + 13sp “发送图片” + 右侧 × 与 ⌄ 两个 26dp 按钮。
 * 外观：152dp × 40dp 白色卡片（圆角 20dp），缩放 0.8→1 回弹并淡入。
 * 位置：贴在 IME 窗口顶部的页眉空条内（左上角），不遮住按键。
 *
 * 配色全部由构造参数 [ScreenshotChipColors] 传入，不直接读取全局主题，方便
 * 在主题切换时通过 [setColors] 刷新。
 */
class ScreenshotChipView(
    context: Context,
    colors: ScreenshotChipColors = ScreenshotChipColors.default(),
) : FrameLayout(context) {
    private var chipColors = colors
    private var boundUri: Uri? = null
    private var thumbnailLoaded = false

    private val thumbnailView = ImageView(context)
    private val titleView = TextView(context)
    private val dismissView = TextView(context)
    private val moreView = TextView(context)

    var onSendClick: (() -> Unit)? = null
    var onDismissClick: (() -> Unit)? = null
    var onMoreClick: (() -> Unit)? = null

    init {
        isClickable = true
        contentDescription = "发送截图到当前输入框"
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        elevation = dpFloat(6f)
        setOnClickListener { onSendClick?.invoke() }

        thumbnailView.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dpFloat(8f))
                    }
                }
        }
        titleView.apply {
            text = "发送图片"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        dismissView.apply {
            text = "×"
            gravity = Gravity.CENTER
            contentDescription = "关闭图片提示"
            setOnClickListener { onDismissClick?.invoke() }
        }
        moreView.apply {
            text = "⌄"
            gravity = Gravity.CENTER
            contentDescription = "更多发送方式"
            setOnClickListener { onMoreClick?.invoke() }
        }

        addView(
            thumbnailView,
            LayoutParams(dp(THUMBNAIL_SIZE_DP), dp(THUMBNAIL_SIZE_DP), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = dp(4) },
        )
        addView(
            titleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL)
                .apply {
                    marginStart = dp(THUMBNAIL_SIZE_DP + 10)
                    marginEnd = dp(BUTTON_SIZE_DP * 2 + 8)
                },
        )
        addView(
            dismissView,
            LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP), Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { marginEnd = dp(BUTTON_SIZE_DP + 2) },
        )
        addView(
            moreView,
            LayoutParams(dp(BUTTON_SIZE_DP), dp(BUTTON_SIZE_DP), Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { marginEnd = dp(2) },
        )
        applyColors()
    }

    /** 绑定要发送的截图并异步加载缩略图；失败时保留占位图。 */
    fun bind(uri: Uri) {
        boundUri = uri
        thumbnailLoaded = false
        thumbnailView.setImageDrawable(createThumbnailPlaceholder())
        ScreenshotThumbnailLoader.load(context, uri) { bitmap ->
            if (boundUri == uri && bitmap != null) {
                thumbnailLoaded = true
                thumbnailView.setImageBitmap(bitmap)
            }
        }
    }

    fun setColors(colors: ScreenshotChipColors) {
        chipColors = colors
        applyColors()
    }

    /** 缩放 0.8→1 回弹 + 淡入。 */
    fun show() {
        animate().cancel()
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.8f
        scaleY = 0.8f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SHOW_ANIMATION_MS)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    /** 反向退出，动画结束后隐藏。 */
    fun hide(onHidden: (() -> Unit)? = null) {
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(HIDE_ANIMATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                visibility = View.INVISIBLE
                onHidden?.invoke()
            }.start()
    }

    fun hideImmediately() {
        animate().cancel()
        alpha = 0f
        visibility = View.INVISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dp(WIDTH_DP)
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val width =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || availableWidth <= 0) {
                desiredWidth
            } else {
                min(desiredWidth, availableWidth)
            }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY),
        )
    }

    private fun applyColors() {
        background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpFloat(CORNER_RADIUS_DP)
                setColor(chipColors.card)
            }
        titleView.setTextColor(chipColors.ink)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SP)
        dismissView.setTextColor(chipColors.muted)
        dismissView.setTextSize(TypedValue.COMPLEX_UNIT_SP, SYMBOL_TEXT_SP)
        moreView.setTextColor(chipColors.muted)
        moreView.setTextSize(TypedValue.COMPLEX_UNIT_SP, SYMBOL_TEXT_SP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = chipColors.dark
            outlineSpotShadowColor = chipColors.dark
        }
        if (!thumbnailLoaded) thumbnailView.setImageDrawable(createThumbnailPlaceholder())
    }

    private fun createThumbnailPlaceholder(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpFloat(8f)
            setColor(ColorUtils.setAlphaComponent(chipColors.accent, PLACEHOLDER_ALPHA))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dpFloat(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val WIDTH_DP = 152
        const val HEIGHT_DP = 40

        private const val CORNER_RADIUS_DP = 20f
        private const val THUMBNAIL_SIZE_DP = 24
        private const val BUTTON_SIZE_DP = 26
        private const val TITLE_TEXT_SP = 13f
        private const val SYMBOL_TEXT_SP = 15f
        private const val SHOW_ANIMATION_MS = 220L
        private const val HIDE_ANIMATION_MS = 160L
        private const val PLACEHOLDER_ALPHA = 48
    }
}

/**
 * “发送图片”胶囊的配色。卡片默认保持白色，强调色使用 iOS 蓝；
 * 文字色可以跟随主题，避免主题字体色与白卡片对比不足。
 */
data class ScreenshotChipColors(
    val card: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val dark: Int,
) {
    companion object {
        fun default(): ScreenshotChipColors =
            ScreenshotChipColors(
                card = Color.WHITE,
                ink = Color.rgb(26, 26, 26),
                muted = Color.rgb(138, 138, 138),
                accent = Color.rgb(10, 132, 255),
                dark = Color.BLACK,
            )

        /** 从当前主题推导配色；拿不到或对比不足时回落到默认值。 */
        fun fromTheme(scope: ThemeScope?): ScreenshotChipColors {
            val base = default()
            if (scope == null) return base
            return runCatching {
                val colors = scope.colors
                base.copy(
                    ink =
                        colors.keyTextColor
                            .takeIf { ColorUtils.calculateLuminance(it) < 0.6f }
                            ?: base.ink,
                    muted =
                        colors.commentTextColor
                            .takeIf { ColorUtils.calculateLuminance(it) < 0.7f }
                            ?: base.muted,
                    dark =
                        colors.shadowColor
                            .takeIf { Color.alpha(it) > 0 }
                            ?: base.dark,
                )
            }.getOrDefault(base)
        }
    }
}
