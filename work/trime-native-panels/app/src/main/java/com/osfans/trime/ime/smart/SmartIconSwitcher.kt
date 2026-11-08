package com.osfans.trime.ime.smart

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object SmartIconSwitcher {
    private val aliases = mapOf("default" to "com.osfans.trime.MainLauncherAlias", "blue" to "com.osfans.trime.BlueLauncherAlias", "green" to "com.osfans.trime.GreenLauncherAlias")
    fun select(context: Context, id: String): Boolean {
        val target = aliases[id] ?: return false
        val manager = context.packageManager
        return runCatching {
            // Enable destination first: a failed alias lookup must never hide every launcher icon.
            manager.setComponentEnabledSetting(ComponentName(context.packageName, target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            aliases.values.filter { it != target }.forEach { name ->
                manager.setComponentEnabledSetting(ComponentName(context.packageName, name), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            true
        }.getOrDefault(false)
    }
}
