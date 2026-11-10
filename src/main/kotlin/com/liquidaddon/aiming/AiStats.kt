package com.liquidaddon.aiming

import kotlin.math.sqrt

/**
 * The telemetry behind `/liquidaddon ai`: one session of [AimassistCore] under `Engine = AI`,
 * reduced to the numbers that say whether the model is really driving.
 *
 * Pure on purpose — counters, two step traces and a formatter, no client, no module, no rotation —
 * so the line a player reads in chat is the same line a test asserts (see `AiStatsTest`):
 *
 * `ai 94% used | fb: init 0, model 0, nan 0, reach 6% | step 1.20° vs curve 0.85° | corr 0.71 | aiStr 100`
 *
 *  - `used` is the share of the ticks that asked the model and got an answer back; the rest fell
 *    back to the curve, and [Fallback] says why (engine not initialised, no model loaded, a NaN out
 *    of the forward pass, anything else);
 *  - `reach` is the share of the asked ticks where [AiReach] faded the answer (blend below 1), i.e.
 *    the situation was outside what the model was fitted on and the curve took a share back;
 *  - `step vs curve` are the mean step magnitudes, in degrees, of what the model drove and of what
 *    the maths curve would have driven on the *same* tick — the pair that tells whether the model
 *    is authoring the movement or just nudging it;
 *  - `corr` is the Pearson correlation between the model's yaw step and the curve's yaw step over
 *    the last [WINDOW] AI ticks. A regression does not err noisily, it errs coherently: a correlate
 *    that decays is the model diverging from the law on the same input, which is exactly when the
 *    law should be trusted again (see the drift guard).
 *
 * Nothing here decides anything; it only counts. The guards live where they can act.
 */
class AiStats {

    /** Why a tick that asked the model ended up running the curve instead. */
    enum class Fallback { INIT, MODEL, NAN, OTHER }

    /**
     * One AI tick, as the two yaw steps it could have been: what the model drove (after the caps)
     * and what the curve would have driven on the same delta. The window correlation lives on these.
     */
    data class Step(val aiYaw: Float, val curveYaw: Float)

    data class Snapshot(
        /** Ticks that asked the model: the denominator of every percentage below. */
        val ticks: Int,
        val usedPct: Int,
        val fbInit: Int,
        val fbModel: Int,
        val fbNan: Int,
        val fbOther: Int,
        val reachPct: Int,
        val meanAiStep: Float,
        val meanCurveStep: Float,
        val corr: Float,
    )

    private var requested = 0
    private var used = 0
    private var faded = 0
    private var fbInit = 0
    private var fbModel = 0
    private var fbNan = 0
    private var fbOther = 0
    private var aiMagSum = 0.0
    private var curveMagSum = 0.0
    private var steps = 0
    private val window = ArrayDeque<Step>()

    /** A tick asked the model for a step, whatever the answer turns out to be. */
    fun noteRequested() {
        requested++
    }

    /** [AiReach] faded the answer of this tick: the curve took a share of the step back. */
    fun noteFaded() {
        faded++
    }

    fun noteFallback(kind: Fallback) {
        when (kind) {
            Fallback.INIT -> fbInit++
            Fallback.MODEL -> fbModel++
            Fallback.NAN -> fbNan++
            Fallback.OTHER -> fbOther++
        }
    }

    /** The model drove this tick: [aiMag]/[curveMag] are whole-step magnitudes in degrees. */
    fun noteUsed(aiMag: Float, curveMag: Float, step: Step) {
        used++
        steps++
        aiMagSum += aiMag
        curveMagSum += curveMag
        window.addLast(step)
        while (window.size > WINDOW) {
            window.removeFirst()
        }
    }

    fun reset() {
        requested = 0
        used = 0
        faded = 0
        fbInit = 0
        fbModel = 0
        fbNan = 0
        fbOther = 0
        aiMagSum = 0.0
        curveMagSum = 0.0
        steps = 0
        window.clear()
    }

    fun snapshot(): Snapshot = Snapshot(
        ticks = requested,
        usedPct = percent(used, requested),
        fbInit = fbInit,
        fbModel = fbModel,
        fbNan = fbNan,
        fbOther = fbOther,
        reachPct = percent(faded, requested),
        meanAiStep = mean(aiMagSum, steps),
        meanCurveStep = mean(curveMagSum, steps),
        corr = windowCorr(),
    )

    /** The correlation of the current window: the number the drift guard acts on (see [AiDrift]). */
    fun windowCorr(): Float = pearson(window.map { it.aiYaw }, window.map { it.curveYaw })

    /** Whether [WINDOW] pairs have been collected: the guard does not act on partial evidence. */
    fun windowFull(): Boolean = window.size >= WINDOW

    private fun percent(part: Int, whole: Int): Int =
        if (whole <= 0) 0 else ((100.0 * part / whole) + 0.5).toInt()

    private fun mean(sum: Double, count: Int): Float =
        if (count <= 0) 0f else (sum / count).toFloat()

    companion object {

        /** Ticks of model/curve pairs the correlation is measured on (the drift guard's window). */
        const val WINDOW = 20

        /**
         * The chat line, exactly as [Snapshot] says it. A session with no AI tick yet is not a line
         * of zeroes — zeroes would read as "the model answered nothing", which is a different fact.
         */
        fun format(s: Snapshot, aiStrength: Float): String {
            if (s.ticks == 0) {
                return "nessun tick AI in questa sessione"
            }
            val other = if (s.fbOther > 0) ", other %d".format(s.fbOther) else ""
            val corr = if (s.corr.isNaN()) "n/a" else "%.2f".format(s.corr)
            return ("ai %d%% used | fb: init %d, model %d, nan %d%s, reach %d%% | " +
                "step %.2f° vs curve %.2f° | corr %s | aiStr %d").format(
                    s.usedPct, s.fbInit, s.fbModel, s.fbNan, other, s.reachPct,
                    s.meanAiStep, s.meanCurveStep, corr, aiStrength.roundToPercent()
                )
        }

        private fun Float.roundToPercent(): Int = (this + 0.5f).toInt()

        /**
         * Pearson's r between two traces of the same length. [Float.NaN] when there is nothing to
         * correlate — fewer than two pairs, or a trace that never moves: a constant has no
         * direction, and "perfectly correlated" would be a lie about a dead signal.
         */
        fun pearson(xs: List<Float>, ys: List<Float>): Float {
            if (xs.size != ys.size || xs.size < 2) {
                return Float.NaN
            }
            val mx = xs.sum() / xs.size
            val my = ys.sum() / ys.size
            var cov = 0.0
            var vx = 0.0
            var vy = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - mx
                val dy = ys[i] - my
                cov += dx * dy
                vx += dx * dx
                vy += dy * dy
            }
            val den = sqrt(vx * vy)
            if (den <= 1e-9) {
                return Float.NaN
            }
            return (cov / den).toFloat()
        }

        /**
         * The reason string [AimassistCore.aiUnavailable] produces, mapped to the counter a player
         * reads. Strings on purpose: the reasons are sentences written for the debug line, and the
         * counter only needs the family of the failure.
         */
        fun fallbackKind(reason: String): Fallback = when {
            reason.contains("not initialised") -> Fallback.INIT
            reason.startsWith("no model") -> Fallback.MODEL
            reason.contains("NaN") -> Fallback.NAN
            else -> Fallback.OTHER
        }
    }
}
