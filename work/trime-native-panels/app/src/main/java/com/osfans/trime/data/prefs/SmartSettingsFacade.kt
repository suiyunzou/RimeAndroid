/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Single convergence point for the settings that the native preference screen
 * and the reference settings page both used to own.
 *
 * Two stores held the same handful of settings:
 *
 *  - the **native** store (`AppPrefs`, `local.trime.panels_preferences.xml`),
 *    which is what the keyboard and `MainActivity` actually read, and
 *  - the **reference page** store (`SharedPreferences("smart_input")`, owned by
 *    `SmartInputPrefs`), which the reference settings page also wrote.
 *
 * For every overlapping setting this facade declares exactly one authority. The
 * losing copy is revoked: its key is removed from the reference page store so
 * the two values can no longer drift apart.
 *
 * The migration is:
 *
 *  - **authority-driven** — the native value is never overwritten, the stale
 *    duplicate is simply dropped;
 *  - **idempotent** — once the duplicate key is gone there is nothing left to
 *    resolve, so a second run writes and removes nothing;
 *  - **per-key independent** — every rule runs in its own [runCatching], so an
 *    unreadable or unwritable value for one setting never blocks the others;
 *  - **lossless for pure reference-page keys** — only the duplicated keys are
 *    touched. `wheel_slots`, `reference_keyboard_height`, `candidate_bold`,
 *    `flick_direction`, the 18 `key_<letter>_down` bindings and every other key
 *    that exists only in `smart_input` are left byte-for-byte intact.
 *
 * Store access is injected through [SharedPreferences], so the logic is fully
 * testable on the JVM with an in-memory fake and needs no Android runtime.
 *
 * Threading: [migrate] only reads its inputs and applies a couple of removals,
 * and is safe to call more than once; do it during app start on a background
 * thread if you care about the file read.
 */
class SmartSettingsFacade(
    private val native: SharedPreferences,
    private val reference: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * One overlapping setting: where its authority lives, which duplicate key
     * the migration may revoke, and how it is labelled in a report.
     */
    private enum class Rule(
        val nativeKey: String,
        val legacyKey: String?,
        val label: String,
    ) {
        UI_MODE(
            nativeKey = AppPrefs.Advanced.UI_MODE,
            legacyKey = SmartInputPrefsKeys.REFERENCE_SETTINGS_THEME,
            label = "深浅色",
        ),
    }

    /**
     * What happened to one overlapping setting during a single [migrate] call.
     *
     * @property category the overlapping setting, e.g. 深浅色
     * @property nativeKey the authoritative key, e.g. `ui_mode`
     * @property nativeValue the value the app will actually use
     * @property staleValue the losing value the duplicate held, if any
     * @property legacyKeyRemoved the duplicate key, when it was revoked
     * @property legacyKeyKept the duplicate key, when it had to stay behind
     *   because revoking it failed; the failure itself is in
     *   [MigrationRecord.failures]
     * @property reason human readable explanation of the decision
     */
    data class Entry(
        val category: String,
        val nativeKey: String,
        val nativeValue: String,
        val staleValue: String? = null,
        val legacyKeyRemoved: String? = null,
        val legacyKeyKept: String? = null,
        val reason: String,
    ) {
        /** True when the two stores actually disagreed on this setting. */
        val isConflict: Boolean get() = legacyKeyRemoved != null || legacyKeyKept != null

        /** Readable line a settings page can show to the user. */
        fun describe(): String {
            val origin =
                if (isConflict) {
                    "${legacyKeyRemoved ?: legacyKeyKept} 原为「${staleValue.orEmpty()}」"
                } else {
                    "无重复键"
                }
            val resolution =
                when {
                    legacyKeyRemoved != null -> "已删除重复键"
                    legacyKeyKept != null -> "删除失败，重复键暂时保留"
                    else -> "无需迁移"
                }
            return "$category：采用 $nativeKey=$nativeValue（$origin，$resolution）"
        }
    }

    /**
     * A step that could not complete. Failures are collected instead of thrown
     * so one bad key cannot abort the migration of the others.
     */
    data class Failure(
        val category: String,
        val key: String,
        val message: String?,
    )

    /**
     * The readable outcome of [migrate]: which settings conflicted, what the app
     * now uses, which duplicates were revoked, and what failed.
     */
    data class MigrationRecord(
        val timestamp: Long,
        val entries: List<Entry>,
        val failures: List<Failure>,
    ) {
        /** Entries whose duplicate key held a different value. */
        val conflicts: List<Entry> get() = entries.filter { it.isConflict }

        /** True when running [migrate] again would change nothing. */
        val isClean: Boolean get() = conflicts.isEmpty() && failures.isEmpty()

        /** Lines for a settings page, one per overlapping setting. */
        fun describe(): List<String> =
            entries.map { it.describe() } +
                failures.map { "${it.category}：迁移 ${it.key} 时失败（${it.message ?: "未知错误"}）" }

        /** One sentence for a status row. */
        fun summary(): String {
            val categories = conflicts.joinToString("、") { it.category }
            val head = if (conflicts.isEmpty()) "重复设置无需迁移" else "已将 $categories 收敛到原生设置"
            val tail = if (failures.isEmpty()) "" else "，另有 ${failures.size} 项迁移失败"
            return "$head$tail。"
        }

        companion object {
            val EMPTY = MigrationRecord(0L, emptyList(), emptyList())
        }
    }

    // ---------------------------------------------------------------------
    // Queries — no side effects, safe to call from any settings page
    // ---------------------------------------------------------------------

    /**
     * The authoritative UI mode: the native `ui_mode` value, never the
     * `smart_input` copy. Unknown or absent values fall back to the same
     * [AppPrefs.Advanced.UiMode.AUTO] default the native preference declares.
     */
    fun uiMode(): AppPrefs.Advanced.UiMode =
        nativeUiModeRaw()?.let { parseUiMode(it) } ?: UI_MODE_DEFAULT

    /**
     * Descriptions of the disagreements that are still unresolved, without
     * modifying any store. Call [migrate] to actually converge them.
     */
    fun pendingConflicts(): List<String> =
        Rule.entries.mapNotNull { rule ->
            val stale = runCatching { staleValueOf(rule) }.getOrNull() ?: return@mapNotNull null
            "${rule.nativeKey}=${nativeValueOf(rule)} 与 ${rule.legacyKey}=$stale 不一致"
        }

    // ---------------------------------------------------------------------
    // Mutation
    // ---------------------------------------------------------------------

    /**
     * Convergence itself.
     *
     * Each overlapping setting is examined independently. On a disagreement the
     * duplicate key in the reference page store is removed and the authoritative
     * native value is left untouched. Pure reference-page keys are never read,
     * written or removed.
     *
     * Calling it a second time is a no-op.
     */
    fun migrate(): MigrationRecord {
        val entries = mutableListOf<Entry>()
        val failures = mutableListOf<Failure>()
        Rule.entries.forEach { rule ->
            // Per-key isolation: a failure of one rule never aborts the rest.
            runCatching { handle(rule, entries, failures) }
                .onFailure { failures += failure(rule, rule.legacyKey ?: rule.nativeKey, it) }
        }
        return MigrationRecord(now(), entries.toList(), failures.toList())
    }

    /**
     * Authoritative write path for the dark/light setting.
     *
     * Writes the native key only and revokes the duplicate, so a settings page
     * that goes through the facade can no longer create a disagreement. The
     * caller still applies the mode to the running app
     * (`AppCompatDelegate.setDefaultNightMode`).
     */
    fun setUiMode(mode: AppPrefs.Advanced.UiMode): MigrationRecord {
        val failures = mutableListOf<Failure>()
        val rule = Rule.UI_MODE
        runCatching {
            val ok = native.edit().putString(rule.nativeKey, mode.name).commit()
            if (!ok) failures += Failure(rule.label, rule.nativeKey, "commit() 返回 false")
        }.onFailure { failures += failure(rule, rule.nativeKey, it) }
        revokeLegacy(rule, failures)
        return MigrationRecord(now(), emptyList(), failures.toList())
    }

    private fun handle(
        rule: Rule,
        entries: MutableList<Entry>,
        failures: MutableList<Failure>,
    ) {
        val nativeValue = nativeValueOf(rule)
        val legacyKey = rule.legacyKey
        val stale = staleValueOf(rule)
        if (stale == null) {
            if (legacyKey != null && isUnusableDuplicate(legacyKey)) {
                // Present but unreadable: keep it for now and report it, so a
                // hand-edited value is never silently destroyed.
                failures += Failure(rule.label, legacyKey, "重复键格式无法识别，未删除")
            }
            entries += Entry(
                category = rule.label,
                nativeKey = rule.nativeKey,
                nativeValue = nativeValue,
                reason = "只有一个来源，无需迁移",
            )
            return
        }
        // Disagreement: the native value wins and the duplicate is revoked.
        revokeLegacy(rule, failures)
        val kept = failures.any { it.key == legacyKey }
        entries += Entry(
            category = rule.label,
            nativeKey = rule.nativeKey,
            nativeValue = nativeValue,
            staleValue = stale,
            legacyKeyRemoved = legacyKey?.takeUnless { kept },
            legacyKeyKept = legacyKey?.takeIf { kept },
            reason = "以 ${rule.nativeKey} 为准",
        )
    }

    /**
     * Raw stored value of [key], whatever its type, or null when absent.
     *
     * Uses the untyped `get(key, defValue)` view so a value stored under the
     * wrong type is visible instead of throwing; the typed getters would.
     */
    private fun rawValue(
        store: SharedPreferences,
        key: String,
    ): Any? = runCatching { store.all[key] }.getOrNull()

    /** True when the duplicate key exists but holds a value we cannot classify. */
    private fun isUnusableDuplicate(legacyKey: String): Boolean {
        val raw = rawValue(reference, legacyKey) ?: return false
        return raw !is String || parseUiMode(raw) == null
    }

    private fun revokeLegacy(
        rule: Rule,
        failures: MutableList<Failure>,
    ) {
        val legacyKey = rule.legacyKey ?: return
        runCatching {
            if (!reference.contains(legacyKey)) return@runCatching
            val ok = reference.edit().remove(legacyKey).commit()
            if (!ok) failures += Failure(rule.label, legacyKey, "commit() 返回 false")
        }.onFailure { failures += failure(rule, legacyKey, it) }
    }

    // ---------------------------------------------------------------------
    // Value helpers
    // ---------------------------------------------------------------------

    private fun nativeUiModeRaw(): String? =
        rawValue(native, Rule.UI_MODE.nativeKey)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun nativeValueOf(rule: Rule): String =
        when (rule) {
            Rule.UI_MODE -> uiMode().name
        }

    /**
     * The losing value held by the duplicate key, or null when the duplicate is
     * absent, blank, unparseable, or already agrees with the authority — in
     * which case there is nothing to revoke and the key is left alone.
     */
    private fun staleValueOf(rule: Rule): String? {
        val legacyKey = rule.legacyKey ?: return null
        val raw = rawValue(reference, legacyKey) as? String ?: return null
        val trimmed = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val canonical = parseUiMode(trimmed) ?: return null
        return trimmed.takeIf { !canonical.name.equals(nativeValueOf(rule), ignoreCase = true) }
    }

    private fun now(): Long = runCatching { clock() }.getOrDefault(0L)

    private fun failure(
        rule: Rule,
        key: String,
        error: Throwable,
    ) = Failure(rule.label, key, error.message ?: error.javaClass.simpleName)

    companion object {
        /** Reference page preference file; the store [SmartInputPrefs] wraps. */
        const val REFERENCE_PREFERENCES_NAME = "smart_input"

        /** Same default the native `ui_mode` preference declares. */
        private val UI_MODE_DEFAULT = AppPrefs.Advanced.UiMode.AUTO

        /**
         * Parses the native spelling (`DARK`) and the reference page's lowercase
         * spelling (`dark`). Returns null for anything else so an odd stored
         * value is reported rather than silently guessed at.
         */
        fun parseUiMode(raw: String): AppPrefs.Advanced.UiMode? =
            when (raw.trim().lowercase()) {
                "auto", "system", "follow_system" -> AppPrefs.Advanced.UiMode.AUTO
                "light" -> AppPrefs.Advanced.UiMode.LIGHT
                "dark" -> AppPrefs.Advanced.UiMode.DARK
                else -> null
            }

        /**
         * Build a facade backed by the real stores.
         *
         * @param context any context; its application context is used to reach
         *   the `smart_input` file
         * @param native the native store — the same `SharedPreferences` instance
         *   [AppPrefs] was initialized with
         */
        fun from(
            context: Context,
            native: SharedPreferences,
        ): SmartSettingsFacade {
            val app = context.applicationContext ?: context
            return SmartSettingsFacade(
                native = native,
                reference = app.getSharedPreferences(REFERENCE_PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
        }

        /** Same as [from], using the already initialized [AppPrefs] singleton. */
        fun fromDefault(context: Context): SmartSettingsFacade =
            from(context, AppPrefs.defaultInstance().advanced.uiMode.sharedPreferences)
    }
}

/** Keys the reference page store shares, or used to share, with the native store. */
internal object SmartInputPrefsKeys {
    /** Legacy duplicate of [AppPrefs.Advanced.UI_MODE]; revoked by the migration. */
    const val REFERENCE_SETTINGS_THEME = "reference_settings_theme"
}
