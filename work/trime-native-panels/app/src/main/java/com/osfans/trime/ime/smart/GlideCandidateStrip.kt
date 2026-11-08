// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Owner supplies the guarded commit callback. Closing/choosing clears pending results first. */
class GlideCandidateStrip(context: Context, private val onChoose: (GlideDecoder.Candidate) -> Unit, private val onCancel: () -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(0xffe5e7ed.toInt()); cornerRadius = dp(12).toFloat() }
        contentDescription = "滑行候选"
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
            addView(row, LayoutParams(-2, -1))
        }, LayoutParams(0, dp(44), 1f))
        addView(TextView(context).apply {
            text = "×"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY)
            contentDescription = "取消滑行候选"; isClickable = true; isFocusable = true
            setOnClickListener { clear(); onCancel() }
        }, LayoutParams(dp(44), dp(44)))
        visibility = GONE
    }
    fun showCandidates(candidates: List<GlideDecoder.Candidate>) {
        row.removeAllViews()
        for (candidate in candidates) row.addView(TextView(context).apply {
            text = candidate.word; textSize = 19f; gravity = Gravity.CENTER; setTextColor(0xff1a1b20.toInt())
            setPadding(dp(15), 0, dp(15), 0); isClickable = true; isFocusable = true
            contentDescription = "滑行候选 ${candidate.word}"
            setOnClickListener { clear(); onChoose(candidate) }
        }, LayoutParams(-2, dp(44)))
        visibility = if (candidates.isEmpty()) GONE else VISIBLE
        bringToFront()
    }
    fun clear() { row.removeAllViews(); visibility = View.GONE }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
