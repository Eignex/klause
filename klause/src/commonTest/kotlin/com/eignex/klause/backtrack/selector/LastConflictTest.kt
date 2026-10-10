package com.eignex.klause.backtrack.selector

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LastConflictTest {

    @Test
    fun `last-conflict prioritises the failing variable on the next pick`() {
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
}
