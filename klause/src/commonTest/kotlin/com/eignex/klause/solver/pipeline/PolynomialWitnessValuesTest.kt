package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PolynomialWitnessValuesTest {
    @Test
    fun `witness preference should retain both Boolean alternatives`() {
        val session = PropagationSession(Problem(1, 0, emptyArray(), emptyArray()))

        for (preferred in listOf(true, false, null)) {
            val selector = PolynomialWitnessValues(IndomainMin, SearchCandidateHints { preferred }).fresh()
            val values = selector.values(session, VarRef.Bool(0), Random(3)).toList()

            assertEquals(if (preferred == true) listOf(1L, 0L) else listOf(0L, 1L), values)
        }
    }

    @Test
    fun `witness preference should retain a custom one-sided Boolean choice`() {
        val session = PropagationSession(Problem(1, 0, emptyArray(), emptyArray()))
        val delegate = object : ValueSelector by IndomainMin {
            override fun values(session: PropagationSession, varRef: VarRef, rng: Random) = sequenceOf(0L)
        }
        val selector = PolynomialWitnessValues(delegate, SearchCandidateHints { true })

        val values = selector.values(session, VarRef.Bool(0), Random(3)).toList()

        assertEquals(listOf(0L), values)
    }

    @Test
    fun `witness preference should leave integer value ordering with its owner`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()))
        val selector = PolynomialWitnessValues(IndomainMin, SearchCandidateHints { error("Boolean hints only") })

        val values = selector.values(session, VarRef.IntVar(0), Random(3)).toList()

        assertEquals(listOf(0L, 1L, 2L), values)
    }
}
