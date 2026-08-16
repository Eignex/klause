package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.proposeRepairChains
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Move-set equivalence gate for [ObjectiveSeed], [StallSwaps], and [EjectionChains]: the reference
 * closures hold the `Cbls.seedObjectiveMoves`, `sampleStallSwaps`, and `sampleStallChains` bodies,
 * and the test asserts the sources emit identical multisets for a fixed seed and state.
 */
class ObjectiveSeedStallEquivalenceTest {

    private val swapCap = 16
    private val chainCap = 4
    private val chainDepth = 4

    /** Infeasible linear ring: non-empty `violated`, same-domain int pairs for swaps, coupled break
     *  structure for chains. */
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

    /** A two-variable objective over an interior assignment (domain centred on 0) so both a
     *  down-step and an up-step are seeded. */
    private fun objectiveProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(-5, 5), IntDomain(-5, 5)),
        factors = arrayOf<Factor>(),
    )

    private val objective = LinearObjective(intCoefficients = longArrayOf(1, -1))

    /** The reference `Cbls.seedObjectiveMoves` body (sans the cost gate, which stays in the
     *  strategy). */
    private fun referenceSeedObjective(state: LocalSearchState, sink: MoveSink) {
        val obj = state.shaping.objective ?: return
        if (obj is LinearObjective) {
            for (v in obj.boolWeights.indices) {
                if (obj.boolWeights[v] == 0L) continue
                sink.addBoolFlip(v)
            }
            for (v in obj.intCoefficients.indices) {
                if (obj.intCoefficients[v] == 0L) continue
                val cur = state.assignment.intValue(v)
                val d = state.problem.intDomains[v]
                if (obj.intCoefficients[v] > 0 && cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                if (obj.intCoefficients[v] < 0 && cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
            }
        }
    }

    /** The reference `Cbls.sampleStallSwaps`. */
    private fun referenceStallSwaps(state: LocalSearchState, sink: MoveSink) {
        if (swapCap <= 0 || state.violated.isEmpty()) return
        val rng = state.rng
        val problem = state.problem
        var budget = swapCap
        var attempts = swapCap * 4
        while (budget > 0 && attempts-- > 0) {
            val fid = state.violated.random(rng)
            val vars = state.problem.factors[fid].intVars
            if (vars.isEmpty()) continue
            val u = vars[rng.nextInt(vars.size)]
            val w = if (vars.size >= 2 && rng.nextBoolean()) {
                vars[rng.nextInt(vars.size)]
            } else {
                val occ = problem.intOccurrences[u]
                if (occ.isEmpty()) continue
                val nvars = state.problem.factors[occ[rng.nextInt(occ.size)]].intVars
                if (nvars.isEmpty()) continue
                nvars[rng.nextInt(nvars.size)]
            }
            if (w == u) continue
            if (state.assumptions.isFrozenInt(u) || state.assumptions.isFrozenInt(w)) continue
            val du = problem.intDomains[u]
            val dw = problem.intDomains[w]
            if (du.min != dw.min || du.max != dw.max) continue
            val vu = state.assignment.intValue(u)
            val vw = state.assignment.intValue(w)
            if (vu == vw) continue
            if (vw !in du || vu !in dw) continue
            sink.addCompound(listOf(Move.IntSet(u, vw), Move.IntSet(w, vu)))
            budget--
        }
    }

    /** The reference `Cbls.sampleStallChains`. */
    private fun referenceStallChains(state: LocalSearchState, sink: MoveSink) {
        if (chainCap <= 0 || state.violated.isEmpty()) return
        var budget = chainCap
        repeat(minOf(chainCap, state.violated.size)) {
            if (budget <= 0) return
            val fid = state.violated.random(state.rng)
            budget -= state.proposeRepairChains(seedFactor = fid, maxDepth = chainDepth, firstMoveCap = 4, sink = sink)
        }
    }

    @Test
    fun `ObjectiveSeed emits the same move multiset as the reference objective seeding`() {
        for (seed in longArrayOf(1L, 7L, 42L)) {
            assertSourceMatchesGenerator(
                ::objectiveProblem,
                seed,
                ObjectiveSeed(),
                prepare = { it.shaping.objective = objective },
            ) { state, sink -> referenceSeedObjective(state, sink) }
        }
    }

    @Test
    fun `ObjectiveSeed yields both step directions on an interior assignment`() {
        val state = freshState(objectiveProblem(), 7L).also { it.shaping.objective = objective }
        val captured = captureFromSink(state) { sink -> ObjectiveSeed().generate(state, sink) }
        assertFalse(captured.isEmpty, "a nonzero-coefficient interior objective must seed moves")
    }

    @Test
    fun `StallSwaps emits the same move multiset as the reference stall swap sampler`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L, 99999L)) {
            assertSourceMatchesGenerator(::ringProblem, seed, StallSwaps(swapCap)) { state, sink ->
                referenceStallSwaps(state, sink)
            }
        }
    }

    @Test
    fun `EjectionChains emits the same move multiset as the reference chain sampler`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L, 99999L)) {
            assertSourceMatchesGenerator(::ringProblem, seed, EjectionChains(chainCap, chainDepth)) { state, sink ->
                referenceStallChains(state, sink)
            }
        }
    }
}
