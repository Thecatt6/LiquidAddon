package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.world.entity.LivingEntity
import kotlin.random.Random

/**
 * HitSelect — port del tuo HitSelectTest (PrismaAC) + miglioramenti richiesti:
 *
 * Cosa fa:
 *  Intercetta AttackEntityEvent. Se decide di "scartare" il colpo chiama event.cancelEvent():
 *  il pacchetto di attacco non parte, ma Minecraft manda comunque lo swing (MultiPlayerGameMode.attack).
 *  Al server arriva swing senza hit — il vanilla non lo manda mai, ma sui server 1.8-style
 *  (Raven/HitSelect) è il trucco per meno KB, più crit e movimento migliore.
 *  Tocca solo entità vive (hanno health), ignora barche/carrelli/cristalli.
 *
 *  Impostazioni base (dal tuo script):
 *   - OnlyWhenTargetVulnerable + MaxTargetHurtTime: scarta se il target è ancora invulnerabile
 *   - AlwaysWhenHurt: se sei stato appena colpito (tuo hurtTime>0) il colpo passa sempre (counter-hit = meno KB)
 *   - OnlyWhenHurt: passa SOLO quando sei stato colpito (estremo)
 *   - ChatLog: ogni 10 colpi logga
 *
 *  Miglioramenti richiesti:
 *   - Chance: probabilità che l'interruzione venga applicata
 *   - Mode Pause (statico): dopo un colpo andato a segno pausa N tick scartando tutto (statico)
 *   - Mode Active (dinamico): scarta solo quando vantaggioso (invulnerabilità / non-crit / sprint)
 *   - Preference: KB_REDUCTION vs CRITICALS — cosa privilegiare, l'altro resta comunque parzialmente
 */
object ModuleHitSelect : ClientModule("HitSelect", Categories.EXAMPLE) {

    // === Settings globali (dal tuo script, validi per entrambe le mode) ===
    private val chance by int("Chance", 100, 0..100, "%")

    private val onlyWhenTargetVulnerable by boolean("OnlyWhenTargetVulnerable", true)
    private val maxTargetHurtTime by int("MaxTargetHurtTime", 0, 0..10, "ticks")
    private val alwaysWhenHurt by boolean("AlwaysWhenHurt", true)
    private val onlyWhenHurt by boolean("OnlyWhenHurt", false)
    private val chatLog by boolean("ChatLog", true)

    // === Mode + Preference (richiesta nuova feature) ===
    private val mode by choices("Mode", Pause, Active)
    private val preference by enumChoice("Preference", Preference.KB_REDUCTION)

    // Pause = static hit selection
    private object Pause : Mode("Pause") {
        override val parent get() = mode
        val pauseTicks by int("PauseTicks", 10, 1..20, "ticks")
        // descrizione per traduzioni
        init {
            // non serve literalDescription se hai en_us.json, ma utile come fallback
        }
    }

    // Active = dynamic hit selection
    private object Active : Mode("Active") {
        override val parent get() = mode
    }

    enum class Preference {
        KB_REDUCTION,
        CRITICALS
    }

    // === Contatori ===
    private var dropped = 0
    private var passed = 0
    private var pauseRemaining = 0 // solo per Mode Pause

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

    // Tick handler per scalare la pausa in Mode Pause
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pauseRemaining > 0) pauseRemaining--
    }

    @Suppress("unused")
    private val attackHandler = handler<net.ccbluex.liquidbounce.event.events.AttackEntityEvent> { event ->
        val me = player ?: return@handler
        // event.entity è LivingEntity target in Nextgen. Usiamo reflection-safe: entity / target / getEntity()
        val targetAny: Any? = try {
            event.javaClass.getMethod("getEntity").invoke(event)
        } catch (_: Exception) {
            try { event.javaClass.getField("entity").get(event) } catch (_: Exception) { null }
        }
        val target = targetAny as? LivingEntity ?: return@handler

        // Solo entità vive (hanno health) — barche, cristalli, minecart ignorati
        // Se non ha LivingEntity, è già esclusa dal cast sopra.

        // Chance: se fallisce, lasciamo passare sempre
        if (chance < 100) {
            if (Random.nextInt(100) >= chance) {
                passed++
                // In Pause mode un hit passato fa partire la pausa
                if (mode.activeChoice == Pause) pauseRemaining = Pause.pauseTicks
                logIfNeeded()
                return@handler
            }
        }

        val shouldDrop = shouldDrop(me, target)

        if (shouldDrop) {
            event.cancelEvent()
            dropped++
        } else {
            passed++
            // In Pause mode: dopo un hit passato, parte la pausa statica
            if (mode.activeChoice == Pause) {
                pauseRemaining = Pause.pauseTicks
            }
        }
        logIfNeeded()
    }

    private fun shouldDrop(me: LivingEntity, target: LivingEntity): Boolean {
        // Pause mode: statico — se siamo in pausa, droppa tutto (a meno di counter-hit)
        if (mode.activeChoice == Pause) {
            if (pauseRemaining > 0) {
                // Counter-hit ha priorità anche in pausa: se sei appena stato colpito, non droppare (prendi meno KB)
                if (alwaysWhenHurt && me.hurtTime > 0) return false
                // OnlyWhenHurt estremo: in pausa droppa tutto tranne quando sei hurt
                if (onlyWhenHurt && me.hurtTime == 0) return true
                return true
            }
            // Fuori pausa: decidere se droppare questo colpo dipende anche dalle altre regole
            // In Pause la logica dinamica è minima: droppa solo se target invulnerabile per risparmiare crits/movement
            // ma rispetta la Preference per dare comunque un po' dell'altro beneficio.
        }

        // --- Logica comune (sia Pause fuori-pausa che Active) ---
        // 1. AlwaysWhenHurt: se sei stato appena colpito, passa sempre (counter-hit = meno KB)
        if (alwaysWhenHurt && me.hurtTime > 0) {
            return false
        }
        // 2. OnlyWhenHurt estremo: passa SOLO quando sei hurt
        if (onlyWhenHurt) {
            // Se non sei hurt, droppa
            return me.hurtTime == 0
        }

        // 3. OnlyWhenTargetVulnerable: se il target è invulnerabile, droppa (non faresti danno, resetti solo hurtTime)
        if (onlyWhenTargetVulnerable && target.hurtTime > maxTargetHurtTime) {
            // In Active mode con Preference, questo è già vantaggioso sia per KB che per crits,
            // ma la Preference decide il peso di ulteriori check.
            return true
        }

        // 4. Logica Preference + Mode Active (dinamico)
        if (mode.activeChoice == Active) {
            return shouldDropActive(me, target)
        }

        // Mode Pause fuori-pausa e senza altre ragioni: non droppare (lascia hit regolari)
        return false
    }

    private fun shouldDropActive(me: LivingEntity, target: LivingEntity): Boolean {
        // Active = droppa solo quando vantaggioso, lascia passare quando benefico.
        // Entrambi i benefit (KB e crits) sono presenti, la Preference sceglie cosa pesare di più.

        val isFallingForCrit = isCriticalCandidate(me)
        val isMovingFast = isMovingForKbBenefit(me)

        return when (preference) {
            Preference.CRITICALS -> {
                // Priorità crits: droppa se NON sei in condizione di crit (aspetta di cadere)
                // Dai comunque un po' di KB benefit: se sei hurt e ti muovi, evita di droppare inutilmente
                if (!isFallingForCrit) {
                    // Se sei in hurtTime e ti muovi veloce, droppare darebbe KB benefit ma crit è priorità:
                    // droppa comunque per aspettare il crit, A MENO CHE non sia solo 1-2 tick di caduta
                    true
                } else {
                    // Sei in caduta = crit imminente -> NON droppare (fai crit)
                    false
                }
            }
            Preference.KB_REDUCTION -> {
                // Priorità KB: droppa se muoversi/essere colpiti darebbe meno KB
                // Miglior movimento: droppare quando sprinti a terra evita slowdown dell'attack
                // Meno KB: droppare quando non sei appena stato colpito, tenere i counter-hit
                if (isMovingFast && me.hurtTime == 0) {
                    // Stai scattando a terra e non sei hurt: droppare migliora movimento e riduce KB preso
                    true
                } else if (me.hurtTime > 0) {
                    // Sei appena stato colpito: fare hit ora riduce il KB che prendi -> NON droppare
                    false
                } else {
                    // Neutro: se non sei in caduta e non ti muovi, droppare non dà grande vantaggio crit/KB,
                    // quindi lascia passare ogni tanto per non sembrare statico
                    false
                }
            }
        }
    }

    private fun isCriticalCandidate(me: LivingEntity): Boolean {
        // In 1.21.11 crit se: in aria, fallDistance>0, non onGround, non in acqua/lava, non in volo, non sprinting eccessivo
        // Approssimazione stabile senza NMS:
        return me.fallDistance > 0f && !me.onGround() && !me.isInWater && !me.isInLava && !me.isFallFlying
    }

    private fun isMovingForKbBenefit(me: LivingEntity): Boolean {
        // Se stai sprintando o hai velocità orizzontale > soglia, droppare l'hit evita reset sprint e riduce KB self-inflicted
        val speed = me.deltaMovement.horizontalDistance()
        return me.isSprinting || speed > 0.15
    }

    private fun logIfNeeded() {
        if (chatLog && (dropped + passed) % 10 == 0 && dropped + passed > 0) {
            // Usiamo chat con prefix del modulo
            chat("§7[HitSelect] colpi annullati $dropped, passati $passed (mode=${mode.activeChoice?.name}, pref=$preference, pause=$pauseRemaining)")
        }
    }
}
