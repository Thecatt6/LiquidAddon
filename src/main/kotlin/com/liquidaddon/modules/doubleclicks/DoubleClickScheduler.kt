package com.liquidaddon.modules.doubleclicks

/** A follow-up is the vanilla attack path, the vanilla use path, or the swing animation alone. */
internal enum class ExtraClick {
    ATTACK,
    USE,
    SWING_ONLY,
}

/**
 * Keeps follow-ups separate from the input that caused them. Minecraft handles all queued clicks in
 * one tick, so adding them to that same input batch would send a burst. The queue is deliberately
 * capped: if the player clicks faster than it can be drained, old clicks must not keep firing long
 * after they stop clicking.
 */
internal class DoubleClickScheduler {
    companion object {
        const val MAX_EXTRA_CLICKS = 3
    }

    private val pending = ArrayDeque<ExtraClick>()
    private var sawOriginalClick = false
    private var ticksUntilNext = 0

    val pendingCount: Int
        get() = pending.size

    fun recordOriginal(extraClicks: Int, click: ExtraClick) {
        // Even if the queue is full, a new original must take precedence over a follow-up this tick.
        sawOriginalClick = true
        repeat(extraClicks.coerceIn(0, MAX_EXTRA_CLICKS)) {
            if (pending.size < MAX_EXTRA_CLICKS) {
                pending.addLast(click)
            }
        }
    }

    /** Called once, after vanilla has handled the tick's real inputs. */
    fun takeNext(delayTicks: Int): ExtraClick? {
        val delay = delayTicks.coerceAtLeast(1)
        if (sawOriginalClick) {
            sawOriginalClick = false
            ticksUntilNext = delay
            return null
        }
        if (pending.isEmpty()) {
            ticksUntilNext = 0
            return null
        }
        if (ticksUntilNext > 0) {
            ticksUntilNext--
        }
        if (ticksUntilNext > 0) {
            return null
        }

        ticksUntilNext = delay
        return pending.removeFirst()
    }

    fun clear() {
        pending.clear()
        sawOriginalClick = false
        ticksUntilNext = 0
    }
}

/**
 * Recheck the crosshair just before executing a follow-up. Only an entity click is worth repeating
 * through `Minecraft.startAttack`: a block click would start another destroy action, and a click on
 * air only sets the client's 10 tick miss cooldown (the swing packet is identical). Everything else
 * becomes the animation alone.
 */
internal fun extraClickAction(
    planned: ExtraClick,
    overEntity: Boolean,
    mining: Boolean,
    missCooldown: Boolean,
): ExtraClick = if (planned == ExtraClick.ATTACK && overEntity && !mining && !missCooldown) {
    ExtraClick.ATTACK
} else {
    ExtraClick.SWING_ONLY
}

/**
 * The right button has no animation to fall back on, so a use that cannot run is dropped instead of
 * being turned into a swing. Vanilla itself refuses a use while a block is being destroyed, while an
 * item is already in use and while the hands are busy (a vehicle); the same rules apply here.
 */
internal fun useExtraAllowed(
    planned: ExtraClick,
    handsBusy: Boolean,
    usingItem: Boolean,
    mining: Boolean,
): Boolean = planned == ExtraClick.USE && !handsBusy && !usingItem && !mining
