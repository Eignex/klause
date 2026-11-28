package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.schedule.AdaptivePolicy
import com.eignex.klause.localsearch.schedule.RoundLog
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.LubyIterator

/** Restart logic for [LocalSearchSolver]: when to restart ([shouldRestart]) and how ([restart]). */
interface RestartPolicy {
    /** Called once per accepted move; true restarts at the engine's next iteration. */
    fun shouldRestart(stepsSinceLastRestart: Int): Boolean

    /** Descent reached a feasible local optimum with [objective]; called right before [restart]. */
    fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {}

    /** A feasible walk reached its restart boundary without establishing local optimality.
     *  Policies may retain this sample as a basin anchor; the notification grants no optimum credit. */
    fun onFeasibleStall(state: LocalSearchState, sample: Sample, objective: Double) {}

    /** Performs the restart. Minimization supplies the best feasible sample as [bestSoFar], satisfaction the best
     *  assignment since its last published sample; initialization and post-publication restarts pass null. */
    fun restart(state: LocalSearchState, bestSoFar: Sample?)

    /**
     * Clears per-solve state so another problem's incumbents cannot affect the next solve; called at solve start.
     * [ScheduleBundle][com.eignex.klause.localsearch.schedule.ScheduleBundle] excludes it from adaptive-member resets.
     */
    fun reset() {}
}

/** Full random restart at a fixed flip cadence. */
class FixedCadenceRestart(
    /** Number of flips between full random restarts. */
    val maxFlipsBeforeRestart: Int = 10_000,
) : RestartPolicy {
    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= maxFlipsBeforeRestart
    override fun restart(state: LocalSearchState, bestSoFar: Sample?) = state.restart()
}

/** How [IteratedLocalSearchRestart] kicks out of a local optimum. */
enum class PerturbationKind {
    /** Re-randomise a uniform random subset of variables. */
    Uniform,

    /** Flip every Boolean and redraw every int of randomly picked factors, a coordinated jump more likely to land
     *  in another basin than scattered single changes. */
    BasinHopping,
}

internal fun anchorAndPerturb(
    state: LocalSearchState,
    anchor: Sample,
    perturbationStrength: Int,
    kind: PerturbationKind = PerturbationKind.Uniform,
) {
    val problem = state.problem
    for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, anchor.boolValue(b))
    for (i in 0 until problem.numIntVars) state.assignment.setInt(i, anchor.intValue(i))
    val totalVars = problem.numBoolVars + problem.numIntVars
    if (totalVars > 0) {
        when (kind) {
            PerturbationKind.Uniform -> repeat(perturbationStrength) {
                kickRandomVar(state)
            }

            PerturbationKind.BasinHopping -> {
                val numFactors = problem.numFactors
                if (numFactors == 0) {
                    repeat(perturbationStrength) { kickRandomVar(state) }
                } else {
                    repeat(perturbationStrength) {
                        val fid = state.rng.nextInt(numFactors)
                        val f = state.problem.factors[fid]
                        for (b in f.boolVars) state.assignment.flipBool(b)
                        for (i in f.intVars) state.assignment.setInt(i, state.randomIntValue(i))
                    }
                }
            }
        }
    }
    state.assumptions.forEachBool { variable, value -> state.assignment.setBool(variable, value) }
    state.assumptions.forEachInt { variable, value -> state.assignment.setInt(variable, value) }
    state.resetStepCounters()
    state.recompute()
}

private fun kickRandomVar(state: LocalSearchState) {
    val problem = state.problem
    val totalVars = problem.numBoolVars + problem.numIntVars
    val pick = state.rng.nextInt(totalVars)
    if (pick < problem.numBoolVars) {
        state.assignment.flipBool(pick)
    } else {
        val v = pick - problem.numBoolVars
        state.assignment.setInt(v, state.randomIntValue(v))
    }
}

/**
 * Restarts from a perturbation of the best sample, or fully at random without one.
 *
 *  - [maxFlipsBeforeRestart]: flips between restarts.
 *  - [perturbationStrength]: variables changed when anchoring.
 */
class AdaptivePerturbationRestart(val maxFlipsBeforeRestart: Int = 10_000, val perturbationStrength: Int = 5) :
    RestartPolicy {
    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= maxFlipsBeforeRestart

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        if (bestSoFar == null) state.restart() else anchorAndPerturb(state, bestSoFar, perturbationStrength)
    }
}

/**
 * Restarts after [patience] consecutive rounds without a strict best-cost improvement, or at
 * [maxFlipsBeforeRestart] if a round never completes. Anchors on the best sample when one exists.
 */
class StagnationRestart(
    /** Consecutive no-improvement rounds before a restart fires. */
    val patience: Int = 8,
    /** Hard flip ceiling that forces a restart even if no round has completed. */
    val maxFlipsBeforeRestart: Int = 100_000,
    /** Variables perturbed when anchoring on the best sample. */
    val perturbationStrength: Int = 5,
) : RestartPolicy,
    AdaptivePolicy {
    init {
        require(patience >= 1) { "patience ≥ 1, got $patience" }
        require(maxFlipsBeforeRestart >= 1) { "maxFlipsBeforeRestart ≥ 1, got $maxFlipsBeforeRestart" }
        require(perturbationStrength >= 0) { "perturbationStrength ≥ 0, got $perturbationStrength" }
    }

    private var bestCost: Double = Double.POSITIVE_INFINITY
    private var roundsSinceImprovement: Int = 0
    private var pendingRestart: Boolean = false

    override fun observe(round: RoundLog) {
        if (round.bestCost < bestCost) {
            bestCost = round.bestCost
            roundsSinceImprovement = 0
        } else if (++roundsSinceImprovement >= patience) {
            pendingRestart = true
        }
    }

    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
        pendingRestart || stepsSinceLastRestart >= maxFlipsBeforeRestart

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        pendingRestart = false
        roundsSinceImprovement = 0
        bestCost = Double.POSITIVE_INFINITY
        if (bestSoFar == null) state.restart() else anchorAndPerturb(state, bestSoFar, perturbationStrength)
    }

    override fun reset() {
        pendingRestart = false
        roundsSinceImprovement = 0
        bestCost = Double.POSITIVE_INFINITY
    }
}

/**
 * Restarts after `unit · luby(i)` flips, following the Luby sequence `1, 1, 2, 1, 1, 2, 4, …` scaled by [unit];
 * learned factor weights survive restarts. M. Luby, A. Sinclair, D. Zuckerman, "Optimal speedup of Las Vegas
 * algorithms", Information Processing Letters 47, 1993, https://doi.org/10.1016/0020-0190(93)90029-9.
 */
class LubyRestart(val unit: Int = 100) : RestartPolicy {
    private val luby = LubyIterator()
    private var cadence: Int = unit * luby.value

    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= cadence

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        state.restart()
        advanceCadence()
    }

    internal fun advanceCadence() {
        luby.advance()
        cadence = unit * luby.value
    }

    override fun reset() {
        luby.reset()
        cadence = unit * luby.value
    }
}
