// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Explicit public downloads only: no keyboard text, cookies or identifiers are sent. */
object OnlineDictionarySource {
    data class Source(val id: String, val title: String, val description: String)
    val sources = listOf(
        Source("mingren", "万象 · 名人", "约 0.64 MiB · 人名词条"),
        Source("yiren", "万象 · 艺人", "约 0.40 MiB · 艺人姓名"),
        Source("duoyin", "万象 · 多音字词", "约 0.13 MiB · 多音词读音"),
    )
    const val attribution = "作者：amzxyz / 万象项目贡献者\n来源：github.com/amzxyz/rime-wanxiang（wanxiang 分支）\n许可：CC BY 4.0 · creativecommons.org/licenses/by/4.0/\n下载后转换为本地 TSV，保留词语、拼音与权重。"
    fun download(source: Source, progress: (Int, Int) -> Unit): LocalDictionaryParser.Result {
        require(source in sources)
        val connection = URL("https://raw.githubusercontent.com/amzxyz/rime-wanxiang/wanxiang/dicts/${source.id}.dict.yaml").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "下载失败（HTTP ${connection.responseCode}），已有词库未改动" }
            val expected = connection.contentLength
            require(expected <= LocalDictionaryParser.MAX_BYTES) { "远程词库超过 8 MB 限制" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= LocalDictionaryParser.MAX_BYTES) { "远程词库超过 8 MB 限制" }
                    output.write(buffer, 0, count); progress(output.size(), expected)
                }
            }
            require(expected < 0 || output.size() == expected) { "下载不完整，已有词库未改动" }
            return parseWanxiang(output.toByteArray())
        } finally { connection.disconnect() }
    }
    fun parseWanxiang(bytes: ByteArray): LocalDictionaryParser.Result {
        require(bytes.size <= LocalDictionaryParser.MAX_BYTES)
        // Wanxiang's optional fourth column is an annotation, never executable YAML.
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(text.lineSequence().any { it.trim() == "..." }) { "下载内容不是 Rime 词库" }
        val normalized = text.lineSequence().map { line ->
            if (line.contains('\t') && !line.startsWith('#')) line.split('\t').take(3).joinToString("\t") else line
        }.joinToString("\n")
        val result = LocalDictionaryParser.parse(normalized.toByteArray())
        require(result.invalid == 0) { "远程词库含 ${result.invalid} 条无效记录，未安装" }
        return result
    }
}
