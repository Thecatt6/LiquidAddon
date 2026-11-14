package com.liquidaddon.modules.fakelag

/** What the shared packet queue is told to do with one outgoing packet. */
internal enum class HeldPacket {
    /** Keep it: the hold is in force, or older ticks are still on their way out. */
    QUEUE,

    /** Let it through now: nothing of ours is being held. */
    PASS,

    /**
     * The drain has reached its end: everything left in the queue belongs to the tick we are inside,
     * so it all goes together and the queue is ours no more.
     */
    FLUSH_ALL,
}

/**
 * The counting behind a held connection: how fast the hold grows and shrinks, which tick the release
 * is cut at, and what happens to a packet that arrives while the hold is coming down.
 *
 * These are the rules that decide packet **order**, which is the part of a fake lag an anticheat can
 * actually read: two of our client ticks delivered inside one server tick is the pattern every "post
 * packet" check looks for. So they are counted here, where they can be tested, instead of living
 * inside an event handler that only a running client can exercise.
 */
internal object HoldRamp {
    /** How many ticks the hold may grow by in one tick. Losing ground is always slower - see [next]. */
    const val UP_TICKS = 4

    /**
     * One step of the hold: up to [UP_TICKS] ticks at a time when it is growing, **one tick at a
     * time** when it is shrinking, and never outside `0..max`.
     *
     * The asymmetry is the whole point. Growing fast only delays our own packets a moment sooner;
     * shrinking fast would release several held ticks inside one server tick, which is exactly the
     * burst a recovering connection never produces.
     */
    fun next(current: Int, desired: Int, max: Int): Int {
        val limit = max.coerceAtLeast(0)
        val held = current.coerceIn(0, limit)
        val want = desired.coerceIn(0, limit)

        val stepped = when {
            want > held -> minOf(want, held + UP_TICKS)
            want < held -> held - 1
            else -> held
        }

        return stepped.coerceIn(0, limit)
    }

    /**
     * The index, in the list of client tick starts (oldest first), of the tick the release is cut at:
     * everything that started **before** it leaves, so the newest `heldTicks + 1` ticks stay held and
     * a tick's packets always leave together.
     *
     * A negative answer means "release nothing": the queue is empty, or the hold is longer than the
     * ticks we know the start of.
     */
    fun cutoffIndex(tickStarts: Int, heldTicks: Int): Int =
        tickStarts.coerceAtLeast(0) - 1 - heldTicks.coerceAtLeast(0)

    /**
     * What one outgoing packet is told while we own the queue.
     *
     * @param holding whether the packets currently in the queue are ours
     * @param heldTicks how many ticks are still being held, i.e. how far the drain has to go
     * @param shouldHold whether holding is allowed at all right now
     */
    fun packet(holding: Boolean, heldTicks: Int, shouldHold: Boolean): HeldPacket = when {
        shouldHold -> HeldPacket.QUEUE
        // Nothing of ours is queued, so the packet is none of our business.
        !holding -> HeldPacket.PASS
        // The drain is over: what is left belongs to the tick we are inside.
        heldTicks <= 0 -> HeldPacket.FLUSH_ALL
        // Still draining: queue it, so a newer packet never overtakes the ticks that are left.
        else -> HeldPacket.QUEUE
    }
}
