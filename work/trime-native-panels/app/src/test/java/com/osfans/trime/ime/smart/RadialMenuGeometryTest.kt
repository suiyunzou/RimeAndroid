// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.cos
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
            val (x, y) = point(60f, 162f - index * 36f)
            menu.release(x, y) shouldBe expected
        }
    }
    test("radial dead zone outside lower half and cancellation do not execute") {
        val menu = RadialMenuGeometry(1f)
        for ((x, y) in listOf(0f to -35f, 0f to -121f, 60f to 1f, Float.NaN to 0f)) {
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
        menu.hitTest(172f, 200f) shouldBe 4
        menu.hitTest(340f, 200f) shouldBe 4
        menu.hitTest(171f, 200f) shouldBe -1
        menu.hitTest(341f, 200f) shouldBe -1
        menu.hitTest(-20f, 200f) shouldBe 0
    }
    test("all visible icon centers hit their sector for one through six slots") {
        val menu = RadialMenuGeometry(1f)
        val actions = RadialMenuGeometry.DEFAULT_SLOTS + "calculator"
        for (count in 1..6) {
            menu.begin(0f, 0f, actions.take(count))
            for (index in 0 until count) {
                val angle = if (count == 1) 90f else 168f - index * 156f / (count - 1)
                val (x, y) = point(112f, angle)
                menu.hitTest(x, y) shouldBe index
            }
        }
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
    test("horizontal time carousel stops at both video endpoints and reverses without a dead drag") {
        val menu = RadialMenuGeometry(1f)
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
})

