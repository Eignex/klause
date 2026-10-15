package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableSelectorTest {

    private class CountingSelectors :
        VariableSelector,
        ValueSelector {
        var commitCount: Int = 0
        var conflictCount: Int = 0
        var restartCount: Int = 0
        val committedVars: MutableList<Int> = ArrayList()
        val conflictVars: MutableList<Int> = ArrayList()

        override fun fresh() = this

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
        override fun onCommit(varRef: VarRef, value: Long) = Unit
        override fun onConflict(varRef: VarRef, value: Long) = Unit
        override fun onSolution(snapshot: Sample) = Unit
    }

    @Test
    fun `onCommit should fire once per successful pin`() {
        // Enumeration blocks each yielded leaf and restarts, so 12 pins reach the 8 leaves.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val h = CountingSelectors()
        val samples = BacktrackSolver(problem.bake()).enumerate(
            BacktrackParams(
                randomSeed = 0L,
                variableSelector = h,
                valueSelector = h,
            ),
        ).toList()
        assertEquals(8, samples.size)
        assertEquals(12, h.commitCount)
        assertEquals(3, h.conflictCount)
    }

    @Test
    fun `onConflict should fire on a propagation conflict`() {
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
        BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                randomSeed = 0L,
                variableSelector = h,
                valueSelector = h,
                maxDecisions = 100L,
            ),
        )
        assertTrue(h.conflictCount > 0)
    }

    @Test
    fun `onRestart should fire on Luby restarts`() {
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
        BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                randomSeed = 0L,
                variableSelector = h,
                valueSelector = h,
                lubyRestartBase = 1L,
                maxDecisions = 30L,
            ),
        )
        assertTrue(h.restartCount > 0)
    }
}
