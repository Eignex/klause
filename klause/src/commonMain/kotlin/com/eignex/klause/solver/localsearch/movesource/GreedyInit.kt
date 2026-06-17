package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Greedy-repair restart initializer — the single implementation behind `LocalSearchSolver`'s
 * post-restart greedy pass (epic #710). Walks variables in randomized order; for each, commits the
 * value (bool: true/false; int: any value for ≤16-size domains, otherwise up to 16 sampled) that
 * minimizes the current `state.cost`, ties broken by keeping the current value. A single forward
 * pass — no fixed-point loop, idempotent on already-feasible states.
 *
 * Unlike the candidate generators in this package, this is **not** a [MoveSource]: it mutates the
 * assignment in place rather than producing a scored candidate pool (each variable's commit changes
 * the cost the next variable is evaluated against), so it carries no [Phase]/[Pool] and fills no
 * sink — matching the epic's catalog classification of `GreedyInit` as a restart-phase, n/a-pool
 * operator. It lives here so the initializer has one named home rather than being inlined in the
 * engine; the satisfy and optimize restart paths share it.
 *
 * The point isn't to reach feasibility (the LS strategies handle that) but to start the search from
 * a low-violation pose: on large decomposed instances a random start has 1000+ violations and this
 * pass typically drops it 30–60% before the main loop runs.
 */
class GreedyInit {

    /** Run one greedy-repair pass over [state], mutating its assignment in place. */
    fun run(state: LocalSearchState) {
        val problem = state.problem
        val varCount = problem.numBoolVars + problem.numIntVars
        if (varCount == 0) return
        val order = IntArray(varCount) { it }
        // Fisher-Yates shuffle using the state's RNG so the pass is deterministic for a
        // given seed.
        for (i in order.size - 1 downTo 1) {
            val j = state.rng.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        for (v in order) {
            if (v < problem.numBoolVars) {
                val boolId = v
                if (state.assumptions.isFrozenBool(boolId)) continue
                val baselineCost = state.cost
                state.apply(Move.BoolFlip(boolId))
                if (state.cost > baselineCost) state.apply(Move.BoolFlip(boolId))
            } else {
                val intId = v - problem.numBoolVars
                if (state.assumptions.isFrozenInt(intId)) continue
                val d = problem.intDomains[intId]
                val cur = state.assignment.intValue(intId)
                if (d.size <= 1) continue
                // For tiny domains (≤16 values) sweep all; for larger domains sample up
                // to 16 candidates to bound the per-pass cost at O(numVars × 16).
                val maxTries = 16
                var bestCost = state.cost
                var bestVal = cur
                if (d.size <= maxTries) {
                    for (idx in 0 until d.size) {
                        val candidate = d.valueAt(idx)
                        if (candidate == cur) continue
                        state.apply(Move.IntSet(intId, candidate))
                        if (state.cost < bestCost) {
                            bestCost = state.cost
                            bestVal = candidate
                        }
                        state.apply(Move.IntSet(intId, cur))
                    }
                } else {
                    repeat(maxTries) {
                        val candidate = d.valueAt(state.rng.nextInt(d.size))
                        if (candidate == cur) return@repeat
                        state.apply(Move.IntSet(intId, candidate))
                        if (state.cost < bestCost) {
                            bestCost = state.cost
                            bestVal = candidate
                        }
                        state.apply(Move.IntSet(intId, cur))
                    }
                }
                if (bestVal != cur) state.apply(Move.IntSet(intId, bestVal))
            }
        }
        // Reset tabu / activity tracking so the main loop doesn't start with every var
        // freshly blocked by the repair pass's apply-then-revert churn.
        state.resetStepCounters()
    }
}
