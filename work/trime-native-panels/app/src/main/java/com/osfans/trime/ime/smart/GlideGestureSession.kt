// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlin.math.hypot

/** Captures a single pointer; only the owner dispatches a tap or a decoded word, never both. */
class GlideGestureSession(private val density: Float) {
    init { require(density.isFinite() && density > 0) }
    enum class Kind { TAP, GLIDE, CANCEL }
    data class Result(val kind: Kind, val points: List<GlideDecoder.Point> = emptyList())
    private val points = ArrayList<GlideDecoder.Point>()
    private var active = false
    private var cancelled = false
    private var pathLength = 0f
    var isGliding = false
        private set

    fun begin(x: Float, y: Float) {
        points.clear(); pathLength = 0f; isGliding = false; cancelled = false
        active = x.isFinite() && y.isFinite()
        if (active) points.add(GlideDecoder.Point(x, y))
    }
    fun move(x: Float, y: Float): Boolean {
        if (!active || cancelled) return false
        if (!x.isFinite() || !y.isFinite()) { cancel(); return false }
        val previous = points.last()
        val step = hypot(x - previous.x, y - previous.y)
        if (step < density * 1.2f) return isGliding
        pathLength += step
        points.add(GlideDecoder.Point(x, y))
        // A bounded resampling preserves shape for slow / unusually long traces.
        if (points.size > 512) {
            val reduced = GlideDecoder.resample(points, 256)
            points.clear(); points.addAll(reduced)
        }
        if (pathLength >= 22f * density && hypot(x - points.first().x, y - points.first().y) > 8f * density) isGliding = true
        return isGliding
    }
    fun finish(x: Float, y: Float): Result {
        if (!active || cancelled) return Result(Kind.CANCEL)
        move(x, y)
        val result = when {
            cancelled -> Result(Kind.CANCEL)
            isGliding -> Result(Kind.GLIDE, points.toList())
            pathLength <= 8f * density -> Result(Kind.TAP)
            else -> Result(Kind.CANCEL)
        }
        cancel()
        return result
    }
    fun cancel() { active = false; cancelled = true; isGliding = false; points.clear() }
}
