package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Brute-force oracle for [Factor.proposeRepairMoves]. Builds the ground-truth set
 * of single-step repairs from a violating assignment by enumerating the 1-flip / 1-IntSet
 * neighborhood, then asserts the factor's proposed moves cover at least one improving move
 * whenever one exists.
 *
 * "Improving" means: after applying the move, the factor's `isViolated` drops to `false`
 * (a single factor's violation is binary). Compound moves are evaluated by sequential apply.
 */
object MoveSetOracle {

    /**
     * For [iters] random starting assignments, force violation if possible, then check the
     * propose-cover property: brute-improving ⇒ propose-improving. Throws AssertionError on
     * any failure with a description of the offending state and the brute oracle's witness.
     *
     * `requireImprovement`: when true (default), every proposed move must be non-worsening
     *   (delta ≤ 0); set false for factors that intentionally propose neutral/diversifying moves.
     */
    fun assertRepairsCoverImproving(
        problem: Problem,
        label: String = "factor",
        iters: Int = 60,
        seed: Long = 0xC0FFEEL,
        requireImprovement: Boolean = true,
    ) {
        require(problem.factors.size == 1) { "MoveSetOracle expects a single-factor Problem" }
        val factor = problem.factors[0]
        val rng = Random(seed)

        repeat(iters) { iter ->
            val state = LocalSearchState(problem, Random(seed + iter))
            randomizeAssignment(state, problem, rng)
            state.recompute()
            if (!factor.isViolated(state, 0)) return@repeat

            val improvingNeighbors = bruteImproving(problem, state, factor)
            val sink = MoveSink()
            factor.proposeRepairMoves(state, 0, sink)
            val proposed = sink.list

            for (move in proposed) {
                assertLegal(move, problem, state, factor, label)
                val delta = applyAndReport(problem, state, factor, move)
                if (requireImprovement) {
                    assertTrue(
                        delta <= 0,
                        "$label: proposed move $move worsens violation (delta=$delta) on iter=$iter",
                    )
                }
            }

            if (improvingNeighbors.isNotEmpty()) {
                val proposedImproves = proposed.any { move ->
                    applyAndReport(problem, state, factor, move) < 0
                }
                assertTrue(
                    proposedImproves,
                    "$label: brute found ${improvingNeighbors.size} improving 1-step move(s) " +
                        "(e.g. ${improvingNeighbors.first()}) but proposed set $proposed contains none. " +
                        "Iter=$iter, bools=${snapshotBools(state, problem)}, ints=${snapshotInts(state, problem)}",
                )
            }
        }
    }

    /**
     * Feasibility-preservation oracle for [Factor.proposeStructuredMoves]. From feasible
     * starting assignments (factor satisfied) every proposed structured move must keep the
     * factor satisfied — that is the contract structured moves rely on (they are offered only
     * to already-feasible factors and must never break them). A factor that proposes nothing
     * trivially passes, so this is safe to run over every factor.
     */
    fun assertStructuredMovesPreserveFeasibility(
        problem: Problem,
        label: String = "factor",
        iters: Int = 60,
        seed: Long = 0x5EED_F00DL,
    ) {
        require(problem.factors.size == 1) { "MoveSetOracle expects a single-factor Problem" }
        val factor = problem.factors[0]
        val rng = Random(seed)

        repeat(iters) { iter ->
            val state = LocalSearchState(problem, Random(seed + iter))
            // Search for a feasible start within a small budget; skip the iteration if none.
            var feasible = false
            for (t in 0 until FEASIBLE_SEARCH_BUDGET) {
                randomizeAssignment(state, problem, rng)
                state.recompute()
                if (!factor.isViolated(state, 0)) {
                    feasible = true
                    break
                }
            }
            if (!feasible) return@repeat

            val sink = MoveSink()
            factor.proposeStructuredMoves(state, 0, sink)
            for (move in sink.list) {
                assertLegal(move, problem, state, factor, label)
                val delta = applyAndReport(problem, state, factor, move)
                assertTrue(
                    delta <= 0,
                    "$label: structured move $move broke feasibility (delta=$delta) on iter=$iter, " +
                        "ints=${snapshotInts(state, problem)}",
                )
            }
        }
    }

    private const val FEASIBLE_SEARCH_BUDGET = 64

    private fun assertLegal(move: Move, problem: Problem, state: LocalSearchState, factor: Factor, label: String) {
        when (move) {
            is Move.BoolFlip -> {
                assertTrue(
                    move.varId in factor.boolVars,
                    "$label: proposed BoolFlip(${move.varId}) on var not in boolVars ${factor.boolVars.toList()}",
                )
            }

            is Move.IntSet -> {
                assertTrue(
                    move.varId in factor.intVars,
                    "$label: proposed IntSet on var ${move.varId} not in intVars ${factor.intVars.toList()}",
                )
                val d = problem.intDomains[move.varId]
                assertTrue(
                    move.newValue in d,
                    "$label: proposed IntSet target ${move.newValue} out of domain $d",
                )
                assertTrue(
                    move.newValue != state.assignment.intValue(move.varId),
                    "$label: proposed no-op IntSet at ${move.newValue}",
                )
            }

            is Move.Compound -> {
                for (part in move.parts) assertLegal(part, problem, state, factor, label)
            }
        }
    }

    /** Returns the delta in this factor's violation status when [move] is applied to a fresh
     *  copy of [state]. Does not mutate [state]. */
    private fun applyAndReport(problem: Problem, state: LocalSearchState, factor: Factor, move: Move): Int {
        val before = if (factor.isViolated(state, 0)) 1 else 0
        val sibling = LocalSearchState(problem, Random(0))
        copyAssignment(state, sibling)
        sibling.recompute()
        sibling.apply(move)
        val after = if (factor.isViolated(sibling, 0)) 1 else 0
        return after - before
    }

    private fun bruteImproving(problem: Problem, state: LocalSearchState, factor: Factor): List<Move> {
        val out = ArrayList<Move>()
        for (b in factor.boolVars) {
            val move = Move.BoolFlip(b)
            if (applyAndReport(problem, state, factor, move) < 0) out.add(move)
        }
        for (v in factor.intVars) {
            val d = problem.intDomains[v]
            val cur = state.assignment.intValue(v)
            for (k in d.min..d.max) {
                if (k !in d) continue
                if (k == cur) continue
                val move = Move.IntSet(v, k)
                if (applyAndReport(problem, state, factor, move) < 0) out.add(move)
            }
        }
        return out
    }

    private fun randomizeAssignment(state: LocalSearchState, problem: Problem, rng: Random) {
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, rng.nextBoolean())
        for (i in 0 until problem.numIntVars) {
            val d: IntDomain = problem.intDomains[i]
            state.assignment.setInt(i, d.valueAt(rng.nextInt(d.size)))
        }
    }

    private fun copyAssignment(src: LocalSearchState, dst: LocalSearchState) {
        for (b in 0 until src.problem.numBoolVars) dst.assignment.setBool(b, src.assignment.boolValue(b))
        for (i in 0 until src.problem.numIntVars) dst.assignment.setInt(i, src.assignment.intValue(i))
    }

    private fun snapshotBools(s: LocalSearchState, p: Problem): List<Boolean> =
        (0 until p.numBoolVars).map { s.assignment.boolValue(it) }

    private fun snapshotInts(s: LocalSearchState, p: Problem): List<Int> =
        (0 until p.numIntVars).map { s.assignment.intValue(it) }
}
