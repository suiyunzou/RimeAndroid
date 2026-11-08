import com.osfans.trime.ime.smart.GlideDecoder
import com.osfans.trime.ime.smart.GlideGestureSession
import java.io.File

fun main(args: Array<String>) {
    val keys = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, letters ->
        letters.mapIndexed { column, c -> GlideDecoder.KeyCenter(c, column * 40f + row * 10f, row * 48f, 40f) }
    }
    fun trace(word: String) = word.map { c -> keys.first { it.letter == c }.let { GlideDecoder.Point(it.x, it.y) } }
    val entries = listOf("english.tsv", "chinese.tsv").flatMap { name -> File(args[0], name).readLines().map { line ->
        val parts = line.split('\t'); GlideDecoder.Entry(parts[0], parts[1], parts[2].toLong())
    } }
    val engine = GlideDecoder(entries)
    for ((code, wanted) in listOf("hello" to "hello", "world" to "world", "keyboard" to "keyboard", "nihao" to "你好", "shijie" to "世界", "xiexie" to "谢谢", "nihaoshijie" to "你好世界")) {
        val start = System.nanoTime()
        val results = engine.decode(trace(code), keys, 5)
        println("$code: $results elapsedMs=${(System.nanoTime() - start)/1000000}")
        check(results.any { it.word.equals(wanted, true) }) { "Missing $wanted" }
    }
    check(engine.decode(List(5) { GlideDecoder.Point(1f, 1f) }, keys).isEmpty())
    val gesture = GlideGestureSession(1f)
    gesture.begin(10f, 10f)
    check(gesture.finish(11f, 11f).kind == GlideGestureSession.Kind.TAP)
    check(gesture.finish(11f, 11f).kind == GlideGestureSession.Kind.CANCEL)
    gesture.begin(10f, 10f); gesture.move(50f, 10f); gesture.move(100f, 40f)
    check(gesture.finish(120f, 40f).kind == GlideGestureSession.Kind.GLIDE)
    check(gesture.finish(120f, 40f).kind == GlideGestureSession.Kind.CANCEL)
    println("PASS real 110000-entry vocabulary, Chinese/English and one-release invariants")
}
