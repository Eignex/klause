package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.schedule.AdaptivePolicy
import com.eignex.klause.localsearch.schedule.RoundLog
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.LubyIterator

/**
 * Pluggable restart logic for [LocalSearchSolver]. Decouples *when* to restart
 * ([shouldRestart]) from *how* ([restart]) so callers can swap a fixed cadence for
 * adaptive perturbation, Luby-sequence schedules, stagnation detection, etc., without
 * touching the engine.
 */
interface RestartPolicy {
    /** Called once per accepted move with the count of moves applied since the last
     *  restart. Return true to trigger a restart at the engine's next iteration. */
    fun shouldRestart(stepsSinceLastRestart: Int): Boolean

    /** Notification from the optimiser path: greedy descent reached a feasible local
     *  optimum at `state.cost == 0` with the given objective value. Policies that want
     *  to track an incumbent across restarts (ILS-style) implement this; the default
     *  is a no-op. The optimiser calls it right before [restart]. */
    fun onLocalOptimum(state: LocalSearchState, sample: Sample, objective: Double) {}

    /** Carry out the restart. Default behaviour is a fresh random assignment via
     *  [LocalSearchState.restart]; policies that anchor to good regions override using
     *  `bestSoFar`. The optimiser path supplies the running best feasible sample;
     *  streaming paths (sample / enumerate) pass null. */
    fun restart(state: LocalSearchState, bestSoFar: Sample?)

    /** Clear per-solve mutable state (tracked incumbents, perturbation strength, cadence position)
     *  so a policy instance reused across solves — e.g. a tuning campaign running one recipe over
     *  many problems — starts each solve clean. A stale incumbent from an earlier problem can even
     *  carry a different variable arity, which the anchor/crossover paths then index against the new
     *  problem's dimensions. [ScheduleBundle][com.eignex.klause.localsearch.schedule.ScheduleBundle]
     *  deliberately leaves the restart policy out of its adaptive-member reset; the engine calls
     *  this at the start of every solve instead. Stateless policies keep the default no-op. */
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

/** Perturbation strategy used by [IteratedLocalSearchRestart] when kicking out of a
 *  local optimum. [Uniform] is the original behaviour (random vars across the whole
 *  problem). [BasinHopping] focuses the kick on a randomly-picked factor's neighbourhood
 *  — every variable touched by that factor is randomised — producing a coordinated
 *  multi-variable jump more likely to land in a different basin than scattered single
 *  flips would. */
enum class PerturbationKind {
    /** Re-randomise a uniform random subset of variables. */
    Uniform,

    /** Apply a coordinated multi-variable jump to escape the current basin. */
    BasinHopping,
}

/**
 * Copy [anchor] into [state]'s assignment, apply [perturbationStrength] mutations
 * according to [kind], then recompute the cost. Shared between [AdaptivePerturbationRestart]
 * and [IteratedLocalSearchRestart].
 */
internal fun anchorAndPerturb(
    state: LocalSearchState,
    anchor: Sample,
    perturbationStrength: Int,
    kind: PerturbationKind = PerturbationKind.Uniform,
) {
    val problem = state.problem
    for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, anchor.bools[b])
    for (i in 0 until problem.numIntVars) state.assignment.setInt(i, anchor.ints[i])
    val totalVars = problem.numBoolVars + problem.numIntVars
    if (totalVars > 0) {
        when (kind) {
            PerturbationKind.Uniform -> repeat(perturbationStrength) {
                kickRandomVar(state, problem)
            }

            PerturbationKind.BasinHopping -> {
                // Pick `perturbationStrength` factors at random; randomise every variable in each
                // factor's scope, for a coordinated kick across a single decision-graph subregion.
                val numFactors = problem.numFactors
                if (numFactors == 0) {
                    repeat(perturbationStrength) { kickRandomVar(state, problem) }
                } else {
                    repeat(perturbationStrength) {
                        val fid = state.rng.nextInt(numFactors)
                        val f = state.problem.factors[fid]
                        for (b in f.boolVars) state.assignment.flipBool(b)
                        for (i in f.intVars) {
                            val d = problem.intDomains[i]
                            state.assignment.setInt(i, d.valueAt(state.rng.nextInt(d.size)))
                        }
                    }
                }
            }
        }
    }
    state.recompute()
}

private fun kickRandomVar(state: LocalSearchState, problem: Problem) {
    val totalVars = problem.numBoolVars + problem.numIntVars
    val pick = state.rng.nextInt(totalVars)
    if (pick < problem.numBoolVars) {
        state.assignment.flipBool(pick)
    } else {
        val v = pick - problem.numBoolVars
        val d = problem.intDomains[v]
        state.assignment.setInt(v, d.valueAt(state.rng.nextInt(d.size)))
    }
}

/**
 * Restart from a perturbation of `bestSoFar` instead of randomising fully — keeps the
 * search anchored to good regions, helping `minimize` escape plateaus without throwing
 * away progress.
 *
 *  - [maxFlipsBeforeRestart] — cadence; identical knob to [FixedCadenceRestart].
 *  - [perturbationStrength] — how many random variables to flip / re-set when anchoring
 *    to `bestSoFar`. Higher values widen the search neighbourhood (closer to a full
 *    restart); lower values stay close to the anchor.
 *
 *  Falls back to a full random restart when `bestSoFar` is null (i.e. no feasible
 *  sample has been seen yet — we have nothing to anchor to).
 */
class AdaptivePerturbationRestart(val maxFlipsBeforeRestart: Int = 10_000, val perturbationStrength: Int = 5) :
    RestartPolicy {
    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= maxFlipsBeforeRestart

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        if (bestSoFar == null) state.restart() else anchorAndPerturb(state, bestSoFar, perturbationStrength)
    }
}

/**
 * Stagnation-driven restart on the shared per-round feedback channel ([AdaptivePolicy]): rather than
 * a fixed flip cadence, restart after [patience] consecutive rounds ([RoundLog]) with no strict
 * improvement in the best cost seen. A [maxFlipsBeforeRestart] ceiling still forces a restart if a
 * round never completes, so the policy can't wedge. Anchors to `bestSoFar` with a
 * [perturbationStrength] perturbation when one exists, else a full random restart.
 */
class StagnationRestart(
    /** Consecutive no-improvement rounds before a restart fires. */
    val patience: Int = 8,
    /** Hard flip ceiling that forces a restart even if no round has completed. */
    val maxFlipsBeforeRestart: Int = 100_000,
    /** Variables perturbed when anchoring to `bestSoFar` (see [anchorAndPerturb]). */
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
 * Luby–Sinclair–Zuckerman restart schedule: cadence follows the Luby sequence
 * `1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, …`, each term scaled by [unit] flips.
 * Universally optimal in expectation for Las Vegas algorithms with unknown runtime
 * distribution — short bursts dominate, but occasional long runs let a hard subproblem
 * converge.
 *
 * `factorWeights` and DDFW-style learnt state survive (`state.restart()` doesn't touch
 * them); only the assignment, `lastTouched`, and `step` reset.
 */
class LubyRestart(val unit: Int = 100) : RestartPolicy {
    private val luby = LubyIterator()
    private var cadence: Int = unit * luby.value

    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean = stepsSinceLastRestart >= cadence

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        state.restart()
        luby.advance()
        cadence = unit * luby.value
    }

    override fun reset() {
        luby.reset()
        cadence = unit * luby.value
    }
}
