// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class RadialMenuGeometryTest : FunSpec({
    fun point(radius: Float, angle: Float): Pair<Float, Float> {
        val radians = Math.toRadians(angle.toDouble())
        return radius * cos(radians).toFloat() to -radius * sin(radians).toFloat()
    }
    test("video default actions are ordered visually left to right") {
        val menu = RadialMenuGeometry(1f)
        RadialMenuGeometry.DEFAULT_SLOTS shouldBe listOf("time", "traditional", "symbols", "clipboard", "settings")
        RadialMenuGeometry.DEFAULT_SLOTS.forEachIndexed { index, expected ->
            menu.begin(0f, 0f)
            val (x, y) = point(12f, 162f - index * 36f)
            menu.release(x, y) shouldBe expected
        }
    }
    test("radial dead zone outside lower half and cancellation do not execute") {
        val menu = RadialMenuGeometry(1f)
        for ((x, y) in listOf(0f to -9.9f, 0f to -121f, 60f to 1f, Float.NaN to 0f)) {
            menu.begin(0f, 0f)
            menu.release(x, y) shouldBe null
        }
        menu.begin(0f, 0f)
        menu.cancel()
        menu.release(60f, 0f) shouldBe null
    }
    test("density and exact radial boundaries preserve left right ordering") {
        val menu = RadialMenuGeometry(2f)
        menu.begin(100f, 200f)
        menu.hitTest(120f, 200f) shouldBe 4
        menu.hitTest(340f, 200f) shouldBe 4
        menu.hitTest(119f, 200f) shouldBe -1
        menu.hitTest(341f, 200f) shouldBe -1
        menu.hitTest(-20f, 200f) shouldBe 0
    }
    test("all visible icon centers hit their sector for one through six slots") {
        val menu = RadialMenuGeometry(1f)
        val actions = RadialMenuGeometry.DEFAULT_SLOTS + "calculator"
        for ((originX, originY, width) in listOf(Triple(192f, 190f, 384f), Triple(96f, 110f, 320f))) {
            for (count in 1..6) {
                menu.begin(originX, originY, actions.take(count))
                val layout = RadialMenuLayout(originX, originY, width, 1f, count)
                for (index in 0 until count) {
                    val (x, y) = layout.icon(index, count)
                    menu.hitTest(x, y) shouldBe index
                    (x >= 20f && x <= width - 20f && y >= 20f) shouldBe true
                }
            }
        }
    }
    test("long drags leave the cursor on the inner track and outside the focus lens") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(192f, 190f)
        val layout = RadialMenuLayout(192f, 190f, 384f, 1f, 5)
        for (index in 0..4) {
            val (iconX, iconY) = layout.icon(index, 5)
            for (above in listOf(false, true)) {
                val (x, y) = layout.cursor(iconX, iconY, above)
                (hypot(x - 192f, y - 190f) <= layout.gestureRadius + .01f) shouldBe true
                (hypot(iconX - x, iconY - y) > 18f + 11f) shouldBe true
            }
            menu.hitTest(iconX, iconY) shouldBe index
        }
        layout.cursor(192f, 174f, above = false) shouldBe RadialMenuLayout.Point(192f, 174f)
        layout.cursor(192f, 190f, above = false) shouldBe RadialMenuLayout.Point(192f, 190f)
    }
    test("custom slots sanitize duplicates and unknown entries") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(0f, 0f, listOf("calculator", "bad", "calculator", "clipboard"))
        menu.slots shouldBe listOf("calculator", "clipboard")
        menu.release(-60f, 0f) shouldBe "calculator"
        menu.begin(0f, 0f, emptyList())
        menu.isActive shouldBe false
    }
    test("only crossing into a sector triggers a tick") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(0f, 0f)
        menu.update(60f, -1f) shouldBe true
        menu.update(61f, -2f) shouldBe false
        menu.update(0f, 0f) shouldBe false
        menu.update(60f, -1f) shouldBe true
    }
    test("clock upward drag opens Chinese date carousel and can leave radial sector") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(0f, 0f)
        menu.update(-70f, -10f)
        menu.update(-70f, -35f)
        menu.timeArmed shouldBe true
        menu.selectedAction shouldBe "chinese_date"
        menu.release(-70f, -130f) shouldBe "chinese_date"
        menu.release(-70f, -130f) shouldBe null
    }
    test("legacy API carousel stops at original endpoints and reverses without a dead drag") {
        val menu = RadialMenuGeometry(1f, includeLunar = false)
        menu.begin(0f, 0f)
        menu.update(-70f, -10f)
        menu.update(-70f, -60f)
        menu.update(-34f, -60f)
        menu.selectedAction shouldBe "time"
        menu.update(2f, -60f)
        menu.selectedAction shouldBe "weekday"
        menu.update(38f, -60f)
        menu.selectedAction shouldBe "datetime"
        menu.update(74f, -60f)
        menu.selectedAction shouldBe "date"
        menu.update(110f, -60f)
        menu.selectedAction shouldBe "date"
        menu.update(74f, -60f)
        menu.selectedAction shouldBe "datetime"
        menu.update(-70f, -60f)
        menu.selectedAction shouldBe "chinese_date"
        menu.update(-106f, -60f)
        menu.selectedAction shouldBe "chinese_date"
        menu.release(-70f, -60f) shouldBe "time"
    }
    test("returning armed date preview to the space center cancels without commit") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(0f, 0f)
        menu.update(-70f, -10f)
        menu.update(-70f, -60f)
        menu.timeArmed shouldBe true
        menu.release(0f, 0f) shouldBe null
    }
    test("time carousel has a lower cancel area and no stale release") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(0f, 0f)
        menu.update(-70f, -10f)
        menu.update(-70f, -35f)
        menu.release(0f, 25f) shouldBe null
        menu.release(-70f, -35f) shouldBe null
    }
    test("invalid coordinates cancel armed time carousel") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(0f, 0f)
        menu.update(-70f, -10f)
        menu.update(-70f, -35f)
        menu.release(Float.NaN, -35f) shouldBe null
    }
    test("lunar date and traditional time append after original five choices") {
        val menu = RadialMenuGeometry(1f)
        menu.begin(0f,0f); menu.update(-70f,-10f); menu.update(-70f,-60f)
        menu.update(110f,-60f)
        menu.selectedAction shouldBe "lunar_date"
        menu.update(146f,-60f)
        menu.selectedAction shouldBe "lunar_datetime"
        menu.update(182f,-60f)
        menu.selectedAction shouldBe "lunar_datetime"
        menu.update(146f,-60f)
        menu.selectedAction shouldBe "lunar_date"
        menu.release(146f,-60f) shouldBe "lunar_date"
    }
})

