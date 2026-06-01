package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lock down the [VariableHeuristic] / [ValueHeuristic] notification contract: every
 * propagation conflict fires `onConflict`, every successful pin fires `onCommit`, and
 * every Luby restart fires `onRestart`. Activity-based heuristics (VSIDS, dom/wdeg,
 * last-conflict) hang off these hooks; if they break the next heuristic-driven CP
 * search work goes blind.
 */
class HeuristicCallbackTest {

    private class CountingHeuristics : VariableHeuristic, ValueHeuristic {
        var commitCount: Int = 0
        var conflictCount: Int = 0
        var restartCount: Int = 0
        val committedVars: MutableList<Int> = ArrayList()
        val conflictVars: MutableList<Int> = ArrayList()

        override fun pick(session: PropagationSession, rng: Random): VarRef? =
            InputOrder.pick(session, rng)

        override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
            IndomainMin.values(session, varRef, rng)

        override fun onCommit(varRef: VarRef) {
            commitCount++
            committedVars.add(varRef.varId)
        }
        override fun onConflict(varRef: VarRef) {
            conflictCount++
            conflictVars.add(varRef.varId)
        }
        override fun onRestart() { restartCount++ }
        override fun onCommit(varRef: VarRef, value: Int) {}
        override fun onConflict(varRef: VarRef, value: Int) {}
        override fun onSolution(snapshot: com.eignex.klause.solver.Sample) {}
    }

    @Test
    fun `onCommit fires once per successful pin`() {
        // 3 bools, no constraints: complete binary tree of depth 3 has 2+4+8 = 14 pins.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray()
        )
        val h = CountingHeuristics()
        val samples = BacktrackSolver(problem).enumerate(
            BacktrackParams(
                randomSeed = 0L,
                variableHeuristic = h,
                valueHeuristic = h,
            )
        ).toList()
        assertEquals(8, samples.size)
        assertEquals(14, h.commitCount, "expected 14 successful pins; got ${h.commitCount}")
        assertEquals(0, h.conflictCount)
    }

    @Test
    fun `onConflict fires on propagation Unsat`() {
        // Four clauses over (a, b) are UNSAT but bake-time unit-prop alone can't see
        // it; the contradiction surfaces only after a DFS pin.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            )
        )
        val h = CountingHeuristics()
        BacktrackSolver(problem).solve(
            BacktrackParams(
                randomSeed = 0L,
                variableHeuristic = h,
                valueHeuristic = h,
                maxDecisions = 100L,
            )
        )
        assertTrue(
            h.conflictCount > 0,
            "expected at least one DFS-level propagation conflict; got ${h.conflictCount}"
        )
        assertEquals(0, h.committedVars.size - h.commitCount, "var-id log matches commit count")
    }

    @Test
    fun `onRestart fires once per Luby restart`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            )
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val h = CountingHeuristics()
        BacktrackSolver(problem).solve(
            BacktrackParams(
                randomSeed = 0L,
                variableHeuristic = h,
                valueHeuristic = h,
                lubyRestartBase = 1L,
                maxDecisions = 30L,
            )
        )
        assertTrue(h.restartCount > 0, "expected at least one restart, got ${h.restartCount}")
    }

    @Test
    fun `default heuristics ignore callbacks without compile errors`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray()
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).toList()
    }
}
