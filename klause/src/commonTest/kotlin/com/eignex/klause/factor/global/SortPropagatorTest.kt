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
    fun `sort preserves feasible tuples with the minimum signed lower bound`() {
        val problem = sortProblem(arrayOf(
            IntDomain(Long.MIN_VALUE, 1),
            IntDomain(1, 1),
            IntDomain(Long.MIN_VALUE, 0),
            IntDomain(0, 1),
        ))
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertTrue(state.factorAt(0).propagate(state, 0))
        assertEquals(1L, state.intDomains[3].min)
    }

    @Test
    fun `two searches sharing one propagator should keep independent per-search state`() {
        // One propagator instance serves concurrent searches, so its working state must live per search.
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
        for ((v, value) in listOf(0 to 3L, 1 to 1L, 2 to 2L)) check(a.setInt(v, value))
        for ((v, value) in listOf(0 to 3L, 1 to 3L, 2 to 3L)) check(b.setInt(v, value))

        a.currentFactor = 0
        b.currentFactor = 0
        assertTrue(prop.propagate(a, 0))
        assertTrue(prop.propagate(b, 0))
        assertTrue(prop.propagate(a, 0))

        assertEquals(listOf(1L, 2L, 3L), listOf(3, 4, 5).map { a.intDomains[it].min })
        assertEquals(listOf(1L, 2L, 3L), listOf(3, 4, 5).map { a.intDomains[it].max })
        assertEquals(listOf(3L, 3L, 3L), listOf(3, 4, 5).map { b.intDomains[it].min })

        val workA = assertNotNull(a.refPayload[0])
        val workB = assertNotNull(b.refPayload[0])
        assertTrue(workA !== workB)
    }

    @Test
    fun `sort deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x5022)
        repeat(100) { iter ->
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
    fun `a sorted bound should cite only the values that rank below it`() {
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
