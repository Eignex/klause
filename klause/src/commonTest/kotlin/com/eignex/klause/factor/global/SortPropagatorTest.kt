package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SortPropagatorTest {

    private fun sortProblem(domains: Array<IntDomain>): Problem {
        val n = domains.size / 2
        return Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(
                Sort(xs = IntArray(n) { it }, ys = IntArray(n) { n + it }),
            ),
        )
    }

    @Test
    fun `two searches sharing one propagator keep independent per-search state`() {
        // A Propagator instance is cached once per Problem and shared across every PropagationState,
        // including the concurrent arms of a parallel portfolio. So the sort working state (matchings,
        // SCC scratch, active-state ref) must live per-search in refPayload, never as propagator
        // fields — else two arms racing on one instance corrupt each other. Two states over one
        // problem drive the *same* propagator with different pinned xs; each must sort independently
        // and own a distinct SortWork.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(0, 9),
                IntDomain(0, 9),
                IntDomain(0, 9),
            ),
            factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val prop = problem.propagators[0]

        val a = PropagationState(problem, Assumptions.None).apply { undoLogging = true }
        val b = PropagationState(problem, Assumptions.None).apply { undoLogging = true }
        // xs pinned differently per search: a → (3,1,2) sorts to (1,2,3); b → (3,3,3) sorts to (3,3,3).
        for ((v, value) in listOf(0 to 3L, 1 to 1L, 2 to 2L)) check(a.setInt(v, value))
        for ((v, value) in listOf(0 to 3L, 1 to 3L, 2 to 3L)) check(b.setInt(v, value))

        // Interleave fires so a field-held active-state would let one search read the other's domains.
        a.currentFactor = 0
        b.currentFactor = 0
        assertTrue(prop.propagate(a, 0))
        assertTrue(prop.propagate(b, 0))
        assertTrue(prop.propagate(a, 0))

        assertEquals(listOf(1L, 2L, 3L), listOf(3, 4, 5).map { a.intDomains[it].min })
        assertEquals(listOf(1L, 2L, 3L), listOf(3, 4, 5).map { a.intDomains[it].max })
        assertEquals(listOf(3L, 3L, 3L), listOf(3, 4, 5).map { b.intDomains[it].min })

        val workA = assertNotNull(a.refPayload[0], "search a must hold its sort working state in refPayload")
        val workB = assertNotNull(b.refPayload[0], "search b must hold its sort working state in refPayload")
        assertTrue(workA !== workB, "each search must own a distinct SortWork; a shared instance is the race")
    }

    @Test
    fun `sort deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x5022)
        repeat(300) { iter ->
            val n = 3
            val problem = sortProblem(Array(2 * n) { IntDomain(0, 3) })
            PropagationReasonOracle.assertReasonsImply(problem, "sort#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(2 * n)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(2))
                    }
                }
            }
        }
    }

    @Test
    fun `a sorted bound cites only the values that rank below it`() {
        // ys = sorted(xs): with x0 <= 2 and x1 <= 3, the second smallest is at most 3; x2's hole plays no part.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 2) && state.tightenIntMax(1, 3) && state.excludeIntValue(2, 5))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMaxAntecedents[4])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(0, AtomKind.LE, 2L), Triple(1, AtomKind.LE, 3L)), cited.toSet())
    }
}
