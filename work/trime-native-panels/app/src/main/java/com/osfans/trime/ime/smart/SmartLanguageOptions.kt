// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import com.osfans.trime.core.RimeApi

/** Runtime options in the shipped Wanxiang schema; no destructive schema replacement. */
object SmartLanguageOptions {
    suspend fun apply(api: RimeApi, prefs: SmartInputPrefs) {
        api.setRuntimeOption("emoji", prefs.candidateEmoji)
        api.setRuntimeOption("smart_correction", prefs.correction)
    }

    suspend fun toggleTraditional(api: RimeApi): Boolean {
        // Wanxiang names the OpenCC option traditionalization. Luna's simplifier
        // instead exposes simplification with the inverse meaning.
        return if (api.schemaCached.schemaId.startsWith("wanxiang")) {
            val enabled = !api.getRuntimeOption("traditionalization")
            api.setRuntimeOption("traditionalization", enabled)
            enabled
        } else {
            val simplified = !api.getRuntimeOption("simplification")
            api.setRuntimeOption("simplification", simplified)
            !simplified
        }
    }
}
