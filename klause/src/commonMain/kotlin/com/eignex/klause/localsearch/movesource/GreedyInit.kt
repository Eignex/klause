package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink

/**
 * Greedy-repair restart initializer. Walks variables in randomized order; for each, commits the
 * value (bool: true/false; int: any value for ≤16-size domains, otherwise up to 16 sampled) that
 * minimizes the current `state.cost`, ties broken by keeping the current value. A single forward
 * pass — no fixed-point loop, idempotent on already-feasible states.
 *
 * Unlike the candidate generators in this package, this is not a [MoveSource]: it mutates the
 * assignment in place rather than producing a scored candidate pool (each variable's commit changes
 * the cost the next variable is evaluated against), so it carries no [Phase]/[Pool] and fills no
 * sink. The satisfy and optimize restart paths share it.
 *
 * The point isn't to reach feasibility (the LS strategies handle that) but to start the search from
 * a low-violation pose, so a pass stopped early leaves a valid, partly repaired start.
 */
class GreedyInit {
    /** Run one greedy-repair pass over [state], mutating its assignment in place, until [stop] says to end it. */
    fun run(state: LocalSearchState, stop: () -> Boolean = { false }) {
        pass(state).advance(stop)
    }

    internal fun pass(state: LocalSearchState): Pass {
        val problem = state.problem
        val varCount = problem.numBoolVars + problem.numIntVars
        val eligibility = MoveSink()
        eligibility.setAssumptions(state.assumptions)
        eligibility.setInvariants(state.invariants)
        eligibility.setOwners(state.seeding.ownerInt)
        val order = IntArray(varCount) { it }
        // Fisher-Yates shuffle on the state's RNG so the pass is deterministic for a given seed.
        for (i in order.size - 1 downTo 1) {
            val j = state.rng.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        return Pass(state, order, eligibility)
    }

    internal inner class Pass(
        private val state: LocalSearchState,
        private val order: IntArray,
        private val eligibility: MoveSink,
    ) {
        private var next = 0

        fun advance(stop: () -> Boolean): Boolean {
            if (next == order.size) return true
            state.repairInitialization {
                while (next < order.size && !stop()) repairCoordinate(state, order[next++], eligibility)
            }
            return next == order.size
        }
    }

    private fun repairCoordinate(state: LocalSearchState, v: Int, eligibility: MoveSink) {
        val numBoolVars = state.problem.numBoolVars
        if (v < numBoolVars) {
            val boolId = v
            if (!eligibility.allowsBool(boolId)) return
            val baselineCost = state.cost
            state.apply(Move.BoolFlip(boolId))
            if (state.cost > baselineCost) state.apply(Move.BoolFlip(boolId))
        } else {
            val intId = v - numBoolVars
            if (!eligibility.allowsInt(intId)) return
            val d = state.rootDomains[intId]
            val cur = state.assignment.intValue(intId)
            if (d.isFixed) return
            // Sweep tiny domains exhaustively; sample larger ones to bound per-pass cost.
            val maxTries = 16
            var bestCost = state.cost
            var bestVal = cur
            val few = d.spanOrNull(maxTries.toLong())
            if (few != null) {
                for (idx in 0 until few.size) {
                    val candidate = few.valueAt(idx)
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
                    val candidate = state.randomIntValue(intId)
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
}
