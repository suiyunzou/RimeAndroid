// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.io.Reader

/** Plans bounded suffix-only queries. The caller's normal candidate stream is never changed. */
object TailCompletion {
    const val SCHEMA = "rime_ice"
    const val PAUSE_MS = 55L
    private const val MAX_TRIALS = 2
    private const val MIN_FREQUENCY = 1_500
    private const val ONE_LETTER_MIN_FREQUENCY = 10_000
    private val inputPattern = Regex("[a-z]{4,24}")
    private val preeditPattern = Regex("[a-z ]+")
    private val syllables = """
        a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
        ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo
        da dai dan dang dao de dei deng di dia dian diao die ding diu dong dou du duan dui dun duo
        e ei en eng er fa fan fang fei fen feng fo fou fu
        ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
        ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
        ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
        ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
        la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve
        ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
        na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve
        o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
        qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
        ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
        sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo
        ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
        wa wai wan wang wei wen weng wo wu
        xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
        ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
        za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo
    """.trim().split(Regex("\\s+")).toSet()
    private val extensions = buildMap<String, List<String>> {
        for (syllable in syllables) {
            for (missing in 1..3) {
                val size = syllable.length - missing
                if (size >= 1 && (missing <= 2 || size == 1)) {
                    val tail = syllable.take(size)
                    put(tail, get(tail).orEmpty() + syllable)
                }
            }
        }
    }

    data class Trial(val spelling: String, val syllable: String)
    data class Suggestion(val text: String, val input: String, val spelling: String)

    fun isInputEligible(input: String, preedit: String?, caret: Int): Boolean {
        if (caret != input.length || !inputPattern.matches(input)) return false
        // librime's soft_cursor option decorates the preedit, not the raw spelling.
        val spelling = preedit?.removeSuffix("\u2038") ?: return false
        return preeditPattern.matches(spelling) && spelling.replace(" ", "") == input
    }

    // The bundled public glide table supplies only a prior for which spellings to query.
    // Actual output and its reading come from the active Rime schema.
    @JvmStatic
    fun readPrior(reader: Reader): Map<String, Int> = reader.buffered().useLines { lines ->
        buildMap {
            for (line in lines) {
                val fields = line.split('\t', limit = 3)
                val score = fields.getOrNull(2)?.toIntOrNull() ?: continue
                if (score < MIN_FREQUENCY || fields[0].length < 2) continue
                put(fields[1], maxOf(get(fields[1]) ?: 0, score))
            }
        }
    }

    fun trials(input: String, prior: Map<String, Int>): List<Trial> {
        // A complete single syllable is not a useful signal for a missing tail.
        if (!inputPattern.matches(input) || input in syllables) return emptyList()
        val reachable = BooleanArray(input.length + 1)
        reachable[0] = true
        for (start in input.indices) {
            if (!reachable[start]) continue
            for (end in (start + 1)..minOf(input.length, start + 6)) {
                if (input.substring(start, end) in syllables) reachable[end] = true
            }
        }
        return ((input.length - 5).coerceAtLeast(2)..input.length - 1)
            .filter { reachable[it] }
            .flatMap { start ->
                val threshold = if (start == input.lastIndex) ONE_LETTER_MIN_FREQUENCY else MIN_FREQUENCY
                val head = input.take(start)
                extensions[input.substring(start)].orEmpty().mapNotNull { syllable ->
                    val spelling = head + syllable
                    if ((prior[spelling] ?: 0) >= threshold) Trial(spelling, syllable) else null
                }
            }
            .sortedWith(
                compareByDescending<Trial> { prior[it.spelling] ?: 0 }
                    .thenBy { it.spelling.length }
                    .thenByDescending { it.syllable.length }
                    .thenBy { it.spelling },
            )
            .distinctBy { it.spelling }
            .take(MAX_TRIALS)
    }

    @JvmStatic
    fun find(
        input: String,
        preedit: String?,
        caret: Int,
        prior: Map<String, Int>,
        primary: Array<String>,
        traditional: Boolean,
    ): Suggestion? {
        if (!isInputEligible(input, preedit, caret)) return null
        if (primary.any { it.equals(input, ignoreCase = true) }) return null
        val trials = trials(input, prior)
        if (trials.isEmpty()) return null
        return TailCompletionNative.withQuietSession {
            for (trial in trials) {
                val text = TailCompletionNative.query(trial.spelling, trial.syllable, traditional) ?: continue
                if (text in primary) continue
                if (text.length >= 2 && text.all { it in '\u3400'..'\u9fff' }) {
                    return@withQuietSession Suggestion(text, input, trial.spelling)
                }
            }
            null
        }
    }
}
