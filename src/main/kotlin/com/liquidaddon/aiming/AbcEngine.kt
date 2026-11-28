package com.liquidaddon.aiming

import com.liquidaddon.abcurses.AbcAssetException
import com.liquidaddon.abcurses.AbcRuntime
import com.liquidaddon.abcurses.AbcWeights
import com.liquidaddon.abcurses.PhysicalKernels
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The ABCurves engine, as an aim engine of this add-on: a goal in degrees in, a camera step in
 * degrees out.
 *
 * Everything under the stream is the model's business (see `com.liquidaddon.abcurses`): a hybrid
 * policy that decides on its own whether the hand is *moving*, *braking* or *holding*, and emits
 * one-millisecond positions in common angular counts. Everything above it is the engine's business,
 * and deliberately the same business every other engine here has:
 *
 *  - the **goal** is the point the assist already picked on the hitbox (`AimPoint`, `Predict`), so
 *    the three engines are interchangeable on a tick and a profile can switch between them;
 *  - the **gates** stay the assist's: the stream is asked for a step only while the assist would
 *    grant help, and the step it returns is the plan's own movement, capped by `MaxSpeed` and
 *    scaled by `ABCStrength`/`PitchAssist`;
 *  - the **lattice** is applied by the caller, where `GcdFix`, `GcdDither` and `MouseNoise` already
 *    act for the other two engines.
 *
 * ## Why a stream and not a per-tick formula
 *
 * The point of ABCurves is that it plans a *movement*: a 32 ms commitment, with an approach that
 * accelerates like a hand, a brake that ends on the target instead of past it, and a hold that is
 * genuinely still. That is also why the step has to be sampled by wall clock (a millisecond at a
 * time) rather than once per 50 ms tick — sampling the plan at tick rate would put the staircase
 * back that the plan was built to avoid.
 *
 * ## Resynchronisation
 *
 * The camera is shared with a hand. When the player takes the aim back by hand, the camera leaves
 * the stream's path; a stream that kept planning towards the old position would then pull the view
 * back to it, which is the one thing an assist must never do. So every tick the engine compares the
 * camera with where the stream believes it is, and when the gap is larger than `ABCResync` mouse
 * counts it *rebases* the stream on the camera: the plan in flight keeps its displacements (it is a
 * movement, not a position), but from the next decision on they are measured from where the view
 * actually is. That is the difference between a port that was dropped in and one that belongs.
 *
 * ## Failure
 *
 * The archive can be missing from a jar, a client can be mid-load, a stream can refuse a goal it
 * cannot represent. None of that is allowed to stop the aim: [step] returns `null`, the tick falls
 * back to the curve, and the reason is visible in `/liquidaddon ai` and the `Debug` line rather than
 * guessed from a crosshair that does not move.
 */
internal class AbcEngine(private val settings: () -> AbcSettings?) {

    /** What the engine is doing, for the debug line and the telemetry. */
    enum class State { OFF, LOADING, READY, FAILED }

    private var weights: AbcWeights? = null
    private var runtime: AbcRuntime? = null
    private var state = State.OFF
    private var failure: String? = null

    /** The stream's own clock: 0 at [begin], microseconds since. */
    private var startedAtNs = 0L
    private var lastAdvanceNs = 0L
    private var lastReceiptUs = -1L
    private var remainderMs = 0.0

    /**
     * The camera's yaw as a continuous number, and the raw yaw it was last advanced from.
     *
     * Yaw is reported in `-180..180` and wraps every half turn; the planner works in an absolute
     * count that must never wrap, or a target that crossed north would be planned as a 360° turn the
     * other way. So the raw angle is unwrapped onto a running total, and every other yaw in this
     * class is measured from it: the camera's count position, and the goal (which is at most half a
     * turn from the camera, and therefore unambiguous from there).
     */
    private var continuousYaw = 0.0
    private var lastRawYaw = 0.0

    /** Degrees the stream itself has moved this session: the telemetry's "is it driving". */
    var movedDegrees = 0.0
        private set

    private var decisions = 0L
    private var holds = 0
    private var rebases = 0
    private var lastMode = "start"
    private var lastNote = "abc idle"

    val isReady: Boolean get() = state == State.READY && runtime != null

    /** One line about the engine, as the debug line and `/liquidaddon ai` print it. */
    val status: String
        get() = when (state) {
            State.OFF -> "abc off"
            State.LOADING -> "abc loading"
            State.READY -> "abc $lastMode, %d dec, %d hold, %d resync, %.1f°".format(
                decisions, holds, rebases, movedDegrees,
            )
            State.FAILED -> "abc failed: ${failure ?: "?"}"
        }

    val note: String get() = lastNote

    /** Decisions the planner took this session (32 ms plans). */
    val decisionCount: Long get() = decisions

    /** Ticks the planner spent holding still: the settle half of the movement. */
    val holdCount: Int get() = holds

    /** Times the camera left the plan and the stream was rebased on it. */
    val resyncCount: Int get() = rebases

    /**
     * Parses the archive once per session. The weights are immutable and 830 kB, so they are kept
     * until the add-on stops; a failure is remembered too, so a missing asset does not cost a
     * parse attempt every tick.
     */
    fun prepare(): Boolean {
        if (state == State.READY) return true
        if (state == State.FAILED) return false
        state = State.LOADING
        val loaded = runCatching {
            // `Class.getResourceAsStream` and not the class loader: only the former accepts an
            // absolute path, and only the absolute path finds a resource outside this package.
            val stream = AbcEngine::class.java.getResourceAsStream(AbcWeights.RESOURCE)
                ?: throw AbcAssetException("${AbcWeights.RESOURCE} is not in the add-on jar")
            AbcWeights.load(stream, verify = true)
        }
        return loaded.fold(
            onSuccess = {
                weights = it
                state = State.READY
                failure = null
                true
            },
            onFailure = { error ->
                weights = null
                state = State.FAILED
                failure = error.message ?: error.javaClass.simpleName
                false
            },
        )
    }

    /**
     * Starts the stream at the camera's angles and forgets everything before it.
     *
     * A stream is bound to a seed and an origin; starting one per tick would throw away the
     * movement being built, so this happens when the module is enabled and when the engagement
     * changes — never on a resync, which only [rebase]s.
     */
    fun begin(yaw: Double, pitch: Double) {
        if (!prepare()) return
        val w = weights ?: return
        continuousYaw = yaw
        lastRawYaw = yaw
        val originX = PhysicalKernels.degreesToCounts(yaw)
        val originY = PhysicalKernels.degreesToCounts(-pitch)
        runtime = AbcRuntime(w, System.nanoTime() xor Random.nextLong(), originX, originY)
        startedAtNs = System.nanoTime()
        lastAdvanceNs = startedAtNs
        remainderMs = 0.0
        lastReceiptUs = -1L
        lastMode = "start"
        lastNote = "abc started"
    }

    /** Drops the stream; the weights stay loaded. */
    fun stop() {
        runtime = null
        state = if (weights != null) State.READY else State.OFF
    }

    /** Forgets the stream and the session counters; called when the module is disabled. */
    fun reset() {
        runtime = null
        decisions = 0
        holds = 0
        rebases = 0
        movedDegrees = 0.0
        remainderMs = 0.0
        lastAdvanceNs = 0L
        lastReceiptUs = -1L
        startedAtNs = 0L
        lastMode = "start"
        lastNote = "abc idle"
        failure = null
        state = if (weights != null) State.READY else State.OFF
    }

    /**
     * The step of this tick in degrees, or `null` when the stream has nothing to say and the caller
     * should run its own curve.
     *
     * [cameraYaw]/[cameraPitch] are where the aim is now (the camera, or the silent rotation) and
     * [goalYaw]/[goalPitch] the point the assist is steering by. The stream is not restarted when
     * the target changes: a planner's whole value is that its movement survives the crosshair moving
     * from one body to the next, and the goal is simply a new point on the same path.
     */
    fun step(
        cameraYaw: Double,
        cameraPitch: Double,
        goalYaw: Double,
        goalPitch: Double,
    ): Pair<Float, Float>? {
        val settings = settings() ?: return null
        if (!prepare()) {
            lastNote = "abc: ${failure ?: "assets missing"}"
            return null
        }
        val runtime = runtime ?: run {
            // Enabled while the aim was already going: the stream starts on the camera and answers
            // on the next tick, because a plan made this tick has nothing to say about this tick.
            begin(cameraYaw, cameraPitch)
            return null
        }

        // Yaw is unwrapped before it becomes a count: the camera reports -180..180, the planner works
        // in a coordinate that must not wrap. Pitch does not wrap, and its sign flips exactly once,
        // here: positive y is up, positive pitch is down.
        continuousYaw += PhysicalKernels.wrapDegrees(cameraYaw - lastRawYaw)
        lastRawYaw = cameraYaw
        val cameraX = PhysicalKernels.degreesToCounts(continuousYaw)
        val cameraY = PhysicalKernels.degreesToCounts(-cameraPitch)

        // The hand took the aim somewhere the plan does not go: the stream continues from the
        // camera instead of planning a way back. Measured against where the stream *is*, not against
        // the last sample it handed out — since a rebase moves the stream itself, the two are the
        // same thing, and there is no gap the engine could count twice.
        val gapX = cameraX - runtime.currentX()
        val gapY = cameraY - runtime.currentY()
        if (hypot(gapX, gapY) > settings.abcResyncCounts) {
            runtime.rebase(gapX, gapY)
            rebases++
        }

        // The goal in the stream's frame: half a turn from the camera at most, measured from the
        // camera's own continuous yaw, so a target that crossed north is one degree away and not
        // three hundred and fifty-nine in the other direction.
        val targetX = PhysicalKernels.degreesToCounts(
            continuousYaw + PhysicalKernels.wrapDegrees(goalYaw - cameraYaw),
        )
        val targetY = PhysicalKernels.degreesToCounts(-goalPitch)

        val now = System.nanoTime()
        var us = (now - startedAtNs) / 1_000L
        if (us <= lastReceiptUs) {
            // A receipt has to be strictly monotonic: two ticks inside one millisecond are separated
            // by a microsecond here rather than dropped.
            us = lastReceiptUs + 1
        }
        lastReceiptUs = us
        runCatching { runtime.updateTarget(targetX, targetY, us) }.onFailure {
            lastNote = "abc: target refused (${it.javaClass.simpleName})"
            return null
        }
        val frame = runCatching { runtime.advance(us) }.getOrElse {
            // The stream refuses to continue after a bad primitive; restart it on the camera, which
            // is always a valid position, instead of leaving the assist without an engine.
            lastNote = "abc restart: ${it.message ?: it.javaClass.simpleName}"
            begin(cameraYaw, cameraPitch)
            return null
        }
        if (frame.size == 0) {
            // Less than a millisecond since the last tick: nothing new was planned.
            return null
        }

        // The plan is read as a *movement*, not as a position: the samples are the displacement of
        // each millisecond, so what the camera is asked for is exactly what the planner asked to
        // move, whatever the camera did in between. Sampling it by wall clock — one millisecond at a
        // time, carrying the sub-millisecond remainder — keeps the shape at any frame rate instead of
        // putting the tick's staircase back.
        remainderMs += (now - lastAdvanceNs) / 1_000_000.0
        lastAdvanceNs = now
        var stepX = 0.0
        var stepY = 0.0
        var read = 0
        while (remainderMs >= 1.0 && read < frame.size) {
            stepX += frame.dx(read)
            stepY += frame.dy(read)
            read++
            remainderMs -= 1.0
        }
        if (read == 0) {
            // A tick shorter than the sample interval still gets the first sample it earned: leaving
            // it for the next tick would put the movement a millisecond behind the clock forever.
            stepX = frame.dx(0)
            stepY = frame.dy(0)
            // The remainder is then negative, and the next tick pays it back.
            remainderMs -= 1.0
        }

        decisions = runCatching { runtime.decisions() }.getOrDefault(decisions)
        lastMode = runCatching { runtime.modeName() }.getOrDefault("move")
        if (lastMode == "hold") holds++

        var stepYaw = PhysicalKernels.countsToDegrees(stepX).toFloat()
        var stepPitch = (-PhysicalKernels.countsToDegrees(stepY)).toFloat()

        // The plan is a movement a hand makes; taking a fraction of it is a shape no hand makes, so
        // the slider exists for a model that turned out too strong, not as a second throttle.
        val share = (settings.abcStrength / 100f).coerceIn(0f, 1f)
        stepYaw *= share
        stepPitch *= share

        // The vertical axis is where a hand is weakest and where a stream chasing a target that
        // steps up and down is most visible: `PitchAssist` still owns that axis on this engine.
        stepPitch *= (settings.pitchAssist / 100f).coerceIn(0f, 1f)

        if (kotlin.math.abs(stepYaw) < 1e-4f && kotlin.math.abs(stepPitch) < 1e-4f) {
            // A hold: the plan asked for nothing, so nothing is written — the same tick the assist
            // writes on a resting hand.
            lastNote = "abc hold"
            return null
        }
        movedDegrees += hypot(stepYaw.toDouble(), stepPitch.toDouble())
        lastNote = "abc $lastMode %.2f°/%.2f°".format(stepYaw, stepPitch)
        return stepYaw to stepPitch
    }

    /** The reason the engine is not answering right now, or `null` when it is. */
    fun unavailableReason(): String? = when {
        state == State.FAILED -> failure ?: "assets missing"
        state != State.READY -> "engine not loaded"
        runtime == null -> "stream not started"
        else -> null
    }
}

/**
 * What the ABCurves stream reads from the module: four values, and nothing about the client.
 *
 * An interface rather than the module itself on purpose — the stream is testable without a
 * Minecraft client, and the two modules that own an aim (`Aimassist` and `AimbotAI`) can feed it
 * from their own setting lists without the engine knowing which one it is running under.
 */
internal interface AbcSettings {

    /** The hard ceiling of a single tick, in degrees: the player's own speed, kept hard. */
    val maxSpeed: Float

    /** Share of the vertical correction, in percent: a hand is weakest on that axis. */
    val pitchAssist: Float

    /**
     * How much of the plan is taken, in percent.
     *
     * `100` is the model's movement whole — the shape it was trained to produce. Lower values take a
     * fraction of a hand's movement, which is a shape no hand produces, so this is a slider for a
     * model that turned out too strong for a server, not a second throttle.
     */
    val abcStrength: Float

    /**
     * How far the camera may be from where the stream thinks it is, in mouse counts, before the
     * stream continues from the camera instead of from its own plan.
     *
     * The number this engine turns on: too small and every hand movement restarts the plan (the
     * stream never gets to brake, which is the half of the movement it is good at); too large and it
     * keeps pulling the view back towards a goal the player has already left. Sixteen counts is
     * about a third of a degree at the default sensitivity — a hand's jitter, not a turn.
     */
    val abcResyncCounts: Float
}
