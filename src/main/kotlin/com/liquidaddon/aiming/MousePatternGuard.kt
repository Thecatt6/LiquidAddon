package com.liquidaddon.aiming

import kotlin.math.abs
import kotlin.random.Random

/**
 * The steps that actually leave, read as a *sequence* instead of one at a time.
 *
 * [AimassistCore.quantizeDelta] already makes every single step look like a mouse: whole counts,
 * a hand's rounding, counts of noise on top. What none of that can fix is the shape the sequence
 * takes over time, because the sequence is not chosen by the noise — it is chosen by the control
 * loop underneath:
 *
 *  - an aim that has arrived is *on* the target, so every tick asks for the same correction, the
 *    quantiser rounds it to the same count and the packets carry `+1, +1, +1, ...`;
 *  - an aim that is one count off alternates: the correction pushes it over, the next one pulls it
 *    back, and the packets carry `+1, -1, +1, -1, ...` for as long as the target stands there.
 *
 * Both are cycles, and a cycle is exactly what a "pattern" check is built to find — it does not
 * need to know how the numbers were produced, only that they keep coming back. The assist is the
 * worse case of the two aiming modules: it holds the crosshair *on* the target for whole seconds
 * (that is its job), while the aimbot is usually in the middle of a flick, and when the player is
 * not moving the mouse at all the packets are the module's alone, with no hand mixed in.
 *
 * This guard watches the last [HISTORY] steps the engine handed out and refuses to append one that
 * would close a cycle of up to [MAX_PERIOD] steps repeated [CYCLES] times. When that is about to
 * happen the step is nudged by a count (never across the goal: a step that was going right is not
 * turned into one going left), and the count it was nudged by is written down as a debt. The debt
 * is paid back on a later step, in the same direction and one count smaller, so the path adds up to
 * what the engine planned and only the digits differ. A hand that is still (`0, 0` for ticks on
 * end) is left alone: resting is not a pattern, it is what a crosshair does when nobody moves.
 */
internal class MousePatternGuard(
    private val history: Int = HISTORY,
    private val maxPeriod: Int = MAX_PERIOD,
    private val random: Random = Random.Default,
) {
    init {
        require(history >= maxPeriod * CYCLES) {
            "history ($history) must hold $CYCLES full cycles of up to $maxPeriod steps"
        }
    }

    /** The steps handed out, newest at [cursor]; two counts packed into one value. */
    private val ring = LongArray(history)
    private var cursor = 0
    private var filled = 0

    /**
     * What a repair owes the path, in counts, per axis. Positive means the engine is one count
     * ahead of where it wanted to be and the next steps must be smaller; negative is the opposite.
     */
    private var owedYaw = 0
    private var owedPitch = 0

    /** Steps this guard changed since the last [reset]; the debug line shows it. */
    var repairs = 0
        private set

    fun reset() {
        ring.fill(0L)
        cursor = 0
        filled = 0
        owedYaw = 0
        owedPitch = 0
        repairs = 0
    }

    /**
     * Returns the step to send in place of [yaw]/[pitch] (whole mouse counts, per axis).
     *
     * Called on every step that reaches the lattice, i.e. once per tick while the assist is
     * correcting. Cheap on purpose: no allocation, at most eight candidates tried.
     */
    fun guard(yaw: Int, pitch: Int): Pair<Int, Int> {
        // 1. Pay what an earlier repair owes. Not on the very next tick every time — a debt paid
        //    back on a fixed beat is itself a beat — and never by turning a step around: the aim
        //    still has to go where the engine pointed it.
        var y = yaw
        var p = pitch
        if (owedYaw != 0 && y != 0 && random.nextFloat() < PAY_CHANCE) {
            val paid = settle(y, owedYaw)
            owedYaw -= paid - y
            y = paid
        }
        if (owedPitch != 0 && p != 0 && random.nextFloat() < PAY_CHANCE) {
            val paid = settle(p, owedPitch)
            owedPitch -= paid - p
            p = paid
        }

        // 2. The candidate takes its slot before it is tested, so the test reads it as the newest
        //    step of a window that is always in the right order.
        ring[cursor] = pack(y, p)
        val period = cycle()
        if (period > 0 && !(ring[cursor] == REST && cycleIsRest(period))) {
            val fixed = breakCycle(y, p)
            if (fixed != null) {
                owedYaw = (owedYaw + (y - fixed[0])).coerceIn(-MAX_OWED, MAX_OWED)
                owedPitch = (owedPitch + (p - fixed[1])).coerceIn(-MAX_OWED, MAX_OWED)
                y = fixed[0]
                p = fixed[1]
                ring[cursor] = pack(y, p)
                repairs++
            }
        }

        cursor = (cursor + 1) % history
        if (filled < history) filled++
        return y to p
    }

    /** The smallest cycle length the window closes, or 0 when it closes none. */
    private fun cycle(): Int {
        val depth = minOf(filled + 1, history)
        for (period in 1..maxPeriod) {
            val need = period * CYCLES
            if (depth < need) continue
            var same = true
            var i = 0
            while (i < need - period) {
                if (sample(i) != sample(i + period)) {
                    same = false
                    break
                }
                i++
            }
            if (same) return period
        }
        return 0
    }

    /** True when the whole window of [period] is `0, 0`: a hand at rest, which is not a pattern. */
    private fun cycleIsRest(period: Int): Boolean {
        for (i in 0 until period * CYCLES) {
            if (sample(i) != REST) return false
        }
        return true
    }

    private fun sample(stepsBack: Int): Long {
        var index = cursor - stepsBack
        while (index < 0) index += history
        return ring[index]
    }

    /**
     * One count off the step, on one axis only: first the axis the engine is busier on, and on
     * both directions of it before the other axis is touched. The change that breaks the cycle is
     * taken, so the sequence never falls back into the same window by being repaired "the usual
     * way" — that is the trap this whole class exists to avoid, and a fixed repair would be a
     * pattern of its own.
     *
     * Returns `null` when no nudge of one or two counts closes the window (a step of one count on
     * both axes, with every neighbour taken), in which case the step is left exactly as planned.
     */
    private fun breakCycle(yaw: Int, pitch: Int): IntArray? {
        val yawFirst = abs(yaw) >= abs(pitch)
        for (amount in 1..MAX_NUDGE) {
            for (pass in 0..1) {
                val onYaw = if (pass == 0) yawFirst else !yawFirst
                val sign = if (random.nextBoolean()) 1 else -1
                if (onYaw) {
                    val candidate = nudge(yaw, sign, amount) ?: continue
                    if (accepts(candidate, pitch)) return intArrayOf(candidate, pitch)
                } else {
                    val candidate = nudge(pitch, sign, amount) ?: continue
                    if (accepts(yaw, candidate)) return intArrayOf(yaw, candidate)
                }
            }
        }
        return null
    }

    /** The nudge, unless it would turn the step around: an aim never walks away from its goal. */
    private fun nudge(counts: Int, sign: Int, amount: Int): Int? {
        val nudged = counts + sign * amount
        if (counts > 0 && nudged < 0) return null
        if (counts < 0 && nudged > 0) return null
        return nudged
    }

    /** True when [yaw]/[pitch] would not close the window it is about to be appended to. */
    private fun accepts(yaw: Int, pitch: Int): Boolean {
        val kept = ring[cursor]
        ring[cursor] = pack(yaw, pitch)
        val period = cycle()
        ring[cursor] = kept
        return period == 0
    }

    /** The step after a debt of [owed] counts, kept on the same side of zero as it was. */
    private fun settle(step: Int, owed: Int): Int {
        val settled = step + owed
        return if (step > 0) maxOf(settled, 0) else minOf(settled, 0)
    }

    private fun pack(yaw: Int, pitch: Int): Long =
        (yaw.toLong() shl 32) or (pitch.toLong() and 0xFFFF_FFFFL)
}

/** Steps of history the guard keeps; enough for [CYCLES] cycles of [MAX_PERIOD] steps. */
private const val HISTORY = 24

/** Longest cycle the guard looks for: six steps is a quarter of a second of packet history. */
private const val MAX_PERIOD = 6

/** How many times a window has to repeat before it counts as a cycle rather than a coincidence. */
private const val CYCLES = 3

/** The largest nudge a step may take to break a cycle, in counts. */
private const val MAX_NUDGE = 2

/** The largest debt the path may carry, in counts: a repair is never paid back for long. */
private const val MAX_OWED = 2

/** Chance that a step pays back what an earlier repair owes, so the payback has no beat. */
private const val PAY_CHANCE = 0.65f

/** The step that is not a movement: both axes at rest. */
private const val REST = 0L
