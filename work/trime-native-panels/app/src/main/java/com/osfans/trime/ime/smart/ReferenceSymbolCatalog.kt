// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/**
 * Observed menu structure, separate from four-row layout and scrolling.
 * The local Unicode inventory is not claimed to be a complete video transcription.
 * Symbols are whole strings; supplementary and joined characters stay intact.
 */
object ReferenceSymbolCatalog {
    data class Group(val id: String, val label: String, val pages: List<Page>)
    data class Page(val id: String, val label: String, val symbols: List<String>)

    const val defaultGroupId = "punctuation"

    private fun points(first: Int, last: Int): List<String> =
        (first..last).map { String(Character.toChars(it)) }

    private fun characters(value: String): List<String> =
        value.codePoints().toArray().map { String(Character.toChars(it)) }

    private fun page(id: String, label: String, symbols: List<String>): Page =
        Page(id, label, symbols.distinct())

    private fun single(id: String, label: String, symbols: List<String>): Group =
        Group(id, label, listOf(page(id, label, symbols)))

    private fun paired(uppercase: List<String>, lowercase: List<String>): List<String> {
        require(uppercase.size == lowercase.size)
        return uppercase.zip(lowercase).flatMap { (upper, lower) -> listOf(upper, lower) }
    }

    private fun ordinaryGreek(): List<String> {
        val capitals = (0x391..0x3a9).filterNot { it == 0x3a2 }
        val pairs = capitals.flatMap { listOf(String(Character.toChars(it)), String(Character.toChars(it + 0x20))) }
        return pairs + characters("ςϐϑϕϖϰϱϵϴϜϝ")
    }

    private fun styledGreek(base: Int): List<String> {
        // Both runs contain an extra slot at offset 17: capital theta symbol versus final sigma.
        // Neither is a case pair. Pair the 24 ordinary letters, then retain all variants at the end.
        val capitals = (0..24).filterNot { it == 17 }.map { String(Character.toChars(base + it)) }
        val lowercase = (0..24).filterNot { it == 17 }.map { String(Character.toChars(base + 26 + it)) }
        val letters = paired(capitals, lowercase)
        return letters + points(base, base + 57).filterNot { it in letters }
    }

    private val punctuationPages = listOf(
        // Column-major first screen transcribed from 06:47. The low-resolution
        // reversed-option, thin postal, circle and macron glyphs use the closest
        // Unicode forms; the video does not expose their exact code points.
        Page("typographic", "排版符号", characters("☑⌘⌥⎇⌴⌅⌫⌦⌀⌖⌗⏏⌜⌝⌞⌟〒●○¯〄々〆〒〓〠〳〴〵〶〻〼") +
            characters("§¶†‡※＊⁂⁎⁕•◦‣⁃·‧…‥⋯©®™℠℗№℅℆‰‱°′″‴‵‶‷")),
        page("lines", "横线竖线", characters("‐‑‒–—―﹘﹣－_＿﹏|｜¦‖∣∥/／\\＼╱╲╳")),
        page("punctuation", "标点", characters("，。？！、；：…·“”‘’﹐﹑﹒﹔﹕﹖﹗,.!?;:'\"@#%&*+=~～") + listOf("\u0060")),
        page("paired", "成对括号", characters("()（）[]［］{}｛｝<>〈〉《》「」『』【】〔〕〖〗〘〙〚〛“”‘’")),
    )

    private val circledNumbers = listOf("⓪") + points(0x2460, 0x2473) +
        points(0x3251, 0x325f) + points(0x32b1, 0x32bf)

    private val numericPages = listOf(
        page("circled", "圈数字", circledNumbers),
        page("parenthesized", "括号数字", points(0x2474, 0x2487)),
        page("full_stop", "点号数字", listOf(String(Character.toChars(0x1f100))) + points(0x2488, 0x249b)),
        page("chinese", "中文数字", characters("〇零一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟萬億兩廿卅卌")),
        page("superscript", "上标", characters("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ")),
        page("subscript", "下标", characters("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎")),
        page("fullwidth", "全角数字", points(0xff10, 0xff19)),
        page("mathematical_digits", "字体数字", points(0x1d7ce, 0x1d7ff)),
        page("roman", "罗马数字", paired(points(0x2160, 0x216f), points(0x2170, 0x217f)) + characters("ↀↁↂↃↄↅↆↇↈ")),
        page("numerals", "数字与数词", characters("0123456789٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹०१२३४५६७८९一二三四五六七八九十")),
        page("fractions", "分数", characters("¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞⅟↉+×÷∑∏")),
    )

    private val greekPages = listOf(
        page("normal", "希腊字母", ordinaryGreek()),
        page("bold", "希腊粗体字母", styledGreek(0x1d6a8)),
        page("italic", "希腊斜体字母", styledGreek(0x1d6e2)),
    )

    private val shapePages = listOf(
        page("circles", "圆形", characters("○◌◍◎●◐◑◒◓◔◕◖◗◉◯◦◴◵◶◷")),
        page("stars", "星形", characters("★☆✦✧✩✪✫✬✭✮✯✰✡✢✣✤✥✱✲✳✴✵✶✷✸✹✺✻✼✽✾✿")),
        page("squares", "方形", characters("■□▢▣▤▥▦▧▨▩▪▫▭▮▯◻◼◽◾")),
        page("triangles", "三角", characters("▲△▴▵▶▷▸▹►▻▼▽▾▿◀◁◂◃◄◅◢◣◤◥◬◭◮")),
        page("diamonds", "菱形", characters("◆◇◈⬖⬗⬘⬙⬠⬡⬢⬣")),
        // The reference truncates this label to “方块填…”. Keep the inferred full label editable here.
        page("blocks", "方块填充", points(0x2580, 0x259f)),
    )

    private val hanPages = listOf(
        page("radicals", "康熙部首", points(0x2f00, 0x2fd5)),
        page("enclosed_han", "圈括汉字", points(0x3220, 0x3243) + points(0x3280, 0x32b0)),
        page("components", "偏旁部件", points(0x2e80, 0x2e99) + points(0x2e9b, 0x2ef3)),
        // A bounded useful inventory rather than tens of thousands of unseen code points.
        page("han", "汉字", characters("〇一丨丶丿乙亅二亠人入八冂冖冫几凵刀刂力勹匕匚匸十卜卩厂厶又口囗土士夂夊夕大女子宀寸小尢尸屮山巛川工己巾干幺广廴廾弋弓彐彡彳心忄戈戶手扌支攴攵文斗斤方无日曰月木欠止歹殳毋比毛氏气水氵火灬爪爫父爻爿片牙牛犬犭王玉瓜瓦甘生用田疋疒癶白皮皿目矛矢石示礻禸禾穴立竹米糸纟缶网罒羊羽老耂而耒耳聿肉臣自至臼舌舛舟艮色艸艹虍虫血行衣衤西覀見见角言讠谷豆豕豸貝贝赤走足身車车辛辰辵辶邑阝酉釆里金钅長长門门阜隶隹雨青非面革韋韦韭音頁页風风飛飞食饣首香馬马骨高髟鬥鬯鬲鬼魚鱼鳥鸟鹵卤鹿麥麦麻黃黄黍黑黹黽黾鼎鼓鼠鼻齊齐齒齿龍龙龜龟龠")),
    )

    /** These twelve roots follow the observed menu, in order. */
    val groups: List<Group> = listOf(
        Group("punctuation", "标点", punctuationPages),
        Group("numbers", "数字", numericPages),
        single("math", "数学", characters("+−×÷=≠≈≃≅≡≢<>≤≥≪≫±∓∞∑∏∐√∛∜∫∬∭∮∯∰∂∇∆∈∉∋∌∅∩∪⊂⊃⊆⊇⊄⊅⊈⊉∀∃∄∧∨¬⊕⊖⊗⊘⊙⊥∥∠∡∢∴∵∝∶∷∎⌈⌉⌊⌋%‰‱")),
        single("arrows", "箭头", points(0x2190, 0x21ff) + characters("⟵⟶⟷⟸⟹⟺⟻⟼⟽⟾⟿⬅⬆⬇➡")),
        Group("shapes", "图形", shapePages),
        single("latin", "拉丁", characters(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ" +
                "ĀāĂăĄąĆćĈĉĊċČčĎďĐđĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥĦħĨĩĪīĬĭĮįİıĲĳĴĵĶķĹĺĻļĽľŁłŃńŅņŇňŊŋŌōŎŏŐőŒœŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťŦŧŨũŪūŬŭŮůŰűŲųŴŵŶŷŸŹźŻżŽž",
        )),
        // Keep unconfirmed submenu names out of the UI; these groups currently use one page.
        single("fonts", "字体", points(0x1d400, 0x1d433) +
            points(0x1d434, 0x1d467).map { if (it.codePointAt(0) == 0x1d455) "ℎ" else it } +
            points(0x1d468, 0x1d49b) + points(0x1d5a0, 0x1d5d3) + points(0x1d670, 0x1d6a3)),
        Group("greek", "希腊", greekPages),
        single("writing", "文字", points(0x3041, 0x3096) + points(0x30a1, 0x30fa)),
        Group("han", "汉字", hanPages),
        single("calendar", "历法", characters("甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥") +
            points(0x3358, 0x3370) + points(0x32c0, 0x32cb) + points(0x33e0, 0x33fe)),
        single("signs", "标志娱乐", characters("©®™℠℗№℅℆℃℉°☀☁☂☃☄★☆☇☈☉☊☋☌☍☎☏☐☑☒☕☘☙☚☛☜☝☞☟☠☢☣☤☥☦☧☨☩☪☫☬☭☮☯☸☹☺☻☼☽☾☿♀♁♂♃♄♅♆♇♈♉♊♋♌♍♎♏♐♑♒♓♔♕♖♗♘♙♚♛♜♝♞♟♠♡♢♣♤♥♦♧♪♫♩♬♭♮♯✓✔✕✖✗✘")),
    )

    /** Existing emoji/kaomoji entry points stay available without changing the observed root menu. */
    val extraGroups: List<Group> = listOf(
        single("emoticons", "颜文字", SymbolVariants.categories.getValue("颜文字")),
        single("emoji", "Emoji", SymbolVariants.categories.getValue("Emoji")),
    )

    fun group(idOrLabel: String): Group? =
        (groups + extraGroups).firstOrNull { it.id == idOrLabel || it.label == idOrLabel }
}
