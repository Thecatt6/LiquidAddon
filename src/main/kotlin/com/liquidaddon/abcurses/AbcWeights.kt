package com.liquidaddon.abcurses

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.asinh

/**
 * The frozen weights of the ABCurves "B2-23" continuous planner, read straight out of the release
 * archive (`weights.npz`, the same file the Python package ships) and arranged for inference.
 *
 * Why the archive and not four ONNX graphs: the ONNX Runtime the client bundles (`ai.djl` +
 * `pytorch-engine`) cannot be reached from an add-on, and `ai.onnxruntime` is not on the classpath,
 * so a port that wanted to load the graphs would have to ship a second native runtime next to the
 * one already in the jar. The checkpoint holds *every* tensor those graphs contain — verified
 * tensor by tensor against the ONNX initializers, zero difference — so the graphs are re-expressed
 * here as ordinary matrix arithmetic instead. The result is one 830 kB resource and no native code.
 *
 * What is loaded:
 *
 *  - the **motor** (a 96-wide GRU over a 70-step coarse window, then a 96→672 `Gemm`) and its
 *    ProDMP geometry — `velocity_basis` (128 x 21) and `carry_velocity`, the two arrays that turn a
 *    coefficient vector back into 32 one-millisecond displacements;
 *  - the **movement selector** (96→64→1 unary, 36→32→1 pairwise, 96→16 context), the network that
 *    scores the sixteen candidate heads;
 *  - the **event encoder / brake / hazard** heads, the three small nets that decide when the stream
 *    stops being a movement and starts being a settle.
 *
 * The archive is read as raw NPY members (`<f8` and `<f4` only) — no pickle, no Python, and the
 * member list is fixed, so a corrupt or unexpected archive fails on load with the member's name
 * instead of producing a model that silently aims nowhere.
 */
internal class AbcWeights private constructor(
    val velocityBasis: FloatArray,
    val carryVelocity: FloatArray,
    val motorInput: FloatArray,
    val motorInputBias: FloatArray,
    val motorGru: Gru,
    val motorTrunk: FloatArray,
    val motorTrunkBias: FloatArray,
    val motorOutput: FloatArray,
    val motorOutputBias: FloatArray,
    val unaryWeight: FloatArray,
    val unaryBias: FloatArray,
    val unaryOut: FloatArray,
    val unaryOutBias: FloatArray,
    val contextWeight: FloatArray,
    val contextBias: FloatArray,
    val pairWeight: FloatArray,
    val pairBias: FloatArray,
    val pairOut: FloatArray,
    val pairOutBias: FloatArray,
    val encoderWeight: FloatArray,
    val encoderBias: FloatArray,
    val encoder2Weight: FloatArray,
    val encoder2Bias: FloatArray,
    val brakeWeight: FloatArray,
    val brakeBias: FloatArray,
    val frequencyWeight: FloatArray,
    val frequencyBias: FloatArray,
    val hazardWeight: FloatArray,
    val hazardBias: FloatArray,
    val hazard2Weight: FloatArray,
    val hazard2Bias: FloatArray,
    val hazardOutWeight: FloatArray,
    val hazardOutBias: FloatArray,
) {

    /** Shapes, asserted once so the arithmetic below can be written without checking anything. */
    init {
        require(velocityBasis.size == 128 * 21) { "velocity_basis is not 128x21" }
        require(carryVelocity.size == 128) { "carry_velocity is not 128" }
        require(motorGru.hidden == 96 && motorGru.inputs == 96) { "motor GRU is not 96x96" }
        require(motorTrunk.size == 96 * 158) { "motor trunk is not 96x158" }
        require(motorOutput.size == 672 * 96) { "motor output is not 672x96" }
        require(brakeWeight.size == 176 * 96) { "brake head is not 176x96" }
        require(hazardWeight.size == 64 * 32 && hazard2Weight.size == 32 * 64) { "hazard head is not 32->64->32->2" }
    }

    /**
     * `[1, 70, 9]` coarse window + `[1, 54]` fine + `[1, 8]` dynamics → `encoded[96]`,
     * `coefficients[16][21][2]`.
     *
     * The graph, node by node:
     *
     *  1. the nine-wide coarse window is projected to 96 and the whole `70 x 96` sequence goes
     *     through the GRU; only the last hidden state is kept (the graph's `Gather` on the sequence
     *     output);
     *  2. `[h_t (96) | fine (54) | dynamics (8)]` — 158 values — is the trunk input: a `Gemm` to 96,
     *     then SiLU;
     *  3. a `Gemm` to `16 · 21 · 2 = 672` values, reshaped as 16 heads × 21 ProDMP coefficients × 2
     *     axes.
     *
     * Every `Gemm` here is `transB = 1` with `alpha = beta = 1`, i.e. [dense].
     */
    fun motor(coarse: FloatArray, fine: FloatArray, dynamics: FloatArray): MotorOutput {
        require(coarse.size == 70 * 9 && fine.size == 54 && dynamics.size == 8) { "motor input shape" }

        // 1. GRU over the 70 coarse steps; the reference keeps only the final state.
        val hidden = FloatArray(96)
        for (t in 0 until 70) {
            // MatMul → Add → Sigmoid → Mul: a SiLU projection of the nine coarse features.
            val step = silu(dense(coarse.copyOfRange(t * 9, t * 9 + 9), motorInput, motorInputBias, 96, 9))
            motorGru.step(step, hidden)
        }

        // 2. Trunk: 158 -> 96, SiLU.
        val trunkInput = FloatArray(158)
        System.arraycopy(hidden, 0, trunkInput, 0, 96)
        System.arraycopy(fine, 0, trunkInput, 96, 54)
        System.arraycopy(dynamics, 0, trunkInput, 150, 8)
        val encoded = silu(dense(trunkInput, motorTrunk, motorTrunkBias, 96, 158))

        // 3. Output head: 96 -> 672 = 16 x 21 x 2.
        val coefficients = dense(encoded, motorOutput, motorOutputBias, 672, 96)
        return MotorOutput(encoded, coefficients)
    }

    /**
     * The selector's sixteen head geometries, in the float32 the selector was trained on.
     *
     * `heads[h][t] = velocity_basis[t] · q[h] + carry_velocity[t] · incoming`, for the first sixteen
     * time rows (which is `t * 4` folded to a mean over four sub-steps — the same thing, because the
     * basis rows were built in groups of four).
     */
    fun decodeHeads(coefficients: FloatArray, incoming: DoubleArray): FloatArray {
        val heads = FloatArray(16 * 16 * 2)
        for (h in 0 until 16) {
            for (t in 0 until 16) {
                val at = t * 4
                for (d in 0 until 2) {
                    var sum = 0.0
                    for (sub in 0 until 4) {
                        val row = t * 4 + sub
                        var value = carryVelocity[row].toDouble() * incoming[d]
                        for (k in 0 until 21) {
                            value += velocityBasis[row * 21 + k].toDouble() * coefficients[h * 42 + k * 2 + d]
                        }
                        sum += value
                    }
                    heads[(h * 16 + t) * 2 + d] = (sum / 4.0).toFloat()
                }
            }
        }
        return heads
    }

    /**
     * The chosen head's 32 × 2 displacements in common counts, computed in float64 like the
     * reference decoder (`decode_selected`), because these numbers are integrated into the aim.
     */
    fun decodeSelected(coefficients: FloatArray, head: Int, incoming: DoubleArray): Array<DoubleArray> {
        val out = Array(32) { DoubleArray(2) }
        for (t in 0 until 32) {
            for (d in 0 until 2) {
                var value = carryVelocity[t].toDouble() * incoming[d]
                for (k in 0 until 21) {
                    value += velocityBasis[t * 21 + k].toDouble() * coefficients[head * 42 + k * 2 + d]
                }
                out[t][d] = value
            }
        }
        return out
    }

    /**
     * The selector graph: unary score per head + pairwise score against the previous head, added.
     *
     *  - unary: `[encoded (96) | asinh(head[t<8]) (16)]` → 64 → SiLU → 1;
     *  - pair:  `[pairs (20) | context (16)]` → 32 → SiLU → 1, where context is `encoded` through a
     *    96→16 SiLU block;
     *  - the pair term is multiplied by `previous_valid` inside the graph, so an invalidated
     *    predecessor contributes exactly zero without a branch here.
     */
    fun choice(encoded: FloatArray, heads: FloatArray, pairs: FloatArray, previousValid: Boolean): FloatArray {
        val unary = FloatArray(16)
        val context = silu(dense(encoded, contextWeight, contextBias, 16, 96))
        val unaryInput = FloatArray(112)
        val pairInput = FloatArray(36)
        for (h in 0 until 16) {
            System.arraycopy(encoded, 0, unaryInput, 0, 96)
            for (j in 0 until 8) {
                unaryInput[96 + j * 2] = asinh(heads[(h * 16 + j) * 2])
                unaryInput[97 + j * 2] = asinh(heads[(h * 16 + j) * 2 + 1])
            }
            val hidden = silu(dense(unaryInput, unaryWeight, unaryBias, 64, 112))
            val score = dense(hidden, unaryOut, unaryOutBias, 1, 64)[0]
            System.arraycopy(pairs, h * 20, pairInput, 0, 20)
            System.arraycopy(context, 0, pairInput, 20, 16)
            val pairHidden = silu(dense(pairInput, pairWeight, pairBias, 32, 36))
            val pairScore = dense(pairHidden, pairOut, pairOutBias, 1, 32)[0]
            unary[h] = score + if (previousValid) pairScore else 0f
        }
        return unary
    }

    /** `context[32]` → hazard logits `[2]`: two SiLU blocks and a linear head, no state. */
    fun hazard(context: FloatArray): FloatArray {
        val first = silu(dense(context, hazardWeight, hazardBias, 64, 32))
        val second = silu(dense(first, hazard2Weight, hazard2Bias, 32, 64))
        return dense(second, hazardOutWeight, hazardOutBias, 2, 32)
    }

    /**
     * The brake head: a shared 32→96→96 encoder, then three outputs — `duration` (`exp(clip(...))`,
     * one per sixteen heads), the `16 x 5 x 2` residual coefficients (scaled by the graph's 64) and
     * the sixteen categorical frequencies.
     */
    fun brake(context: FloatArray): BrakeOutput {
        val first = silu(dense(context, encoderWeight, encoderBias, 96, 32))
        val encoded = silu(dense(first, encoder2Weight, encoder2Bias, 96, 96))
        val raw = dense(encoded, brakeWeight, brakeBias, 176, 96)
        val duration = FloatArray(16)
        val coefficients = FloatArray(16 * 5 * 2)
        for (h in 0 until 16) {
            duration[h] = kotlin.math.exp(raw[h * 11].coerceIn(CLIP_LOW, CLIP_HIGH))
            System.arraycopy(raw, h * 11 + 1, coefficients, h * 10, 10)
        }
        for (i in coefficients.indices) coefficients[i] *= 64f
        val frequency = dense(encoded, frequencyWeight, frequencyBias, 16, 96)
        return BrakeOutput(duration, coefficients, frequency)
    }

    data class MotorOutput(val encoded: FloatArray, val coefficients: FloatArray)

    data class BrakeOutput(val duration: FloatArray, val coefficients: FloatArray, val frequency: FloatArray)

    companion object {

        /**
         * Absolute resource path of the archive this port was written against, and what it must hash
         * to.
         *
         * ## Why 818 kB of weights are committed rather than downloaded
         *
         * The archive is the model. A download at first use would make the engine fail on exactly the
         * sessions where a missing file is least visible (a server with no outbound access, a client
         * behind a proxy), and would make the parity test depend on the network instead of on the
         * bytes. It is committed for the same reason the reference recording next to the test is:
         * both are the *measurement* this port is built on, and neither is reproducible from the
         * source alone.
         *
         * It is not a build artefact and it does not grow: the model is frozen, and its digest is
         * checked on every load (see [SHA256]), so a replaced or truncated copy is refused instead of
         * driving the aim with different weights.
         *
         * The path is absolute and starts at `resources/` because that is where an add-on's files
         * land in the jar: `src/main/resources/resources/<id>/…` is the client's own resource folder,
         * read from the jar directly, and never a Minecraft resource pack. The leading slash matters
         * too — `getResourceAsStream` resolves a relative path against the *caller's package* —
         * `com/liquidaddon/abcurses/resources/…` — and finds nothing.
         */
        const val RESOURCE = "/resources/liquidaddon/abcurses/weights.npz"
        const val SHA256 = "018a096c66213c62b429caf4a8ae6f0470f6e283248a24f476f0fed3e36ea881"

        /** The `Clip` node in front of `Exp` in the brake graph: `[ln 4, ln 192]`. */
        private const val CLIP_LOW = 1.3862944f
        private const val CLIP_HIGH = 5.2574954f

        /** The archive members this port reads; anything else in it is ignored. */
        private val MEMBERS = listOf(
            "motor.position_basis", "motor.velocity_basis", "motor.carry_position", "motor.carry_velocity",
            "motor.input.0.weight", "motor.input.0.bias",
            "motor.encoder.weight_ih_l0", "motor.encoder.weight_hh_l0",
            "motor.encoder.bias_ih_l0", "motor.encoder.bias_hh_l0",
            "motor.trunk.0.weight", "motor.trunk.0.bias",
            "motor.output.weight", "motor.output.bias",
            "motor.selection.unary.0.weight", "motor.selection.unary.0.bias",
            "motor.selection.unary.2.weight", "motor.selection.unary.2.bias",
            "motor.selection.context.0.weight", "motor.selection.context.0.bias",
            "motor.selection.pair.0.weight", "motor.selection.pair.0.bias",
            "motor.selection.pair.2.weight", "motor.selection.pair.2.bias",
            "events.encoder.0.weight", "events.encoder.0.bias",
            "events.encoder.2.weight", "events.encoder.2.bias",
            "events.brake.weight", "events.brake.bias",
            "events.frequency.weight", "events.frequency.bias",
            "events.hazard.0.weight", "events.hazard.0.bias",
            "events.hazard.2.weight", "events.hazard.2.bias",
            "events.hazard.4.weight", "events.hazard.4.bias",
        )

        /**
         * Reads the archive from the add-on's resources (or from any stream, which is what the
         * tests use), verifies its SHA-256 and returns the arranged weights.
         *
         * @throws AbcAssetException with the member name when something is missing or malformed —
         *   the message ends up in the module's chat notice, so it has to name the problem.
         */
        fun load(stream: InputStream, verify: Boolean = true): AbcWeights {
            val bytes = stream.use { it.readBytes() }
            if (verify) {
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                val hex = digest.joinToString("") { "%02x".format(it) }
                if (hex != SHA256) {
                    throw AbcAssetException("weights.npz sha256 $hex != $SHA256")
                }
            }
            val arrays = readArchive(bytes)
            fun get(name: String): FloatArray = arrays[name]
                ?: throw AbcAssetException("weights.npz has no member '$name'")

            return AbcWeights(
                velocityBasis = get("motor.velocity_basis"),
                carryVelocity = get("motor.carry_velocity"),
                motorInput = get("motor.input.0.weight"),
                motorInputBias = get("motor.input.0.bias"),
                // PyTorch stores the gates as (reset, update, new); the graph and this port want
                // them as (update, reset, new). The swap happens here, once, so nothing downstream
                // has to remember which of the two layouts it is holding.
                motorGru = Gru(
                    hidden = 96, inputs = 96,
                    weightIh = gruOrder(get("motor.encoder.weight_ih_l0"), block = 96 * 96),
                    weightHh = gruOrder(get("motor.encoder.weight_hh_l0"), block = 96 * 96),
                    biasIh = gruOrder(get("motor.encoder.bias_ih_l0"), block = 96),
                    biasHh = gruOrder(get("motor.encoder.bias_hh_l0"), block = 96),
                ),
                motorTrunk = get("motor.trunk.0.weight"),
                motorTrunkBias = get("motor.trunk.0.bias"),
                motorOutput = get("motor.output.weight"),
                motorOutputBias = get("motor.output.bias"),
                unaryWeight = get("motor.selection.unary.0.weight"),
                unaryBias = get("motor.selection.unary.0.bias"),
                unaryOut = get("motor.selection.unary.2.weight"),
                unaryOutBias = get("motor.selection.unary.2.bias"),
                contextWeight = get("motor.selection.context.0.weight"),
                contextBias = get("motor.selection.context.0.bias"),
                pairWeight = get("motor.selection.pair.0.weight"),
                pairBias = get("motor.selection.pair.0.bias"),
                pairOut = get("motor.selection.pair.2.weight"),
                pairOutBias = get("motor.selection.pair.2.bias"),
                encoderWeight = get("events.encoder.0.weight"),
                encoderBias = get("events.encoder.0.bias"),
                encoder2Weight = get("events.encoder.2.weight"),
                encoder2Bias = get("events.encoder.2.bias"),
                brakeWeight = get("events.brake.weight"),
                brakeBias = get("events.brake.bias"),
                frequencyWeight = get("events.frequency.weight"),
                frequencyBias = get("events.frequency.bias"),
                hazardWeight = get("events.hazard.0.weight"),
                hazardBias = get("events.hazard.0.bias"),
                hazard2Weight = get("events.hazard.2.weight"),
                hazard2Bias = get("events.hazard.2.bias"),
                hazardOutWeight = get("events.hazard.4.weight"),
                hazardOutBias = get("events.hazard.4.bias"),
            )
        }

        /**
         * Re-stacks a `torch.nn.GRU` gate matrix from PyTorch's `(reset, update, new)` order into
         * ONNX's `(update, reset, new)`: the first two blocks trade places, the third stays.
         *
         * [block] is how many values one gate occupies: `H · H` for a weight matrix (`3H x H`,
         * row-major, one gate's rows contiguous) and `H` for a bias vector. Both are contiguous per
         * gate, so the same swap works for both — as long as the block size is the right one.
         */
        private fun gruOrder(array: FloatArray, block: Int): FloatArray {
            require(array.size == 3 * block) { "a GRU gate array is not 3 x $block long" }
            val out = FloatArray(array.size)
            System.arraycopy(array, block, out, 0, block)
            System.arraycopy(array, 0, out, block, block)
            System.arraycopy(array, 2 * block, out, 2 * block, block)
            return out
        }

        /**
         * Unpacks the NPY members of the zip, keeping only the float arrays this port reads and
         * widening float64 geometry to float32 (the basis values are stored as float64 because
         * they were solved that way; they are used as float32 here, like the ONNX export).
         */
        internal fun readArchive(bytes: ByteArray): Map<String, FloatArray> {
            val out = HashMap<String, FloatArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringBeforeLast('.')
                    if (name !in MEMBERS) continue
                    out[name] = readNpy(zip.readBytes(), name)
                }
            }
            return out
        }

        /**
         * One `.npy` payload: magic, version, header dict, then little-endian payload.
         *
         * Only `<f4` and `<f8` are accepted, and the C order flag is required: the archive is
         * produced by NumPy's `savez`, which writes exactly that, so anything else is a different
         * file wearing the same name.
         */
        private fun readNpy(memberBytes: ByteArray, member: String): FloatArray {
            val data = DataInputStream(memberBytes.inputStream())
            val magic = ByteArray(6)
            data.readFully(magic)
            if (magic[0] != 0x93.toByte() || String(magic, 1, 5, Charsets.US_ASCII) != "NUMPY") {
                throw AbcAssetException("'$member' is not an NPY array")
            }
            val major = data.readUnsignedByte()
            data.readUnsignedByte()
            // NPY headers are little-endian; DataInputStream reads big-endian, so the length is
            // assembled by hand (a v1 header is a uint16, a v2/v3 header a uint32).
            val headerLength = if (major == 1) {
                data.readUnsignedByte() or (data.readUnsignedByte() shl 8)
            } else {
                data.readUnsignedByte() or
                        (data.readUnsignedByte() shl 8) or
                        (data.readUnsignedByte() shl 16) or
                        (data.readUnsignedByte() shl 24)
            }
            val header = ByteArray(headerLength)
            data.readFully(header)
            val descriptor = String(header, Charsets.US_ASCII)
            val isDouble = descriptor.contains("<f8")
            val isFloat = descriptor.contains("<f4")
            if (!isDouble && !isFloat) throw AbcAssetException("'$member' is neither <f4 nor <f8")
            if (!descriptor.contains("'fortran_order': False") && !descriptor.contains("\"fortran_order\": False")) {
                throw AbcAssetException("'$member' is stored in Fortran order")
            }
            val count = countFromShape(descriptor, member)
            val payload = data.readBytes()
            val width = if (isDouble) 8 else 4
            if (payload.size < count * width) {
                throw AbcAssetException("'$member' holds ${payload.size} bytes, expected ${count * width}")
            }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            return if (isDouble) {
                FloatArray(count) { buffer.getDouble().toFloat() }
            } else {
                FloatArray(count) { buffer.getFloat() }
            }
        }

        /** The element count out of the header's `'shape': (a, b, ...)` tuple. */
        private fun countFromShape(descriptor: String, member: String): Int {
            val at = descriptor.indexOf("'shape'")
            val open = descriptor.indexOf('(', at)
            val close = descriptor.indexOf(')', open)
            if (at < 0 || open < 0 || close < 0) throw AbcAssetException("'$member' has no shape")
            val parts = descriptor.substring(open + 1, close).split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return 1
            var count = 1
            for (part in parts) {
                val value = part.toIntOrNull() ?: throw AbcAssetException("'$member' has a bad shape")
                count = Math.multiplyExact(count, value)
            }
            return count
        }
    }
}

/** The failure mode of loading the bundled archive, named so the module can say it in chat. */
internal class AbcAssetException(message: String) : Exception(message)
