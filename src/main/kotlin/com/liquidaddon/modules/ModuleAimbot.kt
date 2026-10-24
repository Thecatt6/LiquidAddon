package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.ModelManager
import net.ccbluex.liquidbounce.deeplearn.data.CombatSample
import net.ccbluex.liquidbounce.deeplearn.models.TwoDimensionalRegressionModel
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerMovementTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ModuleAimbot — Aimbot AI non-detectabile
 * Basato su HitFlick mode 2: GCD quantization + Silent MoveFix + deeplearn AI
 */
object ModuleAimbot : ClientModule("Aimbot", Categories.LIQUIDADDON) {

    enum class Priority(override val tag: String) : Tagged {
        DISTANCE("Distance"), HEALTH("Health"), FOV("Fov"), ANGLE("Angle"), ARMOR("Armor")
    }
    enum class RotationMode(override val tag: String) : Tagged {
        SMOOTH("Smooth"), AI("AI"), SNAP("Snap")
    }
    enum class Smoothing(override val tag: String) : Tagged {
        BEZIER("Bezier"), SIGMOID("Sigmoid"), LINEAR("Linear")
    }
    enum class MoveFix(override val tag: String) : Tagged {
        OFF("Off"), STRICT("Strict"), SILENT("Silent")
    }
    enum class AimPoint(override val tag: String) : Tagged {
        HEAD("Head"), CHEST("Chest"), CLOSEST("Closest"), RANDOMIZED("Randomized")
    }
    enum class AiModel(override val tag: String) : Tagged {
        MODEL_19KC8KP("19KC8KP"), MODEL_21KC11KP("21KC11KP")
    }
    enum class Preset(override val tag: String) : Tagged {
        CUSTOM("Custom"), BLATANT("Blatant"), LEGIT("Legit"), GHOST("Ghost"), CLOSET("Closet")
    }

    private val range by float("Range", 4.2f, 1f..6f, "blocks")
    private val fov by float("FOV", 90f, 10f..180f, "°")
    private val priority by enumChoice("Priority", Priority.DISTANCE)
    private val throughWalls by boolean("ThroughWalls", false)
    private val targetFriends by boolean("TargetFriends", false)
    private val targetInvisible by boolean("TargetInvisible", false)
    private val targetDead by boolean("TargetDead", false)
    private val minHealth by int("MinHealth", 0, 0..20, "HP")
    private val antiBot by boolean("AntiBot", true)
    private val lockTarget by boolean("LockTarget", true)
    private val switchDelay by int("SwitchDelay", 250, 0..2000, "ms")

    private val preset by enumChoice("Preset", Preset.LEGIT)
    private val rotationMode by enumChoice("RotationMode", RotationMode.AI)
    private val smoothing by enumChoice("Smoothing", Smoothing.BEZIER)
    private val aimPoint by enumChoice("AimPoint", AimPoint.CLOSEST)
    private val aimRandomize by float("AimRandomize", 0.25f, 0f..1f)
    private val predict by float("Predict", 0.35f, 0f..1f)
    private val predictSize by int("PredictSize", 3, 0..10, "ticks")

    private val smoothSpeed by float("SmoothSpeed", 1.2f, 0.1f..3f)
    private val flickTicks by int("FlickTicks", 3, 1..10, "ticks")
    private val aiModel by enumChoice("AIModel", AiModel.MODEL_19KC8KP)
    private val aiYawMultiplier by float("AIYawMultiplier", 1.5f, 0.5f..3f)
    private val aiPitchMultiplier by float("AIPitchMultiplier", 1f, 0.5f..3f)
    private val aiCorrection by int("AICorrection", 3, 0..30, "%")
    private val aiMaxTicks by int("AIMaxTicks", 8, 1..20, "ticks")

    private val reactionDelay by int("ReactionDelay", 80, 0..300, "ms")
    private val reactionRandom by int("ReactionRandom", 60, 0..200, "ms")
    private val overshoot by float("Overshoot", 0.08f, 0f..0.5f)
    private val jitterYaw by float("JitterYaw", 0.4f, 0f..2f, "°")
    private val jitterPitch by float("JitterPitch", 0.3f, 0f..2f, "°")
    private val tremor by float("Tremor", 0.15f, 0f..1f, "°")
    private val perlinScale by float("PerlinScale", 0.3f, 0f..1f)
    private val gcdFix by boolean("GCDFix", true)
    private val randomizeOffset by float("RandomizeOffset", 0.08f, 0f..0.5f)
    private val keepSprint by boolean("KeepSprint", true)

    private val silent by boolean("Silent", true)
    private val moveFix by enumChoice("MoveFix", MoveFix.SILENT)
    private val silentFov by boolean("SilentFOV", true)
    private val onSneak by boolean("OnSneak", true)
    private val onSprint by boolean("OnSprint", true)
    private val checkVisible by boolean("CheckVisible", true)

    private val autoBlock by boolean("AutoBlock", false)
    private val pauseOnInventory by boolean("PauseOnInventory", true)
    private val pauseOnUse by boolean("PauseOnUse", false)
    private val chatLog by boolean("ChatLog", false)

    private var currentTarget: LivingEntity? = null
    private var lastTargetSwitch = 0L
    private var reactionUntil = 0L
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var beforeYaw = 0f
    private var beforePitch = 0f
    private var perlinTick = 0
    private var hitCount = 0

    private var spoofing = false
    private var spoofedMovement = false
    private var realYaw = 0f
    private var realPitch = 0f
    private var realYawO = 0f
    private var realPitchO = 0f
    private var realHeadYawO = 0f

    override val tag: String
        get() = currentTarget?.let { "${it.name.string.take(8)} ${String.format("%.1f", it.health)}" } ?: ""

    override fun onEnabled() {
        currentTarget = null
        lastTargetSwitch = 0L
        reactionUntil = 0L
        hitCount = 0
        perlinTick = 0
        resetRotationMemory()
    }

    override fun onDisabled() {
        val me = mc.player
        if (me != null) endSpoof(me)
        // clear rotation — RotationManager has no nullable setter in 26.3, just stop targeting
        try { RotationManager.setRotationTarget(Rotation(me?.yRot ?: 0f, me?.xRot ?: 0f), false, net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable(), net.ccbluex.liquidbounce.utils.aiming.RotationManager.Priority.NORMAL, this) } catch (_: Exception) {}
        currentTarget = null
    }

    private fun resetRotationMemory() {
        val me = mc.player ?: return
        lastYaw = me.yRot
        lastPitch = me.xRot
        beforeYaw = me.yRotO
        beforePitch = me.xRotO
    }

    @Suppress("unused")
    private val movementHandler = handler<PlayerMovementTickEvent> {
        val me = mc.player ?: return@handler
        if (silent && moveFix != MoveFix.OFF) beginSpoof(me, true)
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        if (spoofing && moveFix == MoveFix.SILENT) {
            val me = mc.player ?: return@handler
            event.directionalInput = silentInput(event.directionalInput, realYaw, me.yRot)
        }
    }

    @Suppress("unused")
    private val networkHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        val me = mc.player ?: return@handler
        if (event.state == EventState.PRE) {
            if (silent) beginSpoof(me, false)
        } else {
            endSpoof(me)
        }
    }

    private fun beginSpoof(me: LocalPlayer, movement: Boolean) {
        if (spoofing) return
        spoofing = true
        spoofedMovement = movement
        realYaw = me.yRot
        realPitch = me.xRot
        realYawO = me.yRotO
        realPitchO = me.xRotO
        realHeadYawO = me.yHeadRotO
    }

    private fun endSpoof(me: LocalPlayer) {
        if (!spoofing) return
        spoofing = false
        me.yRot = realYaw
        me.xRot = realPitch
        me.yRotO = realYawO
        me.xRotO = realPitchO
        if (spoofedMovement) {
            me.yHeadRot = realYaw
            me.yHeadRotO = realHeadYawO
            me.yBob = me.yBobO + (realYaw - me.yBobO) * 0.5f
            me.xBob = me.xBobO + (realPitch - me.xBobO) * 0.5f
        }
    }

    private fun silentInput(input: DirectionalInput, realYaw: Float, sentYaw: Float): DirectionalInput {
        val forward = (if (input.forwards) 1 else 0) - (if (input.backwards) 1 else 0)
        val strafe = (if (input.left) 1 else 0) - (if (input.right) 1 else 0)
        if (forward == 0 && strafe == 0) return input
        val meant = realYaw + Math.toDegrees(atan2(-strafe.toDouble(), forward.toDouble()))
        val relative = Math.toRadians(Math.round(Mth.wrapDegrees(meant - sentYaw) / 45.0) * 45.0)
        val newForward = kotlin.math.round(kotlin.math.cos(relative)).toInt()
        val newStrafe = kotlin.math.round(-kotlin.math.sin(relative)).toInt()
        return DirectionalInput(newForward > 0, newForward < 0, newStrafe > 0, newStrafe < 0)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler
        if (mc.gameMode == null) {
            currentTarget = null
            return@handler
        }
        // inventory check - API changed in 26.3 (screen -> currentScreen), fallback disabled
        // if needed, rely on mc.screen check via reflection
        if (pauseOnInventory) {
            try {
                val f = Minecraft::class.java.getDeclaredField("screen").apply { isAccessible = true }
                val scr = f.get(Minecraft.getInstance())
                if (scr != null) return@handler
            } catch (_: Exception) {
                try {
                    val f2 = Minecraft::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }
                    if (f2.get(Minecraft.getInstance()) != null) return@handler
                } catch (_: Exception) {}
            }
        }
        if (pauseOnUse && me.isUsingItem) return@handler
        if (!onSneak && me.isShiftKeyDown) return@handler
        if (!onSprint && me.isSprinting) return@handler

        endSpoof(me)

        val target = findTarget(me) ?: run {
            if (!lockTarget) currentTarget = null
            try { RotationManager.setRotationTarget(Rotation(me.yRot, me.xRot), false, net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable(), RotationManager.Priority.NORMAL, this@ModuleAimbot) } catch (_: Exception) {}
            return@handler
        }

        if (currentTarget == null || !isValidTarget(me, target)) {
            val now = System.currentTimeMillis()
            if (now - lastTargetSwitch < switchDelay) return@handler
            lastTargetSwitch = now
            reactionUntil = now + reactionDelay + Random.nextInt(reactionRandom + 1)
        }
        currentTarget = target

        if (System.currentTimeMillis() < reactionUntil) return@handler

        val rotation = computeRotation(me, target) ?: return@handler
        if (silentFov && !isInFov(me, rotation)) return@handler

        val final = humanize(me, rotation)
        val quantized = if (gcdFix) quantize(final) else final

        if (silent) {
            try {
                // stable API in 26.3: setRotationTarget(plan, priority, provider) where plan = RotationTarget(Rotation)
                val plan = net.ccbluex.liquidbounce.utils.aiming.RotationTarget(quantized)
                RotationManager.setRotationTarget(plan, RotationManager.Priority.NORMAL, this@ModuleAimbot)
            } catch (e: Exception) {
                try {
                    // alternative: setRotationTarget(rotation, valueGroup, priority, provider)
                    RotationManager.setRotationTarget(quantized, false, net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable(), RotationManager.Priority.NORMAL, this@ModuleAimbot)
                } catch (_: Exception) {
                    me.yRot = quantized.yaw
                    me.xRot = quantized.pitch.coerceIn(-90f, 90f)
                }
            }
        } else {
            me.yRot = quantized.yaw
            me.xRot = quantized.pitch.coerceIn(-90f, 90f)
        }

        remember(me, quantized)
        if (chatLog && hitCount++ % 40 == 0) {
            chat("§7[Aimbot] target=${target.name.string} yaw=%.1f pitch=%.1f mode=%s".format(quantized.yaw, quantized.pitch, preset.tag))
        }
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
        for (e in candidates) {
            if (e == me) continue
            if (!isValidTarget(me, e)) continue
            val rot = rotationTo(me, e)
            if (!isInFov(me, rot)) continue
            if (checkVisible && !me.hasLineOfSight(e) && !throughWalls) continue
            if (e.squaredBoxedDistanceTo(me) > range * range) continue
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
        if (!e.isAlive) return false
        if (e.health <= minHealth) return false
        if (!targetDead && e.isDeadOrDying) return false
        if (!targetInvisible && e.isInvisible) return false
        if (!targetFriends) {
            val isFriend = try {
                val fm = Class.forName("net.ccbluex.liquidbounce.utils.client.FriendManager")
                val m = fm.methods.firstOrNull { it.name == "isFriend" && it.parameterCount == 1 }
                m?.invoke(null, e) as? Boolean ?: false
            } catch (_: Exception) { false }
            if (isFriend) return false
        }
        if (antiBot && isBot(e)) return false
        if (!e.shouldBeAttacked()) return false
        return true
    }

    private fun isBot(e: LivingEntity): Boolean {
        return e.name.string.contains("§") || e.hurtTime == 0 && e.tickCount < 80
    }

    private fun getArmorValue(e: LivingEntity): Float {
        return try { e.getArmorValue().toFloat() } catch (_: Exception) { 0f }
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
        val dir = Rotation(me.yRot, me.xRot).directionVector
        val dist = eye.distanceTo(target.position())
        val along = kotlin.math.max(0.5, dist)
        val p = eye.add(dir.scale(along))
        return net.minecraft.world.phys.Vec3(
            p.x.coerceIn(box.minX + 0.15, box.maxX - 0.15),
            p.y.coerceIn(box.minY + 0.1, box.maxY - 0.15),
            p.z.coerceIn(box.minZ + 0.15, box.maxZ - 0.15)
        )
    }

    private fun randomizedPoint(target: LivingEntity): net.minecraft.world.phys.Vec3 {
        val box = target.boundingBox
        val rx = box.minX + Random.nextFloat() * (box.maxX - box.minX)
        val ry = box.minY + Random.nextFloat() * (box.maxY - box.minY) * 0.85f + 0.1
        val rz = box.minZ + Random.nextFloat() * (box.maxZ - box.minZ)
        val rnd = (Random.nextFloat() - 0.5f) * aimRandomize
        return net.minecraft.world.phys.Vec3(rx + rnd, ry + rnd, rz + rnd)
    }

    private fun computeRotation(me: LocalPlayer, target: LivingEntity): Rotation? {
        val base = rotationTo(me, target)
        val mode = when (preset) {
            Preset.BLATANT -> RotationMode.SNAP
            Preset.LEGIT -> RotationMode.AI
            Preset.GHOST -> RotationMode.SMOOTH
            Preset.CLOSET -> RotationMode.SMOOTH
            else -> rotationMode
        }
        val speed = when (preset) {
            Preset.BLATANT -> 3f
            Preset.LEGIT -> 1.0f
            Preset.GHOST -> 0.45f
            Preset.CLOSET -> 0.65f
            else -> smoothSpeed
        }
        return when (mode) {
            RotationMode.SNAP -> base
            RotationMode.SMOOTH -> smoothStep(me, base, speed)
            RotationMode.AI -> aiStep(me, base, target) ?: smoothStep(me, base, speed)
        }
    }

    private fun smoothStep(me: LocalPlayer, target: Rotation, speed: Float): Rotation {
        val cur = Rotation(me.yRot, me.xRot)
        val diffYaw = Mth.wrapDegrees(target.yaw - cur.yaw)
        val diffPitch = target.pitch - cur.pitch
        val factor = when (smoothing) {
            Smoothing.BEZIER -> bezierFactor(abs(diffYaw) / 180f) * speed * 0.15f
            Smoothing.SIGMOID -> sigmoidFactor(abs(diffYaw) / 180f) * speed * 0.15f
            Smoothing.LINEAR -> speed * 0.08f
        }
        val over = 1f + Random.nextFloat() * overshoot * (if (Random.nextBoolean()) 1 else -1)
        var yaw = cur.yaw + diffYaw * factor * over
        var pitch = cur.pitch + diffPitch * factor * over
        yaw += (Random.nextFloat() - 0.5f) * jitterYaw
        pitch += (Random.nextFloat() - 0.5f) * jitterPitch
        yaw += perlinNoise(perlinTick) * tremor
        perlinTick++
        return Rotation(yaw, pitch)
    }

    private fun bezierFactor(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return if (u > 0.35f) bezier(0.05f, 1f, 1 - u) else sigmoid(u)
    }
    private fun bezier(s: Float, e: Float, u: Float) = (1-u)*(1-u)*s + 2*(1-u)*u*1f + u*u*e
    private fun sigmoid(u: Float) = 1 / (1 + kotlin.math.exp(-0.5 * (u - 0.3))).toFloat()
    private fun sigmoidFactor(t: Float) = sigmoid(t)

    private fun humanize(me: LocalPlayer, rot: Rotation): Rotation {
        var yaw = rot.yaw + (Random.nextFloat() - 0.5f) * randomizeOffset * 10f
        var pitch = rot.pitch + (Random.nextFloat() - 0.5f) * randomizeOffset * 8f
        yaw += perlinNoise(perlinTick + 100) * perlinScale
        pitch += perlinNoise(perlinTick + 200) * perlinScale * 0.5f
        if (keepSprint && me.isSprinting) yaw = Mth.wrapDegrees(yaw)
        return Rotation(yaw, pitch.coerceIn(-90f, 90f))
    }

    private fun perlinNoise(t: Int): Float {
        return (kotlin.math.sin(t * 0.31) * 0.5f + kotlin.math.sin(t * 0.07) * 0.3f).toFloat()
    }

    private fun quantize(rot: Rotation): Rotation {
        val sens = Minecraft.getInstance().options.sensitivity().get() * 0.6f + 0.2f
        val gcd = sens * sens * sens * 8f * 0.15f
        fun q(v: Float) = kotlin.math.round(v / gcd) * gcd
        return Rotation(q(rot.yaw), q(rot.pitch))
    }

    private fun mouseFactor(): Double {
        val d = Minecraft.getInstance().options.sensitivity().get() * 0.6f.toDouble() + 0.2f.toDouble()
        return d * d * d * 8.0
    }

    private fun model(): TwoDimensionalRegressionModel? = runCatching {
        if (!DeepLearningEngine.isInitialized) return@runCatching null
        val group = ModelManager.models
        group.modes.firstOrNull { it.tag.equals(aiModel.tag, ignoreCase = true) } ?: group.activeMode
    }.getOrNull()

    private fun aiStep(me: LocalPlayer, goal: Rotation, target: LivingEntity): Rotation? {
        val mdl = model() ?: return null
        val cur = Rotation(me.yRot, me.xRot)
        val prev = Rotation(beforeYaw, beforePitch)
        val output = runCatching {
            val sample = CombatSample(
                cur.directionVector, prev.directionVector, goal.directionVector,
                prev.rotationDeltaTo(cur).toVec2f(),
                me.position().subtract(me.lastPos),
                target.position().subtract(target.lastPos),
                me.squaredBoxedDistanceTo(target).toFloat(),
                target.hurtTime, 0
            )
            mdl.predict(sample.asInput)
        }.getOrNull() ?: return null
        if (output.size < 2 || !output[0].isFinite() || !output[1].isFinite()) return null
        var yaw = cur.yaw + output[0] * aiYawMultiplier
        var pitch = cur.pitch + output[1] * aiPitchMultiplier
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