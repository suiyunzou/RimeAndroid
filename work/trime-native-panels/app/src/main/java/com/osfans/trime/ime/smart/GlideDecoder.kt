// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Local geometric word decoder. No network, trace storage, or claimed trained neural model. */
class GlideDecoder(entries: Collection<Entry>) {
    data class Entry(val text: String, val letters: String, val frequency: Long = 0)
    constructor(words: List<String>) : this(words.map { Entry(it, it) } as Collection<Entry>)
    data class Point(val x: Float, val y: Float)
    data class KeyCenter(val letter: Char, val x: Float, val y: Float, val width: Float)
    data class Candidate(val word: String, val score: Float, val letters: String = word)
    private val lexicon = entries.asSequence().map { it.copy(letters = it.letters.trim().lowercase(java.util.Locale.ROOT)) }
        .filter { it.text.isNotBlank() && it.letters.length in 2..32 && it.letters.all { c -> c in 'a'..'z' } }
        .distinctBy { it.text to it.letters }.toList()
    private val byStart = lexicon.groupBy { it.letters.first() }

    /** Coordinates must all use the same keyboard-local pixel space. Call on a worker thread. */
    fun decode(trace: List<Point>, keys: List<KeyCenter>, limit: Int = 3): List<Candidate> {
        val direct = decodeSingle(trace, keys, limit)
        if (direct.firstOrNull()?.score?.let { it < .12f } == true || trace.size < 5) return direct
        val unit = keys.map { it.width }.filter { it > 0 && it.isFinite() }.sorted().let { if (it.isEmpty()) return direct else it[it.size / 2] }
        // Combine two dictionary words across a connecting segment. No sentence is
        // hard-coded. Simplification retains turns instead of interpreting every
        // key crossed by a straight line as a typed letter.
        val turns = simplify(trace, unit * .18f)
        if (turns.size !in 5..22) return direct
        val combined = ArrayList<Candidate>()
        val splits = (2 until turns.size - 2).toList().take(16)
        for (split in splits) {
            val left = decodeSingle(turns.take(split + 1), keys, 2)
            val right = decodeSingle(turns.drop(split + 1), keys, 2)
            for (a in left) for (b in right) {
                if (a.word.all { it.code < 128 } || b.word.all { it.code < 128 }) continue
                val score = (a.score + b.score) / 2f + .15f
                if (score < .65f) combined.add(Candidate(a.word + b.word, score, a.letters + b.letters))
            }
        }
        return (direct + combined).sortedBy { it.score }.distinctBy { it.word }.take(limit.coerceIn(0, 8))
    }

    private fun decodeSingle(trace: List<Point>, keys: List<KeyCenter>, limit: Int): List<Candidate> {
        if (trace.size < 3 || trace.any { !it.x.isFinite() || !it.y.isFinite() } || limit <= 0) return emptyList()
        val centers = keys.filter { it.width.isFinite() && it.width > 0 && it.x.isFinite() && it.y.isFinite() }
            .associateBy { it.letter.lowercaseChar() }
        if (centers.size < 20) return emptyList()
        val unit = centers.values.map { it.width }.sorted().let { it[it.size / 2] }
        val rawLength = length(trace)
        if (rawLength < unit * .7f) return emptyList()
        val input = resample(trace, SAMPLES)
        val first = trace.first(); val last = trace.last()
        val coarse = ArrayList<Pair<Entry, Pair<List<Point>, Float>>>()
        val potential = centers.values.asSequence().filter { distance(first, Point(it.x, it.y)) <= unit * 1.15f }
            .flatMap { byStart[it.letter.lowercaseChar()].orEmpty().asSequence() }
        for (entry in potential) {
            val word = entry.letters
            val a = centers[word.first()] ?: continue
            val b = centers[word.last()] ?: continue
            val endpoint = distance(first, Point(a.x, a.y)) + distance(last, Point(b.x, b.y))
            if (distance(first, Point(a.x, a.y)) > unit * 1.15f || distance(last, Point(b.x, b.y)) > unit * 1.15f) continue
            val path = ArrayList<Point>()
            for (char in word) {
                val key = centers[char] ?: break
                val point = Point(key.x, key.y)
                if (path.lastOrNull() != point) path.add(point)
            }
            if (path.size < 2) continue
            val pathLength = length(path)
            if (rawLength > pathLength * 2.5f + unit || pathLength > rawLength * 2.5f + unit) continue
            val template = resample(path, SAMPLES)
            var aligned = 0f
            for (i in input.indices) aligned += distance(input[i], template[i]) / unit
            val coarseScore = aligned / SAMPLES + .32f * endpoint / unit
            coarse.add(entry to (template to coarseScore))
        }
        return coarse.sortedWith(compareBy<Pair<Entry, Pair<List<Point>, Float>>> { it.second.second }.thenByDescending { it.first.frequency }).take(SHORTLIST).map { (entry, data) ->
            val template = data.first
            val endpoint = (distance(first, template.first()) + distance(last, template.last())) / unit
            val prior = .006f * kotlin.math.ln(1.0 + entry.frequency.coerceIn(0, 100000000)).toFloat()
            val score = dtw(input, template, unit) + .24f * endpoint + .08f * data.second - prior
            Candidate(entry.text, score, entry.letters)
        }.filter { it.score < 1.3f }.sortedWith(compareBy<Candidate> { it.score }.thenBy { it.word.length }.thenBy { it.word })
            .distinctBy { it.word }.take(limit.coerceAtMost(8))
    }

    private fun dtw(a: List<Point>, b: List<Point>, unit: Float): Float {
        var previous = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }.also { it[0] = 0f }
        for (i in 1..a.size) {
            val current = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }
            for (j in max(1, i - 8)..min(b.size, i + 8)) {
                val cost = distance(a[i - 1], b[j - 1]) / unit
                current[j] = cost + min(previous[j - 1], min(previous[j] + .025f, current[j - 1] + .025f))
            }
            previous = current
        }
        return previous[b.size] / max(a.size, b.size)
    }

    companion object {
        private const val SAMPLES = 32
        private const val SHORTLIST = 96
        private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        private fun length(points: List<Point>): Float = points.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat()
        private fun simplify(points: List<Point>, tolerance: Float): List<Point> {
            if (points.size < 3) return points
            val a = points.first(); val b = points.last()
            val dx = b.x - a.x; val dy = b.y - a.y
            val denominator = dx * dx + dy * dy
            var index = -1; var furthest = tolerance
            for (i in 1 until points.lastIndex) {
                val p = points[i]
                val t = if (denominator <= .0001f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / denominator).coerceIn(0f, 1f)
                val error = distance(p, Point(a.x + dx * t, a.y + dy * t))
                if (error > furthest) { furthest = error; index = i }
            }
            if (index < 0) return listOf(a, b)
            return simplify(points.subList(0, index + 1), tolerance).dropLast(1) + simplify(points.subList(index, points.size), tolerance)
        }
        internal fun resample(points: List<Point>, count: Int): List<Point> {
            if (points.isEmpty() || count < 2) return emptyList()
            val cumulative = FloatArray(points.size)
            for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
            val total = cumulative.last()
            if (total <= .0001f) return List(count) { points.first() }
            var segment = 1
            return List(count) { i ->
                val target = total * i / (count - 1)
                while (segment < points.lastIndex && cumulative[segment] < target) segment++
                val span = cumulative[segment] - cumulative[segment - 1]
                val t = if (abs(span) < .0001f) 0f else ((target - cumulative[segment - 1]) / span).coerceIn(0f, 1f)
                val a = points[segment - 1]; val b = points[segment]
                Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
    }
}
