/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import com.osfans.trime.ui.settingskit.SettingsKitState.RowVisual
import com.osfans.trime.ui.settingskit.SettingsKitState.SwitchVisual
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Row / switch / segment state derivation, exercised without the Android framework. */
class SettingsKitStateTest :
    StringSpec({
        "row without a value hides the muted text" {
            val visual = RowVisual.of(value = "", hasAction = false)
            visual.hasValue shouldBe false
            visual.hasChevron shouldBe false
            visual.tappable shouldBe false
        }

        "row with a value renders the muted text but no chevron" {
            val visual = RowVisual.of(value = "自动识别", hasAction = false)
            visual.hasValue shouldBe true
            visual.hasChevron shouldBe false
            visual.tappable shouldBe false
        }

        "row with an action shows the chevron and becomes tappable" {
            val visual = RowVisual.of(value = "", hasAction = true)
            visual.hasValue shouldBe false
            visual.hasChevron shouldBe true
            visual.tappable shouldBe true
        }

        "row description joins title and value" {
            SettingsKitState.rowDescription("候选文字", "标准") shouldBe "候选文字，标准"
            SettingsKitState.rowDescription("短信验证码", "") shouldBe "短信验证码"
        }

        "toggle states map to the iOS on/off colours of the palette" {
            val light = Palette.of(false)
            light.switchTrack(true) shouldBe 0xFF30D958.toInt()
            light.switchTrack(false) shouldBe 0xFFD1D1D6.toInt()
            val dark = Palette.of(true)
            dark.switchTrack(true) shouldBe 0xFF30D958.toInt()
            dark.switchTrack(false) shouldBe 0xFF636366.toInt()
            SettingsKitState.switchVisual(true) shouldBe SwitchVisual.ON
            SettingsKitState.switchVisual(false) shouldBe SwitchVisual.OFF
        }

        "flipping a switch twice returns to the original state" {
            SettingsKitState.flipped(SettingsKitState.flipped(true)) shouldBe true
            SettingsKitState.flipped(SettingsKitState.flipped(false)) shouldBe false
        }

        "switch description always mentions the state" {
            SettingsKitState.switchDescription("滑行输入", true) shouldBe "滑行输入，已开启"
            SettingsKitState.switchDescription("滑行输入", false) shouldBe "滑行输入，已关闭"
            SettingsKitState.switchDescription(null, true) shouldBe "已开启"
            SettingsKitState.switchDescription("", false) shouldBe "已关闭"
        }

        "knob travel follows the checked state and is clamped" {
            SettingsKitState.knobFraction(checked = true, fraction = 0f) shouldBe 0f
            SettingsKitState.knobFraction(checked = true, fraction = 1f) shouldBe 1f
            SettingsKitState.knobFraction(checked = true, fraction = 0.5f) shouldBe 0.5f
            SettingsKitState.knobFraction(checked = false, fraction = 1f) shouldBe 0f
            SettingsKitState.knobFraction(checked = false, fraction = 0f) shouldBe 1f
            SettingsKitState.knobFraction(checked = false, fraction = 0.25f) shouldBe 0.75f
        }

        "knob travel never leaves the track for out of range animator values" {
            SettingsKitState.knobFraction(checked = true, fraction = -3f) shouldBe 0f
            SettingsKitState.knobFraction(checked = true, fraction = 9f) shouldBe 1f
            SettingsKitState.knobFraction(checked = false, fraction = -3f) shouldBe 1f
            SettingsKitState.knobFraction(checked = false, fraction = 9f) shouldBe 0f
            SettingsKitState.knobFraction(checked = true, fraction = Float.NaN) shouldBe 0f
        }

        "knobFraction endpoints are exactly 0f and 1f (no drift)" {
            SettingsKitState.knobFraction(checked = true, fraction = 0f) shouldBe 0f
            SettingsKitState.knobFraction(checked = true, fraction = 1f) shouldBe 1f
            SettingsKitState.knobFraction(checked = true, fraction = 1f + 1e-7f) shouldBe 1f
            SettingsKitState.knobFraction(checked = true, fraction = -1e-7f) shouldBe 0f
        }

        "knobFraction is monotonic across the animation" {
            var previous = -1f
            var step = 0
            while (step <= 10) {
                val value = SettingsKitState.knobFraction(checked = true, fraction = step / 10f)
                (value >= previous) shouldBe true
                previous = value
                step++
            }
            previous shouldBe 1f
        }

        "knobFraction clamps out of range animator input in both directions" {
            for (out in listOf(-1f, -0.001f, 1.001f, 42f, 1e6f)) {
                SettingsKitState.knobFraction(checked = true, fraction = out) shouldBe
                    if (out < 0f) 0f else 1f
            }
            SettingsKitState.knobFraction(checked = false, fraction = 42f) shouldBe 0f
            SettingsKitState.knobFraction(checked = false, fraction = -42f) shouldBe 1f
        }

        "selected segment is clamped to the available labels" {
            val labels = listOf("小", "标准", "大")
            SettingsKitState.selectedSegment(labels, 0) shouldBe 0
            SettingsKitState.selectedSegment(labels, 1) shouldBe 1
            SettingsKitState.selectedSegment(labels, 2) shouldBe 2
            SettingsKitState.selectedSegment(labels, 7) shouldBe 2
            SettingsKitState.selectedSegment(labels, Int.MAX_VALUE) shouldBe 2
            SettingsKitState.selectedSegment(labels, -1) shouldBe 0
            SettingsKitState.selectedSegment(labels, Int.MIN_VALUE) shouldBe 0
        }

        "selected segment of an empty list is -1 for any input" {
            SettingsKitState.selectedSegment(emptyList(), 0) shouldBe -1
            SettingsKitState.selectedSegment(emptyList(), -5) shouldBe -1
            SettingsKitState.selectedSegment(emptyList(), 99) shouldBe -1
        }

        "single label list always resolves to index 0" {
            val single = listOf("只有一个")
            SettingsKitState.selectedSegment(single, 0) shouldBe 0
            SettingsKitState.selectedSegment(single, 5) shouldBe 0
            SettingsKitState.selectedSegment(single, -5) shouldBe 0
        }

        "segment index falls back when the stored label is unknown" {
            val labels = listOf("关闭", "上拨", "下拨")
            SettingsKitState.segmentIndexOf(labels, "上拨") shouldBe 1
            SettingsKitState.segmentIndexOf(labels, "未知") shouldBe 0
            SettingsKitState.segmentIndexOf(labels, "未知", fallback = 9) shouldBe 2
            SettingsKitState.segmentIndexOf(labels, "未知", fallback = -5) shouldBe 0
        }

        "segments description marks exactly one selection" {
            val labels = listOf("功能", "外观")
            SettingsKitState.segmentsDescription(labels, 1) shouldBe "功能，外观，已选中"
            SettingsKitState.segmentsDescription(labels, 0) shouldBe "功能，已选中，外观"
            SettingsKitState.segmentsDescription(emptyList(), 0) shouldBe ""
        }

        "dividers are drawn between rows only" {
            SettingsKitState.dividerAfter(listOf(0, 1, 2)) shouldBe setOf(0, 1)
            SettingsKitState.dividerAfter(listOf(3, 4)) shouldBe setOf(3)
            SettingsKitState.dividerAfter(listOf(0)) shouldBe emptySet()
            SettingsKitState.dividerAfter(emptyList()) shouldBe emptySet()
        }

        "switch state label is localized" {
            SettingsKitState.switchStateLabel(true) shouldBe "已开启"
            SettingsKitState.switchStateLabel(false) shouldBe "已关闭"
            SettingsKitState.SWITCH_ON_LABEL shouldBe "已开启"
            SettingsKitState.SWITCH_OFF_LABEL shouldBe "已关闭"
        }
    })
