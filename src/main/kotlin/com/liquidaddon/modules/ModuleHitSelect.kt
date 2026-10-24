package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.world.entity.LivingEntity
import kotlin.random.Random

object ModuleHitSelect : ClientModule("HitSelect", Categories.LIQUIDADDON) {
    private val chance by int("Chance", 100, 0..100, "%")
    private val onlyWhenTargetVulnerable by boolean("OnlyWhenTargetVulnerable", true)
    private val maxTargetHurtTime by int("MaxTargetHurtTime", 0, 0..10, "ticks")
    private val alwaysWhenHurt by boolean("AlwaysWhenHurt", true)
    private val onlyWhenHurt by boolean("OnlyWhenHurt", false)
    private val chatLog by boolean("ChatLog", true)
    // false = Active, true = Pause (così in ClickGUI vedi un toggle chiaro)
    private val pauseMode by boolean("PauseMode", true)
    private val preferKBReduction by boolean("PreferKBReduction", true)
    private val pauseTicks by int("PauseTicks", 10, 1..20, "ticks")
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
        val targetAny: Any? = try {
            event.javaClass.getMethod("getEntity").invoke(event)
        } catch (_: Exception) {
            try { event.javaClass.getField("entity").get(event) } catch (_: Exception) {
                try { event.javaClass.getMethod("getTarget").invoke(event) } catch (_: Exception) { null }
            }
        }
        val target = targetAny as? LivingEntity ?: return@handler
        if (chance < 100 && Random.nextInt(100) >= chance) {
            passed++
            if (pauseMode) pauseRemaining = pauseTicks
            logIfNeeded()
            return@handler
        }
        val shouldDrop = shouldDrop(me, target)
        if (shouldDrop) {
            event.cancelEvent()
            dropped++
        } else {
            passed++
            if (pauseMode) pauseRemaining = pauseTicks
        }
        logIfNeeded()
    }
    private fun shouldDrop(me: LivingEntity, target: LivingEntity): Boolean {
        if (pauseMode) {
            if (pauseRemaining > 0) {
                if (alwaysWhenHurt && me.hurtTime > 0) return false
                if (onlyWhenHurt && me.hurtTime == 0) return true
                return true
            }
        }
        if (alwaysWhenHurt && me.hurtTime > 0) return false
        if (onlyWhenHurt) return me.hurtTime == 0
        if (onlyWhenTargetVulnerable && target.hurtTime > maxTargetHurtTime) return true
        if (!pauseMode) { // Active
            return shouldDropActive(me)
        }
        return false
    }
    private fun shouldDropActive(me: LivingEntity): Boolean {
        val isFallingForCrit = me.fallDistance > 0f && !me.onGround() && !me.isInWater && !me.isInLava && !me.isFallFlying
        val speed = me.deltaMovement.horizontalDistance()
        val isMovingFast = me.isSprinting || speed > 0.15
        return if (preferKBReduction) {
            // Priorità KB/movimento
            isMovingFast && me.hurtTime == 0
        } else {
            // Priorità crits
            !isFallingForCrit
        }
    }
    private fun logIfNeeded() {
        if (chatLog && (dropped + passed) % 10 == 0 && dropped + passed > 0) {
            val modeStr = if (pauseMode) "Pause" else "Active"
            val prefStr = if (preferKBReduction) "KB" else "Crit"
            chat("§7[HitSelect] annullati $dropped, passati $passed ($modeStr/$prefStr, pause=$pauseRemaining)")
        }
    }
}
