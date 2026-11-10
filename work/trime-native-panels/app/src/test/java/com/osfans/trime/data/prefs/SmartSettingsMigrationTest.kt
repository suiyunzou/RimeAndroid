/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import android.content.SharedPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [SharedPreferences] fake. The unit test JVM has no Android runtime,
 * so the facade never touches a real preference file.
 *
 * The Android contract the facade relies on: values are visible immediately
 * after `commit()`, `get()` returns null for a missing key, and a type mismatch
 * throws `ClassCastException`.
 */
private class FakeSharedPreferences(
    private val values: MutableMap<String, Any?> = linkedMapOf(),
) : SharedPreferences {
    /** Every key in the exact order it was written; used to prove nothing moved. */
    val writeCount = AtomicInteger(0)

    /** Set to true to make every commit() report failure, like a full disk. */
    var failCommits = false

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as String? ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as Int? ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as Long? ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as Float? ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as Boolean? ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values, failCommits, writeCount)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    /** Snapshot of every stored value, like the real implementation's defensive copy. */
    fun snapshot(): Map<String, Any?> = LinkedHashMap(values)
}

private class FakeEditor(
    private val values: MutableMap<String, Any?>,
    private val failCommits: Boolean,
    private val writeCount: AtomicInteger,
) : SharedPreferences.Editor {
    private val puts = linkedMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearAll = false

    override fun putString(
        key: String,
        value: String?,
    ) = apply { puts[key] = value }

    override fun putStringSet(
        key: String,
        value: MutableSet<String>?,
    ) = apply { puts[key] = value }

    override fun putInt(
        key: String,
        value: Int,
    ) = apply { puts[key] = value }

    override fun putLong(
        key: String,
        value: Long,
    ) = apply { puts[key] = value }

    override fun putFloat(
        key: String,
        value: Float,
    ) = apply { puts[key] = value }

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) = apply { puts[key] = value }

    override fun remove(key: String) = apply { removals += key }

    override fun clear() = apply { clearAll = true }

    override fun commit(): Boolean {
        if (failCommits) return false
        if (clearAll) values.clear()
        removals.forEach(values::remove)
        puts.forEach { (key, value) -> values[key] = value }
        writeCount.incrementAndGet()
        return true
    }

    override fun apply() {
        commit()
    }
}

private const val UI_MODE = "ui_mode"
private const val LEGACY_THEME = "reference_settings_theme"

/**
 * The `key_*_down` bindings actually present on the K80 device, verified against
 * its `smart_input` snapshot. There are 16 of them and no `key_*_up` entries.
 *
 * Declared before [deviceSnapshot] because Kotlin initializes top-level
 * properties in file order.
 */
private val expectedDownBindings: Map<String, String> =
    linkedMapOf(
        "key_a_down" to "text:@",
        "key_b_down" to "text:,",
        "key_c_down" to "action:copy_all",
        "key_d_down" to "text:;",
        "key_g_down" to "text:)",
        "key_h_down" to "text:\u201c",
        "key_j_down" to "text:\u201d",
        "key_k_down" to "text:+",
        "key_l_down" to "text:-",
        "key_m_down" to "text:?",
        "key_n_down" to "text:\u3002",
        "key_s_down" to "text:_",
        "key_v_down" to "action:paste",
        "key_x_down" to "action:cut",
        "key_z_down" to "text:~",
        "key_backspace_down" to "action:clear_all",
    )

/** The K80 device snapshot: pure reference-page keys that must survive untouched. */
private val deviceSnapshot: Map<String, Any> =
    linkedMapOf<String, Any>(
        "wheel_slots" to "time,clipboard,calculator,symbols,traditional,settings",
        "wheel_origin_placement" to "space_center",
        "wheel_motion_style" to "soft",
        "wheel_icon_visibility" to "nearby",
        "reference_keyboard_height" to 216,
        "candidate_bold" to false,
        "reference_key_bold" to false,
        "flick_direction" to "down",
        "glide_enabled" to false,
        "wheel_style" to "band",
        "origin_glow" to true,
        "layout" to "t9",
        "correction_style" to "squiggle",
    ) + expectedDownBindings

private class Fixture(
    nativeValues: Map<String, Any?> = mapOf(UI_MODE to "DARK"),
    referenceValues: Map<String, Any?> = deviceSnapshot,
) {
    val native = FakeSharedPreferences(nativeValues.toMutableMap())
    val reference = FakeSharedPreferences(referenceValues.toMutableMap())
    var now = 1_700_000_000_000L
    val facade = SmartSettingsFacade(native, reference) { now }
}

class SmartSettingsMigrationTest : FunSpec({
    // ---------------------------------------------------------------------
    // 1. No conflict: both stores agree
    // ---------------------------------------------------------------------
    test("no conflict: agreeing duplicate is kept and nothing is written") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "dark"),
        )
        val record = f.facade.migrate()

        record.conflicts.shouldBeEmpty()
        record.failures.shouldBeEmpty()
        record.isClean shouldBe true
        f.native.writeCount.get().toInt() shouldBe 0
        f.reference.writeCount.get().toInt() shouldBe 0
        f.reference.getString(LEGACY_THEME, null) shouldBe "dark"
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.DARK
    }

    test("no conflict: lowercase and uppercase spellings count as agreeing") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "LIGHT"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        f.facade.migrate().conflicts.shouldBeEmpty()
        f.reference.getString(LEGACY_THEME, null) shouldBe "light"
    }

    test("no conflict: auto agrees with auto") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "AUTO"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "auto"),
        )
        f.facade.migrate().conflicts.shouldBeEmpty()
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.AUTO
    }

    // ---------------------------------------------------------------------
    // 2. Conflict: smart_input says light, ui_mode says dark
    // ---------------------------------------------------------------------
    test("conflict: ui_mode wins and the stale duplicate is removed") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        val record = f.facade.migrate()

        record.conflicts.size shouldBe 1
        val entry = record.conflicts.single()
        entry.nativeKey shouldBe UI_MODE
        entry.nativeValue shouldBe "DARK"
        entry.staleValue shouldBe "light"
        entry.legacyKeyRemoved shouldBe LEGACY_THEME
        record.failures.shouldBeEmpty()

        // The authoritative value is untouched ...
        f.native.getString(UI_MODE, null) shouldBe "DARK"
        // ... and the losing copy is gone, so they can no longer drift.
        f.reference.contains(LEGACY_THEME) shouldBe false
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.DARK
        f.facade.pendingConflicts().shouldBeEmpty()
    }

    test("conflict: light in the native store beats dark on the page") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "LIGHT"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "dark"),
        )
        f.facade.migrate().conflicts.size shouldBe 1
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.LIGHT
        f.reference.contains(LEGACY_THEME) shouldBe false
    }

    test("pendingConflicts reports a drift before it is migrated") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        val pending = f.facade.pendingConflicts()
        pending.size shouldBe 1
        pending.single() shouldBe "ui_mode=DARK 与 reference_settings_theme=light 不一致"
        // Query only: nothing was written.
        f.reference.contains(LEGACY_THEME) shouldBe true
        f.reference.writeCount.get().toInt() shouldBe 0
    }

    // ---------------------------------------------------------------------
    // 3. Only the legacy key is present
    // ---------------------------------------------------------------------
    test("only the legacy key present: value already matches the native default and is kept") {
        val f = Fixture(
            nativeValues = emptyMap(),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "auto"),
        )
        f.native.contains(UI_MODE) shouldBe false
        // ui_mode is absent, so the authoritative default AUTO already agrees.
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.AUTO
        val record = f.facade.migrate()
        record.conflicts.shouldBeEmpty()
        record.isClean shouldBe true
        f.native.contains(UI_MODE) shouldBe false
        f.native.writeCount.get().toInt() shouldBe 0
    }

    test("only the legacy key present and it disagrees with the default: default wins") {
        val f = Fixture(
            nativeValues = emptyMap(),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "dark"),
        )
        val record = f.facade.migrate()
        record.conflicts.size shouldBe 1
        record.conflicts.single().nativeValue shouldBe "AUTO"
        record.conflicts.single().staleValue shouldBe "dark"
        f.reference.contains(LEGACY_THEME) shouldBe false
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.AUTO
    }

    // ---------------------------------------------------------------------
    // 4. Only the native key is present
    // ---------------------------------------------------------------------
    test("only the native key present: nothing to migrate and nothing written") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot,
        )
        val record = f.facade.migrate()
        record.isClean shouldBe true
        record.entries.size shouldBe 1
        record.entries.single().nativeValue shouldBe "DARK"
        f.native.writeCount.get().toInt() shouldBe 0
        f.reference.writeCount.get().toInt() shouldBe 0
        f.reference.snapshot().keys.toSet() shouldBe deviceSnapshot.keys
        // The K80 branch: an absent duplicate must stay absent, never be created.
        f.reference.snapshot().containsKey("reference_settings_theme") shouldBe false
    }

    test("neither key present: the native default applies") {
        val f = Fixture(nativeValues = emptyMap(), referenceValues = emptyMap())
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.AUTO
        f.facade.migrate().isClean shouldBe true
    }

    // ---------------------------------------------------------------------
    // 5. Idempotence
    // ---------------------------------------------------------------------
    test("running the migration twice is idempotent") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        val first = f.facade.migrate()
        first.conflicts.size shouldBe 1
        val afterFirst = f.reference.snapshot()
        // The first pass legitimately revokes the duplicate; reset the counters so the
        // assertions below measure the SECOND pass only.
        f.native.writeCount.set(0)
        f.reference.writeCount.set(0)

        val second = f.facade.migrate()
        second.conflicts.shouldBeEmpty()
        second.failures.shouldBeEmpty()
        second.isClean shouldBe true
        // A clean second pass issues no commits at all.
        f.native.writeCount.get().toInt() shouldBe 0
        f.reference.writeCount.get().toInt() shouldBe 0
        f.reference.snapshot() shouldBe afterFirst
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.DARK

        // A third pass is equally quiet.
        f.facade.migrate().isClean shouldBe true
    }

    // ---------------------------------------------------------------------
    // 6. Untouched user state (K80 device snapshot)
    // ---------------------------------------------------------------------
    test("all pure reference-page keys survive byte-for-byte after a conflicting migration") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        f.facade.migrate()

        val surviving = f.reference.snapshot()
        surviving shouldBe deviceSnapshot
        surviving.keys.toSet() shouldBe deviceSnapshot.keys
        surviving["wheel_slots"] shouldBe "time,clipboard,calculator,symbols,traditional,settings"
        surviving["wheel_origin_placement"] shouldBe "space_center"
        surviving["wheel_motion_style"] shouldBe "soft"
        surviving["wheel_icon_visibility"] shouldBe "nearby"
        // The map holds a BOXED Integer; comparing it to a primitive literal with
        // shouldBe never matches, so use an int-typed comparison.
        // The map holds a BOXED Integer; `as Int` unboxes it so shouldBe compares
        // primitives on both sides.
        (surviving["reference_keyboard_height"] as Int) shouldBe 216
        surviving["candidate_bold"] shouldBe false
        surviving["reference_key_bold"] shouldBe false
        surviving["flick_direction"] shouldBe "down"
        surviving["glide_enabled"] shouldBe false
    }

    test("every key_*_down binding from the device survives untouched") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        f.facade.migrate()

        val downKeys = f.reference.snapshot().filterKeys { it.startsWith("key_") && it.endsWith("_down") }
        // The K80 snapshot carries 16 down-bindings and no up-bindings.
        downKeys.size shouldBe 16
        expectedDownBindings.forEach { (key, value) -> downKeys[key] shouldBe value }
        f.reference.snapshot().keys.none { it.startsWith("key_") && it.endsWith("_up") } shouldBe true
        f.reference.getString("key_a_down", null) shouldBe "text:@"
        f.reference.getString("key_backspace_down", null) shouldBe "action:clear_all"
    }

    test("preserved keys keep their original types") {
        val f = Fixture(referenceValues = deviceSnapshot + (LEGACY_THEME to "dark"))
        f.facade.migrate()
        f.reference.getInt("reference_keyboard_height", -1) shouldBe 216
        f.reference.getBoolean("candidate_bold", true) shouldBe false
        f.reference.getBoolean("glide_enabled", true) shouldBe false
    }

    // ---------------------------------------------------------------------
    // 7. Per-key independence and the record
    // ---------------------------------------------------------------------
    test("a failing removal does not abort the migration and is recorded") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        f.reference.failCommits = true

        val record = f.facade.migrate()
        record.failures.size shouldBe 1
        record.failures.single().key shouldBe LEGACY_THEME
        record.isClean shouldBe false
        // The conflict is still reported, with the truth about the kept key ...
        record.conflicts.size shouldBe 1
        record.conflicts.single().legacyKeyKept shouldBe LEGACY_THEME
        record.conflicts.single().legacyKeyRemoved shouldBe null
        // ... and the user's data is still intact.
        f.reference.snapshot() shouldBe deviceSnapshot + (LEGACY_THEME to "light")

        // Once the store accepts writes the migration completes.
        f.reference.failCommits = false
        f.facade.migrate().conflicts.size shouldBe 1
        f.reference.contains(LEGACY_THEME) shouldBe false
        f.facade.migrate().isClean shouldBe true
    }

    test("an unparseable duplicate is reported instead of silently deleted") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "purple"),
        )
        val record = f.facade.migrate()
        record.failures.size shouldBe 1
        record.failures.single().message shouldBe "重复键格式无法识别，未删除"
        f.reference.getString(LEGACY_THEME, null) shouldBe "purple"
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.DARK
    }

    test("the migration record is readable and shows the conflict") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "DARK"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        val record = f.facade.migrate()

        record.timestamp shouldBe 1_700_000_000_000L
        record.summary() shouldBe "已将 深浅色 收敛到原生设置。"
        record.describe().single() shouldBe
            "深浅色：采用 ui_mode=DARK（reference_settings_theme 原为「light」，已删除重复键）"

        // No duplicate key, but the fixture still seeds ui_mode=DARK (the Fixture default),
        // so the clean record must report DARK rather than the AUTO fallback.
        val clean = Fixture(referenceValues = deviceSnapshot).facade.migrate()
        clean.summary() shouldBe "重复设置无需迁移。"
        clean.describe().single() shouldBe "深浅色：采用 ui_mode=DARK（无重复键，无需迁移）"

        // With neither store populated the authoritative default is AUTO.
        val empty = Fixture(nativeValues = emptyMap(), referenceValues = emptyMap()).facade.migrate()
        empty.describe().single() shouldBe "深浅色：采用 ui_mode=AUTO（无重复键，无需迁移）"
    }

    // ---------------------------------------------------------------------
    // 8. Authoritative write path
    // ---------------------------------------------------------------------
    test("setUiMode writes the native key and revokes the duplicate") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "LIGHT"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        val record = f.facade.setUiMode(AppPrefs.Advanced.UiMode.DARK)

        record.failures.shouldBeEmpty()
        f.native.getString(UI_MODE, null) shouldBe "DARK"
        f.reference.contains(LEGACY_THEME) shouldBe false
        f.facade.uiMode() shouldBe AppPrefs.Advanced.UiMode.DARK
        // Everything else is untouched.
        f.reference.snapshot() shouldBe deviceSnapshot
    }

    test("setUiMode is idempotent") {
        val f = Fixture(nativeValues = mapOf(UI_MODE to "LIGHT"), referenceValues = deviceSnapshot)
        f.facade.setUiMode(AppPrefs.Advanced.UiMode.AUTO)
        val writes = f.native.writeCount.get().toInt()
        f.facade.setUiMode(AppPrefs.Advanced.UiMode.AUTO)
        f.native.getString(UI_MODE, null) shouldBe "AUTO"
        // It still writes the authoritative key; convergence stays stable.
        f.native.writeCount.get().toInt() shouldBe writes + 1
        f.reference.contains(LEGACY_THEME) shouldBe false
    }

    test("after setUiMode neither store can disagree") {
        val f = Fixture(
            nativeValues = mapOf(UI_MODE to "LIGHT"),
            referenceValues = deviceSnapshot + (LEGACY_THEME to "light"),
        )
        repeat(3) { AppPrefs.Advanced.UiMode.entries.forEach { f.facade.setUiMode(it) } }
        f.facade.pendingConflicts().shouldBeEmpty()
        f.facade.migrate().isClean shouldBe true
    }

    // ---------------------------------------------------------------------
    // 9. Parsing helpers
    // ---------------------------------------------------------------------
    test("parseUiMode accepts both spellings and rejects junk") {
        SmartSettingsFacade.parseUiMode("DARK") shouldBe AppPrefs.Advanced.UiMode.DARK
        SmartSettingsFacade.parseUiMode("dark") shouldBe AppPrefs.Advanced.UiMode.DARK
        SmartSettingsFacade.parseUiMode(" Light ") shouldBe AppPrefs.Advanced.UiMode.LIGHT
        SmartSettingsFacade.parseUiMode("auto") shouldBe AppPrefs.Advanced.UiMode.AUTO
        SmartSettingsFacade.parseUiMode("system") shouldBe AppPrefs.Advanced.UiMode.AUTO
        SmartSettingsFacade.parseUiMode("purple") shouldBe null
        SmartSettingsFacade.parseUiMode("") shouldBe null
    }

    test("the reference preference file name matches SmartInputPrefs") {
        SmartSettingsFacade.REFERENCE_PREFERENCES_NAME shouldBe "smart_input"
    }

    test("the pure page keys are exactly the ones the facade must not touch") {
        // Guard against accidentally growing the migration to real user state.
        val protectedKeys = setOf(
            "wheel_slots",
            "wheel_origin_placement",
            "wheel_motion_style",
            "wheel_icon_visibility",
            "reference_keyboard_height",
            "candidate_bold",
            "reference_key_bold",
            "flick_direction",
            "glide_enabled",
        )
        deviceSnapshot.keys.toList() shouldContainAll expectedDownBindings.keys.toList().plus(protectedKeys)
        deviceSnapshot.keys.any { it == LEGACY_THEME } shouldBe false
    }
})
