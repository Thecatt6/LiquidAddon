package com.liquidaddon.modules.triggerbot

/**
 * The arithmetic that decides **how far** an attack may be sent, and how much of that distance has
 * to be given back to latency.
 *
 * This is the part of TriggerBotPlus that keeps an attack inside the reach the server accepts, so it
 * is counted here instead of inside the tick handler: a formula that can only run in a live client
 * is a formula nobody can check, and the one invariant that matters — "with `ServerSafe` the attack
 * is never farther than the smallest honest distance" — is asserted in the tests rather than hoped
 * for. Nothing in here widens a reach: every knob only ever takes distance away.
 */
internal object ReachArithmetic {
    /** Below this the module does not attack at all: a click at touching distance is its own tell. */
    const val MIN_TRIGGER = 0.4

    /** The most blocks the latency allowance may carve out of the distance, whatever the ping says. */
    const val MAX_DESYNC = 1.0

    /** A ping above this is treated as this: beyond it the estimate is noise, not information. */
    const val MAX_PING_MS = 500

    /** How much of a closing speed survives one tick of not moving, while the server still sees it. */
    const val CLOSING_DECAY = 0.8

    /** The top of `DesyncPercent`, as the slider allows it. */
    const val MAX_DESYNC_PERCENT = 200

    /**
     * One side of the round trip, in ticks: the server measures our attack from a position that is
     * `ping/2` old, and `ping` in milliseconds over 100 is that half trip in ticks of 50 ms.
     */
    fun delayTicks(pingMs: Int): Double = pingMs.coerceIn(0, MAX_PING_MS) / 100.0

    /**
     * How many blocks the two stale positions add to the separation the server computes:
     *
     *     ownClosing × ownDelay + theirClosing × theirDelay
     *
     * Both sides are paid for, because the server is behind on both. [percent] is `DesyncPercent`,
     * which scales the estimate down (or, at most, doubles it) and never makes it negative.
     */
    fun drift(
        selfClosing: Double,
        targetClosing: Double,
        myPingMs: Int,
        theirPingMs: Int,
        percent: Int,
    ): Double {
        val mine = delayTicks(myPingMs)
        val theirs = delayTicks(theirPingMs)
        if (mine <= 0.0 && theirs <= 0.0) return 0.0

        val raw = selfClosing.coerceAtLeast(0.0) * mine + targetClosing.coerceAtLeast(0.0) * theirs
        return raw * (percent.coerceIn(0, MAX_DESYNC_PERCENT) / 100.0)
    }

    /**
     * The distance given back to latency before an attack: the drift when [desync] is on, plus the
     * vehicle's own prediction error, capped at [MAX_DESYNC] and never below zero.
     */
    fun allowance(desync: Boolean, drift: Double, inVehicle: Boolean, vehicleExtra: Double): Double {
        val estimate = if (desync) drift.coerceAtLeast(0.0) else 0.0
        val vehicle = if (inVehicle) vehicleExtra.coerceAtLeast(0.0) else 0.0
        return (estimate + vehicle).coerceIn(0.0, MAX_DESYNC)
    }

    /**
     * The farthest distance an attack may be sent at with `ServerSafe` on: the smallest of the three
     * distances that mean something — the module's own base, the honest server reach, and the
     * client's interaction range — plus whatever `OverReach` adds (zero by default) and minus the
     * safety gap.
     *
     * Taking the *smallest* of the three is what makes a Reach setting harmless here: raising the
     * client's interaction range cannot push this module's attacks past what the server accepts.
     */
    fun ceiling(
        base: Double,
        serverReach: Double,
        interactionRange: Double,
        overReach: Double,
        safetyGap: Double,
    ): Double = minOf(base, serverReach, interactionRange) +
        overReach.coerceAtLeast(0.0) -
        safetyGap.coerceAtLeast(0.0)

    /**
     * The distance this module attacks at.
     *
     * With `ServerSafe` on it is the [ceiling] minus the latency [allowance]; with it off it is the
     * base plus the early cushion, i.e. the attack itself happens out of range — a reach hack, and
     * the module says so where the setting is. Either way it never goes below [MIN_TRIGGER].
     */
    fun attackLimit(
        serverSafe: Boolean,
        base: Double,
        serverReach: Double,
        interactionRange: Double,
        overReach: Double,
        safetyGap: Double,
        allowance: Double,
        earlyMargin: Double,
    ): Double {
        val limit = if (serverSafe) {
            ceiling(base, serverReach, interactionRange, overReach, safetyGap) - allowance.coerceAtLeast(0.0)
        } else {
            base + earlyMargin.coerceAtLeast(0.0)
        }
        return limit.coerceAtLeast(MIN_TRIGGER)
    }

    /**
     * One step of the closing speed: a **decaying maximum**, not a snapshot. A target that stopped
     * one tick ago is still moving in the position the server has, so its speed is paid for a few
     * ticks instead of being erased on the spot; a speed that is really there always wins.
     */
    fun closing(now: Double, previous: Double, decay: Double = CLOSING_DECAY): Double =
        maxOf(now.coerceAtLeast(0.0), previous.coerceAtLeast(0.0) * decay.coerceIn(0.0, 1.0))
}
