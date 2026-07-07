package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lock down the [VariableSelector] / [ValueSelector] notification contract: every
 * propagation conflict fires `onConflict`, every successful pin fires `onCommit`, and
 * every Luby restart fires `onRestart`. Activity-based heuristics (VSIDS, dom/wdeg,
 * last-conflict) hang off these hooks; if they break the next heuristic-driven CP
 * search work goes blind.
 */
class SelectorCallbackTest {

    private class CountingSelectors :
        VariableSelector,
        ValueSelector {
        var commitCount: Int = 0
        var conflictCount: Int = 0
        var restartCount: Int = 0
        val committedVars: MutableList<Int> = ArrayList()
        val conflictVars: MutableList<Int> = ArrayList()

        override fun pick(session: PropagationSession, rng: Random): VarRef? = InputOrder.pick(session, rng)

        override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> =
            IndomainMin.values(session, varRef, rng)

        override fun onCommit(varRef: VarRef) {
            commitCount++
            committedVars.add(varRef.varId)
        }
        override fun onConflict(varRef: VarRef) {
            conflictCount++
            conflictVars.add(varRef.varId)
        }
        override fun onRestart() {
            restartCount++
        }
        override fun onCommit(varRef: VarRef, value: Long) { /* not exercised by this test */ }
        override fun onConflict(varRef: VarRef, value: Long) { /* not exercised by this test */ }
        override fun onSolution(snapshot: Sample) { /* not exercised by this test */ }
    }

    @Test
    fun `onCommit fires once per successful pin`() {
        // 3 bools, no constraints. Enumeration registers a blocking nogood per yielded
        // solution and restarts from the root, so the deterministic pin count reflects
        // that traversal: 12 successful pins reach the 8 leaves (blocked assignments are
        // pruned by propagation before a pin is attempted, costing fewer commits than the
        // 14 of a plain chronological binary-tree walk).
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val h = CountingSelectors()
        val samples = BacktrackSolver(problem).enumerate(
            BacktrackParams(
                randomSeed = 0L,
                variableSelector = h,
                valueSelector = h,
            ),
        ).toList()
        assertEquals(8, samples.size)
        assertEquals(12, h.commitCount, "expected 12 successful pins; got ${h.commitCount}")
        // The blocking nogoods conflict when the walk re-enters an already-yielded leaf's
        // region — those are genuine conflicts and onConflict fires for them.
        assertEquals(3, h.conflictCount)
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
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val h = CountingSelectors()
        BacktrackSolver(problem).solve(
            BacktrackParams(
                randomSeed = 0L,
                variableSelector = h,
                valueSelector = h,
                maxDecisions = 100L,
            ),
        )
        assertTrue(
            h.conflictCount > 0,
            "expected at least one DFS-level propagation conflict; got ${h.conflictCount}",
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
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val h = CountingSelectors()
        BacktrackSolver(problem).solve(
            BacktrackParams(
                randomSeed = 0L,
                variableSelector = h,
                valueSelector = h,
                lubyRestartBase = 1L,
                maxDecisions = 30L,
            ),
        )
        assertTrue(h.restartCount > 0, "expected at least one restart, got ${h.restartCount}")
    }

    @Test
    fun `default heuristics ignore callbacks without compile errors`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).toList()
    }
}
