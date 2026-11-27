package com.liquidaddon.abcurses

/**
 * A tiny dense float tensor: enough of an array to run the four ABCurves graphs, and nothing more.
 *
 * The head models are ported from ONNX by hand (see [AbcModels]) because the ONNX Runtime the
 * client ships is not reachable from an add-on, and the graphs are small enough that the arithmetic
 * fits in a page: a `Gemm` is [dense], a `GRU` step is [gru], an activation is [silu] or [sigmoid].
 *
 * Everything is stored row-major, contiguous, in a `FloatArray`. Shapes are rank-1 or rank-2 only —
 * the B2-23 graphs never produce anything else once the batch axis is folded away, and folding it
 * away is what makes the arrays tiny (a `[1, 96]` tensor here is a `96` one).
 *
 * Float32 on purpose: the frozen checkpoints are float32, the forward passes happen in float32, and
 * the offline parity test (`AbcModelsParityTest`) compares this arithmetic against ONNX Runtime
 * output sample by sample.
 */
internal class Tensor(val rows: Int, val cols: Int, val data: FloatArray) {

    init {
        require(data.size == rows * cols) { "tensor shape ${rows}x$cols does not fit ${data.size} values" }
    }

    constructor(rows: Int, cols: Int) : this(rows, cols, FloatArray(rows * cols))

    val size: Int get() = data.size

    operator fun get(row: Int, col: Int): Float = data[row * cols + col]

    operator fun set(row: Int, col: Int, value: Float) {
        data[row * cols + col] = value
    }

    /** One row as its own `1 x cols` tensor; a copy, so the caller can hold on to it. */
    fun row(index: Int): Tensor = Tensor(1, cols, data.copyOfRange(index * cols, (index + 1) * cols))

    /** The row multiplied by [gain] and offset by [bias], without allocating a second output. */
    fun scaledRow(index: Int, gain: Float): Tensor {
        val out = row(index)
        for (i in out.data.indices) out.data[i] *= gain
        return out
    }

    fun copy(): Tensor = Tensor(rows, cols, data.copyOf())
}

/**
 * `out = x · wᵀ + b` — an ONNX `Gemm` with `transB = 1`, the only form the head graphs use.
 *
 * [w] is stored as the checkpoint stores it: `[outputs, inputs]`, one row per output neuron. The
 * inner loop walks the row of [w] and the input together, in the order the arrays are laid out, so
 * the sum is accumulated in the reference order rather than in a cache-blocked one — the parity test
 * is what makes that worth saying out loud.
 */
internal fun dense(x: FloatArray, w: FloatArray, b: FloatArray, outputs: Int, inputs: Int): FloatArray {
    require(x.size == inputs) { "gemm input ${x.size} != $inputs" }
    require(w.size == outputs * inputs) { "gemm weight ${w.size} != ${outputs}x$inputs" }
    require(b.size == outputs) { "gemm bias ${b.size} != $outputs" }
    val out = FloatArray(outputs)
    for (o in 0 until outputs) {
        val base = o * inputs
        var sum = 0f
        for (i in 0 until inputs) sum += w[base + i] * x[i]
        out[o] = sum + b[o]
    }
    return out
}

/** `silu(x) = x · sigmoid(x)`, the activation every block of the frozen graphs ends with. */
internal fun silu(x: FloatArray): FloatArray = FloatArray(x.size) { i ->
    val v = x[i]
    v / (1f + kotlin.math.exp(-v))
}

internal fun sigmoid(x: FloatArray): FloatArray = FloatArray(x.size) { i ->
    val v = x[i]
    1f / (1f + kotlin.math.exp(-v))
}

internal fun sigmoid(v: Float): Float = 1f / (1f + kotlin.math.exp(-v))

/**
 * One step of the `torch.nn.GRU` the motor graph carries — the same arithmetic, in float32.
 *
 * Gate order, which is the trap: the exported ONNX graph stacks its gates in the standard ONNX
 * order **z, r, h** (update, reset, candidate), and *not* in PyTorch's own **r, z, h**. Verified
 * block by block against the checkpoint's `motor.encoder.weight_*`: the graph's `z` rows are the
 * checkpoint's second block, its `r` rows the first, its `h` rows the third. The arrays handed to
 * this class are kept in the ONNX order, so the indices below are the graph's, and a port that
 * assumed "PyTorch order" would produce a plausible-looking but different motor.
 *
 * `linear_before_reset = 1` (what the exported graph says, and how `torch.nn.GRU` computes): the
 * candidate is `tanh(Wᵢₕ·x + bᵢₕ + r ⊙ (Wₕₕ·h + bₕₕ))` — the *input* term is not reset, only the
 * recurrent one. The last line is the usual `h ← (1 − z) · h̃ + z · h`.
 *
 * Everything stays in float32 to match the frozen graph's execution; the offline parity test is
 * what pins down the order of the additions.
 */
internal class Gru(
    val hidden: Int,
    val inputs: Int,
    val weightIh: FloatArray,
    val weightHh: FloatArray,
    val biasIh: FloatArray,
    val biasHh: FloatArray,
) {

    /**
     * One timestep: [x] is the projected input of this millisecond, [h] the hidden state, updated
     * in place. Written as four passes over small arrays — at 70 steps per decision and thirty
     * decisions per second, the only thing worth avoiding here is allocation.
     */
    fun step(x: FloatArray, h: FloatArray) {
        require(x.size == inputs && h.size == hidden) { "GRU step shape" }
        val gateInput = FloatArray(3 * hidden)
        for (g in 0 until 3 * hidden) {
            var sum = biasIh[g]
            val base = g * inputs
            for (i in 0 until inputs) sum += weightIh[base + i] * x[i]
            gateInput[g] = sum
        }
        val recurrent = FloatArray(3 * hidden)
        for (g in 0 until 3 * hidden) {
            var sum = biasHh[g]
            val base = g * hidden
            for (k in 0 until hidden) sum += weightHh[base + k] * h[k]
            recurrent[g] = sum
        }
        val update = FloatArray(hidden)
        val candidate = FloatArray(hidden)
        for (j in 0 until hidden) {
            // Gate order z, r, h — ONNX's, which is what the arrays are handed in as.
            val z = sigmoid(gateInput[j] + recurrent[j])
            val r = sigmoid(gateInput[hidden + j] + recurrent[hidden + j])
            val hh = kotlin.math.tanh(gateInput[2 * hidden + j] + r * recurrent[2 * hidden + j])
            update[j] = z
            candidate[j] = hh
        }
        for (j in 0 until hidden) {
            h[j] = (1f - update[j]) * candidate[j] + update[j] * h[j]
        }
    }
}

/**
 * `mt19937`, seeded exactly the way `numpy.random.RandomState(seed & 0xffffffff)` seeds it.
 *
 * The planner's draws are part of its behaviour — which of the sixteen motor heads it takes, when it
 * decides to coast, how long a brake lasts — so a port that only "looks random" produces a different
 * stream, and the parity test against the Python runtime would be comparing two different policies.
 * This is the same init-by-division `randint` NumPy uses, so the *number of words consumed* is the
 * only thing left to reproduce, and that is [RandomStream]'s job.
 */
internal class Mt19937(seed: Long) {

    private val state = IntArray(624)
    private var index = 624

    init {
        reset(seed)
    }

    fun reset(seed: Long) {
        state[0] = seed.toInt()
        for (i in 1 until state.size) {
            state[i] = 1812433253 * (state[i - 1] xor (state[i - 1] ushr 30)) + i
        }
        index = 624
    }

    fun raw(): Int {
        if (index >= 624) twist()
        var y = state[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        return y xor (y ushr 18)
    }

    private fun twist() {
        for (i in 0 until 624) {
            val y = (state[i] and 0x80000000.toInt()) or (state[(i + 1) % 624] and 0x7fffffff)
            state[i] = state[(i + 397) % 624] xor (y ushr 1) xor (if (y and 1 == 0) 0 else 0x9908b0df.toInt())
        }
        index = 0
    }
}

/**
 * The reference draw law of `abcurves/_continuous/rng.py`, word for word.
 *
 * Three things have to match, and each one of them is visible in the output:
 *
 *  - **uniform** is `(word & 0xffffff) * 2^-24`, one word per value — *not* 53-bit doubles;
 *  - **exponential** is Gumbel-race noise built from *pairs* of words (`bits = w0 << 32 | w1`,
 *    `u = (bits & (2^53-1)) * 2^-53`, `-log1p(-u)`), rounded to float32 — one draw per category;
 *  - **categorical** is `argmax(weights / exponential)` over the whole vector, drawn in one go, so
 *    a 16-way choice consumes 32 words and a 2-way choice consumes 4.
 *
 * The Python implementation keeps a 512-word window and derives its uniforms/exponentials from the
 * arena *without* consuming it (taking pairs from adjacent words rather than carrying a parity), so
 * a uniform draw and an exponential draw can share a word. Reproducing that faithfully is what
 * [wordWindow] and the derived arrays below are for: the stream is the raw word sequence, and the
 * derived arrays are looked up by position, exactly like the reference.
 */
internal class RandomStream(seed: Long) {

    private val rng = Mt19937(seed and 0xffffffffL)

    private val words = IntArray(BLOCK)
    private var at = BLOCK
    private var uniforms: FloatArray? = null
    private var exponentials: FloatArray? = null

    /** Restarts the stream at [seed]: the same words a fresh `RandomStream(seed)` would produce. */
    fun reset(seed: Long) {
        rng.reset(seed and 0xffffffffL)
        at = BLOCK
        uniforms = null
        exponentials = null
    }

    private fun reserve(count: Int) {
        if (at + count <= BLOCK) return
        val remaining = BLOCK - at
        if (remaining > 0) {
            System.arraycopy(words, at, words, 0, remaining)
        }
        for (i in remaining until BLOCK) {
            words[i] = rng.raw()
        }
        at = 0
        uniforms = null
        exponentials = null
    }

    private fun words(count: Int): IntArray {
        val out = IntArray(count)
        var written = 0
        while (written < count) {
            val take = minOf(BLOCK, count - written)
            reserve(take)
            System.arraycopy(words, at, out, written, take)
            at += take
            written += take
        }
        return out
    }

    /** `size` uniforms in `[0, 1)`, float32, one word each. */
    fun uniform(count: Int): FloatArray {
        if (count == 0) return FloatArray(0)
        if (count > BLOCK) {
            val raw = words(count)
            return FloatArray(count) { i -> (raw[i] and 0xffffff).toFloat() * TWO_POW_MINUS_24 }
        }
        reserve(count)
        var table = uniforms
        if (table == null) {
            table = FloatArray(BLOCK) { i -> (words[i] and 0xffffff).toFloat() * TWO_POW_MINUS_24 }
            uniforms = table
        }
        val start = at
        at += count
        return FloatArray(count) { i -> table[start + i] }
    }

    /**
     * The exponential race values the reference derives from the arena, one per category.
     *
     * Built from *adjacent* word pairs (`words[i] << 32 | words[i+1]`) and sampled at stride 2 from
     * the current position — so a 16-way categorical consumes words `[at, at+32)` but reads pairs
     * starting at `at, at+2, ...`. A 1-word uniform drawn earlier therefore shifts which pairs are
     * used by a later categorical: the parity test is what pins this down.
     */
    private fun exponentialView(count: Int): FloatArray {
        if (count == 0) return FloatArray(0)
        val width = count * 2
        if (width > BLOCK) {
            val raw = words(width)
            return FloatArray(count) { i ->
                val bits = (raw[2 * i].toLong() shl 32) or (raw[2 * i + 1].toLong() and 0xffffffffL)
                (-kotlin.math.ln(1.0 - (bits and FIFTY_THREE).toDouble() * TWO_POW_MINUS_53)).toFloat()
            }
        }
        reserve(width)
        var table = exponentials
        if (table == null) {
            // Pairs are `(words[i], words[i + 1])` for every i — the *last* word has no successor in
            // the reference either, so the table it derives covers `BLOCK - 1` positions.
            table = FloatArray(BLOCK - 1) { i ->
                val bits = (words[i].toLong() shl 32) or (words[i + 1].toLong() and 0xffffffffL)
                (-kotlin.math.ln(1.0 - (bits and FIFTY_THREE).toDouble() * TWO_POW_MINUS_53)).toFloat()
            }
            exponentials = table
        }
        val start = at
        at += width
        return FloatArray(count) { i -> table[start + 2 * i] }
    }

    /**
     * One categorical draw from [weights] (softmax probabilities): `argmax(weights / exponential)`.
     *
     * A zero exponential makes its category infinitely attractive, which is the reference's own
     * tie-breaking rule (a uniform draw that lands exactly on 0); it is kept as is.
     */
    fun categorical(weights: FloatArray): Int {
        val exponential = exponentialView(weights.size)
        var winner = 0
        var best = Float.NEGATIVE_INFINITY
        for (i in weights.indices) {
            val e = exponential[i]
            val score = if (e == 0f) Float.POSITIVE_INFINITY else weights[i] / e
            if (score > best) {
                best = score
                winner = i
            }
        }
        return winner
    }

    /** Consumes a categorical's words without choosing: the reference does this for unused heads. */
    fun discardCategorical(size: Int = 16) {
        var count = 2 * size
        while (count > 0) {
            val take = minOf(BLOCK, count)
            reserve(take)
            at += take
            count -= take
        }
    }

    private companion object {
        const val BLOCK = 512
        const val TWO_POW_MINUS_24 = 5.9604645e-8f
        const val TWO_POW_MINUS_53 = 1.1102230246251565e-16
        const val FIFTY_THREE = (1L shl 53) - 1
    }
}

/** `softmax` over a logit vector, in float32, the way `rng.softmax` computes it. */
internal fun softmax(logits: FloatArray): FloatArray {
    var max = Float.NEGATIVE_INFINITY
    for (v in logits) if (v > max) max = v
    val out = FloatArray(logits.size)
    var total = 0f
    for (i in logits.indices) {
        val v = kotlin.math.exp(logits[i] - max)
        out[i] = v
        total += v
    }
    check(total.isFinite() && total > 0f) { "invalid categorical probabilities" }
    for (i in out.indices) out[i] /= total
    return out
}
