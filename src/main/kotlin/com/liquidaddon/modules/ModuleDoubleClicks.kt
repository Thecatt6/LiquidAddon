package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * Double Clicks — ogni click del giocatore diventa due click, inviati subito.
 *
 * Il click del giocatore atterra come sempre. Il module gli risponde all'istante con un altro
 * attacco sullo stesso target: niente spread, niente attesa, niente coda. Un click diventa un
 * doppio click.
 *
 * **Disabilitato quando si è su un blocco:** mentre il giocatore è a terra (`onGround`) il module
 * non fa nulla. Funziona solo in aria (ad esempio mentre si rodda o si ponteggia).
 *
 * Il doppio click non si moltiplica da solo: mentre [pay] invia l'attacco di seguito,
 * [attackHandler] lo ignora, quindi non si forma un loop infinito.
 */
object ModuleDoubleClicks : ClientModule("DoubleClicks", Categories.LIQUIDADDON) {

    /** Quando attivo, il doppio click parte solo se il server lo prenderebbe (finestra aperta e arma caricata). */
    private val onlyWhenItLands by boolean("OnlyWhenItLands", true)

    /** Forza minima dell'attacco (cooldown arma 1.9+) prima che il seguito venga inviato. */
    private val minCooldown by float("MinCooldown", 0.9f, 0f..1f)

    /** Quanto lontano può arrivare il seguito. Il server misura la sua distanza, sua è 3.0. */
    private val reach by float("Reach", 3.0f, 2.0f..4.0f)

    /** Lo swing del click: `DoNotHide` è quello che fa una mano. */
    private val swing by enumChoice("Swing", SwingMode.DO_NOT_HIDE)

    /** Quando attivo, il module non agisce mentre il giocatore è su un blocco (a terra). */
    private val disableOnGround by boolean("DisableOnGround", true)

    private val debug by boolean("Debug", false)

    /** Impostato mentre il module invia un attacco proprio, così [attackHandler] lo ignora. */
    private var paying = false

    private var doubled = 0
    private var dropped = 0

    override val tag: String
        get() = ""

    override fun onEnabled() {
        super.onEnabled()
    }

    override fun onDisabled() {
        super.onDisabled()
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        // Il click seguito del module ripete questo evento: ignoriamolo per non fare un loop.
        if (paying) {
            return@handler
        }
        val me = mc.player ?: return@handler
        // Disabilitato mentre si è su un blocco.
        if (disableOnGround && me.onGround()) {
            return@handler
        }
        val entity = event.entity
        if (!entity.shouldBeAttacked()) {
            dropped++
            return@handler
        }
        if (me.distanceTo(entity) > reach) {
            // Fuori portata: il server lo rifiuta comunque.
            dropped++
            return@handler
        }
        if (onlyWhenItLands && !canLand(me, entity)) {
            dropped++
            return@handler
        }
        pay(me, entity)
    }

    /** Vero quando il server prenderebbe questo attacco: finestra aperta e arma caricata. */
    private fun canLand(me: LocalPlayer, entity: Entity): Boolean {
        if (entity is LivingEntity && entity.hurtTime > 0) {
            return false
        }
        return me.getAttackStrengthScale(0.5f) >= minCooldown
    }

    /** Invia il seguito immediato (il doppio click). */
    private fun pay(me: LocalPlayer, entity: Entity): Boolean {
        paying = true
        val went = try {
            attackEntity(entity, swing)
        } finally {
            paying = false
        }
        if (went) {
            doubled++
            if (debug && doubled % 10 == 0) {
                me.sendSystemMessage(Component.literal("§8[DoubleClicks D] §7doppi $doubled, scartati $dropped"))
            }
        } else {
            dropped++
        }
        return went
    }
}
