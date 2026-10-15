package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolutionGuidedTest {

    @Test
    fun `the saved value should be tried first after a solution`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val guided = SolutionGuided(IndomainMin)
        guided.onSolution(Sample(BooleanArray(0), longArrayOf(3)))
        val values = guided.values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(3L, 0L, 1L, 2L, 4L), values)
    }

    @Test
    fun `a saved value outside the domain should fall through to the base order`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(2, 4)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val guided = SolutionGuided(IndomainMin)
        guided.onSolution(Sample(BooleanArray(0), longArrayOf(0)))
        val values = guided.values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(2L, 3L, 4L), values)
    }

    @Test
    fun `saved values should take priority over a later value ordering`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val guided = SolutionGuided(IndomainMin)
        guided.onSolution(Sample(BooleanArray(0), longArrayOf(3)))

        val values = guided.guide(session, VarRef.IntVar(0), sequenceOf(4L, 2L, 3L, 1L, 0L)).toList()

        assertEquals(listOf(3L, 4L, 2L, 1L, 0L), values)
    }

    @Test
    fun `solution-guided should receive onSolution from the engine`() {
        val recorded = ArrayList<Sample>()
        val spy = object : ValueSelector {
            override fun fresh() = this
            override fun values(session: PropagationSession, varRef: VarRef, rng: Random) =
                IndomainMin.values(session, varRef, rng)
            override fun onSolution(snapshot: Sample) {
                recorded.add(snapshot)
            }
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            factors = emptyArray(),
        )
        BacktrackSolver(problem.bake()).enumerate(
            BacktrackParams(
                valueSelector = spy,
                randomSeed = 0L,
            ),
        ).toList()
        assertTrue(recorded.isNotEmpty())
        assertEquals(4, recorded.size, "expected 4 SAT leaves (2^2); got ${recorded.size}")
    }
}
