package com.liquidaddon.abcurses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity of the Kotlin port against the Python reference, on recordings produced by it.
 *
 * The recordings are JSON files next to this test (see `tools/abcurses_dump.py` in the repository
 * root): one per scenario, holding the scenario's inputs and the reference's output samples. The
 * comparison is on the **positions in counts**, sample by sample, which is the only comparison that
 * matters — everything between the inputs and the output (the MT19937 stream, the draw order, the
 * float32 boundaries of the motor, the float64 decoder, the mode machine of the hybrid policy) has
 * to be identical for two streams to agree for four hundred milliseconds, let alone for a hundred
 * decisions.
 *
 * Where floating point drags the two apart by a last bit, the drift is measured rather than
 * asserted away: the tolerance below is a count fraction, not a count, and the test reports the
 * worst sample so a regression has a number attached to it.
 */
class AbcParityTest {

    @Test
    fun `the stream lands where the reference stream landed`() {
        val cases = AbcReference.load()
        assertTrue(cases.isNotEmpty(), "no reference case next to the test")
        for (case in cases) {
            val weights = AbcWeights.load(WeightsStream.open(), verify = true)
            val runtime = AbcRuntime(weights, case.seed, case.startX, case.startY)
            var worst = 0.0
            var at = 0L
            var nextTarget = 0
            // The generator fed a target every 50 ms and advanced in 5 ms steps, which is what the
            // scenario records; replaying the same schedule is what makes the timestamps line up.
            while (at < case.totalUs) {
                if (at % 50_000L == 0L) {
                    val index = minOf(nextTarget, case.targets.size - 1)
                    runtime.updateTarget(case.targets[index][0], case.targets[index][1], at)
                    nextTarget++
                }
                val step = minOf(5_000L, case.totalUs - at)
                at += step
                val frame = runtime.advance(at)
                for (i in 0 until frame.size) {
                    val expected = case.points[case.pointsRead + i]
                    worst = maxOf(worst, kotlin.math.abs(frame.x(i) - expected[0]))
                    worst = maxOf(worst, kotlin.math.abs(frame.y(i) - expected[1]))
                }
                case.pointsRead += frame.size
            }
            assertEquals(case.points.size, case.pointsRead, "${case.name}: sample count")
            assertTrue(
                worst < TOLERANCE,
                "${case.name}: worst deviation %.6f counts (tolerance %.6f)".format(worst, TOLERANCE),
            )
        }
    }

    /** The archive the reference was produced from loads, hashes and shapes up. */
    @Test
    fun `the bundled archive is the frozen one`() {
        val weights = AbcWeights.load(WeightsStream.open(), verify = true)
        assertEquals(128 * 21, weights.velocityBasis.size)
        assertEquals(128, weights.carryVelocity.size)
    }

    /** A different archive must be refused, not silently used. */
    @Test
    fun `a corrupted archive is refused by its digest`() {
        val bytes = WeightsStream.read()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        val failure = runCatching { AbcWeights.load(bytes.inputStream(), verify = true) }.exceptionOrNull()
        assertTrue(failure is AbcAssetException, "expected AbcAssetException, got $failure")
    }

    private companion object {
        /**
         * Two streams that agree on every discrete decision (which head, which mode, when a brake
         * starts) can still differ in the last bits of the float64 integration. This is one part in
         * a hundred of a mouse count, i.e. nothing a player or a server can see.
         */
        const val TOLERANCE = 0.01
    }
}

/** Reads the reference recordings out of the test resources. */
internal object AbcReference {

    class Case(
        val name: String,
        val seed: Long,
        val startX: Double,
        val startY: Double,
        val totalUs: Long,
        val targets: List<DoubleArray>,
        val points: List<DoubleArray>,
    ) {
        var pointsRead = 0
    }

    fun load(): List<Case> {
        val text = AbcReference::class.java.getResourceAsStream("/abcurses/reference.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: return emptyList()
        return parse(text)
    }

    /**
     * A deliberately small reader for the two shapes the recording uses: objects, arrays, numbers
     * and strings. Pulling a JSON library into a test would be another dependency to keep in step
     * with the client's classpath; this is thirty lines and cannot drift.
     */
    fun parse(text: String): List<Case> {
        val root = Json(text).parse() as Map<*, *>
        val cases = root["cases"] as List<*>
        return cases.map { entry ->
            val map = entry as Map<*, *>
            Case(
                name = map["name"] as String,
                seed = (map["seed"] as Number).toLong(),
                startX = (map["startX"] as Number).toDouble(),
                startY = (map["startY"] as Number).toDouble(),
                totalUs = (map["totalUs"] as Number).toLong(),
                targets = (map["targets"] as List<*>).map { pair ->
                    (pair as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
                },
                points = (map["points"] as List<*>).map { pair ->
                    (pair as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
                },
            )
        }
    }

    private class Json(private val text: String) {
        private var at = 0

        fun parse(): Any? {
            skip()
            val value = when (val c = text[at]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $at")
                }
            }
            skip()
            return value
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (peek('}')) {
                at++
                return out
            }
            while (true) {
                skip()
                val key = string()
                skip()
                expect(':')
                out[key] = parse()
                skip()
                if (peek(',')) {
                    at++
                    continue
                }
                expect('}')
                return out
            }
        }

        private fun list(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skip()
            if (peek(']')) {
                at++
                return out
            }
            while (true) {
                out.add(parse())
                skip()
                if (peek(',')) {
                    at++
                    continue
                }
                expect(']')
                return out
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (text[at] != '"') {
                if (text[at] == '\\') {
                    at++
                    out.append(
                        when (text[at]) {
                            'n' -> '\n'
                            't' -> '\t'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> text[at]
                        },
                    )
                } else {
                    out.append(text[at])
                }
                at++
            }
            at++
            return out.toString()
        }

        private fun number(): Double {
            val start = at
            if (text[at] == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            return text.substring(start, at).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            assertTrue(text.startsWith(word, at)) { "expected $word at $at" }
            at += word.length
            return value
        }

        private fun skip() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(c: Char): Boolean = at < text.length && text[at] == c

        private fun expect(c: Char) {
            assertTrue(peek(c)) { "expected '$c' at $at, found '${if (at < text.length) text[at] else "eof"}'" }
            at++
        }
    }
}

/** The bundled archive, as a stream, for the tests. */
internal object WeightsStream {

    private val path = "/resources/liquidaddon/abcurses/weights.npz"

    fun open() = AbcReference::class.java.getResourceAsStream(path)
        ?: error("the add-on resources do not contain $path")

    fun read(): ByteArray = open().use { it.readBytes() }
}
