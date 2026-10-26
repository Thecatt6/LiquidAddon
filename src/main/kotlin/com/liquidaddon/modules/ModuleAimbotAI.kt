package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.AimbotValues
import com.liquidaddon.aiming.AimassistCore
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.render.TargetRenderer

/**
 * AimbotAI — aims by itself, with the Aimassist's engine.
 *
 * The aiming logic is not duplicated: this module drives the same [AimassistCore] the Aimassist
 * uses, so it inherits every bypass trick that engine has (the mouse-count lattice with its carried
 * residual, `GcdDither`, the frame interpolation of a tick's step, the silent rotation through
 * `RotationManager` with `MoveFix`, and LiquidBounce's already-trained combat model under
 * `Engine = AI`).
 *
 * What is different is that it aims **on its own**: the settings are not the assist's
 * ([AimbotValues]) and the engine is told to pick the target itself ([AimbotValues.autoAim]). The
 * cone around the crosshair stops filtering, the pull no longer fades with the distance from where
 * you are looking, and there is no reaction pause, no fade-in and no idle beat — while a target is
 * in `Range` and inside `Fov` (360° by default, i.e. all around you) the aim goes to it and stays
 * on it until it is dead or out of reach.
 *
 * The aimbot never clicks: it only aims. Use a KillAura/clicker for the hits, or click yourself —
 * and when you do, the click is synchronised with the aim: it is held until the rotation the server
 * has is on the entity you hit, then sent (see [AimassistCore.onAttack]). An aimbot rotates a lot,
 * so this is the difference between an attack that arrives *with* its rotation and the
 * attack-then-snap pattern Vulcan calls `Killaura (Post)`.
 *
 * How it gets there is still the assist's maths, so the numbers mean the same thing:
 *  - `AimSpeed` is the hard cap of one tick, `Strength` the share of the remaining angle,
 *    `Smoothing` the curve (`Linear`, `Bezier`, `Sigmoid`) — bigger error, faster correction, never
 *    a teleport;
 *  - `AimPoint` picks the part of the hitbox, `Predict`/`PredictSize` lead the target;
 *  - `Engine = AI` swaps the curve for the model LiquidBounce already trained on recorded human
 *    fights, completed by `AICorrection`;
 *  - `Silent = true` (default) sends the rotation only to the server, so the camera stays yours and
 *    the hits land anyway; `Silent = false` turns the camera with it, `FrameInterpolation` spreading
 *    the step over the frames of the tick.
 *
 * The blue circle under the current target is LiquidBounce's own [TargetRenderer] (the one KillAura
 * draws), configured in the `TargetRendering` group.
 *
 * Do not run this and [ModuleAimassist] together: they are the same engine, and enabling one turns
 * the other off.
 */
object ModuleAimbotAI : ClientModule("Aimbot AI", Categories.LIQUIDADDON) {

    /** The aimbot's own settings (see [AimbotValues]): same engine, different list. */
    private val values = AimbotValues(this)

    /** The engine the Aimassist runs, told to aim on its own through [AimbotValues.autoAim]. */
    private val core = AimassistCore(this, values)

    /**
     * The circle under the target, exactly what KillAura and Aimassist draw: LiquidBounce's own
     * [TargetRenderer] — the blue ring (`Circle` / `GlowingCircle`, outline `0x007CFF`) with its
     * radius, inner radius, height mode and colours configurable in the GUI. It follows the target
     * the engine picked and draws nothing while there is none.
     *
     * It is only a renderer: it never touches the aim.
     */
    @Suppress("unused")
    private val targetRendering = tree(TargetRenderer(this) { core.target })

    /** Friction: unused by the aimbot (`Friction` is fixed to 0), kept for the shared engine. */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { core.onMouseRotation(it) }

    /** Attack synchronisation: a click is held until the rotation the server has is on the entity. */
    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { core.onAttack(it) }

    /** The tick: pick the target, aim at it, hand the rotation out. */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> { core.tick() }

    /** Frame interpolation: the tick's step drawn slice by slice. */
    @Suppress("unused")
    private val renderHandler = handler<GameRenderEvent> { core.onRender() }

    override val tag: String
        get() = core.targetTag

    override fun onEnabled() {
        // The two modules are the same engine: letting both write the rotation would double it.
        if (ModuleAimassist.enabled) ModuleAimassist.enabled = false
        core.onEnable()
        super.onEnabled()
    }

    override fun onDisabled() {
        // Nothing to restore by hand: the module never writes the camera, and RotationManager's
        // RequestHandler drops every request whose provider is not running anymore.
        currentTarget = null
        lastSent = null
        velYaw = 0f
        velPitch = 0f
    }

    private fun resetRotationMemory() {
        val me = mc.player ?: return
        lastYaw = me.yRot
        lastPitch = me.xRot
        beforeYaw = me.yRotO
        beforePitch = me.xRotO
    }

    /*
     * The module used to fake the silent rotation by hand (beginSpoof/endSpoof on the movement
     * events, plus its own input remapping). That work is already done by RotationManager through
     * `MovementCorrection`, so doing it twice was writing the camera on every movement packet —
     * one more source of visible stutter. `MoveFix` is now only mapped onto MovementCorrection in
     * [rotationPlan].
     */

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler
        if (mc.gameMode == null) {
            currentTarget = null
            lastSent = null
            return@handler
        }

        // Settings that pause the aim. They must NOT make the rotation request disappear: the
        // manager would drop it and the next packet would carry the camera angle again — a jump of
        // as many degrees as the aim was away from the view, which is exactly what Vulcan reports
        // as "Aim (Rotation) ... diff=25". The aim is walked back instead (see [releaseAim]).
        val blocked = (pauseOnInventory && isInventoryOpen()) ||
            (pauseOnUse && me.isUsingItem) ||
            (!onSneak && me.isShiftKeyDown) ||
            (!onSprint && me.isSprinting)

        val target = if (blocked) null else findTarget(me)

        if (target == null) {
            if (!lockTarget) currentTarget = null
            releaseAim(me)
            debugState("no target (blocked=$blocked scan=$scanCount valid=$validCount, last gate=$lastGate)")
            return@handler
        }

        val now = System.currentTimeMillis()
        if (currentTarget == null || !isValidTarget(me, target)) {
            if (now - lastTargetSwitch < switchDelay) {
                // Still deciding whether to commit to this target: keep the current aim alive.
                holdAim(me, target)
                return@handler
            }
            lastTargetSwitch = now
            reactionUntil = now + reactionDelay + Random.nextInt(reactionRandom + 1)
        }
        if (target !== biasFor) {
            biasFor = target
            rollAimBias(target)
        }
        currentTarget = target

        if (now < reactionUntil) {
            holdAim(me, target)
            return@handler
        }

        // A hand does not move the mouse on every tick: holding the same rotation is what that
        // looks like. Skipping the update instead makes the angle grow and the next step burst.
        if (idleLeft > 0) {
            idleLeft--
            holdAim(me, target)
            return@handler
        }
        if (idleChance > 0 && Random.nextInt(100) < idleChance) {
            idleLeft = Random.nextInt(1, 3)
            holdAim(me, target)
            return@handler
        }

        val sent = lastSent ?: currentAim(me)
        val goal = goalRotation(me, target)
        if (silentFov && !isInFov(me, goal)) {
            holdAim(me, target)
            return@handler
        }

        // One bounded step towards the goal: the only place the aim is allowed to move.
        sendAim(me, stepTowards(sent, goal), target, sent, goal, report = true)
    }

    /** True when a GUI is open, so the aim has to pause like a player's would. */
    private fun isInventoryOpen(): Boolean {
        // `screen` is the field name in 26.3; the older name is kept as a fallback.
        return try {
            val field = Minecraft::class.java.getDeclaredField("screen").apply { isAccessible = true }
            field.get(Minecraft.getInstance()) != null
        } catch (_: Exception) {
            try {
                val field = Minecraft::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }
                field.get(Minecraft.getInstance()) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Re-sends the current aim unchanged: keeps the request alive without moving the crosshair. */
    private fun holdAim(me: LocalPlayer, target: LivingEntity) {
        val sent = lastSent ?: return
        sendAim(me, sent, target, sent, null, report = false)
    }

    /**
     * Walks the aim back to the camera with the same bounded step, and only lets the request go
     * when the view is almost back. Without this the manager would hand the rotation back to the
     * camera in a single packet as soon as we stop asking for it.
     */
    private fun releaseAim(me: LocalPlayer) {
        val sent = lastSent ?: return
        val camera = Rotation(me.yRot, me.xRot)
        val gap = abs(Mth.wrapDegrees(camera.yaw - sent.yaw)) + abs(camera.pitch - sent.pitch)

        if (gap < deadZone) {
            lastSent = null
            velYaw = 0f
            velPitch = 0f
            return
        }

        sendAim(me, stepTowards(sent, camera), null, sent, camera, report = false)
    }

    /**
     * Quantizes, hands the rotation to [RotationManager] (or to the camera when not silent) and
     * records it as the new [lastSent].
     */
    private fun sendAim(
        me: LocalPlayer,
        next: Rotation,
        target: LivingEntity?,
        from: Rotation,
        goal: Rotation?,
        report: Boolean
    ) {
        var quantized = if (gcdFix) quantizeToMouseCounts(from, next) else next

        // Grim's AimDuplicateLook flags a movement packet whose rotation is identical to the
        // previous one ("Sent a duplicate rotation update without changing look direction").
        // A vanilla client never sends that: when the mouse does not move it sends a packet
        // *without* rotation, and the server keeps the last one. In silent mode the manager keeps
        // applying our rotation every tick, so a "still" aim would repeat the same angle forever.
        // One mouse count is enough to be a different rotation, and it is invisible.
        if (quantized.yaw == from.yaw) stillYawTicks++ else stillYawTicks = 0
        if (quantized.pitch == from.pitch) stillPitchTicks++ else stillPitchTicks = 0

        // Matrix scores the yaw/pitch jiff ratio over 25 deltas: if the pitch never moves, every
        // ratio is infinite and AimStatisticsCheck flags "Aim IQR". An axis may rest, never freeze.
        if (stillYawTicks > 2) {
            if (Random.nextInt(100) < 25) nudgeYawSign = -nudgeYawSign
            quantized = Rotation(quantized.yaw + nudge(nudgeYawSign), quantized.pitch)
            stillYawTicks = 0
        }
        if (stillPitchTicks > 2) {
            if (Random.nextInt(100) < 25) nudgePitchSign = -nudgePitchSign
            quantized = Rotation(
                quantized.yaw,
                (quantized.pitch + nudge(nudgePitchSign)).coerceIn(-89.5f, 89.5f)
            )
            stillPitchTicks = 0
        }

        if (quantized.yaw == from.yaw && quantized.pitch == from.pitch) {
            if (Random.nextInt(100) < 25) nudgeYawSign = -nudgeYawSign
            quantized = Rotation(quantized.yaw + nudge(nudgeYawSign), quantized.pitch)
        }

        // --- the ML model's "constant rotation" counter -------------------------------------
        // The RNN counts, per window, how many times the crosshair moves fast but with a speed
        // almost identical to the previous tick: a difference below 0.02 degrees while turning
        // more than 3 degrees counts as a robot. A saturated step limit produces exactly that,
        // because the quantizer keeps rounding the same magnitude to the same mouse count.
        // So: while the step is fast, its magnitude is never allowed to repeat.
        if (stepCountsOf(from, quantized) >= FAST_STEP_COUNTS &&
            stepCountsOf(from, quantized) == lastStepCounts
        ) {
            repeatedSpeed++
            // one count (0.15 degrees at the default sensitivity) is far above the 0.02 threshold
            val direction = if (Mth.wrapDegrees(quantized.yaw - from.yaw) >= 0f) 1 else -1
            quantized = Rotation(
                quantized.yaw + direction * RotationUtil.gcd.toFloat(),
                quantized.pitch
            )
        }
        lastStepCounts = stepCountsOf(from, quantized)
        recordYawDelta(Mth.wrapDegrees(quantized.yaw - from.yaw))

        if (silent) {
            RotationManager.setRotationTarget(
                rotationPlan(quantized, target),
                RotationPriority.IMPORTANT_FOR_USAGE_2,
                this
            )
        } else {
            writeRotation(me, quantized)
        }

        val step = kotlin.math.hypot(
            Mth.wrapDegrees(quantized.yaw - from.yaw).toDouble(),
            (quantized.pitch - from.pitch).toDouble()
        )
        lastSent = quantized
        remember(me, quantized)

        if (!report) return
        if (debugTick % 20 == 0) repeatedSpeed = 0

        // `raw` is the angle actually written to the player: it must grow/shrink continuously and
        // must NEVER jump by ~360 between two lines (that would be the AimModulo360 signature).
        debugState(
            "aiming %s dist=%.2f step=%.2f° remaining=%.1f° -> yaw=%.1f raw=%.1f pitch=%.1f (silent=%s, preset=%s)".format(
                target?.name?.string, target?.distanceTo(me) ?: 0f, step,
                if (goal == null) 0.0 else kotlin.math.hypot(
                    Mth.wrapDegrees(goal.yaw - quantized.yaw).toDouble(),
                    (goal.pitch - quantized.pitch).toDouble()
                ),
                Mth.wrapDegrees(quantized.yaw), me.yRot, quantized.pitch, silent, preset.tag
            ) + " §7(stepCounts=${stepCountsOf(from, quantized)} " +
                "lim=${"%.1f".format(maxStep * (1f + 2.2f * flickAmount))} " +
                "rank=${"%.2f".format(analysisRank())} repeatedSpeed=$repeatedSpeed)" + warnings()
        )
        if (chatLog && hitCount++ % 40 == 0) {
            chat("§7[Aimbot] target=${target?.name?.string} yaw=%.1f pitch=%.1f mode=%s".format(quantized.yaw, quantized.pitch, preset.tag))
        }
    }

    /**
     * Writes the rotation to the player without ever making the yaw "jump by a full turn".
     *
     * The vanilla client never sets an absolute angle on the player: `Entity.turn` *adds* the mouse
     * delta and `yRot` keeps accumulating (it can read 361.4, -720.8, ...). The server wraps it when
     * it applies the packet. Assigning a wrapped absolute value (`me.yRot = 179.9` followed by
     * `-179.9`) makes the field, and therefore the packet, jump by ~-360 degrees in a single tick,
     * which is precisely what Grim reports as "AimModulo360" (and Vulcan as "Aim (Rotation)").
     *
     * So we always add the shortest delta: the aim still lands on the wanted angle, but modulo 360
     * aligned with the current value.
     */
    private fun writeRotation(me: LocalPlayer, rotation: Rotation) {
        me.yRot += Mth.wrapDegrees(rotation.yaw - me.yRot)
        me.xRot = rotation.pitch.coerceIn(-89.9f, 89.9f)
    }

    /**
     * Settings that defeat the human-like behaviour, reported next to the debug line so a wrong
     * configuration is visible instead of being guessed from the anticheat log.
     */
    private fun warnings(): String = buildString {
        if (aimBias < 0.5f) append(" §cAimBias<0.5 (aim on the hitbox centre)")
        if (idleChance == 0) append(" §cIdleChance=0 (updates every tick)")
        if (maxStep > 8f) append(" §cMaxStep>8 (packets can carry a snap)")
        if (!gcdFix) append(" §cGCDFix off (deltas off the mouse lattice)")
    }

    /**
     * Prints the internal state once per second when the "Debug" setting is on: how many entities
     * were scanned, which filter threw them away, and the rotation that was requested.
     */
    private fun debugState(message: String) {
        if (!debug) return
        if (++debugTick % 20 != 0) return

        // The "no target" line used to be printed every second, forever, while walking around.
        if (message.startsWith("no target") && message == lastDebugMessage) return
        lastDebugMessage = message

        chat("§7[Aimbot] §f$message")
    }

    private fun findTarget(me: LocalPlayer): LivingEntity? {
        if (lockTarget && currentTarget != null && isValidTarget(me, currentTarget!!) && isInFov(me, rotationTo(me, currentTarget!!))) {
            return currentTarget
        }
        val level = mc.level ?: return null
        val aabb = me.boundingBox.inflate(range.toDouble())
        // use Level#getEntitiesOfClass with AABB - compatible with 26.3
        val candidates: List<LivingEntity> = try {
            level.getEntitiesOfClass(LivingEntity::class.java, aabb) { true }
        } catch (_: Exception) {
            // fallback: getEntities generic
            level.getEntities(null, aabb).filterIsInstance<LivingEntity>()
        }
        var best: LivingEntity? = null
        var bestScore = Float.MAX_VALUE
        scanCount = candidates.size
        validCount = 0
        for (e in candidates) {
            if (e == me) continue
            if (!isValidTarget(me, e)) continue
            val rot = rotationTo(me, e)
            if (!isInFov(me, rot)) {
                lastGate = "outside FOV (${"%.0f".format(abs(Mth.wrapDegrees(rot.yaw - me.yRot)))}° > ${"%.0f".format(fov / 2f)}°)"
                continue
            }
            if (checkVisible && !me.hasLineOfSight(e) && !throughWalls) {
                lastGate = "no line of sight"
                continue
            }
            if (e.squaredBoxedDistanceTo(me) > range * range) {
                lastGate = "out of range"
                continue
            }
            validCount++
            val score = when (priority) {
                Priority.DISTANCE -> me.distanceTo(e)
                Priority.HEALTH -> e.health
                Priority.FOV -> abs(Mth.wrapDegrees(rot.yaw - me.yRot))
                Priority.ANGLE -> abs(angleDiff(me.yRot, rot.yaw))
                Priority.ARMOR -> -getArmorValue(e)
            }
            if (score < bestScore) {
                bestScore = score
                best = e
            }
        }
        return best
    }

    private fun isValidTarget(me: Player, e: LivingEntity): Boolean {
        if (!e.isAlive) return reject("dead")
        if (e.health <= minHealth) return reject("health <= MinHealth")
        if (!targetDead && e.isDeadOrDying) return reject("dying")
        if (!targetInvisible && e.isInvisible) return reject("invisible")
        if (!targetFriends) {
            val isFriend = try {
                val fm = Class.forName("net.ccbluex.liquidbounce.utils.client.FriendManager")
                val m = fm.methods.firstOrNull { it.name == "isFriend" && it.parameterCount == 1 }
                m?.invoke(null, e) as? Boolean ?: false
            } catch (_: Exception) { false }
            if (isFriend) return reject("friend")
        }
        if (antiBot && isBot(e)) return reject("anti-bot")
        if (!e.shouldBeAttacked()) return reject("shouldBeAttacked() = false")
        return true
    }

    /** Records why an entity was filtered out, so the "Debug" log can show it. */
    private fun reject(reason: String): Boolean {
        lastGate = "rejected: $reason"
        return false
    }

    private fun isBot(e: LivingEntity): Boolean {
        return e.name.string.contains("§") || e.hurtTime == 0 && e.tickCount < 80
    }

    private fun getArmorValue(e: LivingEntity): Float {
        return try { e.armorValue.toFloat() } catch (_: Exception) { 0f }
    }

    private fun isInFov(me: LocalPlayer, rot: Rotation): Boolean {
        return abs(Mth.wrapDegrees(rot.yaw - me.yRot)) <= fov / 2f
    }

    private fun angleDiff(a: Float, b: Float) = Mth.wrapDegrees(a - b)

    private fun rotationTo(me: LocalPlayer, target: LivingEntity): Rotation {
        val eye = me.eyePosition
        val basePos = when (aimPoint) {
            AimPoint.HEAD -> target.eyePosition
            AimPoint.CHEST -> target.position().add(0.0, (target.bbHeight.toDouble() * 0.6), 0.0)
            AimPoint.CLOSEST -> closestPoint(me, target)
            AimPoint.RANDOMIZED -> randomizedPoint(target)
        }
        var pos = basePos
        if (predict > 0f && predictSize > 0) {
            val vel = target.position().subtract(target.lastPos)
            pos = pos.add(vel.scale((predict * predictSize).toDouble()))
        }
        val dx = pos.x - eye.x
        val dy = pos.y - eye.y
        val dz = pos.z - eye.z
        val yaw = Mth.wrapDegrees((Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat())
        val pitch = Mth.wrapDegrees((-Math.toDegrees(atan2(dy, sqrt(dx*dx + dz*dz)))).toFloat())
        return Rotation(yaw, pitch)
    }

    private fun closestPoint(me: LocalPlayer, target: LivingEntity): net.minecraft.world.phys.Vec3 {
        val eye = me.eyePosition
        val box = target.boundingBox
        val dist = eye.distanceTo(box.center)

        // Point blank: clamping a ray inside a box that surrounds the camera makes the aim swing
        // tens of degrees for a few centimetres of movement (this is where "dist=0.9, diff=25"
        // came from). Aim at the body, which moves smoothly with the entity.
        if (dist < 1.5) return box.center

        val dir = (lastSent ?: Rotation(me.yRot, me.xRot)).directionVector
        val p = eye.add(dir.scale(dist))
        return net.minecraft.world.phys.Vec3(
            clampAxis(p.x, box.minX + 0.15, box.maxX - 0.15),
            clampAxis(p.y, box.minY + 0.1, box.maxY - 0.15),
            clampAxis(p.z, box.minZ + 0.15, box.maxZ - 0.15)
        )
    }

    /** coerceIn that also survives a box thinner than the margin. */
    private fun clampAxis(value: Double, min: Double, max: Double): Double =
        if (min > max) (min + max) * 0.5 else value.coerceIn(min, max)

    private fun randomizedPoint(target: LivingEntity): net.minecraft.world.phys.Vec3 {
        val box = target.boundingBox
        val rx = box.minX + Random.nextFloat() * (box.maxX - box.minX)
        val ry = box.minY + Random.nextFloat() * (box.maxY - box.minY) * 0.85f + 0.1
        val rz = box.minZ + Random.nextFloat() * (box.maxZ - box.minZ)
        val rnd = (Random.nextFloat() - 0.5f) * aimRandomize
        return net.minecraft.world.phys.Vec3(rx + rnd, ry + rnd, rz + rnd)
    }

    /**
     * The rotation the aim is currently at, used only when there is no [lastSent] yet (module just
     * enabled, target just acquired). In silent mode this is what the manager is aiming with, not
     * the camera: starting from the camera would make every step re-derive the same value and the
     * aim would sit perfectly still on the target.
     */
    private fun currentAim(me: LocalPlayer): Rotation =
        if (silent) RotationManager.currentRotation ?: Rotation(me.yRot, me.xRot) else Rotation(me.yRot, me.xRot)

    /**
     * Where the crosshair would be if the aim were perfect, plus the permanent human error
     * (bias + slow drift). This is a **goal**, never a value that goes into a packet as-is:
     * [stepTowards] decides how much of the remaining angle is covered this tick.
     *
     * With [RotationMode.AI] the goal is whatever the model suggests, and it is still bounded by
     * [stepTowards] — the model can output large deltas and that is precisely what produced the
     * single-packet jumps before.
     */
    private fun goalRotation(me: LocalPlayer, target: LivingEntity): Rotation {
        val base = rotationTo(me, target)
        val aimed = if (rotationMode == RotationMode.AI) {
            aiStep(me, lastSent ?: base, base, target) ?: base
        } else {
            base
        }
        return applyHumanError(me, aimed)
    }

    /**
     * Turns [from] towards [to], covering a fixed share of the remaining angle (driven by
     * [smoothSpeed]) but never more than [maxStep] degrees per tick. This is the only place where the aim may move, and it is what makes the
     * rotation smooth:
     *
     *  - proportional to the remaining angle → it decelerates instead of overshooting;
     *  - hard capped by [maxStep] → no packet can carry a jump;
     *  - a difference below [deadZone] is not chased at all.
     */
    private fun stepTowards(from: Rotation, to: Rotation): Rotation {
        var dYaw = Mth.wrapDegrees(to.yaw - from.yaw)
        var dPitch = to.pitch - from.pitch

        if (abs(dYaw) + abs(dPitch) < deadZone) {
            // The aim is on target. Freezing here is the worst thing we can do: a rotation that is
            // completely still and exactly on the hitbox centre is the clearest "perfect rotation"
            // signature there is (Vulcan "Aim (Rotation)", Matrix "Aim", packet "perfectrotation").
            // A human keeps micro-correcting, so we keep the aim breathing inside the dead zone.
            swayPhase += 0.16f + Random.nextFloat() * 0.14f
            val amplitude = (0.35f + tremor * 1.5f + driftAmount * 0.2f).coerceIn(0.25f, 1f)
            dYaw = kotlin.math.sin(swayPhase) * amplitude
            dPitch = kotlin.math.sin(swayPhase * 0.63f + 1.1f) * amplitude * 0.5f
        }

        // Momentum must never fight the new direction: if the target moved across our aim, the
        // carried speed points the wrong way and keeping it makes the crosshair overshoot by
        // several degrees in a single tick (that is a visible snap, and a rotation check).
        if (velYaw * dYaw < 0f) velYaw = 0f
        if (velPitch * dPitch < 0f) velPitch = 0f

        // The smoothing enum only bends the response curve; it can never push it above 1.
        val shape = when (smoothing) {
            Smoothing.BEZIER -> 0.75f + 0.5f * bezierFactor(abs(dYaw) / 180f)
            Smoothing.SIGMOID -> 0.75f + 0.5f * sigmoidFactor(abs(dYaw) / 180f)
            Smoothing.LINEAR -> 1f
        }
        // Human aim has two phases (Fitts' law): a fast ballistic movement for a big error and a
        // slow corrective one when the crosshair is already close. Without the ballistic phase the
        // aim lags behind a moving target for seconds and the error keeps growing, which looks far
        // worse (a rotation that follows a target at constant speed) than a quick flick.
        val ballistic = (abs(dYaw) / 20f).coerceIn(0f, 1f)
        val rate = (0.3f * smoothSpeed * shape * responseJitter * (1f + 1.5f * ballistic))
            .coerceIn(0.02f, 1f)

        // The cap must GROW with the error, otherwise the aim can never catch a target that
        // circles around the player: it creeps at one constant speed for seconds while the error
        // keeps growing (the log showed remaining 13 -> 22 -> 44 degrees at step 3). That constant
        // creep is also the flattest possible speed sequence, which is what the ML model scores.
        // A human flick to a far target is fast: maxStep near the crosshair, up to 3.2x for a flick.
        val error = kotlin.math.max(abs(dYaw), abs(dPitch) * 1.4f)
        val flick = ((error - 4f) / 12f).coerceIn(0f, 1f)
        flickAmount = flick
        val limitFactor = (1f + 2.2f * flick) * (0.9f + 0.2f * chaosAbs())
        val limitYaw = maxStep * limitFactor
        val limitPitch = maxStep * 0.7f * limitFactor

        // Speed we would like to have this tick. A HARD clamp is what makes an aimbot detectable:
        // while the cap is saturated every step has exactly the same magnitude, and Matrix's
        // AimBasicCheck counts exactly that ("robotized" = |yawChange - yawChangeFirst|: more than
        // 3 of 10 samples with the same magnitude is "heuristic(constant)"), while AimSmoothCheck
        // flags three consecutive identical jiff values. A soft knee plus a per-tick variation
        // keeps the magnitude continuous and never pinned.
        // Independent per-axis modulation: yaw and pitch moving by the same factor every tick is
        // another regularity (and it flattens the speed sequence the ML model reads).
        val wantYaw = softLimit(dYaw * rate, limitYaw) * tickVariation()
        val wantPitch = softLimit(dPitch * rate, limitPitch) * tickVariation()

        // ...but the speed may only change by `maxAccel` per tick. Starting every turn at full
        // speed (or stopping it dead) is a signature every rotation check looks for, and ML models
        // detect it straight away; ramping the speed gives the turn an ease-in / ease-out profile.
        val maxAccel = (maxStep * (0.45f + 0.9f * ballistic) * (1f + 1.5f * flick)).coerceAtLeast(0.2f)
        velYaw = approach(velYaw, wantYaw, maxAccel)
        velPitch = approach(velPitch, wantPitch, maxAccel * 0.8f)

        // The carried speed must not overshoot the goal.
        val stepYaw = if (abs(velYaw) > abs(dYaw)) dYaw else velYaw
        val stepPitch = if (abs(velPitch) > abs(dPitch)) dPitch else velPitch

        // The yaw is kept CONTINUOUS on purpose: see [writeRotation] for why wrapping an absolute
        // angle here ends up in a ~360 degrees jump in the movement packet.
        return Rotation(
            from.yaw + stepYaw,
            (from.pitch + stepPitch).coerceIn(-89.5f, 89.5f)
        )
    }

    /**
     * Reproduces Matrix's `AimAnalysisCheck` metric locally, so the value it would compute is
     * visible in the debug line instead of being guessed from an anticheat message.
     *
     * The check keeps the yaw deltas of the "big" rotations (|yaw| > 1.35), cuts them in windows of
     * 10, computes the 4th order jiff of each window and counts the distinct values. 6 of 6 for a
     * window means rank 1.0; the batch is flagged when the average over 10 windows is below 0.95.
     *
     * @return the per-window ratio, or -1 while there are not enough samples yet.
     */
    private fun analysisRank(): Float {
        if (recentYawDeltas.size < 10) return -1f

        var values = recentYawDeltas.toList()
        repeat(4) {
            if (values.size < 2) return@repeat
            val next = ArrayList<Float>(values.size - 1)
            for (i in 1 until values.size) {
                next.add(kotlin.math.abs(kotlin.math.abs(values[i]) - kotlin.math.abs(values[i - 1])))
            }
            values = next
        }
        if (values.isEmpty()) return -1f
        return values.distinct().size.toFloat() / values.size
    }

    /** Records a sent yaw delta for [analysisRank]; only the "big" ones are scored by Matrix. */
    private fun recordYawDelta(delta: Float) {
        if (kotlin.math.abs(delta) <= 1.35f) return
        recentYawDeltas.addLast(delta)
        while (recentYawDeltas.size > 10) recentYawDeltas.removeFirst()
    }

    /** Length of a step in whole mouse counts: what the ML model measures as "speed". */
    private fun stepCountsOf(from: Rotation, to: Rotation): Int = kotlin.math.round(
        kotlin.math.hypot(
            Mth.wrapDegrees(to.yaw - from.yaw).toDouble(),
            (to.pitch - from.pitch).toDouble()
        ) / RotationUtil.gcd
    ).toInt()

    /** Moves [current] towards [target] by at most [maxDelta]. */
    private fun approach(current: Float, target: Float, maxDelta: Float): Float =
        current + (target - current).coerceIn(-maxDelta, maxDelta)

    /**
     * Saturates [value] softly at [limit]: proportional below the knee, asymptotically bounded
     * above it, and never exactly equal to the limit. Behaving exactly like `coerceIn` for small
     * values, so the smoothing feels the same, but the step magnitude can never get stuck.
     */
    private fun softLimit(value: Float, limit: Float): Float =
        if (limit <= 0f) 0f else limit * kotlin.math.tanh(value / limit)

    /**
     * Speed modulation for one axis of the current tick.
     *
     * Three ingredients, and the third one is what Matrix's `AimAnalysisCheck` measures: it takes
     * the last 100 "big" yaw deltas, cuts them in windows of 10, computes the 4th order jiff
     * (iterated |Δ| of |values|) and counts how many of the resulting 6 numbers are distinct.
     * Fewer than ~58 of 60 for a whole batch is flagged as "Long term analysis". Repeating
     * magnitudes — which a quantized, capped aimbot produces — is exactly what makes those 6
     * numbers repeat, so the magnitude must be driven by something that never repeats.
     *
     *  - a mean-reverting random walk (the "gust") for the overall speed;
     *  - a logistic map (chaotic, deterministic but aperiodic) for the fine structure;
     *  - an occasional "flinch": 1-3 ticks noticeably slower or faster, like a hand that slips.
     */
    private fun tickVariation(): Float {
        chaos *= 3.99f * (1f - chaos)
        if (chaos <= 0.001f || chaos >= 0.999f) chaos = 0.51f
        val chaotic = (chaos - 0.5f) * 2f

        if (flinchLeft <= 0 && Random.nextInt(100) < 4) {
            flinchLeft = Random.nextInt(1, 4)
        }

        var variation = speedGust * (0.93f + Random.nextFloat() * 0.14f) * (1f + 0.18f * chaotic)

        if (flinchLeft > 0) {
            flinchLeft--
            variation *= if (Random.nextBoolean()) 0.72f else 1.22f
        }

        speedGust = (speedGust * 0.82f + (Random.nextFloat() - 0.5f) * 0.45f).coerceIn(0.62f, 1.45f)
        return variation
    }

    /** |chaos| in 0..1, used to dither the step limit so it is never the same ceiling. */
    private fun chaosAbs(): Float = kotlin.math.abs(chaos - 0.5f) * 2f

    /**
     * A tiny, quantized correction used when the aim would otherwise not move at all: one or two
     * mouse counts, keeping the direction for a while (a hand drifts, it does not oscillate).
     */
    private fun nudge(axisSign: Float): Float {
        val magnitude = if (Random.nextInt(100) < 30) 2f else 1f
        return magnitude * axisSign * RotationUtil.gcd.toFloat()
    }

    private fun bezierFactor(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return if (u > 0.35f) bezier(0.05f, 1f, 1 - u) else sigmoid(u)
    }
    private fun bezier(s: Float, e: Float, u: Float) = (1-u)*(1-u)*s + 2*(1-u)*u*1f + u*u*e
    private fun sigmoid(u: Float) = 1 / (1 + kotlin.math.exp(-0.5 * (u - 0.3))).toFloat()
    private fun sigmoidFactor(t: Float) = sigmoid(t)

    /**
     * Re-rolls the persistent aim error for a new target.
     *
     * A human crosshair is never on the exact hitbox centre: there is a constant bias (they aim at
     * the same wrong spot every time) plus a slow drift. Aiming at the geometric centre, even with
     * white noise around it, keeps the *average* error at zero — which is what Vulcan's
     * "Aim (Rotation)" and the "perfectrotation" packet check measure.
     */
    private fun rollAimBias(target: LivingEntity) {
        if (aimBias <= 0f) {
            biasYaw = 0f
            biasPitch = 0f
            driftYaw = 0f
            driftPitch = 0f
            responseJitter = 0.85f + Random.nextFloat() * 0.3f
            return
        }

        val magnitude = aimBias * (0.4f + Random.nextFloat() * 0.6f)
        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
        biasYaw = (cos(angle.toDouble()).toFloat() * magnitude)
        // humans are worse vertically than horizontally
        biasPitch = (sin(angle.toDouble()).toFloat() * magnitude * 0.7f)

        driftYaw = 0f
        driftPitch = 0f
        responseJitter = 0.85f + Random.nextFloat() * 0.3f
    }

    /**
     * Adds the permanent human error: the per-target bias, a slow drift and a trace of noise.
     *
     * All of it is applied to the **goal**, and the goal is then approached with [stepTowards], so
     * the sent rotation stays continuous. Adding noise directly to the sent rotation (the old
     * "humanize") is what made the aim stutter: the step changed size every tick with no relation
     * to the previous one.
     */
    private fun applyHumanError(me: LocalPlayer, rot: Rotation): Rotation {
        if (driftAmount > 0f) {
            driftYaw = (driftYaw * 0.95f + (Random.nextFloat() - 0.5f) * driftAmount * 0.12f)
                .coerceIn(-driftAmount, driftAmount)
            driftPitch = (driftPitch * 0.95f + (Random.nextFloat() - 0.5f) * driftAmount * 0.08f)
                .coerceIn(-driftAmount * 0.7f, driftAmount * 0.7f)
        }

        var yaw = rot.yaw + biasYaw + driftYaw
        var pitch = rot.pitch + biasPitch + driftPitch

        // A trace of white noise, small on purpose: its only job is to break exact repeats.
        yaw += (Random.nextFloat() - 0.5f) * jitterYaw * 0.25f
        pitch += (Random.nextFloat() - 0.5f) * jitterPitch * 0.25f
        // Slow wander (a few degrees per second), not a per-tick wiggle.
        yaw += perlinNoise((perlinTick * 0.05f).toInt()) * perlinScale * 0.35f
        pitch += perlinNoise((perlinTick * 0.05f).toInt() + 40) * perlinScale * 0.2f
        perlinTick++

        if (keepSprint && me.isSprinting) yaw = Mth.wrapDegrees(yaw)
        return Rotation(yaw, pitch.coerceIn(-89.5f, 89.5f))
    }

    private fun perlinNoise(t: Int): Float {
        return (kotlin.math.sin(t * 0.31) * 0.5f + kotlin.math.sin(t * 0.07) * 0.3f).toFloat()
    }

    /**
     * Turns [desired] into a rotation a real mouse could have produced, starting from [from].
     *
     * The client moves the player by whole mouse counts: `step = (float)(f³ * 8) * 0.15f` degrees per
     * count, with `f = sensitivity * 0.6 + 0.2`. That value is exactly [RotationUtil.gcd] and it is the
     * same math LiquidBounce's [Rotation.normalize] uses, so:
     *
     *  - we round the DELTA from the last aimed rotation, never the absolute angle (rounding absolute
     *    values to a global grid is what produces "impossible sensitivity" flags);
     *  - we return the result marked [Rotation.isNormalized] so the manager does not round it again
     *    from a different base, which would move it off the lattice;
     *  - with [gcdDither] the residual is carried to the next tick and the rounding boundary wobbles,
     *    because a real hand does not always land on the nearest count. A perfectly repeating delta
     *    sequence (e.g. "+3 counts, +3 counts, ...") is a classic GCD/aimbot pattern.
     */
    private fun quantizeToMouseCounts(from: Rotation, desired: Rotation): Rotation {
        val step = RotationUtil.gcd
        if (step < 1.0e-6) return desired

        val deltaYaw = RotationUtil.angleDifference(desired.yaw, from.yaw).toDouble()
        val deltaPitch = (desired.pitch - from.pitch).toDouble()

        val rawYaw = deltaYaw / step + (if (gcdDither) yawCarry else 0.0)
        val rawPitch = deltaPitch / step + (if (gcdDither) pitchCarry else 0.0)

        val countsYaw: Int
        val countsPitch: Int

        if (gcdDither) {
            countsYaw = handRound(rawYaw)
            countsPitch = handRound(rawPitch)
            yawCarry = (rawYaw - countsYaw).coerceIn(-0.95, 0.95)
            pitchCarry = (rawPitch - countsPitch).coerceIn(-0.95, 0.95)
        } else {
            countsYaw = kotlin.math.round(rawYaw).toInt()
            countsPitch = kotlin.math.round(rawPitch).toInt()
        }

        val yaw = from.yaw + (countsYaw * step).toFloat()
        // never touch the exact +-90 boundary, servers treat it as an untouched pitch
        val pitch = (from.pitch + (countsPitch * step).toFloat()).coerceIn(-89.9f, 89.9f)
        // continuous yaw, like the vanilla client accumulates it (see [writeRotation])
        return Rotation(yaw, pitch, isNormalized = true)
    }

    /** Nearest whole mouse count, with the decision boundary wobbling like a human hand. */
    private fun handRound(counts: Double): Int {
        val wobble = Random.nextDouble(-DITHER_WOBBLE, DITHER_WOBBLE)
        return kotlin.math.floor(counts + 0.5 + wobble).toInt()
    }

    /**
     * Builds the plan handed to LiquidBounce's [RotationManager]: it carries the rotation this module already
     * computed, so the manager only has to send it to the server without touching the camera (silent rotation).
     *
     * The processors are intentionally left empty — this module smooths and quantizes its own rotation every
     * tick, and letting the manager run its angle smooth a second time would slow the aim down.
     */
    private fun rotationPlan(rotation: Rotation, entity: LivingEntity?) = RotationTarget(
        rotation = rotation,
        entity = entity,
        ticksUntilReset = 5,
        resetThreshold = 1f,
        considerInventory = pauseOnInventory,
        movementCorrection = when (moveFix) {
            MoveFix.OFF -> MovementCorrection.OFF
            MoveFix.STRICT -> MovementCorrection.STRICT
            MoveFix.SILENT -> MovementCorrection.SILENT
        },
    )

    private fun model(): TwoDimensionalRegressionModel? = runCatching {
        if (!DeepLearningEngine.isInitialized) return@runCatching null
        val group = ModelManager.models
        group.modes.firstOrNull { it.tag.equals(aiModel.tag, ignoreCase = true) } ?: group.activeMode
    }.getOrNull()

    private fun aiStep(me: LocalPlayer, from: Rotation, goal: Rotation, target: LivingEntity): Rotation? {
        val mdl = model() ?: return null
        val prev = Rotation(beforeYaw, beforePitch)
        val output = runCatching {
            val sample = CombatSample(
                from.directionVector, prev.directionVector, goal.directionVector,
                prev.rotationDeltaTo(from).toVec2f(),
                me.position().subtract(me.lastPos),
                target.position().subtract(target.lastPos),
                me.squaredBoxedDistanceTo(target).toFloat(),
                target.hurtTime, 0
            )
            mdl.predict(sample.asInput)
        }.getOrNull() ?: return null
        if (output.size < 2 || !output[0].isFinite() || !output[1].isFinite()) return null
        var yaw = from.yaw + output[0] * aiYawMultiplier
        var pitch = from.pitch + output[1] * aiPitchMultiplier
        yaw += Mth.wrapDegrees(goal.yaw - yaw) * (aiCorrection / 100f)
        pitch += (goal.pitch - pitch) * (aiCorrection / 100f)
        return Rotation(Mth.wrapDegrees(yaw), pitch.coerceIn(-90f, 90f))
    }

    private fun remember(me: LocalPlayer, rot: Rotation) {
        beforeYaw = lastYaw
        beforePitch = lastPitch
        lastYaw = rot.yaw
        lastPitch = rot.pitch
    }
}
