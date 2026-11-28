package com.liquidaddon.abcurses

/**
 * The stateful half of the planner: one mutable ABCurves aim stream, `abcurves/_continuous/
 * planner.py` ported decision by decision.
 *
 * The model is a **hybrid policy**, and that is the whole idea: it does not answer "where is the
 * crosshair" every frame, it decides *which of three things* the hand is doing —
 *
 *  - `MOVE` (0): the motor network's 32 ms displacement plan, chosen among sixteen heads by the
 *    selector network;
 *  - `BRAKE` (1): a C2 stop curve, when the hazard network says the movement is ending;
 *  - `HOLD` (2): nothing at all, when the stream has settled on the target.
 *
 * Each decision commits to 32 one-millisecond displacements, and no new target can change a
 * commitment that is already in flight — that is what makes the output a movement a hand could
 * have made instead of a control loop that answers every packet of jitter with a packet of its own.
 *
 * Everything is float64 in counts (the unit the model was trained in), one decision every 32
 * samples; the samples are 1 ms apart and are consumed by [AbcSamples] for playback.
 */
internal class AbcPlanner(private val w: AbcWeights, seed: Long) {

    private val motorRng = RandomStream(seed)
    private val selectorRng = RandomStream(seed xor 0x5397A1L)
    private val brakeRng = RandomStream(seed xor 0x1763B4L)

    private var mode = -1

    /** The predecessor head's first sixteen rows, as the selector's pair block wants them. */
    private var previous: FloatArray? = null
    private var origin: DoubleArray? = null
    private var previousValid = false

    private var age = 160.0
    private var duration = 64.0
    private var holdError = 0.0
    private var innovAt = Double.NaN
    private var resumeIntegral = 0.0
    private var stopIntegral = 0.0
    private var resumeBudget = 0.0
    private var stopBudget = 0.0
    private var brakeVelocity = DoubleArray(2)
    private var brakeAcceleration = DoubleArray(2)
    private var holdGoal = DoubleArray(2)
    private var holdPosition = DoubleArray(2)
    private var brakeCoefficients = Array(5) { DoubleArray(2) }
    private var changed = false

    /** Decisions taken since the stream was created: the number that says the engine is running. */
    var decisions: Long = 0
        private set

    /** The mode the previous decision ended in, for the debug line: `move`, `brake`, `hold`. */
    val modeName: String
        get() = when (mode) {
            0 -> "move"
            1 -> "brake"
            2 -> "hold"
            else -> "start"
        }

    fun reset(newSeed: Long) {
        motorRng.reset(newSeed)
        selectorRng.reset(newSeed xor 0x5397A1L)
        brakeRng.reset(newSeed xor 0x1763B4L)
        mode = -1
        previous = null
        origin = null
        previousValid = false
        resumeIntegral = 0.0
        stopIntegral = 0.0
        age = 160.0
        duration = 64.0
        brakeVelocity = DoubleArray(2)
        brakeAcceleration = DoubleArray(2)
        brakeCoefficients = Array(5) { DoubleArray(2) }
        holdGoal = DoubleArray(2)
        holdPosition = DoubleArray(2)
        holdError = 0.0
        innovAt = Double.NaN
        changed = false
        decisions = 0
    }

    /**
     * One 32 ms decision: `history` is the chronological 640 ms window ending at [nowUs],
     * [position] is where the aim is now, `target` is the history of the goal (same layout, the
     * goal that was known at that row's time), `available` is the receipt timestamp of each target
     * row and `valid` / `motionKnown` say which rows mean anything.
     *
     * Returns 32 displacements in counts, `[dx, dy]` per millisecond.
     */
    fun plan(
        history: DoubleArray,
        position: DoubleArray,
        target: DoubleArray,
        available: LongArray,
        valid: BooleanArray,
        motionKnown: BooleanArray,
        nowUs: Long,
    ): Array<DoubleArray> {
        val known = valid[639] && available[639] <= nowUs
        val goal = if (known) doubleArrayOf(target[1278], target[1279]) else position.copyOf()

        if (mode < 0) initialize(history, position, goal, nowUs)
        val old = mode

        if (old != 0) {
            previousValid = false
            for (i in 0 until 32) {
                val at = 608 + i
                val recentKnown = valid[at] && available[at] <= nowUs + (i - 31L) * 1000L
                val dx = target[at * 2] - holdGoal[0]
                val dy = target[at * 2 + 1] - holdGoal[1]
                if (recentKnown && hypot(dx, dy) > 1e-7) {
                    changed = true
                    if (innovAt.isNaN()) innovAt = nowUs / 1000.0 - 31 + i
                    break
                }
            }
        }

        val innovationAge = if (innovAt.isNaN()) 0.0 else maxOf(0.0, nowUs / 1000.0 - innovAt)
        val event = PhysicalKernels.eventFeatures(
            history, position, target, available, valid, motionKnown, nowUs,
            age, holdGoal, holdPosition, holdError, innovationAge, old,
        )
        val innovation = old == 2 && (
                changed ||
                        hypot(position[0] - holdPosition[0], position[1] - holdPosition[1]) > 1e-5 ||
                        event.raw[0] > 10
                )

        val probabilities = FloatArray(2)
        if (old == 0 || innovation) {
            val hazard = w.hazard(event.context)
            for (i in 0 until 2) {
                probabilities[i] = (1.0 / (1 + kotlin.math.exp(-(hazard[i].coerceIn(-40f, 40f)).toDouble()))).toFloat()
            }
        }

        val draws = selectorRng.uniform(2)
        var brakeHead: Int? = null
        if (old == 0) {
            if (known) stopIntegral += -kotlin.math.ln(maxOf(1e-8, 1.0 - probabilities[0]))
            if (stopIntegral >= stopBudget && known) {
                val brake = w.brake(event.context)
                brakeHead = brakeRng.categorical(softmax(brake.frequency))
                mode = 1
                age = 0.0
                brakeVelocity = doubleArrayOf(history[1278], history[1279])
                val v8 = mean(history, 632, 640)
                val v8old = mean(history, 624, 632)
                brakeAcceleration = doubleArrayOf((v8[0] - v8old[0]) / 8, (v8[1] - v8old[1]) / 8)
                duration = brake.duration[brakeHead].toDouble()
                // The graph's coefficients are (16 heads, 5 bands, 2 axes); the reference rotates
                // the chosen head's five bands into the error frame of this decision.
                val local = Array(5) { DoubleArray(2) }
                for (j in 0 until 5) {
                    for (d in 0 until 2) {
                        local[j][d] = brake.coefficients[brakeHead * 10 + j * 2 + d].toDouble()
                    }
                }
                brakeCoefficients = Array(5) { j ->
                    doubleArrayOf(
                        local[j][0] * event.basis[0][0] + local[j][1] * event.basis[0][1],
                        local[j][0] * event.basis[1][0] + local[j][1] * event.basis[1][1],
                    )
                }
                holdGoal = goal.copyOf()
                holdError = event.raw[0].toDouble()
                changed = false
                innovAt = Double.NaN
            }
        } else if (old == 2) {
            if (innovation && known) {
                resumeIntegral += -kotlin.math.ln(maxOf(1e-8, 1.0 - probabilities[1]))
            }
            if (innovation && resumeIntegral >= resumeBudget && known) {
                mode = 0
                age = 0.0
                changed = false
                innovAt = Double.NaN
                stopIntegral = 0.0
                stopBudget = -kotlin.math.ln(maxOf(draws[0].toDouble(), 1e-8))
            }
        }
        if (brakeHead == null) brakeRng.discardCategorical()

        val latent = if (mode == 0) {
            motor(history, position, target, available, valid, motionKnown, nowUs)
        } else {
            motorRng.discardCategorical()
            null
        }

        val actual: Array<DoubleArray>
        when (mode) {
            1 -> {
                actual = PhysicalKernels.brakePath(
                    brakeVelocity, brakeAcceleration, duration, brakeCoefficients, age,
                )
                age += 32
                if (age >= duration) {
                    mode = 2
                    age = maxOf(0.0, age - duration)
                    var sx = 0.0
                    var sy = 0.0
                    for (row in actual) {
                        sx += row[0]
                        sy += row[1]
                    }
                    holdPosition = doubleArrayOf(position[0] + sx, position[1] + sy)
                    holdGoal = goal.copyOf()
                    holdError = hypot(goal[0] - holdPosition[0], goal[1] - holdPosition[1])
                    changed = false
                    innovAt = Double.NaN
                    resumeIntegral = 0.0
                    resumeBudget = -kotlin.math.ln(maxOf(draws[1].toDouble(), 1e-8))
                }
            }

            2 -> {
                actual = Array(32) { DoubleArray(2) }
                age += 32
            }

            else -> {
                actual = latent ?: error("motor did not answer a MOVE decision")
                age += 32
            }
        }
        if (mode != 0) previousValid = false
        for (row in actual) {
            for (value in row) {
                if (!value.isFinite() || kotlin.math.abs(value) > 1e5) {
                    throw IllegalStateException("ABCurves primitive is not finite")
                }
            }
        }
        decisions++
        return actual
    }

    /**
     * The MOVE decision: motor network → sixteen candidate heads → selector → the chosen head's
     * 32 displacements. The selector's pair block reads the *previous* head, so the stream has a
     * memory of what it just committed to; an invalidated predecessor (after a brake or a hold) is
     * zeroed and the graph's own `previous_valid` flag removes the term.
     */
    private fun motor(
        history: DoubleArray,
        position: DoubleArray,
        target: DoubleArray,
        available: LongArray,
        valid: BooleanArray,
        motionKnown: BooleanArray,
        nowUs: Long,
    ): Array<DoubleArray> {
        val features = PhysicalKernels.motorFeatures(history, position, target, available, valid, motionKnown, nowUs)
        val output = w.motor(features.coarse, features.fine, features.dynamics)
        val incoming = doubleArrayOf(history[1278], history[1279])
        val heads = w.decodeHeads(output.coefficients, incoming)
        val actualStep = origin?.let { doubleArrayOf(position[0] - it[0], position[1] - it[1]) }
            ?: DoubleArray(2)
        val (unary, pairs) = PhysicalKernels.selectorInputs(
            output.encoded,
            heads,
            if (previousValid) previous else null,
            actualStep,
        )
        val logits = w.choice(output.encoded, heads, pairs, previousValid)
        val chosen = motorRng.categorical(softmax(logits))
        previous = FloatArray(32) { i -> heads[(chosen * 16 + i / 2) * 2 + (i % 2)] }
        origin = position.copyOf()
        previousValid = true
        return w.decodeSelected(output.coefficients, chosen, incoming)
    }

    private fun initialize(history: DoubleArray, position: DoubleArray, goal: DoubleArray, nowUs: Long) {
        var max = 0.0
        for (i in 608 until 640) {
            max = maxOf(max, kotlin.math.abs(history[i * 2]), kotlin.math.abs(history[i * 2 + 1]))
        }
        mode = if (max <= 1e-12) 2 else 0
        age = 160.0
        duration = 64.0
        brakeVelocity = DoubleArray(2)
        brakeAcceleration = DoubleArray(2)
        brakeCoefficients = Array(5) { DoubleArray(2) }
        holdGoal = goal.copyOf()
        holdPosition = position.copyOf()
        holdError = hypot(goal[0] - position[0], goal[1] - position[1])
        changed = holdError > 1e-7
        innovAt = if (changed) nowUs / 1000.0 else Double.NaN
        resumeIntegral = 0.0
        stopIntegral = 0.0
        resumeBudget = -kotlin.math.ln(maxOf(selectorRng.uniform(1)[0].toDouble(), 1e-8))
        stopBudget = -kotlin.math.ln(maxOf(brakeRng.uniform(1)[0].toDouble(), 1e-8))
    }

    private fun mean(values: DoubleArray, start: Int, end: Int): DoubleArray {
        var x = 0.0
        var y = 0.0
        for (i in start until end) {
            x += values[i * 2]
            y += values[i * 2 + 1]
        }
        val n = (end - start).toDouble()
        return doubleArrayOf(x / n, y / n)
    }

    private fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)
}

/**
 * The streaming shell around [AbcPlanner]: a target in, one-millisecond positions out.
 *
 * `abcurves/_continuous/runtime.py`, ported with its two contracts intact because they are what
 * makes the output usable as an *aim*:
 *
 *  - **causality** — a target received at time `t` can influence the next uncommitted 32 ms plan,
 *    never one already in flight, and never a sample whose time has passed. There is no
 *    interpolation towards a future target and no sleeping: [advance] computes and returns.
 *  - **the warm start** — the ring is 640 rows of motion and 640 of target. The last 160 motion
 *    rows are known (zeros unless the caller supplies a warm history), the rest are unknown; that
 *    is the "we have been sitting here for 160 ms" state a stream starts in.
 *
 * The output is a list of absolute positions on the planner's own count grid (`x` right, `y` up),
 * still in counts: converting them to a yaw/pitch is the caller's job, because only the caller
 * knows which way is north.
 */
internal class AbcRuntime(private val weights: AbcWeights, seed: Long, x: Double, y: Double) {

    private val planner = AbcPlanner(weights, seed)
    private val history = DoubleArray(2 * HISTORY * 2)
    private val targetHistory = DoubleArray(2 * HISTORY * 2)
    private val available = LongArray(2 * HISTORY)
    private val valid = BooleanArray(2 * HISTORY)
    private val motionKnown = BooleanArray(2 * HISTORY)
    private val actions = Array(COMMIT) { DoubleArray(2) }
    private val actionPoints = Array(COMMIT) { DoubleArray(2) }
    private var position = doubleArrayOf(x, y)
    private var pending = COMMIT
    private var cursor = 0
    private var nowUs = 0L
    private var requestedUs = 0L
    private var lastReceiptUs = -1L
    private var receiptUs = 0L
    private var target: DoubleArray? = null
    private var observed = false
    private var seedValue = seed
    private var initial = doubleArrayOf(x, y)
    private val receipts = ArrayDeque<Receipt>()

    init {
        reset(seed, x, y)
    }

    /**
     * The samples [advance] returns: absolute positions, their displacements and their timestamps.
     *
     * Both are kept because they answer different questions. An *aim* wants the displacement — the
     * movement the plan asked for, which no change of frame can corrupt; a *diagnostic* (and the
     * parity test) wants the position, the same number the reference prints.
     */
    class Frame(
        private val times: LongArray,
        private val points: Array<DoubleArray>,
        private val deltas: Array<DoubleArray>,
    ) {
        val size: Int get() = times.size
        fun timeUs(index: Int): Long = times[index]
        fun x(index: Int): Double = points[index][0]
        fun y(index: Int): Double = points[index][1]
        fun dx(index: Int): Double = deltas[index][0]
        fun dy(index: Int): Double = deltas[index][1]
    }

    fun reset(newSeed: Long, x: Double, y: Double) {
        require(x.isFinite() && y.isFinite()) { "initial position must be finite" }
        seedValue = newSeed
        initial = doubleArrayOf(x, y)
        position = initial.copyOf()
        nowUs = 0
        requestedUs = 0
        receiptUs = 0
        lastReceiptUs = -1
        target = null
        cursor = 0
        pending = COMMIT
        observed = false
        receipts.clear()
        history.fill(0.0)
        targetHistory.fill(0.0)
        available.fill(0)
        valid.fill(false)
        motionKnown.fill(false)
        for (i in HISTORY - WARM until HISTORY) {
            motionKnown[i] = true
            motionKnown[i + HISTORY] = true
        }
        planner.reset(newSeed)
    }

    /**
     * Queues a target, in counts, received at [atUs] on the stream's own clock.
     *
     * Throws when the timestamp is not monotonic or precedes committed movement: a target from the
     * past would change a plan that is already in flight, which is the one thing this stream exists
     * not to do.
     */
    fun updateTarget(x: Double, y: Double, atUs: Long) {
        require(x.isFinite() && y.isFinite()) { "target must be finite" }
        require(atUs >= 0 && atUs >= maxOf(nowUs, lastReceiptUs)) { "target receipt must be monotonic" }
        receipts.addLast(Receipt(atUs, doubleArrayOf(x, y)))
        lastReceiptUs = atUs
    }

    /**
     * Returns every 1 ms position endpoint that has closed up to [untilUs], oldest first.
     *
     * With no target, the position does not move and the policy's randomness is *not* consumed, so
     * a stream that is switched on before the crosshair finds anybody starts its draw stream where
     * a stream that was started on a target would have.
     */
    fun advance(untilUs: Long): Frame {
        require(untilUs >= 0 && untilUs >= requestedUs) { "advance clock must be monotonic" }
        requestedUs = untilUs
        val count = Math.toIntExact((untilUs - nowUs) / SAMPLE_US).coerceAtLeast(0)
        val times = LongArray(count)
        val points = Array(count) { DoubleArray(2) }
        val deltas = Array(count) { DoubleArray(2) }
        arrive()
        var written = 0
        while (written < count) {
            if (pending == COMMIT) {
                if (target == null) {
                    append(null)
                    nowUs += SAMPLE_US
                    arrive()
                    points[written] = position.copyOf()
                    times[written] = nowUs
                    written++
                    continue
                }
                plan()
            }
            val at = pending++
            position = actionPoints[at].copyOf()
            append(actions[at])
            deltas[written][0] = actions[at][0]
            deltas[written][1] = actions[at][1]
            nowUs += SAMPLE_US
            arrive()
            points[written] = position.copyOf()
            times[written] = nowUs
            written++
        }
        return Frame(times, points, deltas)
    }

    /**
     * Moves the stream's own notion of where it is, without changing the movement it planned.
     *
     * This is what an *assist* needs and a pure planner does not: the camera is shared with a hand,
     * and when the hand takes the crosshair somewhere the stream did not go, the stream has to
     * continue from where the camera actually is — otherwise the distance the planner still believes
     * it has to cover is the distance to a place the crosshair left.
     *
     * What moves and what does not is the whole point:
     *
     *  - the **displacements** of the commitment in flight do not change — a movement is a movement,
     *    and the samples already played are history;
     *  - the **origin** of the samples not yet played shifts with the position, so the path continues
     *    from where the camera is instead of jumping back to where the plan thought it was.
     *
     * That makes the shift immediate and idempotent: the stream is where it says it is from this
     * call on, which is what lets the caller compare the camera against [currentX]/[currentY] on the
     * very next tick without the two disagreeing in between.
     */
    fun rebase(dx: Double, dy: Double) {
        if (!dx.isFinite() || !dy.isFinite()) return
        position[0] += dx
        position[1] += dy
        // Anything not yet played out of the current commitment keeps its slope and moves with the
        // frame, so no sample jumps when the origin moves under it.
        var at = pending
        while (at < COMMIT) {
            actionPoints[at][0] += dx
            actionPoints[at][1] += dy
            at++
        }
        // The history is a record of displacements, which are frame-independent: it is left alone.
    }

    /** Where the stream is now, in counts: the last committed endpoint. */
    fun currentX(): Double = position[0]

    fun currentY(): Double = position[1]

    fun decisions(): Long = planner.decisions

    fun modeName(): String = planner.modeName

    private fun plan() {
        if (!observed) {
            for (k in 0 until HISTORY - WARM) {
                val i = (cursor + k) % HISTORY
                history[i * 2] = 0.0
                history[i * 2 + 1] = 0.0
                history[(i + HISTORY) * 2] = 0.0
                history[(i + HISTORY) * 2 + 1] = 0.0
                targetHistory[i * 2] = 0.0
                targetHistory[i * 2 + 1] = 0.0
                targetHistory[(i + HISTORY) * 2] = 0.0
                targetHistory[(i + HISTORY) * 2 + 1] = 0.0
                available[i] = 0
                available[i + HISTORY] = 0
                valid[i] = false
                valid[i + HISTORY] = false
                motionKnown[i] = false
                motionKnown[i + HISTORY] = false
            }
            observed = true
        }
        val planned = planner.plan(
            ordered(history), position.copyOf(), ordered(targetHistory), orderedLong(available),
            orderedBool(valid), orderedBool(motionKnown), nowUs,
        )
        check(planned.size == COMMIT) { "policy returned ${planned.size} samples" }
        var origin = position
        for (j in 0 until COMMIT) {
            actions[j][0] = planned[j][0]
            actions[j][1] = planned[j][1]
        }
        // The reference rebases the absolute path on every 8 ms slice; keeping that summation
        // boundary keeps the float64 error identical to the Python runtime's.
        var base = 0
        while (base < COMMIT) {
            for (j in base until base + 8) {
                if (!actions[j][0].isFinite() || !actions[j][1].isFinite()) {
                    throw IllegalStateException("ABCurves action is not finite")
                }
                var px = origin[0]
                var py = origin[1]
                for (k in base..j) {
                    px += actions[k][0]
                    py += actions[k][1]
                }
                actionPoints[j][0] = px
                actionPoints[j][1] = py
            }
            origin = actionPoints[base + 7].copyOf()
            base += 8
        }
        pending = 0
    }

    private fun arrive() {
        if (receipts.isEmpty() || receipts.first().timeUs > nowUs) return
        while (receipts.isNotEmpty() && receipts.first().timeUs <= nowUs) {
            val receipt = receipts.removeFirst()
            receiptUs = receipt.timeUs
            target = receipt.point
        }
        val at = (cursor + HISTORY - 1) % HISTORY
        val point = target ?: return
        writeTarget(at, point)
        available[at] = receiptUs
        available[at + HISTORY] = receiptUs
        valid[at] = true
        valid[at + HISTORY] = true
    }

    private fun writeTarget(at: Int, point: DoubleArray) {
        targetHistory[at * 2] = point[0]
        targetHistory[at * 2 + 1] = point[1]
        targetHistory[(at + HISTORY) * 2] = point[0]
        targetHistory[(at + HISTORY) * 2 + 1] = point[1]
    }

    private fun append(delta: DoubleArray?) {
        val at = cursor
        val mirror = at + HISTORY
        history[at * 2] = if (delta == null) 0.0 else delta[0]
        history[at * 2 + 1] = if (delta == null) 0.0 else delta[1]
        history[mirror * 2] = history[at * 2]
        history[mirror * 2 + 1] = history[at * 2 + 1]
        val point = target
        if (point == null) {
            writeTarget(at, ZERO)
            available[at] = 0
            available[mirror] = 0
            valid[at] = false
            valid[mirror] = false
        } else {
            writeTarget(at, point)
            available[at] = receiptUs
            available[mirror] = receiptUs
            valid[at] = true
            valid[mirror] = true
        }
        motionKnown[at] = true
        motionKnown[mirror] = true
        cursor = (at + 1) % HISTORY
    }

    private fun ordered(source: DoubleArray): DoubleArray {
        val out = DoubleArray(HISTORY * 2)
        for (i in 0 until HISTORY) {
            val src = (cursor + i) % HISTORY
            out[i * 2] = source[src * 2]
            out[i * 2 + 1] = source[src * 2 + 1]
        }
        return out
    }

    private fun orderedLong(source: LongArray): LongArray = LongArray(HISTORY) { source[(cursor + it) % HISTORY] }

    private fun orderedBool(source: BooleanArray): BooleanArray = BooleanArray(HISTORY) { source[(cursor + it) % HISTORY] }

    private class Receipt(val timeUs: Long, val point: DoubleArray)

    private companion object {
        const val HISTORY = 640
        const val WARM = 160
        const val COMMIT = 32
        const val SAMPLE_US = 1000L
        val ZERO = doubleArrayOf(0.0, 0.0)
    }
}
