/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.SchemaItem
import androidx.core.view.updateLayoutParams
import com.osfans.trime.ime.smart.CorrectionPresentation
import com.osfans.trime.ime.smart.OfflinePrivacyPolicy
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.horizontalPadding
import kotlin.math.ceil

class PreeditDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {

    private val context: ContextThemeWrapper by instance()
    private val scope: ThemeScope by instance()
    private val rime: RimeSession by instance()

    private val theme: Theme
        get() = scope.theme

    /** Applies the text-background styling; re-applied on scheme refreshes. */
    private fun setupPreeditBackground(view: View) {
        if (theme.isIosStyle) {
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.clipToOutline = false
            return
        }
        val startRadius = view.dp(theme.preedit.topStartRadius)
        val endRadius = view.dp(theme.preedit.topEndRadius)
        val radii = if (view.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
            floatArrayOf(startRadius, startRadius, endRadius, endRadius, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(endRadius, endRadius, startRadius, startRadius, 0f, 0f, 0f, 0f)
        }
        view.background = GradientDrawable().apply {
            setColor(scope.colors.textBackColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
        }
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    val ui =
        PreeditUi(
            context,
            scope,
            setupPreeditView = {
                setupPreeditBackground(this)
                horizontalPadding = if (theme.isIosStyle) maxOf(dp(theme.preedit.horizontalPadding), dp(16))
                    else dp(theme.preedit.horizontalPadding)
            },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        ).apply {
            preedit.compactPinyinLine = compactPinyinSchema(rime.run { schemaCached.schemaId })
            root.alpha = theme.preedit.alpha
            root.visibility = if (theme.isIosStyle) View.GONE else View.INVISIBLE
        }

    private fun compactPinyinSchema(id: String): Boolean = theme.isIosStyle &&
        (id.startsWith("wanxiang") || id.startsWith("rime_ice") || id.contains("pinyin"))

    // Reserve one stable line while typing. Other schemas keep the full font metrics.
    val embeddedSlotHeight: Int
        get() = if (ui.preedit.compactPinyinLine) maxOf(context.dp(14), ui.preedit.compactLineHeight)
            else maxOf(context.dp(16), ui.preedit.paint.fontMetrics.let { ceil((it.descent - it.ascent).toDouble()).toInt() })

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        ui.preedit.compactPinyinLine = compactPinyinSchema(schema.id)
        if (theme.isIosStyle && ui.root.layoutParams != null) {
            ui.root.updateLayoutParams { height = embeddedSlotHeight }
        }
    }

    var onEmbeddedLayoutChanged: ((Boolean) -> Unit)? = null

    fun refreshEmbeddedLayout() = updateVisibility()

    private var embeddedSlotSuppressed = false
    private var hasCandidates = false

    /** Emoji owns this unused slot while attached and restores it before returning to the keyboard. */
    fun setEmbeddedSlotSuppressed(suppressed: Boolean): Int {
        if (!theme.isIosStyle || embeddedSlotSuppressed == suppressed) return 0
        val reclaimed = if (suppressed) ui.root.height.takeIf { it > 0 } ?: embeddedSlotHeight else 0
        embeddedSlotSuppressed = suppressed
        updateVisibility()
        return reclaimed
    }

    private fun updateVisibility() {
        if (theme.isIosStyle && !embeddedSlotSuppressed) {
            onEmbeddedLayoutChanged?.invoke(ui.visible || hasCandidates)
        }
        ui.root.visibility = when {
            theme.isIosStyle && embeddedSlotSuppressed -> View.GONE
            theme.isIosStyle -> if (ui.visible || hasCandidates) View.VISIBLE else View.GONE
            ui.visible -> View.VISIBLE
            else -> View.INVISIBLE
        }
    }

    /** Restyles the text and its background after a scheme switch. */
    fun refreshColors() {
        ui.refreshColors()
        setupPreeditBackground(ui.preedit)
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(ui.root)
    private var lastComposition = CompositionProto()
    private var correction: CorrectionPresentation.Hint? = null

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        hasCandidates = data.candidates.isNotEmpty()
        correction = if (OfflinePrivacyPolicy.sensitiveEditor) null else data.candidates.asSequence()
            .mapNotNull { CorrectionPresentation.parse(it.comment) }.firstOrNull { !it.sentence }
        ui.update(lastComposition, correction)
        updateVisibility()
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        lastComposition = data
        if (data.length == 0) correction = null
        ui.update(data, correction)
        updateVisibility()
        if (!theme.isIosStyle && data.length > 0) {
            touchEventReceiverWindow.show()
        } else {
            touchEventReceiverWindow.dismiss()
        }
    }
}
