package com.osfans.trime.ime.smart

/** Explicit syllable selection keeps ambiguous nine-key spelling predictable. */
object T9PinyinEngine {
    private val syllables = ("a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
        "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo " +
        "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo " +
        "e ei en eng er fa fan fang fei fen feng fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
        "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
        "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
        "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve " +
        "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
        "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve " +
        "o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
        "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru ruan rui run ruo " +
        "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo " +
        "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
        "wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
        "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo").split(' ')
    private val mapping = mapOf('a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9')
    fun encode(pinyin: String): String = pinyin.lowercase().map { mapping[it] ?: error("拼音字母无效") }.joinToString("")
    fun candidates(digits: String): List<String> {
        if (digits.isEmpty() || digits.length > 6 || digits.any { it !in '2'..'9' }) return emptyList()
        return syllables.filter { encode(it) == digits }
    }
}
