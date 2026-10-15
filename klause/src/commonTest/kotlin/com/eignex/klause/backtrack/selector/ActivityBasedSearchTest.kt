package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityBasedSearchTest {

    @Test
    fun `ABS should score vars by activity over domain size`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 9) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val abs = ActivityBasedSearch()
        abs.pick(session, Random(0L))
        repeat(10) {
            abs.onPropagation(implied(intKeys = intArrayOf(2)))
            abs.onCommit(VarRef.IntVar(2))
        }
        val picked = abs.pick(session, Random(0L))
        assertEquals(
            VarRef.IntVar(2),
            picked,
            "ABS should prefer the var with bumped activity",
        )
    }

    @Test
    fun `ABS reset-on-restart should clear state`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 9) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val abs = ActivityBasedSearch(resetOnRestart = true)
        abs.pick(session, Random(0L))
        abs.onPropagation(implied(intKeys = intArrayOf(1)))
        assertEquals(VarRef.IntVar(1), abs.pick(session, Random(0L)))
        abs.onRestart()
        assertEquals(VarRef.IntVar(0), abs.pick(session, Random(0L)))
    }

    private fun implied(boolKeys: IntArray = IntArray(0), intKeys: IntArray = IntArray(0)): PropagationResult.Implied =
        PropagationResult.Implied(
            boolKeys,
            BooleanArray(boolKeys.size),
            intKeys,
            LongArray(intKeys.size),
        )
}
