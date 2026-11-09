// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.res.AssetManager
import android.system.Os
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import java.io.File
import java.util.UUID

/** Adds one translator to the active schema, preserving all existing user settings. */
object LocalDictionaryIntegration {
    suspend fun ensureInstalled(assets: AssetManager) {
        val sessionName = "local-dictionary-installer"
        val session = RimeDaemon.createSession(sessionName)
        try {
            val schema = session.runOnReady { selectedSchemaId() }
            require(schema.matches(Regex("[A-Za-z0-9_-]+"))) { "当前方案名称无效" }
            val root = DataManager.userDataDir
            val luaDir = File(root, "lua").apply { check(isDirectory || mkdirs()) }
            val lua = File(luaDir, "clean_local_dictionary.lua")
            var changed = false
            if (!lua.exists()) { atomicWrite(lua, assets.open("local-dictionary/clean_local_dictionary.lua").use { it.readBytes() }); changed = true }
            val custom = File(root, "$schema.custom.yaml")
            val previous = if (custom.exists()) custom.readText() else "patch:\n"
            val updated = addTranslator(previous)
            if (updated != previous || !custom.exists()) {
                if (custom.exists()) {
                    val backup = File(root, "$schema.custom.before-local-dictionary.yaml")
                    if (!backup.exists()) atomicWrite(backup, custom.readBytes())
                }
                atomicWrite(custom, updated.toByteArray()); changed = true
            }
            if (changed) {
                try { session.runOnReady { deploy() } }
                catch (error: Exception) {
                    // Preserve the prior patch even when native compilation fails. The new Lua
                    // is harmless without a translator entry and can be reused on the next try.
                    if (updated != previous) atomicWrite(custom, previous.toByteArray())
                    throw error
                }
            }
        } finally { RimeDaemon.destroySession(sessionName) }
    }
    internal fun addTranslator(original: String): String {
        val config = original.replace("\r\n", "\n")
        if (config.contains("lua_translator@*clean_local_dictionary")) return config
        val list = Regex("(?m)^( +)engine/translators(?:/\\+)?[ \\t]*:[ \\t]*$").find(config)
        if (list != null) {
            val following = config.substring(list.range.last + 1).trimStart('\r', '\n')
            val indent = Regex("^( +)-").find(following)?.groupValues?.get(1) ?: (list.groupValues[1] + "  ")
            return config.replaceRange(list.range, "${list.value}\n${indent}- lua_translator@*clean_local_dictionary")
        }
        require(Regex("(?m)^patch:[ \\t]*$").containsMatchIn(config)) { "自定义方案格式不支持自动接入；原配置未修改" }
        require(!config.contains("engine/translators")) { "自定义翻译器格式不支持自动接入；原配置未修改" }
        return config.replaceFirst(Regex("(?m)^patch:[ \\t]*$"), "patch:\n  engine/translators/+:\n    - lua_translator@*clean_local_dictionary")
    }
    private fun atomicWrite(target: File, bytes: ByteArray) {
        require(target.canonicalFile.parentFile == target.parentFile.canonicalFile)
        val temporary = File(target.parentFile, "dictionary-install-${UUID.randomUUID()}.tmp")
        try { temporary.outputStream().use { it.write(bytes); it.fd.sync() }; Os.rename(temporary.absolutePath, target.absolutePath) }
        finally { temporary.delete() }
    }
}
