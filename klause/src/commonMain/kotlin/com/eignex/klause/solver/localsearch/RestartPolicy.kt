package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams

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
     *  [bestSoFar]. The optimiser path supplies the running best feasible sample;
     *  streaming paths (sample / enumerate) pass null. */
    fun restart(state: LocalSearchState, bestSoFar: Sample?)
}

/** Full random restart at a fixed flip cadence. */
class FixedCadenceRestart(val maxFlipsBeforeRestart: Int = 10_000) : RestartPolicy {
    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
        stepsSinceLastRestart >= maxFlipsBeforeRestart
    override fun restart(state: LocalSearchState, bestSoFar: Sample?) = state.restart()
}

/** Perturbation strategy used by [IteratedLocalSearchRestart] when kicking out of a
 *  local optimum. [Uniform] is the original behaviour (random vars across the whole
 *  problem). [BasinHopping] focuses the kick on a randomly-picked factor's neighbourhood
 *  — every variable touched by that factor is randomised — producing a coordinated
 *  multi-variable jump more likely to land in a different basin than scattered single
 *  flips would. */
enum class PerturbationKind { Uniform, BasinHopping }

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
                // Pick `perturbationStrength` factors at random; for each, randomise every
                // variable in that factor's scope. The localisation produces a coordinated
                // kick that traverses a single decision-graph subregion in one shot.
                val numFactors = problem.numFactors
                if (numFactors == 0) {
                    repeat(perturbationStrength) { kickRandomVar(state, problem) }
                } else {
                    repeat(perturbationStrength) {
                        val fid = state.rng.nextInt(numFactors)
                        val f = state.factors[fid]
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
 * Restart from a perturbation of [bestSoFar] instead of randomising fully — keeps the
 * search anchored to good regions, helping `minimize` escape plateaus without throwing
 * away progress.
 *
 *  - [maxFlipsBeforeRestart] — cadence; identical knob to [FixedCadenceRestart].
 *  - [perturbationStrength] — how many random variables to flip / re-set when anchoring
 *    to [bestSoFar]. Higher values widen the search neighbourhood (closer to a full
 *    restart); lower values stay close to the anchor.
 *
 *  Falls back to a full random restart when [bestSoFar] is null (i.e. no feasible
 *  sample has been seen yet — we have nothing to anchor to).
 */
class AdaptivePerturbationRestart(
    val maxFlipsBeforeRestart: Int = 10_000,
    val perturbationStrength: Int = 5,
) : RestartPolicy {
    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
        stepsSinceLastRestart >= maxFlipsBeforeRestart

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        if (bestSoFar == null) state.restart() else anchorAndPerturb(state, bestSoFar, perturbationStrength)
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
    private var u: Int = 1
    private var v: Int = 1
    private var cadence: Int = unit

    override fun shouldRestart(stepsSinceLastRestart: Int): Boolean =
        stepsSinceLastRestart >= cadence

    override fun restart(state: LocalSearchState, bestSoFar: Sample?) {
        state.restart()
        advance()
    }

    /** Knuth's O(1) iterative form of the Luby sequence. */
    private fun advance() {
        if ((u and -u) == v) {
            u += 1
            v = 1
        } else {
            v *= 2
        }
        cadence = unit * v
    }
}
