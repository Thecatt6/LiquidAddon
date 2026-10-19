package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.world.entity.LivingEntity
import kotlin.random.Random

/**
 * HitSelect — port del tuo HitSelectTest (PrismaAC) + miglioramenti:
 *
 * Intercetta AttackEntityEvent. Se decide di "scartare" chiama event.cancelEvent():
 * il pacchetto non parte ma lo swing viene comunque inviato (vanilla non lo manda mai).
 * Solo entità vive (LivingEntity), ignora barche/cristalli.
 *
 * - Chance: probabilità di applicare la logica
 * - Mode: PAUSE (statico N tick) vs ACTIVE (dinamico quando vantaggioso)
 * - Preference: KB_REDUCTION vs CRITICALS
 */
object ModuleHitSelect : ClientModule("HitSelect", Categories.EXAMPLE) {

    // === Settings dal tuo script ===
    private val chance by int("Chance", 100, 0..100, "%")
    private val onlyWhenTargetVulnerable by boolean("OnlyWhenTargetVulnerable", true)
    private val maxTargetHurtTime by int("MaxTargetHurtTime", 0, 0..10, "ticks")
    private val alwaysWhenHurt by boolean("AlwaysWhenHurt", true)
    private val onlyWhenHurt by boolean("OnlyWhenHurt", false)
    private val chatLog by boolean("ChatLog", true)

    // === Nuove feature richieste ===
    private val mode by enumChoice("Mode", HitSelectMode.PAUSE)
    private val preference by enumChoice("Preference", HitSelectPreference.KB_REDUCTION)
    private val pauseTicks by int("PauseTicks", 10, 1..20, "ticks")

    enum class HitSelectMode(override val choiceName: String) : NamedChoice {
        PAUSE("Pause"),
        ACTIVE("Active")
    }

    enum class HitSelectPreference(override val choiceName: String) : NamedChoice {
        KB_REDUCTION("KBReduction"),
        CRITICALS("Criticals")
    }

    private var dropped = 0
    private var passed = 0
    private var pauseRemaining = 0

    override val tag: String
        get() = if (dropped + passed == 0) "" else "$dropped/$passed"

    override fun onEnabled() {
        dropped = 0
        passed = 0
        pauseRemaining = 0
    }

    override fun onDisabled() {
        pauseRemaining = 0
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pauseRemaining > 0) pauseRemaining--
    }

    @Suppress("unused")
    private val attackHandler = handler<net.ccbluex.liquidbounce.event.events.AttackEntityEvent> { event ->
        val me = player ?: return@handler

        // Prova a ottenere il target in modo compatibile con diverse versioni dell'evento
        val targetAny: Any? = try {
            event.javaClass.getMethod("getEntity").invoke(event)
        } catch (_: Exception) {
            try {
                event.javaClass.getField("entity").get(event)
            } catch (_: Exception) {
                try {
                    // alcuni build usano "target"
                    event.javaClass.getMethod("getTarget").invoke(event)
                } catch (_: Exception) { null }
            }
        }
        val target = targetAny as? LivingEntity ?: return@handler

        // Chance
        if (chance < 100 && Random.nextInt(100) >= chance) {
            passed++
            if (mode == HitSelectMode.PAUSE) pauseRemaining = pauseTicks
            logIfNeeded()
            return@handler
        }

        val shouldDrop = shouldDrop(me, target)

        if (shouldDrop) {
            event.cancelEvent()
            dropped++
        } else {
            passed++
            if (mode == HitSelectMode.PAUSE) {
                pauseRemaining = pauseTicks
            }
        }
        logIfNeeded()
    }

    private fun shouldDrop(me: LivingEntity, target: LivingEntity): Boolean {
        // Pause mode: se in pausa, droppa tutto (tranne counter-hit)
        if (mode == HitSelectMode.PAUSE) {
            if (pauseRemaining > 0) {
                if (alwaysWhenHurt && me.hurtTime > 0) return false
                if (onlyWhenHurt && me.hurtTime == 0) return true
                return true
            }
        }

        if (alwaysWhenHurt && me.hurtTime > 0) return false
        if (onlyWhenHurt) return me.hurtTime == 0

        if (onlyWhenTargetVulnerable && target.hurtTime > maxTargetHurtTime) {
            return true
        }

        if (mode == HitSelectMode.ACTIVE) {
            return shouldDropActive(me)
        }

        return false
    }

    private fun shouldDropActive(me: LivingEntity): Boolean {
        val isFallingForCrit = isCriticalCandidate(me)
        val isMovingFast = isMovingForKbBenefit(me)

        return when (preference) {
            HitSelectPreference.CRITICALS -> {
                // Priorità crit: droppa se non sei in caduta
                !isFallingForCrit
            }
            HitSelectPreference.KB_REDUCTION -> {
                // Priorità KB/movimento: droppa se sprinti veloce e non sei hurt
                if (isMovingFast && me.hurtTime == 0) true else false
            }
        }
    }

    private fun isCriticalCandidate(me: LivingEntity): Boolean {
        return me.fallDistance > 0f && !me.onGround() && !me.isInWater && !me.isInLava && !me.isFallFlying
    }

    private fun isMovingForKbBenefit(me: LivingEntity): Boolean {
        val speed = me.deltaMovement.horizontalDistance()
        return me.isSprinting || speed > 0.15
    }

    private fun logIfNeeded() {
        if (chatLog && (dropped + passed) % 10 == 0 && dropped + passed > 0) {
            chat("§7[HitSelect] annullati $dropped, passati $passed (mode=${mode.choiceName}, pref=${preference.choiceName}, pause=$pauseRemaining)")
        }
    }
}
