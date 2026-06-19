package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntHashSet
import kotlin.test.Test

/**
 * Move-set equivalence gate for [StallKick]. The reference closure holds `Cbls.buildStallKick`
 * (returning its single flattened perturbation, which the closure adds to the sink); the test
 * asserts [StallKick] emits the identical move for a fixed seed and state. The kick consumes RNG
 * identically (same violated seed + walk draws), so the one-element multiset must match.
 */
class StallKickEquivalenceTest {

    private val kickVars = 8

    /** Infeasible ring with int vars and shared-variable occurrences so the walk has somewhere to
     *  hop. */
    private fun ringProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 7),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
            Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.GE, 4),
        ),
    )

    /** The reference `Cbls.buildStallKick`, adding its result to the sink. */
    private fun oldBuildStallKick(state: LocalSearchState, out: MoveSink) {
        val kickSink = MoveSink()
        val move = run {
            if (state.violated.isEmpty()) return@run null
            val problem = state.problem
            var factor = state.factors[state.violated.random(state.rng)]
            kickSink.clear()
            kickSink.setAssumptions(state.assumptions)
            kickSink.setInvariants(state.invariants)
            var budget = kickVars
            var attempts = kickVars * 4
            while (budget > 0 && attempts-- > 0) {
                val nInts = factor.intVars.size
                val nBools = factor.boolVars.size
                if (nInts + nBools == 0) break
                val pick = state.rng.nextInt(nInts + nBools)
                val occ: IntArray
                if (pick < nInts) {
                    val v = factor.intVars[pick]
                    val d = problem.intDomains[v]
                    val span = (d.max.toLong() - d.min.toLong()).toInt()
                    if (span > 0) {
                        val nv = d.min + state.rng.nextInt(span + 1)
                        if (nv != state.assignment.intValue(v)) {
                            kickSink.addChannelingIntSet(state, v, nv)
                            budget--
                        }
                    }
                    occ = problem.intOccurrences[v]
                } else {
                    val v = factor.boolVars[pick - nInts]
                    kickSink.addBoolFlip(v)
                    budget--
                    occ = problem.boolOccurrences[v]
                }
                if (occ.isEmpty()) break
                factor = state.factors[occ[state.rng.nextInt(occ.size)]]
            }
            val parts = ArrayList<Move>()
            val seenSlots = IntHashSet()
            fun addPart(p: Move) {
                val slot = when (p) {
                    is Move.BoolFlip -> p.varId
                    is Move.IntSet -> state.problem.numBoolVars + p.varId
                    is Move.Compound -> return
                }
                if (seenSlots.add(slot)) parts.add(p)
            }
            for (m in kickSink.list) {
                when (m) {
                    is Move.Compound -> for (p in m.parts) addPart(p)
                    else -> addPart(m)
                }
            }
            when (parts.size) {
                0 -> null
                1 -> parts[0]
                else -> Move.Compound(parts)
            }
        }
        when (move) {
            null -> {}
            is Move.BoolFlip -> out.addBoolFlip(move.varId)
            is Move.IntSet -> out.addIntSet(move.varId, move.newValue)
            is Move.Compound -> out.addCompound(move.parts)
        }
    }

    @Test
    fun `StallKick matches the old buildStallKick`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L, 99999L)) {
            assertSourceMatchesGenerator(::ringProblem, seed, StallKick(kickVars)) { state, sink ->
                oldBuildStallKick(state, sink)
            }
        }
    }
}
