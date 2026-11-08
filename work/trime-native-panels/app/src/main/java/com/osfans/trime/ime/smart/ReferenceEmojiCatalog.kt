// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.io.Reader
import java.util.Locale

object ReferenceEmojiCatalog {
    data class Group(val id: String, val label: String, val icon: String)
    val groups = listOf(Group("recent","最近","◷"),Group("faces","笑脸","☺"),Group("people","手势与人物","♧"),
        Group("nature","动物与自然","♧"),Group("food","食物","♜"),Group("activity","活动","⚽"),
        Group("travel","交通与地点","♧"),Group("objects","物品","♧"),Group("symbols","符号","#"),Group("flags","旗帜","⚑"))
    val emoticons = listOf("^_^", "^o^", ">_<", "T_T", "-_-", "o_O", "(^^)") + SymbolVariants.categories.getValue("颜文字")
    fun read(reader: Reader): Map<String,List<String>> {
        val result = groups.associate { it.id to mutableListOf<String>() }
        reader.buffered().useLines { lines -> lines.filter { !it.startsWith('#') && it.isNotBlank() }.forEach {
            val fields = it.split('\t',limit=2)
            require(fields.size == 2 && fields[0] in result && fields[1].isNotEmpty()) { "Invalid emoji inventory" }
            result.getValue(fields[0]).add(fields[1])
        } }
        // Unicode regional-indicator sequences are data, rendered by the device's own emoji font.
        Locale.getISOCountries().sorted().forEach { country ->
            result.getValue("flags").add(country.map { String(Character.toChars(0x1f1e6 + it.code-'A'.code)) }.joinToString(""))
        }
        result.getValue("people").addAll(listOf("👨‍👩‍👧‍👦","👩‍💻","👨‍💻"))
        result.getValue("flags").add("🏳️‍🌈")
        return result.mapValues { it.value.distinct() }
    }
    fun variants(value: String): List<String> {
        if(value.isEmpty())return emptyList()
        val cp = value.codePointAt(0)
        val supports = cp in 0x1f446..0x1f450 || cp in 0x1f466..0x1f478 || cp in 0x1f590..0x1f596 ||
            cp in 0x1f918..0x1f91f || cp in 0x1faf0..0x1faf8 || cp in setOf(0x270a,0x270b,0x270c,0x270d,0x261d,0x1f44d,0x1f44e,0x1f44f,0x1f4aa,0x1f64c,0x1f64f)
        if (!supports || value.codePointCount(0,value.length)>2) return emptyList()
        val base=String(Character.toChars(cp))
        return listOf(base) + (0x1f3fb..0x1f3ff).map { base + String(Character.toChars(it)) }
    }
}
