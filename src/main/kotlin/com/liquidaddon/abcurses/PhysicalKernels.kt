package com.liquidaddon.abcurses

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The non-learned half of the planner: everything between "where the crosshair is" and "what the
 * network is shown", plus the curves the network's coefficients are drawn into.
 *
 * A straight port of `abcurves/_continuous/kernels.py`, kept in the same float64 arithmetic for the
 * same reason the reference keeps it there: these are physical quantities — an error in counts, a
 * velocity in counts per millisecond — and the learned layers are only ever shown float32 summaries
 * of them. The two deliberate float32 boundaries (the motor's trunk, the selector's inputs) are
 * written out where they happen.
 *
 * One history layout everywhere: chronological, `[640][2]` float64, the last row being the present.
 * The runtime keeps it in a ring; the kernels only ever see it as a flat array, because copying
 * 640 x 2 doubles 30 times a second would be the only cost of this port that matters.
 */
internal object PhysicalKernels {

    /** Counts per millisecond at which the model's normalisations sit (see `kernels.py`). */
    const val VELOCITY_SCALE = 1.1982087601807259
    const val POSITION_SCALE = 81.73350722609987

    /** Rows of the 640 ms window the coarse tokens look at: every 16th, then every 4th. */
    val INDEX = IntArray(70).also { out ->
        var n = 0
        var i = 15
        while (i < 480) {
            out[n++] = i
            i += 16
        }
        i = 483
        while (i < 640) {
            out[n++] = i
            i += 4
        }
        check(n == 70) { "coarse token index is not 70 rows" }
    }

    /** The per-feature scales the event context is `asinh`-normalised with. */
    private val SCALES = doubleArrayOf(
        10.0, .1, .1, .1, 1.0, .1, .1, .1, .1, .1, .01, 10.0,
        128.0, 1.0, 1.0, 1.0, 128.0, 10.0, 10.0, .1, 1.0, 10.0,
    )

    /**
     * The 1 ms grid offset of every history row relative to the decision time: row `i` is the
     * position `i - 639` milliseconds before `cut`. Used for the "was this target known yet" test.
     */
    fun offsets(): LongArray = LongArray(640) { (it - 639).toLong() * 1000L }

    /** The motor's three inputs, already shaped `70 x 9`, `54`, `8`. */
    class MotorFeatures(val coarse: FloatArray, val fine: FloatArray, val dynamics: FloatArray)

    /**
     * Preprocessing for the motor graph, `kernels.motor_features` line by line.
     *
     * The physical story, which the code below only spells:
     *
     *  - [history] is the *displacement* stream in common counts, one row per millisecond;
     *  - `points` is that stream integrated backwards from the present position, so `rel` is the
     *    goal minus the point the aim actually passed through, in `asinh` (a bounded "how far the
     *    cursor is from where it should have been" that keeps working at 80° of error);
     *  - `tv` is the target's own velocity over 32 ms, the thing a hand leads;
     *  - `coarse` folds the 640 rows into 70 tokens (16-row averages for the first 480, 4-row for the
     *    rest) carrying velocity, error, target velocity, a known-flag and a coverage flag;
     *  - `fine` is the last 16 rows in full plus the current error and target velocity — the part the
     *    model is allowed to look at with no averaging at all;
     *  - `dynamics` rotates the velocity, the acceleration and the residual into the frame of the
     *    error vector, which is how the network sees "closing" and "strafing" separately.
     */
    fun motorFeatures(
        history: DoubleArray,
        position: DoubleArray,
        target: DoubleArray,
        available: LongArray,
        valid: BooleanArray,
        motionKnown: BooleanArray,
        cutUs: Long,
    ): MotorFeatures {
        val known = BooleanArray(640)
        val m = DoubleArray(640 * 2)
        val g = DoubleArray(640 * 2)
        val tv = DoubleArray(640 * 2)
        for (i in 0 until 640) {
            val rowKnown = valid[i] && available[i] <= cutUs + (i - 639L) * 1000L
            known[i] = rowKnown
            if (motionKnown[i]) {
                m[i * 2] = history[i * 2]
                m[i * 2 + 1] = history[i * 2 + 1]
            }
            if (rowKnown) {
                g[i * 2] = target[i * 2]
                g[i * 2 + 1] = target[i * 2 + 1]
            }
        }
        for (i in 32 until 640) {
            if (known[i] && known[i - 32]) {
                tv[i * 2] = (g[i * 2] - g[(i - 32) * 2]) / 32.0
                tv[i * 2 + 1] = (g[i * 2 + 1] - g[(i - 32) * 2 + 1]) / 32.0
            }
        }
        var totalX = 0.0
        var totalY = 0.0
        for (i in 0 until 640) {
            totalX += m[i * 2]
            totalY += m[i * 2 + 1]
        }
        val rel = DoubleArray(640 * 2)
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until 640) {
            sumX += m[i * 2]
            sumY += m[i * 2 + 1]
            if (known[i] && motionKnown[i]) {
                val px = position[0] + sumX - totalX
                val py = position[1] + sumY - totalY
                rel[i * 2] = asinh((g[i * 2] - px) / POSITION_SCALE)
                rel[i * 2 + 1] = asinh((g[i * 2 + 1] - py) / POSITION_SCALE)
            }
        }

        val coarse = FloatArray(70 * 9)
        var start = 0
        for (token in 0 until 70) {
            val width = if (token < 30) 16 else 4
            val end = start + width
            var mx = 0.0
            var my = 0.0
            var coverage = 0.0
            for (j in start until end) {
                mx += m[j * 2]
                my += m[j * 2 + 1]
                if (motionKnown[j]) coverage += 1.0
            }
            val at = token * 9
            val row = INDEX[token]
            coarse[at] = (mx / width / VELOCITY_SCALE).toFloat()
            coarse[at + 1] = (my / width / VELOCITY_SCALE).toFloat()
            coarse[at + 2] = rel[row * 2].toFloat()
            coarse[at + 3] = rel[row * 2 + 1].toFloat()
            coarse[at + 4] = (tv[row * 2] / VELOCITY_SCALE).toFloat()
            coarse[at + 5] = (tv[row * 2 + 1] / VELOCITY_SCALE).toFloat()
            coarse[at + 6] = if (known[row]) 1f else 0f
            coarse[at + 7] = (coverage / width).toFloat()
            coarse[at + 8] = if (token < 30) 1f else .25f
            start = end
        }

        val fine = FloatArray(54)
        for (i in 0 until 16) {
            fine[i * 2] = (m[(624 + i) * 2] / VELOCITY_SCALE).toFloat()
            fine[i * 2 + 1] = (m[(624 + i) * 2 + 1] / VELOCITY_SCALE).toFloat()
            fine[32 + i] = if (motionKnown[624 + i]) 1f else 0f
        }
        if (known[639]) {
            fine[48] = asinh((g[639 * 2] - position[0]) / POSITION_SCALE).toFloat()
            fine[49] = asinh((g[639 * 2 + 1] - position[1]) / POSITION_SCALE).toFloat()
        }
        fine[50] = (tv[639 * 2] / VELOCITY_SCALE).toFloat()
        fine[51] = (tv[639 * 2 + 1] / VELOCITY_SCALE).toFloat()
        fine[52] = if (known[639]) 1f else 0f
        var coverage = 0
        for (b in motionKnown) if (b) coverage++
        fine[53] = coverage / 640f

        return MotorFeatures(coarse, fine, motorDynamics(fine))
    }

    /**
     * The frame-of-error features: acceleration with the float32 rounding of the reference, the
     * unit direction towards the goal, and the velocity/residual split along and across it.
     */
    fun motorDynamics(fine: FloatArray): FloatArray {
        val velocity = FloatArray(32)
        System.arraycopy(fine, 0, velocity, 0, 32)
        val cx = velocity[30]
        val cy = velocity[31]
        var accX = 0f
        var accY = 0f
        var allKnown = true
        for (i in 40 until 48) allKnown = allKnown && fine[i] > .5f
        if (allKnown) {
            var x1 = 0f
            var x0 = 0f
            var y1 = 0f
            var y0 = 0f
            for (i in 0 until 4) {
                x1 += velocity[24 + i * 2]
                x0 += velocity[16 + i * 2]
                y1 += velocity[25 + i * 2]
                y0 += velocity[17 + i * 2]
            }
            x1 /= 4f
            x0 /= 4f
            y1 /= 4f
            y0 /= 4f
            accX = ((x1 - x0) * VELOCITY_SCALE.toFloat() / 4f) / .25f
            accY = ((y1 - y0) * VELOCITY_SCALE.toFloat() / 4f) / .25f
        }
        val ex = kotlin.math.sinh(fine[48].toDouble()) * POSITION_SCALE
        val ey = kotlin.math.sinh(fine[49].toDouble()) * POSITION_SCALE
        var dx = (ex / sqrt(ex * ex + ey * ey + 100.0)).toFloat()
        var dy = (ey / sqrt(ex * ex + ey * ey + 100.0)).toFloat()
        if (fine[52] <= .5f) {
            dx = 0f
            dy = 0f
        }
        val nx = -dy
        val ny = dx
        val rx = cx - fine[50]
        val ry = cy - fine[51]
        return floatArrayOf(
            cx, cy, accX, accY,
            rx * dx + ry * dy, rx * nx + ry * ny,
            accX * dx + accY * dy, accX * nx + accY * ny,
        )
    }

    /**
     * The movement selector's two inputs, `kernels.selector_inputs`.
     *
     * [heads] is `16 x 16 x 2` (head, time, axis) as [AbcWeights.decodeHeads] returns it. The unary
     * block gets `asinh` of the first eight time rows of every head; the pairwise block gets the
     * difference against the *previous* head's rows 8..15 plus the "did the last plan land where the
     * predecessor said it would" residual, all in `asinh` so a 30-count difference does not swamp a
     * 1-count one. Without a predecessor the pair block is zeros and the graph multiplies it by
     * `previous_valid = 0` anyway.
     */
    fun selectorInputs(
        encoded: FloatArray,
        heads: FloatArray,
        previous: FloatArray?,
        actualStep: DoubleArray,
    ): Pair<FloatArray, FloatArray> {
        val unary = FloatArray(16 * 112)
        for (h in 0 until 16) {
            val at = h * 112
            System.arraycopy(encoded, 0, unary, at, 96)
            for (j in 0 until 16) {
                unary[at + 96 + j] = asinh(heads[(h * 16 + j / 2) * 2 + (j % 2)].toDouble()).toFloat()
            }
        }
        val pairs = FloatArray(16 * 20)
        if (previous != null) {
            val ax = actualStep[0].toFloat()
            val ay = actualStep[1].toFloat()
            var px = 0f
            var py = 0f
            var p8x = 0f
            var p8y = 0f
            for (j in 0 until 16) {
                px += previous[j * 2]
                py += previous[j * 2 + 1]
            }
            for (j in 0 until 8) {
                p8x += previous[j * 2]
                p8y += previous[j * 2 + 1]
            }
            for (h in 0 until 16) {
                val p = h * 20
                var hx = 0f
                var hy = 0f
                for (j in 0 until 8) {
                    hx += heads[(h * 16 + j) * 2]
                    hy += heads[(h * 16 + j) * 2 + 1]
                }
                for (j in 0 until 8) {
                    val prevAt = (8 + j) * 2
                    val headAt = (h * 16 + j) * 2
                    pairs[p + j * 2] = asinh((previous[prevAt] - heads[headAt]).toDouble()).toFloat()
                    pairs[p + j * 2 + 1] = asinh((previous[prevAt + 1] - heads[headAt + 1]).toDouble()).toFloat()
                }
                pairs[p + 16] = asinh(((px * 4 - ax) - hx * 4) / 32.0).toFloat()
                pairs[p + 17] = asinh(((py * 4 - ay) - hy * 4) / 32.0).toFloat()
                pairs[p + 18] = asinh((p8x * 4 - ax) / 32.0).toFloat()
                pairs[p + 19] = asinh((p8y * 4 - ay) / 32.0).toFloat()
            }
        }
        return unary to pairs
    }

    /** The raw physical features (22) the event model is fitted on, plus the 32-value context. */
    class EventFeatures(val raw: FloatArray, val context: FloatArray, val basis: Array<DoubleArray>)

    /**
     * `kernels.event_features`: "is this still a movement, or has it become a settle?"
     *
     * The 22 raw numbers are, in order: the distance left, the speed now / over 8 ms / over 32 ms,
     * how fast the hand is slowing, the target's own speed over 32 and 128 ms, the closing speed,
     * the strafing speed, the distance growth, the deceleration along the motion, the excursion, how
     * long the target has been where it is, how much of the window was known, whether the latest
     * target is known, motion coverage, the hold age and errors, and two correlations. The context
     * vector adds the same numbers rotated into the error frame, which is what the brake and hazard
     * heads read.
     */
    fun eventFeatures(
        history: DoubleArray,
        position: DoubleArray,
        target: DoubleArray,
        available: LongArray,
        valid: BooleanArray,
        motionKnown: BooleanArray,
        cutUs: Long,
        holdAge: Double,
        holdTarget: DoubleArray,
        holdPosition: DoubleArray,
        initialError: Double,
        innovationAge: Double,
        mode: Int,
    ): EventFeatures {
        val known = BooleanArray(640)
        val knownContext = BooleanArray(640)
        for (i in 0 until 640) {
            val kc = valid[i] && available[i] <= cutUs + (i - 639L) * 1000L
            knownContext[i] = kc
            known[i] = kc && target[i * 2].isFinite() && target[i * 2 + 1].isFinite()
        }
        val latest = known[639]
        val gx = if (latest) target[639 * 2] else position[0]
        val gy = if (latest) target[639 * 2 + 1] else position[1]
        var ex = gx - position[0]
        var ey = gy - position[1]
        val distance = hypot(ex, ey)
        var axisX = ex / maxOf(distance, 1e-8)
        var axisY = ey / maxOf(distance, 1e-8)

        val v1x = history[639 * 2]
        val v1y = history[639 * 2 + 1]
        val v8 = meanVector(history, 632, 640)
        val v32 = meanVector(history, 608, 640)
        val s1 = hypot(v1x, v1y)
        val s8 = hypot(v8[0], v8[1])
        val s32 = hypot(v32[0], v32[1])

        val tv = Array(3) { DoubleArray(2) }
        val periods = intArrayOf(8, 32, 128)
        for (j in periods.indices) {
            val k = periods[j]
            if (latest && known[639 - k]) {
                tv[j][0] = (gx - target[(639 - k) * 2]) / k
                tv[j][1] = (gy - target[(639 - k) * 2 + 1]) / k
            }
        }
        val ts8 = hypot(tv[0][0], tv[0][1])
        val ts32 = hypot(tv[1][0], tv[1][1])
        val ts128 = hypot(tv[2][0], tv[2][1])

        var growth = 0.0
        if (latest && known[607]) {
            val oldX = target[607 * 2] - (position[0] - v32[0] * 32)
            val oldY = target[607 * 2 + 1] - (position[1] - v32[1] * 32)
            growth = (distance - hypot(oldX, oldY)) / 32
        }
        val v8old = meanVector(history, 624, 632)
        val acceleration = doubleArrayOf((v8[0] - v8old[0]) / 8, (v8[1] - v8old[1]) / 8)

        var excursion = 0.0
        var lastChange = -1
        var knownCount = 0
        var motionCount = 0
        for (i in 0 until 640) if (motionKnown[i]) motionCount++
        for (i in 512 until 640) {
            val dx = target[i * 2] - gx
            val dy = target[i * 2 + 1] - gy
            val d = hypot(dx, dy)
            if (d > 1e-8 || !known[i]) lastChange = i - 512
            if (known[i]) {
                if (d > excursion) excursion = d
                knownCount++
            }
        }

        val raw = FloatArray(22)
        raw[0] = distance.toFloat()
        raw[1] = s1.toFloat()
        raw[2] = s8.toFloat()
        raw[3] = s32.toFloat()
        raw[4] = (s8 / maxOf(s32, .005)).toFloat()
        raw[5] = ts32.toFloat()
        raw[6] = ts128.toFloat()
        raw[7] = (v8[0] * axisX + v8[1] * axisY).toFloat()
        raw[8] = abs(v8[0] * axisY - v8[1] * axisX).toFloat()
        raw[9] = growth.toFloat()
        raw[10] = (-(acceleration[0] * v8[0] + acceleration[1] * v8[1]) / maxOf(s8, .005)).toFloat()
        raw[11] = excursion.toFloat()
        raw[12] = (127 - lastChange).toFloat()
        raw[13] = knownCount / 128f
        raw[14] = if (latest) 1f else 0f
        raw[15] = motionCount / 640f
        raw[16] = minOf(holdAge, 4096.0).toFloat()
        raw[17] = hypot(gx - holdTarget[0], gy - holdTarget[1]).toFloat()
        raw[18] = hypot(position[0] - holdPosition[0], position[1] - holdPosition[1]).toFloat()
        raw[19] = ts8.toFloat()
        raw[20] = ((v8[0] * tv[1][0] + v8[1] * tv[1][1]) / maxOf(s8 * ts32, 1e-8)).toFloat()
        raw[21] = initialError.toFloat()
        if (mode == 0) {
            raw[17] = 0f
            raw[18] = 0f
            raw[21] = 0f
        }

        // The context copy: everything above, clamped where the reference clamps, plus the ten
        // rotated extras.
        val contextRaw = raw.copyOf()
        contextRaw[16] = minOf(contextRaw[16], 256f)
        val goalX = if (knownContext[639]) target[639 * 2] else position[0]
        val goalY = if (knownContext[639]) target[639 * 2 + 1] else position[1]
        ex = goalX - position[0]
        ey = goalY - position[1]
        val cd = hypot(ex, ey)
        if (cd > 1e-6) {
            axisX = ex / maxOf(cd, 1e-9)
            axisY = ey / maxOf(cd, 1e-9)
        } else if (s8 > 1e-6) {
            axisX = v8[0] / maxOf(s8, 1e-9)
            axisY = v8[1] / maxOf(s8, 1e-9)
        } else {
            axisX = 1.0
            axisY = 0.0
        }
        val basis = arrayOf(doubleArrayOf(axisX, -axisY), doubleArrayOf(axisY, axisX))
        val extra = DoubleArray(10)
        val rv1 = rotate(doubleArrayOf(v1x, v1y), basis)
        val racc = rotate(acceleration, basis)
        val rv8 = rotate(v8, basis)
        extra[0] = rv1[0]
        extra[1] = rv1[1]
        extra[2] = racc[0] * 8
        extra[3] = racc[1] * 8
        for (j in 0 until 2) {
            val k = if (j == 0) 32 else 128
            var tx = 0.0
            var ty = 0.0
            if (knownContext[639] && knownContext[639 - k]) {
                tx = (goalX - target[(639 - k) * 2]) / k
                ty = (goalY - target[(639 - k) * 2 + 1]) / k
            }
            val r = rotate(doubleArrayOf(tx, ty), basis)
            extra[4 + j * 2] = r[0]
            extra[5 + j * 2] = r[1]
        }
        extra[8] = rv8[1]
        extra[9] = minOf(2048.0, innovationAge) / 128

        val context = FloatArray(32)
        for (i in 0 until 22) {
            val clamp = if (i == 16) 256.0 else Double.POSITIVE_INFINITY
            context[i] = asinh(minOf(clamp, contextRaw[i].toDouble()) / SCALES[i]).toFloat()
        }
        for (i in 0 until 10) {
            context[22 + i] = asinh(extra[i]).toFloat()
        }
        return EventFeatures(raw, context, basis)
    }

    /**
     * The C2 brake curve: a displacement profile that starts from the incoming velocity and
     * acceleration, ends at rest on the residual `coefficients[0]`, and passes through the four
     * residual harmonics without a discontinuity in any derivative the hand can feel.
     *
     * Returns the 32 one-millisecond displacements of this decision, exactly as the reference
     * (`np.diff` of the 33 sampled positions).
     */
    fun brakePath(
        velocity: DoubleArray,
        acceleration: DoubleArray,
        duration: Double,
        coefficients: Array<DoubleArray>,
        age: Double,
    ): Array<DoubleArray> {
        val path = Array(33) { DoubleArray(2) }
        for (i in 0..32) {
            val s = ((age + i) / duration).coerceIn(0.0, 1.0)
            val z = 2 * s - 1
            val envelope = 64 * s * s * s * (1 - s) * (1 - s) * (1 - s)
            val residual = doubleArrayOf(
                envelope,
                envelope * z,
                envelope * (3 * z * z - 1) / 2,
                envelope * (5 * z * z * z - 3 * z) / 2,
            )
            val carry = s - 6 * s * s * s + 8 * s * s * s * s - 3 * s * s * s * s * s
            val acc = .5 * s * s * (1 - s) * (1 - s) * (1 - s)
            val goal = 10 * s * s * s - 15 * s * s * s * s + 6 * s * s * s * s * s
            for (d in 0 until 2) {
                var value = carry * duration * velocity[d] +
                        acc * duration * duration * acceleration[d] +
                        goal * coefficients[0][d]
                for (j in 0 until 4) value += residual[j] * coefficients[j + 1][d]
                path[i][d] = value
            }
        }
        return Array(32) { i -> doubleArrayOf(path[i + 1][0] - path[i][0], path[i + 1][1] - path[i][1]) }
    }

    private fun meanVector(values: DoubleArray, start: Int, end: Int): DoubleArray {
        var x = 0.0
        var y = 0.0
        for (i in start until end) {
            x += values[i * 2]
            y += values[i * 2 + 1]
        }
        val n = (end - start).toDouble()
        return doubleArrayOf(x / n, y / n)
    }

    private fun rotate(v: DoubleArray, basis: Array<DoubleArray>): DoubleArray = doubleArrayOf(
        v[0] * basis[0][0] + v[1] * basis[1][0],
        v[0] * basis[0][1] + v[1] * basis[1][1],
    )

    /**
     * The count ↔ degree conversion of the whole planner: one common count is
     * `0.0003509487083280618` radians, i.e. about 0.0201°, so a step of 50 counts is 1°.
     */
    const val COMMON_RADIANS_PER_COUNT = 0.0003509487083280618

    fun countsToDegrees(counts: Double): Double = counts * COMMON_RADIANS_PER_COUNT * (180.0 / Math.PI)

    fun degreesToCounts(degrees: Double): Double = degrees * (Math.PI / 180.0) / COMMON_RADIANS_PER_COUNT

    /** The yaw of a direction (Minecraft convention: `atan2(dz, dx) - 90°`), in degrees. */
    fun yawOf(dx: Double, dz: Double): Double = Math.toDegrees(atan2(dz, dx)) - 90.0

    /** Wrap an angle difference into `[-180, 180)` the way the client does before a packet. */
    fun wrapDegrees(value: Double): Double {
        var v = value % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /** Unused hooks kept explicit so a future consumer of the geometry does not re-derive them. */
    fun unitDirection(yaw: Double, pitch: Double): DoubleArray {
        val yawRad = Math.toRadians(yaw)
        val pitchRad = Math.toRadians(pitch)
        val cosPitch = cos(pitchRad)
        return doubleArrayOf(-sin(yawRad) * cosPitch, -sin(pitchRad), cos(yawRad) * cosPitch)
    }

    fun log1p(x: Double): Double = ln(1.0 + x)

    /** Exposed for the tests: the same value the reference `np.arcsinh` produces. */
    fun asinhOf(x: Double): Double = asinh(x)

    fun sqrtOf(x: Double): Double = sqrt(x)
}
