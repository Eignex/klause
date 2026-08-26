package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test

/**
 * Move-set equivalence gate for [ViolatedRepairs] / [Frontier]. The reference closures here hold the
 * `Cbls.sampleFromViolated` / `sampleFrontier` (+ `addNeighbourMoves`) generators; the test asserts
 * the sources emit the identical candidate multiset for a fixed seed and state. If a later edit
 * drifts a source away from the behaviour it replaced, this fails.
 */
class ViolatedRepairsFrontierEquivalenceTest {

    private val sampleCount = 4
    private val frontierCap = 32

    /** Infeasible linear ring: `violated` is always non-empty (so both sources emit) and the
     *  factors share variables 0/1 (so frontier has neighbours to expand). */
    private fun ringProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 7),
        ),
    )

    /** The reference `Cbls.sampleFromViolated`. */
    private fun referenceSampleFromViolated(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        repeat(minOf(sampleCount, state.violated.size)) {
            val fid = state.violated.random(state.rng)
            state.factors[fid].proposeRepairMoves(state, fid, sink)
        }
    }

    /** The reference `Cbls.sampleFrontier` + `addNeighbourMoves`. */
    private fun referenceSampleFrontier(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        val problem = state.problem
        var budget = frontierCap
        repeat(minOf(sampleCount, state.violated.size)) {
            if (budget <= 0) return
            val fid = state.violated.random(state.rng)
            val f = state.problem.factors[fid]
            for (v in f.intVars) {
                for (nf in problem.intOccurrences[v]) {
                    if (nf == fid) continue
                    budget = addNeighbourMoves(state, sink, nf, budget)
                    if (budget <= 0) return
                }
            }
            for (v in f.boolVars) {
                for (nf in problem.boolOccurrences[v]) {
                    if (nf == fid) continue
                    budget = addNeighbourMoves(state, sink, nf, budget)
                    if (budget <= 0) return
                }
            }
        }
    }

    private fun addNeighbourMoves(state: LocalSearchState, sink: MoveSink, nf: Int, budget: Int): Int {
        var b = budget
        val nfac = state.problem.factors[nf]
        for (u in nfac.intVars) {
            if (b <= 0) return b
            val cur = state.assignment.intValue(u)
            val d = state.problem.requireFiniteIntDomains()[u]
            if (cur < d.max) {
                sink.addChannelingIntSet(state, u, cur + 1)
                b--
            }
            if (b <= 0) return b
            if (cur > d.min) {
                sink.addChannelingIntSet(state, u, cur - 1)
                b--
            }
        }
        for (u in nfac.boolVars) {
            if (b <= 0) return b
            sink.addBoolFlip(u)
            b--
        }
        return b
    }

    @Test
    fun `ViolatedRepairs emits the same move multiset as the reference violated sampler`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L, 99999L)) {
            assertSourceMatchesGenerator(::ringProblem, seed, ViolatedRepairs(sampleCount)) { state, sink ->
                referenceSampleFromViolated(state, sink)
            }
        }
    }

    @Test
    fun `single-draw ViolatedRepairs matches a one-factor opener`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L)) {
            assertSourceMatchesGenerator(::ringProblem, seed, ViolatedRepairs.SINGLE) { state, sink ->
                if (!state.violated.isEmpty()) {
                    val fid = state.violated.random(state.rng)
                    state.factors[fid].proposeRepairMoves(state, fid, sink)
                }
            }
        }
    }

    @Test
    fun `Frontier emits the same move multiset as the reference frontier sampler`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L, 99999L)) {
            assertSourceMatchesGenerator(::ringProblem, seed, Frontier(sampleCount, frontierCap)) { state, sink ->
                referenceSampleFrontier(state, sink)
            }
        }
    }
}
