/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Colour tokens of the reference settings pages.
 *
 * Only [Palette] and [SettingsPalette]'s constants are touched: they are plain ints, so the
 * test runs on a bare JVM without the Android framework.
 */
class SettingsPaletteTest :
    StringSpec({
        "light palette matches the reference screenshot" {
            val palette = Palette.of(dark = false)
            palette.dark shouldBe false
            palette.ink shouldBe 0xFF111111.toInt()
            palette.muted shouldBe 0xFF818186.toInt()
            palette.background shouldBe 0xFFF2F2F6.toInt()
            palette.group shouldBe 0xFFFFFFFF.toInt()
            palette.selected shouldBe 0xFFFFFFFF.toInt()
            palette.track shouldBe 0xFFE4E4E9.toInt()
        }

        "dark palette matches the reference screenshot" {
            val palette = Palette.of(dark = true)
            palette.dark shouldBe true
            palette.ink shouldBe 0xFFFFFFFF.toInt()
            palette.muted shouldBe 0xFF929296.toInt()
            palette.background shouldBe 0xFF000000.toInt()
            palette.group shouldBe 0xFF262628.toInt()
            palette.selected shouldBe 0xFF56565A.toInt()
            palette.track shouldBe 0xFF363639.toInt()
        }

        "iOS switch colours: green when on, grey when off" {
            val light = Palette.of(false)
            light.switchOn shouldBe 0xFF30D958.toInt()
            light.switchOff shouldBe 0xFFD1D1D6.toInt()
            light.switchTrack(true) shouldBe 0xFF30D958.toInt()
            light.switchTrack(false) shouldBe 0xFFD1D1D6.toInt()

            val dark = Palette.of(true)
            dark.switchOn shouldBe 0xFF30D958.toInt()
            dark.switchOff shouldBe 0xFF636366.toInt()
            dark.switchTrack(true) shouldBe 0xFF30D958.toInt()
            dark.switchTrack(false) shouldBe 0xFF636366.toInt()
        }

        "page background and cards differ in both themes" {
            for (dark in listOf(false, true)) {
                val palette = Palette.of(dark)
                palette.background shouldNotBe palette.group
                palette.ink shouldNotBe palette.muted
                palette.track shouldNotBe palette.group
                palette.note shouldBe palette.muted
            }
        }

        "light and dark palettes differ on background, group and ink" {
            val light = Palette.of(false)
            val dark = Palette.of(true)
            (light.background != dark.background) shouldBe true
            (light.group != dark.group) shouldBe true
            (light.ink != dark.ink) shouldBe true
            (light.muted != dark.muted) shouldBe true
            (light.track != dark.track) shouldBe true
            (light.selected != dark.selected) shouldBe true
            (light.switchOff != dark.switchOff) shouldBe true
        }

        "switchOn is the same iOS green in both themes" {
            Palette.of(false).switchOn shouldBe Palette.of(true).switchOn
            Palette.of(false).switchOn shouldBe 0xFF30D958.toInt()
        }

        "every token is fully opaque" {
            for (dark in listOf(false, true)) {
                val palette = Palette.of(dark)
                listOf(
                    palette.ink,
                    palette.muted,
                    palette.background,
                    palette.group,
                    palette.selected,
                    palette.track,
                    palette.switchOn,
                    palette.switchOff,
                    palette.chevron,
                    palette.divider,
                    palette.note,
                ).forEach { argb ->
                    withClue("token ${Integer.toHexString(argb)} in dark=$dark") {
                        ((argb ushr 24) and 0xFF) shouldBe 0xFF
                    }
                }
            }
        }

        "group card stays lighter than the page in both themes" {
            val light = Palette.of(false)
            luminance(light.group) shouldBe 0xFF
            luminance(light.background) shouldBe 0xF2
            // Light grey page with white cards, as in the reference screenshot.
            (luminance(light.group) > luminance(light.background)) shouldBe true

            val dark = Palette.of(true)
            (luminance(dark.group) > luminance(dark.background)) shouldBe true
        }

        "dark page background is pure black" {
            Palette.of(true).background shouldBe 0xFF000000.toInt()
        }

        "paletteOf(dark) builds the same tokens as Palette.of(dark)" {
            // Palette has no equals(): compare the values it is made of, not the instance.
            for (dark in listOf(true, false)) {
                val fromObject = SettingsPalette.paletteOf(dark)
                val fromClass = Palette.of(dark)
                fromObject.dark shouldBe fromClass.dark
                fromObject.background shouldBe fromClass.background
                fromObject.group shouldBe fromClass.group
                fromObject.ink shouldBe fromClass.ink
                fromObject.muted shouldBe fromClass.muted
                fromObject.track shouldBe fromClass.track
                fromObject.selected shouldBe fromClass.selected
                fromObject.switchOn shouldBe fromClass.switchOn
                fromObject.switchOff shouldBe fromClass.switchOff
            }
        }

        "group radius token is 22 dp and the row height 54 dp" {
            SettingsPalette.RADIUS_GROUP shouldBe 22
            SettingsPalette.RADIUS_SEGMENT shouldBe 19
            SettingsPalette.ROW_HEIGHT_DP shouldBe 54
        }

        "iOS switch metrics and animation duration" {
            SettingsPalette.SWITCH_WIDTH_DP shouldBe 51
            SettingsPalette.SWITCH_HEIGHT_DP shouldBe 31
            SettingsPalette.SWITCH_ANIMATION_MS shouldBe 200
            // Capsule: the height must be less than the width.
            (SettingsPalette.SWITCH_HEIGHT_DP < SettingsPalette.SWITCH_WIDTH_DP) shouldBe true
        }

        "section caption colour is the muted token" {
            SettingsBuilder.sectionColor(false) shouldBe 0xFF818186.toInt()
            SettingsBuilder.sectionColor(true) shouldBe 0xFF929296.toInt()
        }
    })

/** Red channel as a crude luminance, sufficient for ordering the greys of this palette. */
private fun luminance(argb: Int): Int = (argb shr 16) and 0xFF
