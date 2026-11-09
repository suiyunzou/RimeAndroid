package com.osfans.trime.ime.smart

import android.system.Os
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import java.io.File
import java.util.UUID

object ModelSchemaIntegration {
    suspend fun ensureConfigured() {
        val name = "model-schema-installer"
        val session = RimeDaemon.createSession(name)
        try {
            val schema = session.runOnReady { selectedSchemaId() }
            require(schema.matches(Regex("[A-Za-z0-9_-]+")))
            val root = DataManager.userDataDir
            val custom = File(root, "$schema.custom.yaml")
            val before = if (custom.isFile) custom.readText().replace("\r\n", "\n") else "patch:\n"
            if (before.contains("wanxiang-lts-zh-hans")) return
            require(Regex("(?m)^patch:[ \\t]*$").containsMatchIn(before)) { "当前方案不支持自动接入模型，原配置未改动" }
            require(!before.contains("grammar/language:")) { "当前方案已配置其他模型，请先移除原模型配置" }
            val after = before.replaceFirst(Regex("(?m)^patch:[ \\t]*$"), "patch:\n  grammar/language: wanxiang-lts-zh-hans" + if (before.contains("translator/contextual_suggestions:")) "" else "\n  translator/contextual_suggestions: true")
            val backup = File(root, "$schema.custom.before-model-manager.yaml")
            if (custom.exists() && !backup.exists()) custom.copyTo(backup)
            val temp = File(root, "model-config-${UUID.randomUUID()}.tmp")
            try { temp.outputStream().use { it.write(after.toByteArray()); it.fd.sync() }; Os.rename(temp.absolutePath, custom.absolutePath) }
            finally { temp.delete() }
            try { session.runOnReady { deploy() } }
            catch (error: Exception) {
                val rollback = File(root, "model-config-rollback-${UUID.randomUUID()}.tmp")
                try { rollback.outputStream().use { it.write(before.toByteArray()); it.fd.sync() }; Os.rename(rollback.absolutePath, custom.absolutePath) }
                finally { rollback.delete() }
                throw error
            }
        } finally { RimeDaemon.destroySession(name) }
    }
}
