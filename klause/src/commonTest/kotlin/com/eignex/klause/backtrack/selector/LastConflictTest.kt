package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LastConflictTest {

    @Test
    fun `last-conflict prioritises the failing variable on the next pick`() {
        // Wrap an InputOrder base with LastConflict. After a conflict on v3, the next
        // pick should be v3 (when still free). We can't directly inspect "which var
        // was picked first" — instead, verify behaviour with a fake conflict-trigger:
        // call onConflict(v3) directly, then ask `pick` on a fresh session.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val base = RandomVariable
        val lc = LastConflict(base)
        lc.onConflict(VarRef.Bool(3))
        val session = PropagationSession(problem)
        val picked = lc.pick(session, Random(0L))
        assertEquals(
            VarRef.Bool(3),
            picked,
            "last-conflict should return v3 when it triggered the most recent conflict",
        )
    }

    @Test
    fun `last-conflict clears its pending var on successful commit`() {
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val lc = LastConflict(SmallestDomain)
        lc.onConflict(VarRef.Bool(2))
        lc.onCommit(VarRef.Bool(2))
        val session = PropagationSession(problem)
        val picked = lc.pick(session, Random(0L))
        assertEquals(
            VarRef.Bool(0),
            picked,
            "last-conflict should defer to base after the prioritised var commits",
        )
    }

    @Test
    fun `a reused selector drops a stale pending var from a prior larger problem`() {
        // A shared BacktrackParams reuses one selector across solves; a pending var recorded on a
        // large problem must not be probed against a smaller one (its id may be out of range).
        val lc = LastConflict(RandomVariable)
        val large = Problem(
            numBoolVars = 0,
            numIntVars = 100,
            intDomains = Array(100) { IntDomain(0, 1) },
            factors = emptyArray(),
        )
        lc.pick(PropagationSession(large), Random(0L))
        lc.onConflict(VarRef.IntVar(94)) // valid for `large`, out of range for the smaller problem below

        val small = Problem(
            numBoolVars = 0,
            numIntVars = 11,
            intDomains = Array(11) { IntDomain(0, 1) },
            factors = emptyArray(),
        )
        val picked = lc.pick(PropagationSession(small), Random(0L))
        assertTrue(
            picked == null || (picked as VarRef.IntVar).varId < 11,
            "a stale pending var from the prior problem must be dropped, not probed on the new one",
        )
    }

    @Test
    fun `last-conflict composes with vsids end-to-end`() {
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                    ),
                ),
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(3, true),
                        Lit.make(4, true),
                        Lit.make(5, true),
                    ),
                ),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableSelector = LastConflict(Vsids()),
                randomSeed = 0L,
            ),
        )
        assertIs<SolveResult.Sat>(r)
    }
}
