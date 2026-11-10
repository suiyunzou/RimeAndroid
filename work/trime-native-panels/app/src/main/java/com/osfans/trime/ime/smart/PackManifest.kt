// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * 公开词包的远端清单（纯数据、只读）。
 *
 * 数据格式由发布方维护，解析严格：缺少必填字段即报错；未知字段一律忽略，
 * 以便日后先发布字段、后升级客户端。清单只描述"能下载到什么"，不含任何用户数据。
 */
@Serializable
data class PackManifest(
    val version: Int,
    val packs: List<PackEntry>,
) {
    /** 公开词包条目。文件名由客户端按 [id] 生成，绝不允许清单指定落地路径。 */
    @Serializable
    data class PackEntry(
        val id: String,
        val title: String,
        /** 该词包自身的版本号，单调递增；越大越新。 */
        val version: Int,
        val url: String,
        val bytes: Long,
        val sha256: String,
        val minAppVersion: Int,
    )

    /** 单个包体允许的最大字节数：与本地词库导入共用 8 MB 上限。 */
    fun pick(id: String): PackEntry? = packs.firstOrNull { it.id == id }

    companion object {
        const val MAX_BYTES = LocalDictionaryParser.MAX_BYTES
        const val MAX_PACK_BYTES = LocalDictionaryParser.MAX_BYTES
        const val MAX_PACKS = 64
        const val MAX_MANIFEST_BYTES = 256 * 1024

        private val json = Json {
            // 未知字段忽略：清单先行发布新字段不会让老客户端整份作废。
            ignoreUnknownKeys = true
            // 缺失的必填字段必须报错，不能静默补默认值。
            explicitNulls = false
            isLenient = false
        }

        /** 校验 [id] 是否可安全用作文件名（仅 ASCII 小写、数字、连字符、下划线）。 */
        fun isValidId(id: String): Boolean = id.length in 1..40 && id.matches(Regex("[a-z0-9][a-z0-9_-]*"))

        /** 校验 sha256 是否为 64 位十六进制串。 */
        fun isValidSha256(sha256: String): Boolean = sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * 解析清单文本。任何结构问题都抛出 [IllegalArgumentException]，消息可直接展示给用户。
         * 校验内容包括：UTF-8 合法性、大小上限、每个包的 id/url/sha256/bytes 合法性。
         */
        fun parse(bytes: ByteArray): PackManifest {
            require(bytes.size <= MAX_MANIFEST_BYTES) { "清单文件超过 256 KB，已忽略" }
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (_: Exception) {
                throw IllegalArgumentException("清单不是有效的 UTF-8 文本")
            }
            val manifest = try {
                json.decodeFromString(PackManifest.serializer(), text)
            } catch (missing: kotlinx.serialization.MissingFieldException) {
                // 缺必填字段：绝不能用默认值兜底，直接报错。
                throw IllegalArgumentException("清单缺少必填字段：${missing.missingFields.joinToString("、")}")
            } catch (error: Exception) {
                throw IllegalArgumentException("清单格式无效：${error.message?.take(120) ?: "无法解析"}")
            }
            manifest.validate()
            return manifest
        }

        fun parse(text: String): PackManifest = parse(text.toByteArray(Charsets.UTF_8))

        /** 序列化回 JSON，仅用于测试/调试，不作为落盘格式。 */
        fun encode(manifest: PackManifest): String = json.encodeToString(PackManifest.serializer(), manifest)
    }

    fun validate() {
        require(version > 0) { "清单版本号无效" }
        require(packs.size <= MAX_PACKS) { "清单中词包超过 $MAX_PACKS 个，已忽略" }
        val seen = HashSet<String>()
        packs.forEach { pack ->
            require(isValidId(pack.id)) { "词包 id“${pack.id.take(40)}”不合法" }
            require(seen.add(pack.id)) { "清单中词包 id 重复：${pack.id}" }
            require(pack.title.isNotBlank() && pack.title.length <= 60) { "词包 ${pack.id} 的名称无效" }
            require(pack.url.startsWith("https://")) { "词包 ${pack.id} 的下载地址必须是 https" }
            require(pack.url.length <= 512) { "词包 ${pack.id} 的下载地址过长" }
            require(isValidSha256(pack.sha256)) { "词包 ${pack.id} 的校验值无效" }
            require(pack.bytes in 1..MAX_PACK_BYTES) { "词包 ${pack.id} 超出 8 MB 限制" }
            require(pack.version > 0) { "词包 ${pack.id} 的版本号无效" }
            require(pack.minAppVersion >= 0) { "词包 ${pack.id} 的最低版本号无效" }
        }
    }
}
