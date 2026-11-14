package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.modules.hitselect.DropRule
import com.liquidaddon.modules.hitselect.HitSelectSettings
import com.liquidaddon.modules.hitselect.HitSelectStance
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.world.entity.LivingEntity
import kotlin.random.Random

/**
 * Lets some attacks through and drops others, so the hits you land are not one unbroken stream.
 *
 * ## What "dropping" an attack actually stops
 *
 * `AttackEntityEvent` is fired from **two** places in the client, and they are not equivalent — this
 * is the part that is easy to describe wrong, so it is written down here (both verified against
 * LiquidBounce 0.40.0, 0.41.0 and 0.42.0):
 *
 *  1. `utils/combat/CombatExtensions.kt`, inside `attackEntity(entity, swing, keepSprint)`: the event
 *     is called before anything is sent, and a cancelled event makes that function `return false`
 *     before the attack packet **and** before its `swing.swing(MAIN_HAND)`. An attack dropped here
 *     leaves nothing at all on the wire. This is the path every module uses — including our own
 *     TriggerBotPlus, which sees the `false` and simply tries again a tick later.
 *     One exception on the same path: a piercing weapon is handled *above* the event and returns
 *     early, so this module never sees that attack and cannot drop it.
 *  2. `injection/mixins/minecraft/network/MixinMultiPlayerGameMode.java`, at the HEAD of
 *     `MultiPlayerGameMode.attack(Player, Entity)`: this is your own left click. A cancelled event
 *     stops that method, so no attack packet is sent — but the arm swing of a click is decided by
 *     `Minecraft.startAttack`, outside it. Whether the swing survives could not be read from the
 *     sources available here; what can be read is that the client itself cancels `startAttack` at
 *     its HEAD with the comment "Prevent swinging" when it wants no swing, which is why the honest
 *     answer is: **the attack is dropped, the swing most likely is not, and that is not measured.**
 *
 * So the same drop has two different footprints depending on who attacked, and neither of them is
 * something this module can make equal from here.
 *
 * ## The shape it shows from the outside
 *
 * With the defaults (`PauseMode` on, `PauseTicks` 10, `AlwaysWhenHurt` on,
 * `OnlyWhenTargetVulnerable` on at `MaxTargetHurtTime` 0) an attack goes through, arms a pause, and
 * everything inside that pause is dropped unless you are hurt — so the module lets roughly one
 * attack through per pause. That pause is spread by a quarter of its length ([DropRule.pauseLength])
 * because a fixed one is a metronome: the average stays where the slider says it is, and no attack
 * is added.
 */
object ModuleHitSelect : ClientModule("HitSelect", Categories.LIQUIDADDON) {
    private val chance by int("Chance", 100, 0..100, "%")
    private val onlyWhenTargetVulnerable by boolean("OnlyWhenTargetVulnerable", true)
    private val maxTargetHurtTime by int("MaxTargetHurtTime", 0, 0..10, "ticks")
    private val alwaysWhenHurt by boolean("AlwaysWhenHurt", true)
    private val onlyWhenHurt by boolean("OnlyWhenHurt", false)
    private val chatLog by boolean("ChatLog", true)

    /** `false` = Active, `true` = Pause, so the ClickGUI shows the toggle the way it is used. */
    private val pauseMode by boolean("PauseMode", true)
    private val preferKBReduction by boolean("PreferKBReduction", true)

    /** How long a pause lasts, on average — each one is spread around it, see [DropRule]. */
    private val pauseTicks by int("PauseTicks", 10, 1..20, "ticks")

    private var dropped = 0
    private var passed = 0
    private var pauseRemaining = 0

    /** What the last decision was, for the [chatLog] line: a counter alone says nothing. */
    private var lastReason = "idle"

    override val tag: String
        get() = if (dropped + passed == 0) "" else "$dropped/$passed"

    override fun onEnabled() {
        dropped = 0
        passed = 0
        pauseRemaining = 0
        lastReason = "idle"
    }

    override fun onDisabled() {
        pauseRemaining = 0
        lastReason = "idle"
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pauseRemaining > 0) pauseRemaining--
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val me = mc.player ?: return@handler

        // `AttackEntityEvent.entity` is a public property of the event in every client build this
        // addon compiles against, so the target is read directly: no reflection, and nothing that
        // can silently stop working when the client is updated.
        val target = event.entity as? LivingEntity ?: return@handler

        if (chance < 100 && Random.nextInt(100) >= chance) {
            pass("chance roll failed")
            return@handler
        }

        val reason = DropRule.reason(settings(), pauseRemaining, stance(me, target))
        if (reason == null) {
            pass(null)
        } else {
            event.cancelEvent()
            dropped++
            lastReason = reason
        }
        logIfNeeded()
    }

    /** An attack that went through — which is also what arms the pause in Pause mode. */
    private fun pass(reason: String?) {
        passed++
        lastReason = reason ?: "kept"
        if (pauseMode) {
            pauseRemaining = DropRule.pauseLength(pauseTicks, Random.nextInt(DropRule.pauseRolls(pauseTicks)))
        }
    }

    private fun settings() = HitSelectSettings(
        pauseMode = pauseMode,
        alwaysWhenHurt = alwaysWhenHurt,
        onlyWhenHurt = onlyWhenHurt,
        onlyWhenTargetVulnerable = onlyWhenTargetVulnerable,
        maxTargetHurtTime = maxTargetHurtTime,
        preferKBReduction = preferKBReduction,
    )

    private fun stance(me: LivingEntity, target: LivingEntity) = HitSelectStance(
        myHurtTime = me.hurtTime,
        targetHurtTime = target.hurtTime,
        fallingForCrit = me.fallDistance > 0f && !me.onGround() &&
            !me.isInWater && !me.isInLava && !me.isFallFlying,
        moving = me.isSprinting || me.deltaMovement.horizontalDistance() > MOVING_SPEED,
    )

    private fun logIfNeeded() {
        val total = dropped + passed
        if (chatLog && total > 0 && total % LOG_EVERY == 0) {
            val mode = if (pauseMode) "Pause" else "Active"
            val preference = if (preferKBReduction) "KB" else "Crit"
            chat(
                "§7[HitSelect] annullati §f$dropped§7, passati §f$passed§7 " +
                    "($mode/$preference, pausa $pauseRemaining, ultimo: $lastReason)"
            )
        }
    }

    /** Horizontal speed that counts as "moving", in blocks per tick. */
    private const val MOVING_SPEED = 0.15

    /** How often the decision is summarised in chat. */
    private const val LOG_EVERY = 10
}
