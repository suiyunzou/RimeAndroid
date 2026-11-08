package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WheelSlotOrderTest : FunSpec({
    val slots = RadialMenuGeometry.DEFAULT_SLOTS
    test("dropping each icon back on its center preserves order") {
        slots.forEachIndexed { i, id -> WheelSlotOrder.drop(slots, id, i.toFloat()/slots.lastIndex) shouldBe slots }
    }
    test("drag can move first to last and last to first") {
        WheelSlotOrder.drop(slots, slots.first(), 1f) shouldBe slots.drop(1)+slots.first()
        WheelSlotOrder.drop(slots, slots.last(), 0f) shouldBe listOf(slots.last())+slots.dropLast(1)
    }
    test("addition respects six slots and unknown or invalid drops do not mutate") {
        val six = WheelSlotOrder.drop(slots, "calculator", .5f)
        six.size shouldBe 6
        WheelSlotOrder.drop(six, "quotes", .5f) shouldBe six
        WheelSlotOrder.drop(slots, "unknown", .5f) shouldBe slots
        WheelSlotOrder.drop(slots, "symbols", Float.NaN) shouldBe slots
    }
    test("deleting last function is refused") {
        WheelSlotOrder.remove(listOf("time"), "time") shouldBe listOf("time")
        WheelSlotOrder.remove(slots, "symbols") shouldBe slots.filterNot { it == "symbols" }
    }
})
