/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Contract of the iOS switch semantics, expressed on a framework free mirror of the logic.
 *
 * [IosSwitchView] itself cannot run in a JVM unit test: it extends `android.view.View` and uses
 * `Paint` / `ValueAnimator`, and the Android unit test runtime throws "not mocked" for those
 * classes. So this spec verifies the *rules* the view implements, held in [SwitchSemantics],
 * which is a line by line copy of the view's state transitions (isChecked setter, toggle,
 * setCheckedAnimated, syncFromCallback). The view's own wiring is a thin shell around these
 * transitions; if the two ever drift, this test keeps its value only for the stated rules.
 */
class IosSwitchSemanticsTest :
    StringSpec({
        "isChecked setter does not fire the listener" {
            val semantics = SwitchSemantics()
            var calls = 0
            semantics.listener = { calls++ }
            semantics.isChecked = true
            semantics.isChecked shouldBe true
            calls shouldBe 0

            semantics.isChecked = false
            semantics.isChecked shouldBe false
            calls shouldBe 0
        }

        "isChecked setter with the same value is a no-op" {
            val semantics = SwitchSemantics()
            var calls = 0
            semantics.listener = { calls++ }
            semantics.isChecked = true
            semantics.isChecked = true
            calls shouldBe 0
        }

        "toggle flips the state and fires the listener once with the new value" {
            val semantics = SwitchSemantics()
            val seen = mutableListOf<Boolean>()
            semantics.listener = { seen.add(it) }
            semantics.toggle()
            semantics.isChecked shouldBe true
            seen shouldBe listOf(true)

            semantics.toggle()
            semantics.isChecked shouldBe false
            seen shouldBe listOf(true, false)
        }

        "setCheckedAnimated honours notify = true" {
            val semantics = SwitchSemantics()
            val seen = mutableListOf<Boolean>()
            semantics.listener = { seen.add(it) }
            semantics.setCheckedAnimated(value = true, notify = true)
            semantics.isChecked shouldBe true
            seen shouldBe listOf(true)
        }

        "setCheckedAnimated honours notify = false" {
            val semantics = SwitchSemantics()
            var calls = 0
            semantics.listener = { calls++ }
            semantics.setCheckedAnimated(value = true, notify = false)
            semantics.isChecked shouldBe true
            calls shouldBe 0
        }

        "setCheckedAnimated to the current value does nothing" {
            val semantics = SwitchSemantics()
            semantics.isChecked = true
            var calls = 0
            semantics.listener = { calls++ }
            semantics.setCheckedAnimated(value = true, notify = true)
            semantics.isChecked shouldBe true
            calls shouldBe 0
        }

        "syncFromCallback does not re-notify the listener" {
            val semantics = SwitchSemantics()
            var calls = 0
            semantics.listener = { calls++ }
            semantics.syncFromCallback(true)
            semantics.isChecked shouldBe true
            calls shouldBe 0

            semantics.syncFromCallback(false)
            semantics.isChecked shouldBe false
            calls shouldBe 0
        }

        "syncFromCallback after the listener already flipped does not double notify" {
            val semantics = SwitchSemantics()
            val seen = mutableListOf<Boolean>()
            // Mimic SettingsBuilder.toggle: tap, then reflect whatever the handler persisted.
            semantics.listener = { seen.add(it) }
            semantics.toggle()
            semantics.syncFromCallback(semantics.isChecked)
            seen shouldBe listOf(true)
        }

        "a handler can veto a change and the reflected state follows it" {
            val semantics = SwitchSemantics()
            var calls = 0
            // Handler that immediately reverts (e.g. "maximum reached" guard).
            semantics.listener = {
                calls++
                semantics.isChecked = false
            }
            semantics.toggle()
            calls shouldBe 1
            semantics.isChecked shouldBe false
        }

        "null listener is safe for every transition" {
            val semantics = SwitchSemantics()
            semantics.listener = null
            semantics.toggle()
            semantics.setCheckedAnimated(true, notify = true)
            semantics.syncFromCallback(false)
            semantics.isChecked shouldBe false
        }
    })

/**
 * Framework free mirror of [IosSwitchView]'s state machine.
 *
 * Kept in the test source set on purpose: the production view is the single source of truth for
 * behaviour, and putting a second copy in `main` would let them silently diverge.
 */
private class SwitchSemantics {
    var listener: ((Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        set(value) {
            // Same guard and no-notify contract as the view's `isChecked` setter.
            if (field == value) return
            field = value
        }

    fun toggle() {
        isChecked = !isChecked
        listener?.invoke(isChecked)
    }

    fun setCheckedAnimated(
        value: Boolean,
        notify: Boolean,
    ) {
        if (isChecked == value) return
        isChecked = value
        if (notify) listener?.invoke(value)
    }

    fun syncFromCallback(value: Boolean) {
        if (isChecked == value) return
        isChecked = value
    }
}
