// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GlideDecoderTest : FunSpec({
    val keys = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, letters ->
        letters.mapIndexed { column, c -> GlideDecoder.KeyCenter(c, column * 40f + row * 10f, row * 48f, 40f) }
    }
    fun trace(word: String) = word.map { c -> keys.first { it.letter == c }.let { GlideDecoder.Point(it.x, it.y) } }
    val decoder = GlideDecoder(listOf("hello", "help", "world", "word", "keyboard", "test", "there"))
    test("continuous letter-center gesture ranks the intended English word") {
        decoder.decode(trace("hello"), keys).first().word shouldBe "hello"
        decoder.decode(trace("world"), keys).first().word shouldBe "world"
    }
    test("path resampling ignores duplicate samples and tolerates small offset") {
        val noisy = trace("keyboard").flatMap { listOf(it, it.copy(x = it.x + 3f, y = it.y - 2f)) }
        decoder.decode(noisy, keys).first().word shouldBe "keyboard"
    }
    test("Chinese Pinyin maps to dictionary text and uses actual weights for homophones") {
        val chinese = GlideDecoder(listOf(GlideDecoder.Entry("你好", "nihao", 1000), GlideDecoder.Entry("拟好", "nihao", 1), GlideDecoder.Entry("世界", "shijie", 900)))
        chinese.decode(trace("nihao"), keys).first().word shouldBe "你好"
        chinese.decode(trace("shijie"), keys).first().word shouldBe "世界"
    }
    test("two Chinese words join across a continuous connector without hardcoded phrase") {
        val chinese = GlideDecoder(listOf(GlideDecoder.Entry("你好", "nihao", 1000), GlideDecoder.Entry("世界", "shijie", 900)))
        chinese.decode(trace("nihaoshijie"), keys).first().word shouldBe "你好世界"
    }
    test("tap and invalid paths cannot become a decoded word") {
        decoder.decode(List(8) { GlideDecoder.Point(20f, 20f) }, keys) shouldBe emptyList()
        decoder.decode(listOf(GlideDecoder.Point(Float.NaN, 2f)), keys) shouldBe emptyList()
        decoder.decode(trace("hello"), keys.take(5)) shouldBe emptyList()
    }
    test("single-pointer session produces either a tap or glide only once") {
        val gesture = GlideGestureSession(1f)
        gesture.begin(10f, 10f)
        gesture.finish(11f, 11f).kind shouldBe GlideGestureSession.Kind.TAP
        gesture.finish(11f, 11f).kind shouldBe GlideGestureSession.Kind.CANCEL
        gesture.begin(10f, 10f); gesture.move(50f, 10f); gesture.move(100f, 40f)
        gesture.finish(120f, 40f).kind shouldBe GlideGestureSession.Kind.GLIDE
        gesture.finish(120f, 40f).kind shouldBe GlideGestureSession.Kind.CANCEL
    }
    test("cancelled and ambiguous short drags cannot leak a tap") {
        val gesture = GlideGestureSession(1f)
        gesture.begin(10f, 10f); gesture.move(22f, 10f)
        gesture.finish(22f, 10f).kind shouldBe GlideGestureSession.Kind.CANCEL
        gesture.begin(10f, 10f); gesture.move(70f, 10f); gesture.cancel()
        gesture.finish(80f, 10f).kind shouldBe GlideGestureSession.Kind.CANCEL
    }
    test("overlapping-pointer peek preserves stationary owner and rejects folded motion") {
        val gesture = GlideGestureSession(1f)
        gesture.isStationaryTap shouldBe false
        gesture.begin(0f, 0f)
        gesture.isStationaryTap shouldBe true
        gesture.isStationaryTap shouldBe true
        gesture.finish(0f, 0f).kind shouldBe GlideGestureSession.Kind.TAP
        gesture.isStationaryTap shouldBe false
        gesture.begin(0f, 0f); gesture.move(6f, 0f); gesture.move(0f, 0f)
        gesture.isStationaryTap shouldBe false
        gesture.finish(0f, 0f).kind shouldBe GlideGestureSession.Kind.CANCEL
        gesture.begin(0f, 0f); gesture.move(30f, 0f)
        gesture.isStationaryTap shouldBe false
        gesture.finish(30f, 0f).kind shouldBe GlideGestureSession.Kind.GLIDE
    }
})
