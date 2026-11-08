// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** A presentation-only edit alignment. Display offsets always map back to original input. */
object CorrectionStyle {
    val labels = linkedMapOf("underline" to "原输入 · 下划线", "highlight" to "建议拼音 · 高亮",
        "plain" to "原输入 · 无标记", "strike_background" to "A · 斜线与底色",
        "strike_color" to "B · 斜线与字色", "inline" to "C · 行内替换")
    enum class Kind { UNDERLINE, STRIKE, ERROR, CORRECT, BACKGROUND }
    data class Mark(val start: Int, val end: Int, val kind: Kind)
    data class Plan(val text: String, val marks: List<Mark>, val originalOffsets: List<Int>) {
        fun byteOffset(displayOffset: Int, original: String): Int {
            var at=originalOffsets[displayOffset.coerceIn(0, originalOffsets.lastIndex)].coerceIn(0, original.length)
            if(at>0 && at<original.length && original[at].isLowSurrogate() && original[at-1].isHighSurrogate())at--
            return original.substring(0,at).toByteArray(Charsets.UTF_8).size
        }
    }
    private data class UnitEdit(val source: Int?, val target: Int?, val same: Boolean)
    fun normalize(value: String) = CorrectionPresentation.foldPinyin(value).replace(" ", "").replace("'", "")
    /** Rime's soft caret is presentation only; never put it into raw-input offsets. */
    fun matchesPreedit(original: String, preedit: String) = normalize(original) == normalize(preedit.replace("‸", ""))
    fun plan(original: String, corrected: String, style: String): Plan {
        val rawPositions = original.indices.filter { original[it] != '\'' && original[it] != ' ' }
        val a = normalize(original); val b = normalize(corrected)
        if (a.length > 80 || b.length > 160 || a.any { it !in 'a'..'z' } || b.any { it !in 'a'..'z' })
            return Plan(original, emptyList(), (0..original.length).toList())
        val d = Array(a.length+1) { IntArray(b.length+1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = minOf(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+(if(a[i-1]==b[j-1])0 else 1))
        var i=a.length; var j=b.length; val edits=mutableListOf<UnitEdit>()
        while(i>0 || j>0) when {
            i>0 && j>0 && d[i][j]==d[i-1][j-1]+(if(a[i-1]==b[j-1])0 else 1) -> { edits.add(UnitEdit(i-1,j-1,a[i-1]==b[j-1]));i--;j-- }
            i>0 && d[i][j]==d[i-1][j]+1 -> { edits.add(UnitEdit(i-1,null,false));i-- }
            else -> { edits.add(UnitEdit(null,j-1,false));j-- }
        }
        edits.reverse()
        if(style !in labels || style in setOf("underline","plain")) {
            val marks=if(style=="plain" || rawPositions.isEmpty()) emptyList() else edits.mapIndexedNotNull { index, edit ->
                if(edit.same) null else {
                    val source=edit.source ?: edits.take(index).lastOrNull { it.source!=null }?.source ?: 0
                    val at=rawPositions[source]; Mark(at,at+1,Kind.UNDERLINE)
                }
            }.distinct()
            return Plan(original,marks,(0..original.length).toList())
        }
        val out=StringBuilder(); val marks=mutableListOf<Mark>(); val offsets=mutableListOf<Int>()
        var sourceCursor=0
        fun append(value:String, at:Int, vararg kinds:Kind) {
            val start=out.length;out.append(value);repeat(value.length){offsets.add(at)}
            kinds.forEach { if(value.isNotEmpty()) marks.add(Mark(start,out.length,it)) }
        }
        for(edit in edits) {
            val position=edit.source?.let{rawPositions[it]} ?: sourceCursor
            if(edit.same) append(b[edit.target!!].toString(),position)
            else if(style=="highlight") edit.target?.let { append(b[it].toString(),position,Kind.BACKGROUND,Kind.CORRECT) }
            else {
                val old=edit.source?.let{a[it].toString()}.orEmpty(); val next=edit.target?.let{b[it].toString()}.orEmpty()
                when(style) {
                    "strike_background" -> { append(old,position,Kind.STRIKE,Kind.ERROR);append(next,position,Kind.BACKGROUND,Kind.CORRECT) }
                    "strike_color" -> { append(old,position,Kind.STRIKE,Kind.ERROR);append(next,position,Kind.CORRECT) }
                    "inline" -> append(old+"→"+next,position,Kind.BACKGROUND)
                }
            }
            if(edit.source!=null)sourceCursor=rawPositions[edit.source]+1
        }
        offsets.add(original.length)
        return Plan(out.toString(),marks,offsets)
    }
}
