package com.liquidaddon.aiming

/**
 * The client-stall guard: what a freeze does to an aim assist, and what this class takes back.
 *
 * A stall — a frozen client (garbage collection, a driver hitch, a saturated CPU) or a frozen
 * server (a lag spike) — lasts seconds while ticks are fifty milliseconds, and it leaves three
 * wounds on the tick that resumes:
 *
 *  1. **The queued jump.** The mouse kept reporting while nothing was running, so the first frame
 *     after the freeze applies seconds of movement at once: one camera packet of tens of degrees,
 *     the single most machine-impossible number a rotation check can read. In visible mode the
 *     camera *is* the packet, so the jump itself cannot be taken off the wire — but the assist can
 *     refuse to *stack* its own step on top of it, which is the difference between the jump and
 *     the jump plus a pull;
 *  2. **A hand that never moved.** The gate ([HandGate]) measures the player's own turn, and the
 *     queued jump reads as a huge one: the assist would be certified for a hand that was in fact
 *     frozen, exactly the tick it should have stood aside;
 *  3. **A silent friction spike.** In silent mode friction subtracts a share of the player's own
 *     turn from the rotation the server receives; subtracting a queued jump subtracts tens of
 *     degrees of movement nobody made.
 *
 * So the guard watches the wall clock between ticks. A gap of [stallMs] or more opens a window:
 * the resume tick glides at [RESUME_SHARE] of its step instead of full strength, the strength
 * ramps back to one over [recoveryTicks], and for the first [SANITIZE_TICKS] ticks of the window
 * the measured own-turn is reported as zero to whoever asks ([trustedOwn], [trustedCounts]) — the
 * jump is not a hand. Stalls are counted ([stalls]) with their last gap ([lastStallMs]) so that
 * `/liquidaddon ai` can say how many freezes a session flew through: a flag that lands on a stall
 * tick is a different fact from a flag that lands on a shaped step.
 *
 * The class is stateful but has no dependencies — a clock and counters — so the whole rule is
 * testable without a game running (see `StallGuardTest`).
 */
internal class StallGuard(
    private val stallMs: Long = DEFAULT_STALL_MS,
    private val recoveryTicks: Int = DEFAULT_RECOVERY_TICKS,
) {

    /** Where the tick sits against the last freeze: outside the window, on it, or behind it. */
    enum class Phase { NORMAL, RESUME, RECOVERY }

    /** Freezes seen since [reset]: the number the telemetry line reports. */
    var stalls: Int = 0
        private set

    /** How long the last freeze lasted, in milliseconds; 0 before the first one. */
    var lastStallMs: Long = 0
        private set

    /** The phase of the tick [observe] last saw. */
    var phase: Phase = Phase.NORMAL
        private set

    /** Ticks since the last freeze opened: -1 outside the window, 0 on the resume tick. */
    private var tickSinceStall = -1

    private var lastTickMs: Long? = null

    /** Forgets the clock and the counters: a fresh session has seen nothing. */
    fun reset() {
        stalls = 0
        lastStallMs = 0
        phase = Phase.NORMAL
        tickSinceStall = -1
        lastTickMs = null
    }

    /**
     * One tick, seen at wall-clock [nowMs]. Returns the phase of *this* tick: [Phase.RESUME] for
     * the first tick after a gap of [stallMs] or more, [Phase.RECOVERY] for the [recoveryTicks]
     * ticks behind it, [Phase.NORMAL] everywhere else. The very first tick of a session has no
     * gap to compare and is normal.
     */
    fun observe(nowMs: Long): Phase {
        val last = lastTickMs
        lastTickMs = nowMs
        val gap = if (last == null) 0L else nowMs - last
        phase = if (last != null && gap >= stallMs) {
            stalls++
            lastStallMs = gap
            tickSinceStall = 0
            Phase.RESUME
        } else if (tickSinceStall in 0 until recoveryTicks) {
            tickSinceStall++
            Phase.RECOVERY
        } else {
            tickSinceStall = -1
            Phase.NORMAL
        }
        return phase
    }

    /**
     * The share of the assist's step this tick may hand out. The resume tick glides at
     * [RESUME_SHARE] — the same fraction an idle tick glides at, so a freeze and a rest look alike
     * from the wire — and the window climbs back to one linearly, never in a single step.
     */
    fun assistShare(): Float = when (val i = tickSinceStall) {
        -1 -> 1f
        0 -> RESUME_SHARE
        // The clamp is the contract, not a courtesy: 0.15f + 0.85f is above one in binary
        // floats, and a share above one would hand out more step than the tick computed.
        else -> (RESUME_SHARE + (1f - RESUME_SHARE) * i / recoveryTicks).coerceAtMost(1f)
    }

    /**
     * The player's own turn of this tick as the gate and friction may read it: zero while the
     * queued jump can still be the whole measurement ([SANITIZE_TICKS] ticks from the freeze),
     * the measured value everywhere else. A frozen hand is not a moving one, however loud the
     * packet that says otherwise.
     */
    fun trustedOwn(measured: Float): Float =
        if (tickSinceStall in 0 until SANITIZE_TICKS) 0f else measured

    /** The mouse counts of this tick as [HandGate] may certify them: see [trustedOwn]. */
    fun trustedCounts(measured: Float): Float = trustedOwn(measured)

    companion object {

        /**
         * The gap that counts as a freeze, in milliseconds. A tick is fifty; a client under load
         * drops to a hundred, a hard hitch to two or three hundred. Five hundred is above every
         * ordinary bad tick and far below the seconds a real stall lasts.
         */
        const val DEFAULT_STALL_MS = 500L

        /** Ticks of ramp behind the resume tick before the assist is at full strength again. */
        const val DEFAULT_RECOVERY_TICKS = 3

        /** The glide of the resume tick: the idle glide of the engine, on purpose. */
        const val RESUME_SHARE = 0.15f

        /**
         * Ticks from the freeze during which the measured own-turn is not trusted: the queued
         * delta lands on the first frame after the stall, which is the resume tick or the one
         * behind it, depending on where the frame boundary fell.
         */
        const val SANITIZE_TICKS = 2
    }
}
